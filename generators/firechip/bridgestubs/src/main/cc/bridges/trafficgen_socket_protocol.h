// Protocol v7 mirror of gpu_model commit 7b6abcafe312c21e87393181410eb2f2aea1d938.
// Keep archive field order and Boost class versions synchronized; checked by the
// producer/consumer interoperability test. Normal driver builds need no GPU checkout.
#ifndef TRAFFICGEN_SOCKET_PROTOCOL_H
#define TRAFFICGEN_SOCKET_PROTOCOL_H
#include <cstdint>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <boost/archive/binary_iarchive.hpp>
#include <boost/archive/binary_oarchive.hpp>
#include <boost/serialization/access.hpp>
#include <boost/serialization/library_version_type.hpp>
#include <boost/serialization/map.hpp>
#include <boost/serialization/set.hpp>
#include <boost/serialization/string.hpp>
#include <boost/serialization/unordered_map.hpp>
#include <boost/serialization/unordered_set.hpp>
#include <boost/serialization/vector.hpp>
#include <boost/serialization/version.hpp>
namespace trafficgen_socket {
using L2SubpartitionReservationsByCycle = std::unordered_map<std::uint64_t, std::unordered_set<unsigned>>;
using OrderedL2SubpartitionReservationsByCycle = std::map<std::uint64_t, std::set<unsigned>>;
constexpr std::uint32_t kSocketProtocolVersion = 7;
constexpr std::uint32_t kSocketMagic = 0x47505532;
struct TrafficGenInitializationMessage {
  std::uint64_t currentCycle = 0;
  std::uint32_t protocolVersion = kSocketProtocolVersion;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & protocolVersion;
    ar & currentCycle;
  }
};

struct KernelLaunchRequest {
  std::uint64_t registryId = 0, launchId = 0, streamId = 0;
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & registryId; ar & launchId; ar & streamId;
  }
};
struct LaunchRejection {
  std::uint64_t launchId = 0;
  std::string reason;
  template<class Archive> void serialize(Archive &ar, unsigned) { ar & launchId; ar & reason; }
};
struct SchedulingRoundStateMessage {
  bool mainLoopComplete = false, idle = true, truncated = false;
  std::uint64_t currentCycle = 0;
  std::vector<std::uint64_t> acceptedLaunchIds, dispatchedLaunchIds, completedLaunchIds;
  std::vector<LaunchRejection> rejectedLaunches;
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & mainLoopComplete; ar & idle; ar & truncated; ar & currentCycle;
    ar & acceptedLaunchIds; ar & dispatchedLaunchIds; ar & completedLaunchIds; ar & rejectedLaunches;
  }
};
struct RoundControlMessage {
  std::uint64_t currentCycle = 0;
  L2SubpartitionReservationsByCycle reservedSubpartitionsByCycle;
  std::vector<KernelLaunchRequest> launches;
  bool endOfLaunches = false;
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & currentCycle; ar & reservedSubpartitionsByCycle; ar & launches; ar & endOfLaunches;
  }
};

struct socket_warp_key_t {
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;
  std::uint64_t launchId = 0;

  bool operator<(const socket_warp_key_t &other) const {
    return std::tie(launchId, smId, schedulerId, warpId) <
           std::tie(other.launchId, other.smId, other.schedulerId, other.warpId);
  }

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int version) {
    ar & smId;
    ar & schedulerId;
    ar & warpId;
    if (version > 0) ar & launchId;
  }
};

enum class LrcBatchAction { None, Issue, Attach, Reuse };
struct socket_l2_access_t {
  std::uint64_t mUniqueId = 0;
  std::uint64_t mAddress = 0;
  std::uint64_t mCycleCount = 0;
  unsigned mSubpartition = 0;
  unsigned mSetIndex = 0;
  std::uint64_t mTag = 0;
  unsigned mMask = 0;
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;
  bool mIsWrite = false;
  std::uint64_t mBundleId = 0;
  bool mWakeRelevantBundle = false;
  std::string mKernelFolder;
  std::uint64_t launchId = 0, registryId = 0;
  bool mLrcEnabled = false, mHasLrcService = false;
  std::uint64_t mLrcServiceId = 0, mLrcIssueCycle = 0, mLrcLatency = 0;
  std::uint64_t mPhysicalId = 0, mSourceReadyCycle = 0;
  bool mPriorRoundJoin = false;
  LrcBatchAction mBatchAction = LrcBatchAction::None;
  std::vector<socket_l2_access_t> mContributors;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int version) {
    ar & mUniqueId;
    ar & mAddress;
    ar & mCycleCount;
    ar & mSubpartition;
    ar & mSetIndex;
    ar & mTag;
    ar & mMask;
    ar & smId;
    ar & schedulerId;
    ar & warpId;
    ar & mIsWrite;
    ar & mBundleId;
    ar & mWakeRelevantBundle;
    ar & mKernelFolder;
    if (version > 0) { ar & launchId; ar & registryId; }
    if (version > 1) {
      ar & mLrcEnabled & mHasLrcService & mLrcServiceId & mLrcIssueCycle & mLrcLatency;
      ar & mPhysicalId & mSourceReadyCycle & mPriorRoundJoin & mBatchAction & mContributors;
    }
  }
};


