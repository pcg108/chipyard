// Single-launch, recorded-latency oracle linked against the unmodified v6 model.
// The upstream example intentionally submits a single registry ID twice.
#include "socket_transport.h"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <set>
#include <stdexcept>

static_assert(GPU::kSocketProtocolVersion == 6, "the reference must use the pinned protocol-v6 model");

int main(int argc, char **argv) {
  try {
    if (argc != 5) throw std::runtime_error(
        "usage: lrc-reference-client PORT REGISTRY_ID L2_TRACE_ROOT EVENTS_CSV");
    const auto port = std::stoul(argv[1]);
    const auto registry = std::stoull(argv[2]);
    if (!port || port > 65535 || !registry) throw std::runtime_error("invalid port or registry ID");
    std::ofstream log(argv[4]);
    if (!log) throw std::runtime_error("cannot create event log");
    std::ofstream boundaries(std::filesystem::path(argv[4]).parent_path() / "lrc_boundaries.csv");
    if (!boundaries) throw std::runtime_error("cannot create boundary log");
    boundaries << "round,cycle,flags,drain_only,has_pending_work,deadline\n";
    log << "event,round,cycle,launch_id,request_uid,physical_id,recorded_service_id,bundle_id,address,is_write,action,source_ready_cycle\n";
    using Key = std::pair<std::uint64_t, std::uint64_t>;
    std::map<Key, GPU::L2Access> requests;
    std::map<std::uint64_t, GPU::L2Access> services, bundles;
    std::map<std::uint64_t, std::vector<Key>> bundleMembers;
    std::set<Key> admitted;
    std::set<std::uint64_t> physicalIssued;
    std::uint64_t round = 0, cycle = 0, completedLaunches = 0;
    const auto actionName = [](GPU::LrcBatchAction a) {
      switch (a) {
        case GPU::LrcBatchAction::Issue: return "Issue";
        case GPU::LrcBatchAction::Attach: return "Attach";
        case GPU::LrcBatchAction::Reuse: return "Reuse";
        default: return "None";
      }
    };
    const auto row = [&](const char *event, std::uint64_t at, const GPU::L2Access &a) {
      log << event << ',' << round << ',' << at << ',' << a.launchId << ',' << a.mUniqueId
          << ',' << a.mPhysicalId << ',' << a.mLrcServiceId << ',' << a.mBundleId
          << ',' << a.mAddress << ',' << a.mIsWrite << ',' << actionName(a.mBatchAction)
          << ',' << a.mSourceReadyCycle << '\n';
    };
    GPU::SocketClientTransport socket(static_cast<std::uint16_t>(port));
    socket.SendMessage(GPU::TrafficGenInitializationMessage{});
    GPU::SimpleTrafficGen replay;
    replay.SetL2TraceRoot(argv[3]);
    while (true) {
      const auto status = socket.RecvMessage<GPU::SchedulingRoundStateMessage>();
      if (status.truncated || !status.rejectedLaunches.empty())
        throw std::runtime_error("reference scheduler truncated or rejected launch");
      completedLaunches += status.completedLaunchIds.size();
      if (status.mainLoopComplete) {
        if (completedLaunches != 1 || replay.HasPendingWork())
          throw std::runtime_error("reference session did not complete exactly one launch and drain");
        std::cout << "PASS LRC reference rounds=" << round << " final_cycle=" << cycle << '\n';
        return 0;
      }
      GPU::RoundControlMessage control;
      control.currentCycle = cycle;
      control.reservedSubpartitionsByCycle = replay.GetReservedSubpartitionsByCycle();
      if (!round) control.launches.push_back({registry, 1, 1});
      control.endOfLaunches = true;
      socket.SendMessage(control);
      const auto schedule = socket.RecvMessage<GPU::SchedulerRoundMessage>();
      ++round;
      for (const auto &command : schedule.allL2TraceSteps) {
        const auto registerSource = [&](const GPU::L2Access &a) {
          if (!requests.emplace(Key{a.launchId, a.mUniqueId}, a).second)
            throw std::runtime_error("duplicate scheduled original identity");
          if (a.mBundleId) {
            bundles.emplace(a.mBundleId, a);
            bundleMembers[a.mBundleId].push_back({a.launchId, a.mUniqueId});
          }
          row("scheduled", a.mCycleCount, a);
        };
        if (command.mContributors.empty()) registerSource(command);
        else for (const auto &a : command.mContributors) registerSource(a);
        if (command.mBatchAction == GPU::LrcBatchAction::Issue)
          services.emplace(command.mPhysicalId, command);
      }
      GPU::TrafficGenResultMessage response;
      auto &r = response.trafficGenResult;
      const auto allIssuedAndAdmitted = [&] {
        return physicalIssued.size() == services.size() && admitted.size() == requests.size();
      };
      const bool drainOnly = allIssuedAndAdmitted();
      r = replay.IssueSchedule(schedule.allL2TraceSteps, schedule.min_issue_cycle, schedule.blockedWarpIds);
      response.hasPendingWork = replay.HasPendingWork();
      if (!response.hasPendingWork && schedule.min_issue_cycle != std::numeric_limits<std::uint64_t>::max())
        r.currentCycleAfterIssue = std::max(r.currentCycleAfterIssue, schedule.min_issue_cycle);
      const auto originals = [&](const char *event, const auto &points) {
        for (const auto &p : points) row(event, p.cycleIssued, requests.at({p.launchId, p.requestUid}));
      };
      originals("issued", r.issuedAccesses);
      for (const auto &p : r.issuedAccesses) admitted.emplace(p.launchId, p.requestUid);
      originals("source_ready", r.sourceReadyAccesses);
      originals("completed", r.completedAccesses);
      originals("reused", r.reusedAccesses);
      for (const auto &p : r.physicalAccesses) {
        auto a = requests.at({p.launchId, p.sourceRequestUid});
        a.mPhysicalId = p.physicalId;
        a.mLrcServiceId = p.recordedServiceId;
        services[p.physicalId] = a;
        physicalIssued.insert(p.physicalId);
        row("physical_issue", p.cycleIssued, a);
      }
      for (const auto &p : r.physicalCompletions) {
        row("physical_return", p.cycleCompleted, services.at(p.physicalId));
        for (const auto uid : p.requestUids) row("member", p.cycleCompleted, requests.at({p.launchId, uid}));
      }
      bool wake = false;
      for (const auto id : r.completedBundleIds) {
        row("bundle_complete", r.currentCycleAfterIssue, bundles.at(id));
        for (const auto &key : bundleMembers.at(id)) {
          const auto &a = requests.at(key);
          wake |= a.mWakeRelevantBundle && schedule.blockedWarpIds.count({a.smId,a.schedulerId,a.warpId,a.launchId});
        }
      }
      const unsigned flags = (wake ? 1 : 0) |
          ((!drainOnly && r.currentCycleAfterIssue >= schedule.min_issue_cycle) ? 2 : 0) |
          ((!drainOnly && allIssuedAndAdmitted()) ? 4 : 0) | (!response.hasPendingWork ? 8 : 0);
      boundaries << round << ',' << r.currentCycleAfterIssue << ',' << flags << ',' << drainOnly
          << ',' << response.hasPendingWork << ',' << schedule.min_issue_cycle << '\n';
      cycle = r.currentCycleAfterIssue;
      socket.SendMessage(response);
    }
  } catch (const std::exception &e) {
    std::cerr << "LRC reference failed: " << e.what() << '\n';
    return 1;
  }
}
