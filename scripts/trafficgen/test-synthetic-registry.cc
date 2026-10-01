// Link against the actual gpu_model_core, using headers from gpu_model/src/include.
// Run with a registry produced by generate-synthetic-registry.py and its iteration count.
#ifdef NDEBUG
#undef NDEBUG
#endif
#include "socket_session.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <map>
#include <set>
#include <string>

int main(int argc, char **argv) {
  assert(argc == 3 && "usage: test-synthetic-registry registry.json iterations");
  const unsigned iterations = std::stoul(argv[2]);
  const std::array<std::uint64_t, 4> registryIds{{1106, 1003, 1044, 1001}};
  GPU::KernelRegistry registry(argv[1]);
  GCoM::HWConfig config;
  config.numSMs = 1;
  config.numSubcorePerSM = 1;
  config.maxThreadsPerSM = 128;
  config.maxCTAPerSM = 4;
  config.registerPerSM = 4096;
  config.sharedMemSizeOption = {64};
  config.issueLatencyScale = 1;
  GPU::L2TraceScheduler::ConfigureFunctionalUnitLatencies(config);
  GPU::SocketSchedulingSession session(registry, config, 0);
  std::uint64_t cycle = 0;
  std::map<std::uint64_t, GPU::L2Access> pending;
  std::set<std::pair<std::uint64_t, std::uint64_t>> issuedKeys;
  std::set<std::uint64_t> completedBundles, accepted, finished;
  std::map<std::uint64_t, std::set<std::uint64_t>> lines, recordedUids;
  std::map<std::uint64_t, unsigned> loads, stores;
  bool provedFourResident = false;

  for (unsigned round = 0; round < iterations * 16 + 32; ++round) {
    const auto status = session.TakeStatus();
    assert(status.rejectedLaunches.empty());
    accepted.insert(status.acceptedLaunchIds.begin(), status.acceptedLaunchIds.end());
    finished.insert(status.completedLaunchIds.begin(), status.completedLaunchIds.end());
    if (status.mainLoopComplete) {
      assert(provedFourResident && accepted.size() == 4 && finished.size() == 4);
      assert(pending.empty() && session.ResidentCTAs(0) == 0);
      assert(issuedKeys.size() == 8 * iterations && completedBundles.size() == 8 * iterations);
      for (std::uint64_t launch = 1; launch <= 4; ++launch) {
        assert(loads[launch] == iterations && stores[launch] == iterations);
        assert(recordedUids[launch] == recordedUids[1]);
        for (std::uint64_t other = 1; other < launch; ++other)
          for (auto line : lines[launch]) assert(!lines[other].count(line));
      }
      std::cout << "PASS: four resident CTAs; repeated UIDs and warp IDs; disjoint remapped lines; "
                << loads[1] * 4 << " loads and " << stores[1] * 4 << " non-wake stores completed once\n";
      return 0;
    }

    GPU::RoundControlMessage control;
    control.currentCycle = cycle;
    if (round < registryIds.size()) control.launches.push_back({registryIds[round], round + 1, round + 1});
    control.endOfLaunches = round >= registryIds.size() - 1;
    for (const auto &[bundle, access] : pending)
      control.reservedSubpartitionsByCycle[access.mCycleCount].insert(access.mSubpartition);
    const auto schedule = session.Schedule(control);
    for (const auto &access : schedule.allL2TraceSteps) {
      assert(access.launchId >= 1 && access.launchId <= 4);
      assert(access.registryId == registryIds[access.launchId - 1]);
      assert(access.warpId == 0 && access.smId == 0 && access.schedulerId == 0);
      assert(pending.emplace(access.mBundleId, access).second);
      lines[access.launchId].insert((access.mAddress & 0xffffffffULL) >> 6);
      recordedUids[access.launchId].insert(access.mUniqueId);
      ++(access.mIsWrite ? stores[access.launchId] : loads[access.launchId]);
      if (access.mIsWrite) assert(!access.mWakeRelevantBundle);
    }
    if (round == registryIds.size() - 1) {
      assert(session.ResidentCTAs(0) == 4);
      for (std::uint64_t launch = 1; launch <= 4; ++launch) assert(session.AdmittedCTAs(launch) == 1);
      std::set<std::uint64_t> outstandingLaunches;
      for (const auto &[bundle, access] : pending) outstandingLaunches.insert(access.launchId);
      assert(outstandingLaunches.size() == 4);
      assert(finished.empty());
      provedFourResident = true;
    }

    cycle = std::max(cycle, schedule.min_issue_cycle);
    GPU::TrafficGenResultMessage result;
    // Withhold completions until all four CTAs are resident. Once admitted,
    // service each access with a finite latency, including final store bundles.
    if (provedFourResident) {
      for (const auto &[bundle, access] : pending) {
        cycle = std::max(cycle, access.mCycleCount + 20);
        GPU::SimpleTrafficGen::IssuedAccessPoint point;
        point.launchId = access.launchId;
        point.requestUid = access.mUniqueId;
        point.cycleIssued = std::max(control.currentCycle, access.mCycleCount);
        point.address = access.mAddress;
        point.isWrite = access.mIsWrite;
        assert(issuedKeys.emplace(point.launchId, point.requestUid).second);
        result.trafficGenResult.issuedAccesses.push_back(point);
        assert(completedBundles.insert(bundle).second);
        result.trafficGenResult.completedBundleIds.push_back(bundle);
      }
      pending.clear();
    }
    result.trafficGenResult.currentCycleAfterIssue = cycle;
    result.hasPendingWork = !pending.empty();
    session.CompleteRound(result);
  }
  assert(false && "synthetic fixture failed to drain within its round bound");
}