// Stable across scheduler transport-bundle remapping and concurrent launches.
struct TmaRequestIdentity {
    std::uint64_t launchId = 0, requestUid = 0;
    bool operator<(const TmaRequestIdentity &other) const {
        return std::tie(launchId, requestUid) < std::tie(other.launchId, other.requestUid);
    }
    bool operator==(const TmaRequestIdentity &other) const {
        return launchId == other.launchId && requestUid == other.requestUid;
    }
    template<class Archive> void serialize(Archive &ar, unsigned) { ar & launchId & requestUid; }
};

struct TmaWakeGroup {
    std::uint64_t launchId = 0;
    unsigned destinationCta = 0;
    std::uint64_t barrierAddress = 0, phase = 0;
    // Nonzero only for conservative transfer groups; phase groups retain the
    // destination identity above and never create synthetic bundle completions.
    std::uint64_t transferId = 0;
    bool conservative = false;
    std::uint64_t readyCycle = 0;
    std::vector<TmaRequestIdentity> requests;
    template<class Archive> void serialize(Archive &ar, unsigned) {
        ar & launchId & destinationCta & barrierAddress & phase & transferId;
        ar & conservative & readyCycle & requests;
    }
};

struct TmaWakePolicy {
    // A complete replacement snapshot for this exchange, not an incremental update.
    std::vector<TmaWakeGroup> groups;
    std::set<TmaRequestIdentity> outstandingRequests;
    template<class Archive> void serialize(Archive &ar, unsigned) { ar & groups & outstandingRequests; }
};

struct TrafficWakeDiagnostics {
    std::uint64_t phaseWakeups = 0, transferWakeups = 0;
    std::uint64_t tmaCompletionsWithoutWakeup = 0, ordinaryWakeups = 0;
    std::uint64_t deadlineReturns = 0, issueBoundaryReturns = 0, drainReturns = 0;
    template<class Archive> void serialize(Archive &ar, unsigned) {
        ar & phaseWakeups & transferWakeups & tmaCompletionsWithoutWakeup & ordinaryWakeups;
        ar & deadlineReturns & issueBoundaryReturns & drainReturns;
    }
};

struct SchedulerRoundMessage {
  std::vector<socket_l2_access_t> allL2TraceSteps;
  std::uint64_t min_issue_cycle = 0;
  std::set<socket_warp_key_t> blockedWarpIds;
  TmaWakePolicy tmaWakePolicy;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & allL2TraceSteps;
    ar & min_issue_cycle;
    ar & blockedWarpIds;
    ar & tmaWakePolicy;
  }
};

struct SocketAllL2TraceStepsSnapshot {
  std::vector<socket_l2_access_t> steps;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & steps;
  }
};

struct SocketBlockedWarpIdsSnapshot {
  std::set<socket_warp_key_t> blockedWarpIds;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & blockedWarpIds;
  }
};

struct OrderedReservedSubpartitionsSnapshot {
  OrderedL2SubpartitionReservationsByCycle reservationsByCycle;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & reservationsByCycle;
  }
};

struct IssuedAccessPoint {
  std::uint64_t requestUid = 0;
  std::uint64_t cycleIssued = 0;
  std::uint64_t address = 0;
  bool isWrite = false;
  std::uint64_t launchId = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int version) {
    ar & requestUid;
    ar & cycleIssued;
    ar & address;
    ar & isWrite;
    if (version > 0) ar & launchId;
  }
};

