// Interoperability with the actual gpu_model serializers, not a second mirror.
#include "bridges/trafficgen_socket_protocol.h"
#include "socket_transport.h"
#include <cassert>
#include <iostream>
namespace wire = trafficgen_socket;
// Serialization needs only this empty upstream constructor, not scheduler code.
GPU::L2Access::L2Access() = default;

template<class T> std::string gpu_encode(const T &value) {
  std::ostringstream out(std::ios::binary);
  boost::archive::binary_oarchive archive(out);
  GPU::SocketMessageHeader<boost::archive::binary_oarchive, T>(archive);
  archive << value;
  return out.str();
}
template<class T> T gpu_decode(const std::string &bytes) {
  std::istringstream in(bytes, std::ios::binary);
  boost::archive::binary_iarchive archive(in);
  GPU::SocketMessageHeader<boost::archive::binary_iarchive, T>(archive);
  T value{}; archive >> value; return value;
}
template<class F> void must_fail(F f) {
  bool failed = false;
  try { f(); } catch (const std::exception &) { failed = true; }
  assert(failed);
}
int main() {
  constexpr std::uint64_t high = 1ULL << 50;
  wire::TrafficGenInitializationMessage init;
  init.currentCycle = high + 1;
  auto gpu_init = gpu_decode<GPU::TrafficGenInitializationMessage>(wire::serialize_message(init));
  assert(gpu_init.currentCycle == init.currentCycle && gpu_init.protocolVersion == 2);
  wire::RoundControlMessage control;
  control.currentCycle = high + 2;
  control.launches = {{high + 10, high + 100, 1}, {high + 11, high + 101, 2}};
  control.reservedSubpartitionsByCycle[high + 5] = {3, 7};
  control.endOfLaunches = true;
  auto gpu_control = gpu_decode<GPU::RoundControlMessage>(wire::serialize_message(control));
  assert(gpu_control.currentCycle == control.currentCycle && gpu_control.endOfLaunches);
  assert(gpu_control.launches[1].registryId == high + 11 && gpu_control.launches[1].launchId == high + 101);
  assert(gpu_control.launches[1].streamId == 2 && gpu_control.reservedSubpartitionsByCycle == control.reservedSubpartitionsByCycle);
  GPU::SchedulingRoundStateMessage status;
  status.idle = false; status.currentCycle = high + 2; status.truncated = true;
  status.acceptedLaunchIds = {high + 100}; status.dispatchedLaunchIds = {high + 100};
  status.completedLaunchIds = {high + 99}; status.rejectedLaunches = {{high + 101, "unknown registryId"}};
  auto received_status = wire::deserialize_message<wire::SchedulingRoundStateMessage>(gpu_encode(status));
  assert(!received_status.idle && received_status.truncated && received_status.currentCycle == high + 2);
  assert(received_status.acceptedLaunchIds == status.acceptedLaunchIds && received_status.dispatchedLaunchIds == status.dispatchedLaunchIds);
  assert(received_status.completedLaunchIds == status.completedLaunchIds && received_status.rejectedLaunches[0].reason == "unknown registryId");
  GPU::SchedulerRoundMessage schedule; schedule.min_issue_cycle = high + 20;
  for (unsigned i = 0; i < 2; ++i) {
    GPU::L2Access access;
    access.mUniqueId = 7; access.launchId = high + 100 + i; access.registryId = high + 10 + i;
    access.mAddress = high + 0x100; access.mCycleCount = high + 5 + i;
    access.mSubpartition = 3; access.mSetIndex = 2; access.mTag = high + 0x80;
    access.mMask = 15; access.smId = 4; access.schedulerId = 2; access.warpId = 9;
    access.mIsWrite = true; access.mBundleId = high + 200 + i; access.mWakeRelevantBundle = false;
    access.mKernelFolder = "same_recorded_kernel";
    schedule.allL2TraceSteps.push_back(access); schedule.blockedWarpIds.insert(access.GetWarpKey());
  }
  auto received = wire::deserialize_message<wire::SchedulerRoundMessage>(gpu_encode(schedule));
  assert(received.allL2TraceSteps.size() == 2 && received.blockedWarpIds.size() == 2 && received.min_issue_cycle == high + 20);
  for (unsigned i = 0; i < 2; ++i) {
    const auto &a = received.allL2TraceSteps[i];
    assert(a.launchId == high + 100 + i && a.registryId == high + 10 + i && a.mUniqueId == 7);
    assert(a.mBundleId == high + 200 + i && a.mIsWrite && !a.mWakeRelevantBundle);
    assert(a.mAddress == high + 0x100 && a.mCycleCount == high + 5 + i && a.mSubpartition == 3 && a.mSetIndex == 2);
    assert(a.mTag == high + 0x80 && a.mMask == 15 && a.smId == 4 && a.schedulerId == 2 && a.warpId == 9);
    assert(a.mKernelFolder == "same_recorded_kernel");
    assert(received.blockedWarpIds.count({4, 2, 9, high + 100 + i}) == 1);
  }
  // Re-encode the mirror back through the official reader: class versions must
  // match on both output and input, including nested vector/set item versions.
  auto roundtrip = gpu_decode<GPU::SchedulerRoundMessage>(wire::serialize_message(received));
  assert(roundtrip.allL2TraceSteps[1].launchId == high + 101 && roundtrip.blockedWarpIds.size() == 2);
  wire::TrafficGenResultMessage result;
  result.trafficGenResult.issuedAccesses = {{7, high + 5, high + 0x100, true, high + 100}, {7, high + 6, high + 0x100, false, high + 101}};
  result.trafficGenResult.completedBundleIds = {high + 200, high + 201};
  result.trafficGenResult.currentCycleAfterIssue = high + 30; result.hasPendingWork = true;
  auto gpu_result = gpu_decode<GPU::TrafficGenResultMessage>(wire::serialize_message(result));
  assert(gpu_result.hasPendingWork && gpu_result.trafficGenResult.currentCycleAfterIssue == high + 30);
  assert(gpu_result.trafficGenResult.issuedAccesses[1].launchId == high + 101);
  assert(gpu_result.trafficGenResult.issuedAccesses[0].isWrite && !gpu_result.trafficGenResult.issuedAccesses[1].isWrite);
  assert(gpu_result.trafficGenResult.completedBundleIds == result.trafficGenResult.completedBundleIds);
  must_fail([&] { wire::deserialize_message<wire::RoundControlMessage>(gpu_encode(status)); });
  must_fail([&] { gpu_decode<GPU::SchedulingRoundStateMessage>(wire::serialize_message(result)); });
  for (unsigned mode = 0; mode < 3; ++mode) {
    std::ostringstream out(std::ios::binary); boost::archive::binary_oarchive archive(out);
    std::uint32_t magic = mode == 0 ? 0 : wire::kSocketMagic, version = mode == 1 ? 1 : 2, kind = mode == 2 ? 5 : 2;
    archive & magic; archive & version; archive & kind; archive << status;
    must_fail([&] { wire::deserialize_message<wire::SchedulingRoundStateMessage>(out.str()); });
  }
  std::cout << "TrafficGen protocol v2 official producer/consumer tests passed\n";
}
