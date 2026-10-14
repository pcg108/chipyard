#include "bridges/trafficgen_lrc_ledger.h"
#include <cassert>
#include <iostream>
#include <functional>
using Ledger = trafficgen_lrc_ledger;
using A = Ledger::action_t;
static Ledger::access_t member(std::uint64_t uid, std::uint64_t bundle, std::uint64_t at,
                              A action = A::Issue, std::uint64_t physical = 1) {
  Ledger::access_t a; a.launchId = 1; a.registryId = 2; a.mUniqueId = uid;
  a.mBundleId = bundle; a.mCycleCount = at; a.mSourceReadyCycle = at-1;
  a.mLrcEnabled = a.mHasLrcService = true; a.mLrcServiceId = physical+10;
  a.mPhysicalId = physical; a.mBatchAction = action; a.mAddress = 0x1000;
  a.mLrcLatency = 20; a.mWakeRelevantBundle = bundle != 0; a.warpId = uid;
  return a;
}
static Ledger::access_t command(std::vector<Ledger::access_t> members) {
  auto a = members.front(); a.mContributors = std::move(members); return a;
}
static void fails(const std::function<void()> &f) {
  bool bad = false; try { f(); } catch (const std::exception &) { bad = true; } assert(bad);
}
static void future_join_reuse() {
  Ledger l;
  const auto a = member(1,100,10), b = member(2,101,40,A::Attach);
  const auto physical = l.add_schedule({command({a,b})},{{0,0,2,1}},0);
  assert(physical.size() == 1 && l.batch_active());
  const auto token = l.stage_service({1,1});
  const auto descriptors = l.descriptors();
  assert(descriptors.size() == 3);
  assert(Ledger::bits(descriptors.back(),88,4) == 3);
  l.issued({1,1},token,12); l.returned(token,20); l.complete_bundle(100,20);
  auto r = l.take_result(20);
  assert(r.physicalAccesses.size() == 1 && r.physicalCompletions.size() == 1);
  assert(r.physicalCompletions[0].requestUids == std::vector<std::uint64_t>({1,2}));
  assert(r.issuedAccesses.size() == 1 && r.completedAccesses.size() == 1 && l.has_pending());
  const auto timer = l.descriptors();
  assert(timer.size() == 1 && Ledger::bits(timer[0],88,17) == 0 && Ledger::bits(timer[0],105,64) == 40);
  l.add_schedule({},{{0,0,2,1}},20); l.complete_bundle(101,40); r = l.take_result(40);
  assert(r.issuedAccesses[0].requestUid == 2 && r.completedAccesses[0].cycleIssued == 40 && !l.has_pending());
  auto reuse = member(3,0,45,A::Reuse);
  assert(l.add_schedule({command({reuse})},{},40).empty());
  r = l.take_result(45);
  assert(r.physicalAccesses.empty() && r.physicalCompletions.empty() && r.completedBundleIds.empty());
  assert(r.reusedAccesses.size() == 1 && r.completedAccesses.size() == 1 && !l.has_pending());
  assert(l.active_service_slots() == 0 && l.active_bundle_slots() == 0);
  fails([&]{ l.add_schedule({command({reuse})},{},45); });
}
static void multiplicity_and_slots() {
  Ledger l(1);
  auto a = member(1,100,10), b = member(2,100,11,A::Attach);
  l.add_schedule({command({a,b})},{{0,0,2,1}},0);
  auto token = l.stage_service({1,1});
  auto d = l.descriptors();
  assert(Ledger::bits(d[0],88,17) == 1 && Ledger::bits(d[1],88,4) == 1);
  assert(Ledger::bits(d[0],169,1) == 1);
  l.issued({1,1},token,10); l.returned(token,20); l.complete_bundle(100,20);
  auto r = l.take_result(20); assert(r.completedAccesses.size() == 2);
  a = member(3,101,30,A::Issue,2);
  l.add_schedule({command({a})},{},20); const auto next = l.stage_service({1,3});
  assert((next & 0xffff) == (token & 0xffff) && next != token);
  fails([&]{ l.returned(token,40); });
  l.issued({1,3},next,30); l.returned(next,40); l.complete_bundle(101,40); l.take_result(40);
  assert(l.service_high_water() == 1 && l.bundle_high_water() == 1);
}
static void cross_round_mixed() {
  Ledger l;
  auto ordinary = member(100,5,10); ordinary.mLrcEnabled = ordinary.mHasLrcService = false;
  ordinary.mBatchAction = A::None; ordinary.mPhysicalId = 0; ordinary.mLrcServiceId = 0;
  l.add_schedule({ordinary},{},0); const auto ordinary_token = l.stage_service({1,100});
  l.issued({1,100},ordinary_token,12); auto r = l.take_result(12);
  assert(!l.batch_active() && r.issuedAccesses[0].cycleIssued == 12 && r.physicalAccesses.empty());
  auto a = member(1,100,20);
  l.add_schedule({command({a})},{},12); const auto token = l.stage_service({1,1});
  l.issued({1,1},token,21); r = l.take_result(21); assert(r.physicalAccesses.size() == 1);
  auto b = member(2,101,25,A::Attach); b.mPriorRoundJoin = true;
  assert(l.add_schedule({command({b})},{},21).empty());
  l.returned(ordinary_token,28); l.complete_bundle(5,28);
  l.returned(token,30); l.complete_bundle(100,30); l.complete_bundle(101,30);
  r = l.take_result(30);
  assert(r.lrcStats.crossRoundJoins == 1 && r.completedAccesses.size() == 3 && !l.has_pending());
}
static void reject_bad_schedule() {
  Ledger l;
  auto a = member(1,100,10,A::Attach);
  fails([&]{ l.add_schedule({command({a})},{},0); });
  Ledger small(1); auto b = member(2,101,11,A::Attach);
  a.mBatchAction = A::Issue;
  fails([&]{ small.add_schedule({command({a,b})},{},0); });
  Ledger invalid; a.mSourceReadyCycle = 11;
  fails([&]{ invalid.add_schedule({command({a})},{},0); });
}
static void dirty_metadata() {
  Ledger l;
  auto a = member(1,100,10), b = member(2,101,40,A::Attach);
  l.add_schedule({command({a})},{{0,0,1,1}},0);
  const auto token = l.stage_service({1,1});
  auto d = l.descriptors(true); assert(d.size() == 2);
  l.descriptors_committed(); assert(l.descriptors(true).empty());
  l.issued({1,1},token,10); l.take_result(10);
  // Attach adds its complete future obligation and modifies the service's
  // fanout, while the changed blocked snapshot writes only op3 for old rows.
  l.add_schedule({command({b})},{{0,0,2,1}},10);
  d = l.descriptors(true); assert(d.size() == 3);
  assert(Ledger::bits(d[0],0,8) == 3 && Ledger::bits(d[0],88,1) == 0);
  assert(Ledger::bits(d[1],0,8) == 2 && Ledger::bits(d[1],105,64) == 40);
  assert(Ledger::bits(d[2],0,8) == 1 && Ledger::bits(d[2],88,4) == 3);
  l.descriptors_committed();
  l.returned(token,20); l.complete_bundle(100,20); l.take_result(20);
  l.add_schedule({},{{0,0,2,1}},20);
  // The native return scheduled this future timer. No absolute bundle rewrite
  // may reset or duplicate it at a private refill or unchanged socket round.
  assert(l.descriptors(true).empty());
  l.descriptors_committed(); l.complete_bundle(101,40); l.take_result(40);
}
static void ordinary_native_before_legacy() {
  for (const bool migrate : {false,true}) {
    Ledger l;
    auto a = member(100,5,10);
    a.mBatchAction=A::None; a.mLrcEnabled=a.mHasLrcService=false;
    a.mLrcServiceId=a.mPhysicalId=0;
    l.add_schedule({a},{{0,0,100,1}},0);
    const auto handle=l.bundles().at(5).handle;
    const auto token=l.stage_service({1,100}); l.descriptors_committed();
    l.issued({1,100},token,12); l.returned(token,20); l.advance(20);
    l.ordinary_native_retired(5,20);
    auto r=l.take_result(20);
    assert(r.completedBundleIds.empty() && r.completedAccesses.size()==1 && r.completedAccesses[0].cycleIssued==20 && l.has_pending());
    // A changed blocked snapshot must not update an inactive hardware row.
    l.add_schedule({}, {},20); assert(l.descriptors(true).empty()); l.take_result(21);
    if (!migrate) {
      l.add_schedule({},{{0,0,100,1}},21); assert(l.descriptors(true).empty());
      l.complete_bundle(5,22); r=l.take_result(22);
      assert(r.completedBundleIds==std::vector<std::uint64_t>{5} && !l.has_pending());
    } else {
      auto b=member(1,100,22);
      l.add_schedule({command({b})},{{0,0,100,1}},21);
      const auto d=l.descriptors(true);
      assert(d.size()==2 && Ledger::bits(d[0],0,8)==2 && Ledger::bits(d[0],24,64)==5);
      assert(Ledger::bits(d[0],88,17)==0 && Ledger::bits(d[0],105,64)==21);
      assert(Ledger::bits(d[0],170,64)!=handle && Ledger::bits(d[0],169,1)==1);
      const auto next=l.stage_service({1,1}); l.descriptors_committed();
      l.complete_bundle(5,21); l.issued({1,1},next,22); l.returned(next,30); l.complete_bundle(100,30);
      r=l.take_result(30);
      assert(r.completedBundleIds==std::vector<std::uint64_t>({5,100}));
      assert(r.physicalAccesses.size()==1 && r.physicalCompletions.size()==1 && r.completedAccesses.size()==1);
      assert(!l.has_pending());
    }
  }
}
int main() {
  future_join_reuse(); multiplicity_and_slots(); cross_round_mixed(); reject_bad_schedule(); dirty_metadata();
  ordinary_native_before_legacy();
  std::cout << "TrafficGen LRC ledger tests passed\n";
}
