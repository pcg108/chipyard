// Socket peer for a real metasim: TMA without LRC, an ordinary producer-warp
// wake, multicast phase release, and policy replacement after memory drains.
#include "socket_transport.h"
#include <algorithm>
#include <cassert>
#include <iostream>
#include <set>
#include <stdexcept>
int main(int argc,char **argv) {
  try {
    if(argc!=2) throw std::runtime_error("usage: tma-metasim-fixture PORT");
    GPU::SocketServerTransport socket(std::stoul(argv[1])); socket.AcceptClient();
    const auto init=socket.RecvMessage<GPU::TrafficGenInitializationMessage>();
    assert(init.protocolVersion==7);
    GPU::SchedulingRoundStateMessage status; status.currentCycle=init.currentCycle;
    socket.SendMessage(status);
    std::uint64_t launch=0,registry=0,base=0,lastIssue=0,cycle=init.currentCycle;
    bool registered=false,announced=false,closed=false,legacyBoundary=false,deadlineObserved=false;
    unsigned stage=0,round=0,ordinaryWakes=0;
    std::set<std::uint64_t> issued,completed,bundles;
    while(round++<100) {
      auto control=socket.RecvMessage<GPU::RoundControlMessage>(); closed|=control.endOfLaunches;
      if(!control.launches.empty()) {
        assert(!launch && control.launches.size()==1);
        launch=control.launches[0].launchId; registry=control.launches[0].registryId; base=control.currentCycle;
      }
      GPU::SchedulerRoundMessage schedule; schedule.min_issue_cycle=UINT64_MAX;
      if(launch && stage<2) {
        if(!registered) {
          for(unsigned n=1;n<=6;++n) {
            GPU::L2Access a(n,0x1000+32*n,base+10,n%16,0,0,1,0,0,1,false,"fixture",100+n,true);
            a.launchId=launch; a.registryId=registry; schedule.allL2TraceSteps.push_back(a);
          }
          registered=true;
        }
        schedule.blockedWarpIds.insert({0,0,1,launch});
        GPU::TmaWakeGroup group; group.launchId=launch; group.barrierAddress=0x1000; group.phase=stage;
        group.readyCycle=stage==0?base+500:cycle+20;
        for(unsigned n=1;n<=5;++n) group.requests.push_back({launch,n});
        schedule.tmaWakePolicy.groups.push_back(group);
        if(stage==0) {
          for(unsigned n=1;n<=5;++n) if(!completed.count(n)) schedule.tmaWakePolicy.outstandingRequests.insert({launch,n});
          group.destinationCta=1; schedule.tmaWakePolicy.groups.push_back(group);
          group.conservative=true; group.transferId=7; group.readyCycle=base+1000;
          schedule.tmaWakePolicy.groups.push_back(group);
        }
      } else schedule.min_issue_cycle=control.currentCycle;
      if(stage==0 && ordinaryWakes && !deadlineObserved) schedule.min_issue_cycle=base+100;
      const auto floor=schedule.tmaWakePolicy.groups.empty()?0:schedule.tmaWakePolicy.groups[0].readyCycle;
      socket.SendMessage(schedule);
      const auto response=socket.RecvMessage<GPU::TrafficGenResultMessage>();
      const auto &r=response.trafficGenResult; cycle=r.currentCycleAfterIssue;
      assert(r.physicalAccesses.empty() && r.physicalCompletions.empty());
      for(const auto &p:r.issuedAccesses) { assert(p.launchId==launch && issued.insert(p.requestUid).second); lastIssue=std::max(lastIssue,p.cycleIssued); }
      for(const auto &p:r.completedAccesses) { assert(p.launchId==launch && completed.insert(p.requestUid).second && p.cycleIssued<=cycle); }
      for(auto id:r.completedBundleIds) assert(bundles.insert(id).second);
      const auto &d=r.wakeDiagnostics; ordinaryWakes+=d.ordinaryWakeups;
      if(d.issueBoundaryReturns && issued.size()==6) { assert(cycle==lastIssue+1); legacyBoundary=true; }
      assert(d.transferWakeups==0);
      if(d.deadlineReturns) { assert(cycle==base+100 && stage==0); deadlineObserved=true; }
      if(d.phaseWakeups) {
        assert(completed.size()==6 && bundles.size()==6 && cycle==floor);
        assert(d.phaseWakeups==(stage==0?2:1)); ++stage;
      }
      std::cout<<"round="<<round<<" cycle="<<cycle<<" issued="<<issued.size()<<" completed="<<completed.size()
        <<" phase="<<d.phaseWakeups<<" ordinary="<<d.ordinaryWakeups<<" issue_boundary="<<d.issueBoundaryReturns<<std::endl;
      status={}; status.currentCycle=cycle; status.idle=stage==2;
      if(launch && !announced) { status.acceptedLaunchIds={launch}; status.dispatchedLaunchIds={launch}; announced=true; }
      if(stage==2) {
        assert(!response.hasPendingWork && legacyBoundary && deadlineObserved && ordinaryWakes==1);
        status.completedLaunchIds={launch}; status.mainLoopComplete=closed;
      }
      socket.SendMessage(status);
      if(status.mainLoopComplete) { std::cout<<"PASS TMA metasim fixture"<<std::endl; return 0; }
    }
    throw std::runtime_error("fixture did not complete");
  } catch(const std::exception &e) { std::cerr<<e.what()<<std::endl; return 1; }
}