struct PhysicalAccessPoint {
  std::uint64_t physicalId = 0, recordedServiceId = 0, sourceRequestUid = 0, cycleIssued = 0;
  std::uint64_t address = 0;
  bool isWrite = false;
  std::uint64_t launchId = 0;
  bool isLrcService = false;
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & physicalId & recordedServiceId & sourceRequestUid & cycleIssued;
    ar & address & isWrite & launchId & isLrcService;
  }
};
struct PhysicalCompletionPoint {
  std::uint64_t physicalId = 0, recordedServiceId = 0, launchId = 0, cycleCompleted = 0;
  std::vector<std::uint64_t> requestUids;
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & physicalId & recordedServiceId & launchId & cycleCompleted & requestUids;
  }
};
struct LrcStatistics {
  std::uint64_t sourceReads = 0, sourceWrites = 0, physicalReads = 0, physicalWrites = 0;
  std::uint64_t joinedContributors = 0, crossRoundJoins = 0, splitServices = 0;
  std::uint64_t distinctSplitGroups = 0, reusedContributors = 0;
  LrcStatistics &operator+=(const LrcStatistics &r) {
    sourceReads += r.sourceReads; sourceWrites += r.sourceWrites;
    physicalReads += r.physicalReads; physicalWrites += r.physicalWrites;
    joinedContributors += r.joinedContributors; crossRoundJoins += r.crossRoundJoins;
    splitServices += r.splitServices; distinctSplitGroups += r.distinctSplitGroups;
    reusedContributors += r.reusedContributors; return *this;
  }
  template<class Archive> void serialize(Archive &ar, unsigned) {
    ar & sourceReads & sourceWrites & physicalReads & physicalWrites;
    ar & joinedContributors & crossRoundJoins & splitServices & distinctSplitGroups & reusedContributors;
  }
};
struct IssueScheduleResult {
  std::vector<IssuedAccessPoint> issuedAccesses;
  std::vector<std::uint64_t> completedBundleIds;
  std::uint64_t currentCycleAfterIssue = 0;
  std::vector<PhysicalAccessPoint> physicalAccesses;
  std::vector<PhysicalCompletionPoint> physicalCompletions;
  LrcStatistics lrcStats;
  std::map<std::uint64_t, LrcStatistics> lrcStatsByLaunch;
  std::vector<IssuedAccessPoint> sourceReadyAccesses, completedAccesses, reusedAccesses;
  TrafficWakeDiagnostics wakeDiagnostics;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int version) {
    ar & issuedAccesses;
    ar & completedBundleIds;
    ar & currentCycleAfterIssue;
    if (version > 0) ar & physicalAccesses & physicalCompletions & lrcStats & lrcStatsByLaunch;
    if (version > 1) ar & sourceReadyAccesses;
    if (version > 2) ar & completedAccesses & reusedAccesses;
    if (version > 3) ar & wakeDiagnostics;
  }
};

struct TrafficGenResultMessage {
  IssueScheduleResult trafficGenResult;
  bool hasPendingWork = false;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & trafficGenResult;
    ar & hasPendingWork;
  }
};

struct IssuedAccessesSnapshot {
  std::vector<IssuedAccessPoint> issuedAccesses;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & issuedAccesses;
  }
};

struct CompletedBundleIdsSnapshot {
  std::vector<std::uint64_t> completedBundleIds;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & completedBundleIds;
  }
};

struct CurrentCycleAfterIssueSnapshot {
  std::uint64_t currentCycleAfterIssue = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & currentCycleAfterIssue;
  }
};

struct MinIssueCycleSnapshot {
  std::uint64_t minIssueCycle = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & minIssueCycle;
  }
};

} // namespace trafficgen_socket
BOOST_CLASS_VERSION(trafficgen_socket::socket_warp_key_t, 1)
BOOST_CLASS_VERSION(trafficgen_socket::socket_l2_access_t, 2)
BOOST_CLASS_VERSION(trafficgen_socket::IssuedAccessPoint, 1)
BOOST_CLASS_VERSION(trafficgen_socket::IssueScheduleResult, 4)
namespace trafficgen_socket {
template<class T> struct message_tag;
template<> struct message_tag<TrafficGenInitializationMessage> { static constexpr unsigned value = 1; };
template<> struct message_tag<SchedulingRoundStateMessage> { static constexpr unsigned value = 2; };
template<> struct message_tag<RoundControlMessage> { static constexpr unsigned value = 3; };
template<> struct message_tag<SchedulerRoundMessage> { static constexpr unsigned value = 4; };
template<> struct message_tag<TrafficGenResultMessage> { static constexpr unsigned value = 5; };
template<class T, class Archive> void message_header(Archive &ar) {
  std::uint32_t magic = kSocketMagic, version = kSocketProtocolVersion, kind = message_tag<T>::value;
  ar & magic; ar & version; ar & kind;
  if (magic != kSocketMagic || version != kSocketProtocolVersion || kind != message_tag<T>::value)
    throw std::runtime_error("TrafficGen requires socket protocol v6 and the expected message type (received version " + std::to_string(version) + ")");
}
template<class T> std::string serialize_message(const T &message) {
  std::ostringstream stream(std::ios::binary);
  boost::archive::binary_oarchive archive(stream);
  message_header<T>(archive);
  archive << message;
  return stream.str();
}
template<class T> T deserialize_message(const std::string &payload) {
  std::istringstream stream(payload, std::ios::binary);
  boost::archive::binary_iarchive archive(stream);
  message_header<T>(archive);
  T message{};
  archive >> message;
  return message;
}
} // namespace trafficgen_socket
#endif
