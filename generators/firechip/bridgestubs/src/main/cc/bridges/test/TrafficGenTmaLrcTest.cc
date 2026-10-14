#include "bridges/trafficgen_tma_policy.h"
#include <cassert>
#include <functional>
#include <iostream>
using Ledger=trafficgen_lrc_ledger;
using Compiler=trafficgen_tma_policy;
using A=Ledger::action_t;
namespace wire=trafficgen_socket;
static Ledger::access_t member(std::uint64_t uid,std::uint64_t bundle,std::uint64_t at,A action=A::Issue,std::uint64_t physical=1) {
  Ledger::access_t a; a.launchId=1; a.registryId=2; a.mUniqueId=uid;
  a.mBundleId=bundle; a.mCycleCount=at; a.mSourceReadyCycle=at-1;
  a.mLrcEnabled=a.mHasLrcService=true; a.mLrcServiceId=physical+10;
  a.mPhysicalId=physical; a.mBatchAction=action; a.mAddress=0x1000;
  a.mLrcLatency=20; a.mWakeRelevantBundle=bundle!=0; a.warpId=uid; return a;
}
static Ledger::access_t command(std::vector<Ledger::access_t> members) { auto a=members.front(); a.mContributors=std::move(members); return a; }
static void fails(const std::function<void()> &f) { bool failed=false; try { f(); } catch(const std::exception &) { failed=true; } assert(failed); }
static wire::TmaWakePolicy policy(std::initializer_list<wire::TmaRequestIdentity> ids,std::uint64_t floor=0) {
  wire::TmaWakePolicy p; wire::TmaWakeGroup g; g.launchId=1; g.requests=ids; g.readyCycle=floor; p.groups={g}; return p;
}
int main() {
  Ledger l; Compiler c;
  auto a=member(1,100,10), b=member(2,100,40,A::Attach);
  b.smId=3;
  assert(l.add_schedule({command({a,b})},{{3,0,2,1}},0).size()==1);
  c.replace(policy({{1,1},{1,2},{1,1}}),l); l.suppress_tma_wakes(c.known());
  assert(c.edge_count()==1 && c.groups()[0].floor==40 && !l.bundles().at(100).wake);
  auto token=l.stage_service({1,1}); c.committed(l);
  l.issued({1,1},token,12); l.returned(token,20); l.advance(20);
  fails([&]{c.wake(std::uint64_t{1}<<32,20,false,l);});
  auto r=l.take_result(20); assert(r.completedAccesses.size()==1 && r.completedBundleIds.empty());
  l.add_schedule({}, {},20);
  // Complete phase replaces a conservative group after the physical service returned.
  auto p=policy({{1,1},{1,2}},50); p.groups[0].conservative=true;
  c.replace(p,l); assert(c.edge_count()==0 && c.groups()[0].floor==50);
  l.advance(40); l.complete_bundle(100,40); r=l.take_result(40);
  assert(r.completedAccesses.size()==1 && r.completedAccesses[0].cycleIssued==40);
  l.add_schedule({}, {},40); p.groups[0].conservative=false; c.replace(p,l);
  fails([&]{c.wake(std::uint64_t{2}<<32,50,true,l);});
  fails([&]{c.wake(std::uint64_t{3}<<32,49,false,l);});
  c.wake(std::uint64_t{3}<<32,50,false,l); l.take_result(50);
  // Reuse creates only a timing constraint, no service edge or bundle obligation.
  const auto reuse=member(3,0,80,A::Reuse);
  assert(l.add_schedule({command({reuse})},{},50).empty());
  c.replace(policy({{1,3}}),l); assert(c.edge_count()==0 && c.groups()[0].floor==80);
  l.advance(79); fails([&]{c.wake(std::uint64_t{4}<<32,79,false,l);});
  l.advance(80); c.wake(std::uint64_t{4}<<32,80,false,l); r=l.take_result(80);
  assert(r.physicalAccesses.empty() && r.physicalCompletions.empty() && r.completedBundleIds.empty());
  assert(r.completedAccesses.size()==1 && r.reusedAccesses.size()==1);
  // A cross-round attachment retains the published physical identity.
  Ledger cross; Compiler crossCompiler;
  cross.add_schedule({command({member(10,10,10)})},{},0); token=cross.stage_service({1,10});
  cross.issued({1,10},token,10); cross.take_result(10);
  auto attached=member(11,11,30,A::Attach); attached.mPriorRoundJoin=true;
  assert(cross.add_schedule({command({attached})},{},10).empty());
  crossCompiler.replace(policy({{1,10},{1,11}}),cross);
  assert(cross.services().at(1).token==token && crossCompiler.edge_count()==1);
  cross.returned(token,20); cross.advance(30);
  crossCompiler.wake(std::uint64_t{1}<<32,30,false,cross);
  // Edge capacity is checked across the entire policy before replacing its epoch.
  Ledger overflow; Compiler capacity;
  std::vector<Ledger::access_t> requests;
  wire::TmaWakeGroup g;
  for(unsigned n=1;n<=65;++n) {
    auto ordinary=member(n,n,1); ordinary.mBatchAction=A::None; ordinary.mLrcEnabled=ordinary.mHasLrcService=false;
    ordinary.mPhysicalId=ordinary.mLrcServiceId=0; requests.push_back(ordinary); g.requests.push_back({1,n});
  }
  overflow.add_schedule(requests,{},0,true);
  wire::TmaWakePolicy tooLarge; tooLarge.groups.assign(4096,g);
  try { capacity.replace(tooLarge,overflow); assert(false); }
  catch(const std::runtime_error &e) {
    const std::string message=e.what();
    assert(message.find("dependency edges")!=std::string::npos && message.find("limit=262144")!=std::string::npos);
  }
  assert(capacity.epoch()==0 && !capacity.pending());
  std::cout<<"TMA/LRC future admission, Reuse, replacement, cross-round join, and overflow tests passed\n";
}
