#include "bridges/trafficgen_tma_policy.h"
#include "live_fixture.h"
#include <cassert>
#include <iostream>
#include <filesystem>
#include <fstream>
#include <unistd.h>
using Ledger=trafficgen_lrc_ledger;
using Policy=trafficgen_tma_policy;
namespace wire=trafficgen_socket;
template<class T,class U> T convert(const U &value) {
  std::ostringstream out(std::ios::binary); { boost::archive::binary_oarchive a(out); a<<value; }
  std::istringstream in(out.str(),std::ios::binary); boost::archive::binary_iarchive a(in); T result; a>>result; return result;
}
template<class F> void reject(F f) { bool failed=false; try { f(); } catch(const std::exception &) { failed=true; } assert(failed); }
static wire::socket_l2_access_t ordinary(unsigned uid,unsigned bundle,unsigned at=0) {
  wire::socket_l2_access_t a; a.launchId=1; a.registryId=1; a.mUniqueId=uid; a.mBundleId=bundle;
  a.mAddress=0x1000+32*uid; a.mCycleCount=at; a.mWakeRelevantBundle=true; a.warpId=uid; return a;
}
int main() {
  Ledger ledger; Policy compiler; wire::TmaWakePolicy policy;
  std::vector<wire::socket_l2_access_t> input;
  wire::TmaWakeGroup phase; phase.launchId=1; phase.destinationCta=3; phase.barrierAddress=0x100;
  for(unsigned n=1;n<=5;++n) { input.push_back(ordinary(n,100+n,10)); phase.requests.push_back({1,n}); policy.outstandingRequests.insert({1,n}); }
  policy.groups.push_back(phase);
  auto physical=ledger.add_schedule(input,{{0,0,1,1}},0,true);
  compiler.replace(policy,ledger); ledger.suppress_tma_wakes(compiler.known());
  assert(compiler.groups().size()==1 && compiler.groups()[0].services.size()==5 && compiler.edge_count()==5);
  assert(!ledger.bundles().at(101).wake);
  // Preflight retains edges for services which are beyond the current window.
  auto first=ledger.stage_service({1,1}); auto d=compiler.descriptors(ledger);
  unsigned heads=0; for(auto &r:d) heads+=Ledger::bits(r,0,8)==7; assert(heads==1);
  compiler.committed(ledger);
  for(unsigned n=2;n<=5;++n) ledger.stage_service({1,n});
  d=compiler.descriptors(ledger); assert(d.size()==4);
  for(auto &r:d) assert(Ledger::bits(r,0,8)==7);
  compiler.committed(ledger); assert(compiler.descriptors(ledger).empty());
  // Original completions and all five transport bundles survive a single phase wake.
  for(unsigned n=1;n<=5;++n) {
    auto key=Ledger::key_t{1,n}; auto id=ledger.requests().at(key).physical; auto token=ledger.services().at(id).token;
    ledger.issued(key,token,10); ledger.returned(token,10*n); ledger.advance(10*n); ledger.complete_bundle(100+n,10*n);
    if(n<5) reject([&]{compiler.wake(std::uint64_t{1}<<32,10*n,false,ledger);});
  }
  (void)first;
  compiler.wake(std::uint64_t{1}<<32,50,false,ledger); assert(!compiler.pending());
  reject([&]{compiler.wake(std::uint64_t{1}<<32,50,false,ledger);});
  const auto result=ledger.take_result(50);
  assert(result.completedAccesses.size()==5 && result.completedBundleIds.size()==5 && result.physicalAccesses.empty());
  // Compare identities and timestamps with the untouched legacy SimpleTrafficGen.
  GPU::SimpleTrafficGen model;
  const auto root=std::filesystem::temp_directory_path()/("tma_compiler_"+std::to_string(getpid()));
  std::filesystem::create_directories(root/"kernel_1_phase/shader_0/scheduler_0");
  {
    std::ofstream timing(root/"kernel_1_phase/shader_0/scheduler_0/l2_to_icnt_timing.txt");
    for(unsigned n=1;n<=5;++n) timing << "request_uid=" << n << " elapsed_cycle=" << 10*n-10 << '\n';
  }
  model.SetL2TraceRoot(root);
  auto commands=convert<std::vector<GPU::L2Access>>(input);
  for(auto &a:commands) a.mKernelFolder="kernel_1_phase";
  auto oracle=model.IssueSchedule(commands,UINT64_MAX,{},convert<GPU::TmaWakePolicy>(policy));
  auto tail=model.IssueSchedule({},UINT64_MAX,{},convert<GPU::TmaWakePolicy>(policy));
  assert(tail.wakeDiagnostics.phaseWakeups==1 && tail.currentCycleAfterIssue==50);
  oracle.completedAccesses.insert(oracle.completedAccesses.end(),tail.completedAccesses.begin(),tail.completedAccesses.end());
  assert(oracle.completedAccesses.size()==result.completedAccesses.size());
  for(const auto &r:result.completedAccesses) {
    auto it=std::find_if(oracle.completedAccesses.begin(),oracle.completedAccesses.end(),[&](const auto &a){return a.requestUid==r.requestUid;});
    assert(it!=oracle.completedAccesses.end() && it->cycleIssued==r.cycleIssued);
  }
  // Completion history survives replacement; floor-only groups need no new edge.
  policy.groups[0].readyCycle=100; compiler.replace(policy,ledger);
  assert(compiler.groups()[0].services.empty() && compiler.groups()[0].floor==100);
  reject([&]{compiler.wake(std::uint64_t{2}<<32,99,false,ledger);});
  compiler.wake(std::uint64_t{2}<<32,100,false,ledger);
  compiler.replace({},ledger); assert(!compiler.pending() && !compiler.honor_deadline() && compiler.known().size()==5);
  // Preflight failure leaves the previous epoch intact.
  const auto epoch=compiler.epoch(); policy.groups[0].requests.push_back({2,1});
  reject([&]{compiler.replace(policy,ledger);}); assert(compiler.epoch()==epoch);
  policy.groups.assign(4097,{}); reject([&]{compiler.replace(policy,ledger);}); assert(compiler.epoch()==epoch);
  // No four-bundle fan-out restriction applies to independent wake groups.
  Ledger fanout; fanout.add_schedule({ordinary(1,1)}, {},0,true);
  policy={}; phase.requests={{1,1},{1,1}}; policy.groups.assign(9,phase);
  compiler.replace(policy,fanout); assert(compiler.edge_count()==9);
  for(const auto &g:compiler.groups()) assert(g.services.size()==1);
  std::filesystem::remove_all(root);
  std::cout<<"TMA policy compiler and pinned legacy oracle tests passed\n";
}
