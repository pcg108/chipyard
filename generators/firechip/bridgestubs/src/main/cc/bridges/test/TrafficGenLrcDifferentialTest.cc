// Inject the untouched pinned replay model's physical responses into the host
// ledger. The native monitor's choice of target stopping token is tested in RTL.
#include "bridges/trafficgen_lrc_ledger.h"
#include "lrc_batch.h"
#include "live_fixture.h"
#include <cassert>
#include <iostream>
#include <sstream>
#include <tuple>
using Ledger = trafficgen_lrc_ledger;
template<class T> static std::string bytes(const T &value) {
  std::ostringstream out(std::ios::binary); boost::archive::binary_oarchive ar(out); ar << value; return out.str();
}
template<class T,class U> static T convert(const U &value) {
  std::istringstream in(bytes(value),std::ios::binary); boost::archive::binary_iarchive ar(in); T result; ar >> result; return result;
}
static auto source_key(const GPU::SimpleTrafficGen::IssuedAccessPoint &a) {
  return std::tie(a.launchId,a.requestUid,a.cycleIssued,a.address,a.isWrite);
}
static void normalize(GPU::SimpleTrafficGen::IssueScheduleResult &r) {
  auto source = [](auto &v) { std::sort(v.begin(),v.end(),[](const auto &a,const auto &b){return source_key(a)<source_key(b);}); };
  source(r.issuedAccesses); source(r.completedAccesses); source(r.sourceReadyAccesses); source(r.reusedAccesses);
  std::sort(r.completedBundleIds.begin(),r.completedBundleIds.end());
  // Ordinary physical IDs are private high-half handles; no scheduler identity
  // refers to their numeric allocator sequence before LRC activation.
  for (auto &a : r.physicalAccesses) if (a.physicalId >> 63) a.physicalId = 0;
  for (auto &a : r.physicalCompletions) {
    if (a.physicalId >> 63) a.physicalId = 0;
    std::sort(a.requestUids.begin(),a.requestUids.end());
  }
  std::sort(r.physicalAccesses.begin(),r.physicalAccesses.end(),[](const auto &a,const auto &b){
    return std::tie(a.launchId,a.physicalId,a.sourceRequestUid)<std::tie(b.launchId,b.physicalId,b.sourceRequestUid); });
  std::sort(r.physicalCompletions.begin(),r.physicalCompletions.end(),[](const auto &a,const auto &b){
    return std::tie(a.launchId,a.physicalId,a.requestUids)<std::tie(b.launchId,b.physicalId,b.requestUids); });
}
struct Harness {
  GPU::SimpleTrafficGen model;
  GPU::LrcBatchCoordinator coalescer;
  Ledger ledger;
  std::map<std::uint64_t,Ledger::key_t> physical_sources;
  std::uint64_t cycle = 0;
  void round(const std::vector<GPU::L2Access> &commands,std::uint64_t deadline,
             const std::set<GPU::WarpKey> &blocked = {}) {
    const auto input = convert<std::vector<Ledger::access_t>>(commands);
    const auto warps = convert<std::set<trafficgen_socket::socket_warp_key_t>>(blocked);
    const bool old_batch = ledger.batch_active();
    const auto physical = ledger.add_schedule(input,warps,cycle);
    for (const auto &a : physical) ledger.stage_service({a.launchId,a.mUniqueId});
    auto expected = model.IssueSchedule(commands,deadline,blocked);
    for (const auto &p : expected.physicalAccesses) {
      physical_sources[p.physicalId] = {p.launchId,p.sourceRequestUid};
      const auto key = physical_sources.at(p.physicalId);
      const auto token = ledger.stage_service(key);
      ledger.issued(key,token,p.cycleIssued);
    }
    if (!old_batch && !ledger.batch_active()) for (const auto &p : expected.issuedAccesses) {
      const Ledger::key_t key{p.launchId,p.requestUid};
      ledger.issued(key,ledger.stage_service(key),p.cycleIssued);
    }
    for (const auto &p : expected.physicalCompletions) {
      const auto key = physical_sources.at(p.physicalId);
      const auto id = ledger.requests().at(key).physical;
      ledger.returned(ledger.services().at(id).token,p.cycleCompleted);
    }
    // Migration retains legacy requests already in flight; replay exposes their
    // original completion without inventing a new physical issue/return pair.
    for (const auto &p : expected.completedAccesses) {
      const auto id = ledger.requests().at({p.launchId,p.requestUid}).physical;
      const auto &s = ledger.services().at(id);
      if (!s.returned && s.first.mBatchAction == Ledger::action_t::None)
        ledger.returned(s.token,p.cycleIssued);
    }
    ledger.advance(expected.currentCycleAfterIssue);
    for (const auto id : expected.completedBundleIds) {
      const auto &b = ledger.bundles().at(id);
      std::uint64_t at = b.floor;
      for (const auto service : b.services) at = std::max(at,ledger.services().at(service).return_cycle);
      ledger.complete_bundle(id,at);
    }
    auto actual = convert<GPU::SimpleTrafficGen::IssueScheduleResult>(ledger.take_result(expected.currentCycleAfterIssue));
    coalescer.ApplyCompletions(expected);
    // The ledger owns event reconstruction; v7 boundary diagnostics are owned
    // by the driver/monitor and tested separately with the wake-policy fixtures.
    actual.wakeDiagnostics = {}; expected.wakeDiagnostics = {};
    normalize(actual); normalize(expected);
    assert(bytes(actual) == bytes(expected));
    assert(ledger.has_pending() == model.HasPendingWork());
    const auto reservations = ledger.reservations(expected.currentCycleAfterIssue);
    assert(reservations == model.GetReservedSubpartitionsByCycle());
    cycle = expected.currentCycleAfterIssue;
  }
  std::vector<GPU::L2Access> batch(std::vector<GPU::L2Access> sources) {
    coalescer.BeginRound(); auto slots = model.GetReservedSubpartitionsByCycle();
    for (auto &a : sources) coalescer.Admit(a,slots);
    return GPU::LrcBatchCoordinator::BuildBatch(sources);
  }
};
static GPU::L2Access make_read(std::uint64_t uid,std::uint64_t bundle,std::uint64_t ready,
                         std::uint64_t service = 10,std::uint64_t latency = 20) {
  GPU::L2Access a(uid,0x1000+32*service,ready,0,0,0,1,unsigned(uid%4),0,unsigned(uid),false,"kernel_1_test",bundle,bundle!=0);
  a.launchId = 1; a.mLrcEnabled = a.mHasLrcService = true; a.mLrcServiceId = service; a.mLrcLatency = latency; return a;
}
int main() {
  constexpr auto forever = std::numeric_limits<std::uint64_t>::max();
  for (unsigned count = 1; count <= 4; ++count) {
    Harness h; std::vector<GPU::L2Access> sources;
    for (unsigned i=0;i<count;++i) sources.push_back(make_read(i+1,7,10+i));
    auto batch = h.batch(sources); h.round(batch,forever,{sources.back().GetWarpKey()});
    while (h.model.HasPendingWork()) h.round({},forever,{sources.back().GetWarpKey()});
  }
  {
    Harness h; auto a=make_read(1,1,100); h.round(h.batch({a}),100);
    auto b=make_read(2,2,140); h.round(h.batch({b}),forever,{a.GetWarpKey()});
    h.round({},forever,{b.GetWarpKey()});
    h.round(h.batch({make_read(3,0,145)}),forever);
  }
  {
    Harness h; h.round(h.batch({make_read(1,1,10,10,0),make_read(2,2,10,20,0)}),forever);
  }
  {
    LiveFixture fixture; fixture.Add(1);
    const auto root=fixture.root/"1/l2_trace";
    std::ofstream(root/"kernel_1_test/shader_0/scheduler_0/l2_to_icnt_timing.txt")
        << "request_uid=101 elapsed_cycle=20\nrequest_uid=102 elapsed_cycle=20\n";
    Harness h; h.model.SetL2TraceRoot(root);
    GPU::L2Access a(101,0x2000,10,0,0,0,1,0,0,0,false,"kernel_1_test",1,true); a.launchId=2;
    auto b=a; b.mUniqueId=102; b.mCycleCount=100; b.mBundleId=2;
    h.round({a,b},10);
    auto c=make_read(1,1000,11,10,5); h.round(h.batch({c}),forever,{c.GetWarpKey()});
    while(h.model.HasPendingWork()) h.round({},forever);
  }
  std::cout << "TrafficGen pinned-v6 LRC event differential tests passed\n";
}
