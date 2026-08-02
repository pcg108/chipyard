#if __has_include(<svdpi.h>)
#include <svdpi.h>
#else
using svBit = unsigned char;
#endif

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <boost/archive/binary_iarchive.hpp>
#include <boost/archive/binary_oarchive.hpp>
#include <boost/serialization/access.hpp>
#include <boost/serialization/map.hpp>
#include <boost/serialization/set.hpp>
#include <boost/serialization/string.hpp>
#include <boost/serialization/vector.hpp>

namespace {

constexpr const char *kDefaultTraceFolder =
    "/home/prashanth/gpu_model/accel-sim-data-rodinia_nn";
constexpr const char *kDefaultKernelName = "kernel_1__Z6euclidPcffPfiii";
constexpr const char *kDpiLogPath = "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0/dpi_log.txt";
constexpr const char *kDefaultRoundLogDir = "trafficgen_dpi";
constexpr const char *kRoundLogBase = "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0";
constexpr std::uint32_t kDpiStateFatalConfigError = 0xdead0001;
constexpr std::uint32_t kRoundExitScheduling = 0;
constexpr std::uint32_t kRoundExitCapacity = 1;
constexpr std::size_t kMaxCompletedBundleIds = 4096;

struct WarpKey {
  std::uint32_t smId = 0;
  std::uint32_t schedulerId = 0;
  std::uint32_t warpId = 0;

  bool operator<(const WarpKey &other) const {
    return std::tie(smId, schedulerId, warpId) <
           std::tie(other.smId, other.schedulerId, other.warpId);
  }

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & smId;
    ar & schedulerId;
    ar & warpId;
  }
};

struct Access {
  std::uint64_t id = 0;
  std::uint64_t address = 0;
  std::uint64_t cycleCount = 0;
  std::uint32_t subpartition = 0;
  std::uint32_t setIndex = 0;
  std::uint64_t tag = 0;
  std::uint32_t mask = 0;
  std::uint32_t smId = 0;
  std::uint32_t schedulerId = 0;
  std::uint32_t warpId = 0;
  std::uint64_t bundleId = 0;
  bool wakeRelevantBundle = false;
  bool isWrite = false;
  bool warpBlocked = false;
  std::string kernelFolder;

  WarpKey warpKey() const { return WarpKey{smId, schedulerId, warpId}; }

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & id;
    ar & address;
    ar & cycleCount;
    ar & subpartition;
    ar & setIndex;
    ar & tag;
    ar & mask;
    ar & smId;
    ar & schedulerId;
    ar & warpId;
    ar & isWrite;
    ar & bundleId;
    ar & wakeRelevantBundle;
    ar & kernelFolder;
  }
};

struct PendingAccessInfo {
  std::uint64_t finishCycle = 0;
  WarpKey warpKey;
  std::uint64_t bundleId = 0;
};

struct OutstandingBundleInfo {
  unsigned remainingRequestCount = 0;
  WarpKey warpKey;
  bool wakeRelevant = false;
  bool warpBlocked = false;
};

struct DebugCompletionEvent {
  bool valid = false;
  bool wakeExit = false;
  std::uint64_t bundleId = 0;
  bool wakeRelevant = false;
  bool warpBlocked = false;
  bool currentWarpBlocked = false;
  std::uint32_t smId = 0;
  std::uint32_t schedulerId = 0;
  std::uint32_t warpId = 0;
  std::uint64_t cycle = 0;
};

using TimingMap = std::unordered_map<std::uint64_t, int>;
using SchedulerKey = std::tuple<std::string, std::string, unsigned, unsigned>;
using ReservedSubpartitionsByCycle = std::map<std::uint64_t, std::set<unsigned>>;

struct ReservedSubpartitionsSnapshot {
  ReservedSubpartitionsByCycle reservationsByCycle;

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & reservationsByCycle;
  }
};

struct AllL2TraceStepsSnapshot {
  std::vector<Access> steps;

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & steps;
  }
};

template <typename T, typename U>
T dpiArrayValue(const U *array, int idx) {
  return array ? static_cast<T>(array[idx]) : T{};
}

bool dpiArrayBit(const svBit *array, int idx) {
  return dpiArrayValue<svBit>(array, idx) != 0;
}

struct IssuedAccessPoint {
  std::uint64_t requestUid = 0;
  std::uint64_t cycleIssued = 0;
  std::uint64_t address = 0;
  bool isWrite = false;

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & requestUid;
    ar & cycleIssued;
    ar & address;
    ar & isWrite;
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

struct BlockedWarpIdsSnapshot {
  std::set<WarpKey> blockedWarpIds;

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & blockedWarpIds;
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

std::ofstream &dpiLog();

struct RuntimeConfig {
  std::filesystem::path traceRoot;
  std::string kernelName;
  std::filesystem::path roundLogRoot;
  bool valid = true;
  std::string errorMessage;
};

std::vector<std::string> readProcessArgs() {
  std::vector<std::string> args;
  std::ifstream ifs("/proc/self/cmdline", std::ios::binary);
  if (!ifs) {
    return args;
  }

  std::string arg;
  while (std::getline(ifs, arg, '\0')) {
    if (!arg.empty()) {
      args.push_back(arg);
    }
  }
  return args;
}

std::string plusargValue(const std::vector<std::string> &args,
                         const std::string &key) {
  const std::string prefix = "+" + key + "=";
  for (const auto &arg : args) {
    if (arg.rfind(prefix, 0) == 0) {
      return arg.substr(prefix.size());
    }
  }
  return "";
}

std::filesystem::path normalizeTraceRoot(std::filesystem::path traceFolder) {
  std::error_code ec;
  if (traceFolder.filename() == "l2_trace") {
    const auto canonical = std::filesystem::weakly_canonical(traceFolder, ec);
    return ec ? traceFolder.lexically_normal() : canonical;
  }

  const auto l2TracePath = traceFolder / "l2_trace";
  if (std::filesystem::exists(l2TracePath, ec) &&
      std::filesystem::is_directory(l2TracePath, ec)) {
    const auto canonical = std::filesystem::weakly_canonical(l2TracePath, ec);
    return ec ? l2TracePath.lexically_normal() : canonical;
  }

  const auto canonical = std::filesystem::weakly_canonical(traceFolder, ec);
  return ec ? traceFolder.lexically_normal() : canonical;
}

const RuntimeConfig &runtimeConfig() {
  static const RuntimeConfig config = []() {
    const auto args = readProcessArgs();
    std::string traceFolder =
        plusargValue(args, "trafficgen-trace-folder");
    if (traceFolder.empty()) {
      traceFolder = plusargValue(args, "trafficgen-trace-root");
    }
    if (traceFolder.empty()) {
      traceFolder = kDefaultTraceFolder;
    }

    std::string kernelName = plusargValue(args, "trafficgen-kernel");
    if (kernelName.empty()) {
      kernelName = kDefaultKernelName;
    }

    std::string roundLogDir =
        plusargValue(args, "trafficgen-dpi-round-log-dir");
    if (roundLogDir.empty()) {
      roundLogDir = kDefaultRoundLogDir;
    }
    std::filesystem::path roundLogRoot(roundLogDir);
    if (roundLogRoot.is_relative()) {
      roundLogRoot = std::filesystem::path(kRoundLogBase) / roundLogRoot;
    }

    RuntimeConfig config{normalizeTraceRoot(traceFolder),
                         kernelName,
                         roundLogRoot.lexically_normal()};
    std::error_code ec;
    if (!std::filesystem::exists(config.traceRoot, ec) ||
        !std::filesystem::is_directory(config.traceRoot, ec)) {
      config.valid = false;
      config.errorMessage =
          "TrafficGen DPI trace folder does not exist or is not a directory: " +
          config.traceRoot.string();
    } else {
      const auto kernelTraceFolder = config.traceRoot / config.kernelName;
      if (!std::filesystem::exists(kernelTraceFolder, ec) ||
          !std::filesystem::is_directory(kernelTraceFolder, ec)) {
        config.valid = false;
        config.errorMessage =
            "TrafficGen DPI kernel trace folder does not exist or is not a directory: " +
            kernelTraceFolder.string();
      }
    }

    dpiLog() << "[trafficgen dpi] trace_root=" << config.traceRoot
             << " kernel=" << config.kernelName
             << " round_log_root=" << config.roundLogRoot
             << " valid=" << (config.valid ? "true" : "false") << "\n";
    if (!config.valid) {
      dpiLog() << "[trafficgen dpi] fatal: " << config.errorMessage << "\n";
      std::cerr << "[trafficgen dpi] fatal: " << config.errorMessage << std::endl;
    }
    dpiLog().flush();
    return config;
  }();
  return config;
}

std::vector<std::filesystem::path> findTimingFiles(unsigned smId,
                                                   unsigned schedulerId) {
  static std::map<SchedulerKey, std::vector<std::filesystem::path>> cache;
  const auto &config = runtimeConfig();
  const SchedulerKey key{
      config.traceRoot.string(), config.kernelName, smId, schedulerId};
  const auto found = cache.find(key);
  if (found != cache.end()) {
    return found->second;
  }

  std::vector<std::filesystem::path> matches;
  std::error_code ec;
  const std::filesystem::path root = config.traceRoot;
  if (!std::filesystem::exists(root, ec)) {
    cache.emplace(key, matches);
    return matches;
  }

  const auto timingPath =
      root / config.kernelName / ("shader_" + std::to_string(smId)) /
      ("scheduler_" + std::to_string(schedulerId)) / "l2_to_icnt_timing.txt";
  if (std::filesystem::exists(timingPath, ec) &&
      std::filesystem::is_regular_file(timingPath, ec)) {
    matches.push_back(timingPath);
  }

  cache.emplace(key, matches);
  return matches;
}

bool parseTimingLine(const std::string &line,
                     std::uint64_t &requestUidOut,
                     int &elapsedCycleOut) {
  std::unordered_map<std::string, std::string> fields;
  std::istringstream stream(line);
  std::string token;
  while (stream >> token) {
    const std::size_t equalPos = token.find('=');
    if (equalPos == std::string::npos || equalPos + 1 >= token.size()) {
      continue;
    }
    fields[token.substr(0, equalPos)] = token.substr(equalPos + 1);
  }

  const auto requestUidIt = fields.find("request_uid");
  const auto elapsedIt = fields.find("elapsed_cycle");
  if (requestUidIt == fields.end() || elapsedIt == fields.end()) {
    return false;
  }

  requestUidOut = static_cast<std::uint64_t>(
      std::strtoull(requestUidIt->second.c_str(), nullptr, 0));
  elapsedCycleOut = static_cast<int>(
      std::strtol(elapsedIt->second.c_str(), nullptr, 0));
  return true;
}

const TimingMap &loadTimingData(const std::filesystem::path &timingPath) {
  static std::unordered_map<std::string, TimingMap> timingCache;
  const std::string key = timingPath.lexically_normal().string();
  const auto found = timingCache.find(key);
  if (found != timingCache.end()) {
    return found->second;
  }

  TimingMap timings;
  std::ifstream ifs(timingPath);
  std::string line;
  while (std::getline(ifs, line)) {
    std::uint64_t requestUid = 0;
    int elapsedCycle = 0;
    if (parseTimingLine(line, requestUid, elapsedCycle)) {
      timings[requestUid] = elapsedCycle;
    }
  }

  return timingCache.emplace(key, std::move(timings)).first->second;
}

bool findAccessTime(const Access &access, int &accessTime) {
  const auto timingFiles = findTimingFiles(access.smId, access.schedulerId);
  for (const auto &timingFile : timingFiles) {
    const auto &timings = loadTimingData(timingFile);
    const auto it = timings.find(access.id);
    if (it != timings.end()) {
      accessTime = it->second;
      return true;
    }
  }
  return false;
}

std::ofstream &dpiLog() {
  static const bool initialized = []() {
    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(kDpiLogPath).parent_path(), ec);
    return true;
  }();
  static std::ofstream log(kDpiLogPath, std::ios::out | std::ios::trunc);
  (void)initialized;
  return log;
}

std::string roundFolderName(std::uint64_t roundNumber) {
  std::ostringstream oss;
  oss << "round_";
  oss.width(6);
  oss.fill('0');
  oss << roundNumber;
  return oss.str();
}

template <typename T>
void writeBinarySnapshot(const std::filesystem::path &path, const T &snapshot) {
  std::ofstream ofs(path, std::ios::binary);
  if (!ofs.is_open()) {
    throw std::runtime_error("failed to open snapshot for writing: " + path.string());
  }
  boost::archive::binary_oarchive archive(ofs);
  archive << snapshot;
}

template <typename T>
T readBinarySnapshot(const std::filesystem::path &path) {
  std::ifstream ifs(path, std::ios::binary);
  if (!ifs.is_open()) {
    throw std::runtime_error("failed to open snapshot for reading: " + path.string());
  }
  boost::archive::binary_iarchive archive(ifs);
  T snapshot{};
  archive >> snapshot;
  return snapshot;
}

void writeRoundManifest(
    const std::filesystem::path &roundDir,
    std::uint64_t roundNumber,
    const ReservedSubpartitionsSnapshot &reservations,
    const AllL2TraceStepsSnapshot &steps,
    const BlockedWarpIdsSnapshot &blockedWarpIds,
    const MinIssueCycleSnapshot &minIssueCycle,
    const IssuedAccessesSnapshot &issuedAccesses,
    const CompletedBundleIdsSnapshot &completedBundleIds,
    const CurrentCycleAfterIssueSnapshot &currentCycleAfterIssue) {
  std::ofstream manifest(roundDir / "manifest.txt");
  if (!manifest.is_open()) {
    throw std::runtime_error("failed to write manifest: " +
                             (roundDir / "manifest.txt").string());
  }

  std::size_t reservedSubpartitionCount = 0;
  for (const auto &entry : reservations.reservationsByCycle) {
    reservedSubpartitionCount += entry.second.size();
  }

  manifest << "round=" << roundNumber << "\n";
  manifest << "files=reserved_subpartitions.bin,all_l2_trace_steps.bin,"
           << "blocked_warp_ids.bin,min_issue_cycle.bin,issued_accesses.bin,"
           << "completed_bundle_ids.bin,current_cycle_after_issue.bin\n";
  manifest << "reserved_cycle_count=" << reservations.reservationsByCycle.size() << "\n";
  manifest << "reserved_subpartition_count=" << reservedSubpartitionCount << "\n";
  manifest << "all_l2_trace_steps_count=" << steps.steps.size() << "\n";
  manifest << "blocked_warp_ids_count=" << blockedWarpIds.blockedWarpIds.size() << "\n";
  manifest << "min_issue_cycle=" << minIssueCycle.minIssueCycle << "\n";
  manifest << "issued_accesses_count=" << issuedAccesses.issuedAccesses.size() << "\n";
  manifest << "completed_bundle_ids_count=" << completedBundleIds.completedBundleIds.size() << "\n";
  manifest << "current_cycle_after_issue="
           << currentCycleAfterIssue.currentCycleAfterIssue << "\n";
}

void logRoundInputSnapshots(std::uint64_t roundNumber,
                            const ReservedSubpartitionsByCycle &reservations,
                            const std::vector<Access> &allL2TraceSteps,
                            const std::set<WarpKey> &blockedWarpIds,
                            std::uint64_t minIssueCycle) {
  const std::filesystem::path roundDir =
      runtimeConfig().roundLogRoot / roundFolderName(roundNumber);
  std::error_code ec;
  std::filesystem::create_directories(roundDir, ec);
  if (ec) {
    throw std::runtime_error("failed to create round log directory: " +
                             roundDir.string() + " (" + ec.message() + ")");
  }

  writeBinarySnapshot(roundDir / "reserved_subpartitions.bin",
                      ReservedSubpartitionsSnapshot{reservations});
  writeBinarySnapshot(roundDir / "all_l2_trace_steps.bin",
                      AllL2TraceStepsSnapshot{allL2TraceSteps});
  writeBinarySnapshot(roundDir / "blocked_warp_ids.bin",
                      BlockedWarpIdsSnapshot{blockedWarpIds});
  writeBinarySnapshot(roundDir / "min_issue_cycle.bin",
                      MinIssueCycleSnapshot{minIssueCycle});
}

void logRoundOutputSnapshots(std::uint64_t roundNumber,
                             const std::vector<IssuedAccessPoint> &issuedAccesses,
                             const std::vector<std::uint64_t> &completedBundleIds,
                             std::uint64_t currentCycleAfterIssue) {
  const std::filesystem::path roundDir =
      runtimeConfig().roundLogRoot / roundFolderName(roundNumber);
  std::error_code ec;
  std::filesystem::create_directories(roundDir, ec);
  if (ec) {
    throw std::runtime_error("failed to create round log directory: " +
                             roundDir.string() + " (" + ec.message() + ")");
  }

  const auto reservations =
      readBinarySnapshot<ReservedSubpartitionsSnapshot>(roundDir / "reserved_subpartitions.bin");
  const auto steps =
      readBinarySnapshot<AllL2TraceStepsSnapshot>(roundDir / "all_l2_trace_steps.bin");
  const auto blockedWarpIds =
      readBinarySnapshot<BlockedWarpIdsSnapshot>(roundDir / "blocked_warp_ids.bin");
  const auto minIssueCycle =
      readBinarySnapshot<MinIssueCycleSnapshot>(roundDir / "min_issue_cycle.bin");
  const IssuedAccessesSnapshot issuedAccessesSnapshot{issuedAccesses};
  const CompletedBundleIdsSnapshot completedBundleSnapshot{completedBundleIds};
  const CurrentCycleAfterIssueSnapshot currentCycleSnapshot{currentCycleAfterIssue};

  writeBinarySnapshot(roundDir / "issued_accesses.bin", issuedAccessesSnapshot);
  writeBinarySnapshot(roundDir / "completed_bundle_ids.bin", completedBundleSnapshot);
  writeBinarySnapshot(roundDir / "current_cycle_after_issue.bin", currentCycleSnapshot);
  writeRoundManifest(roundDir,
                     roundNumber,
                     reservations,
                     steps,
                     blockedWarpIds,
                     minIssueCycle,
                     issuedAccessesSnapshot,
                     completedBundleSnapshot,
                     currentCycleSnapshot);
}

class TrafficGenDPIModel {
public:
  void reset() {
    mState = State::Idle;
    mCurrentCycle = 0;
    mInflight.clear();
    mOutstandingBundles.clear();
    mPendingAccessesByCycle.clear();
    mPendingAccessIds.clear();
    mPendingAccessCycles.clear();
    mLoadedAccesses.clear();
    mReservedSubpartitionsByCycle.clear();
    mBlockedWarpSet.clear();
    mIssuedQueue.clear();
    mIssuedRequestIds.clear();
    mIssuedAccessPoints.clear();
    mCompletedBundleQueue.clear();
    mPendingBundleIdIdx = 0;
    mRoundCurrentCycle = 0;
    mRoundHasPendingWork = false;
    mRoundHasFutureIssueWork = false;
    mRoundCapacityBounded = false;
    mRoundExitReason = kRoundExitScheduling;
    mAccessLoadCycle = 0;
    mAccessLoadEndCycle = 0;
    mAccessReadRespConsumed = false;
    mLastConsumedAccessReadRespId = 0;
    mRoundNumber = 0;
    mDebugCompletionEvent = DebugCompletionEvent{};
  }

  void step(
      svBit startRound,
      svBit uploadReady,
      std::uint32_t accessStoreCount,
      std::uint64_t accessStoreMaxCycle,
      svBit accessStoreHasEntries,
      svBit accessStoreHasMore,
      std::uint64_t minIssueCycle,
      svBit accessReadRespValid,
      std::uint32_t accessReadRespId,
      const std::vector<Access> &accessReadBatch,
      svBit accessReadBucketDone,
      svBit accessReadReady,
      svBit issuedAccessReady,
      svBit *targetBusy,
      svBit *hasPendingWork,
      svBit *roundStarted,
      svBit *roundComplete,
      std::uint32_t *roundExitReason,
      std::uint64_t *currentCycleAfterIssue,
      std::uint32_t *dpiState,
      svBit *accessReadEn,
      std::uint64_t *accessReadCycle,
      svBit *accessReadBatchReady,
      svBit *issuedAccessValid,
      Access *issuedAccess,
      svBit *completedBundleCountWriteEn,
      std::uint32_t *completedBundleCountWriteData,
      svBit *completedBundleIdWriteEn,
      std::uint32_t *completedBundleIdWriteIdx,
      std::uint64_t *completedBundleIdWriteData,
      svBit *debugCompletionEventValid,
      svBit *debugCompletionEventWakeExit,
      std::uint64_t *debugCompletionEventBundleId,
      svBit *debugCompletionEventWakeRelevant,
      svBit *debugCompletionEventWarpBlocked,
      svBit *debugCompletionEventCurrentWarpBlocked,
      std::uint32_t *debugCompletionEventSmId,
      std::uint32_t *debugCompletionEventSchedulerId,
      std::uint32_t *debugCompletionEventWarpId,
      std::uint64_t *debugCompletionEventCycle) {
    const auto &config = runtimeConfig();
    bool roundFinishedThisStep = false;
    mDebugCompletionEvent = DebugCompletionEvent{};
    *targetBusy = (mState != State::Idle) ? 1 : 0;
    *hasPendingWork = (!mInflight.empty() || !mPendingAccessesByCycle.empty() ||
                       accessStoreCount != 0 || mState != State::Idle) ? 1 : 0;
    *roundStarted = 0;
    *roundComplete = 0;
    *roundExitReason = mRoundExitReason;
    *currentCycleAfterIssue = mCurrentCycle;
    *dpiState = stateCode(mState);
    *accessReadEn = 0;
    *accessReadCycle = 0;
    *accessReadBatchReady = 0;
    *issuedAccessValid = 0;
    *issuedAccess = Access{};
    *completedBundleCountWriteEn = 0;
    *completedBundleCountWriteData = 0;
    *completedBundleIdWriteEn = 0;
    *completedBundleIdWriteIdx = 0;
    *completedBundleIdWriteData = 0;

    if (!config.valid) {
      *targetBusy = 0;
      *hasPendingWork = 0;
      *roundStarted = 0;
      *roundComplete = 0;
      *roundExitReason = mRoundExitReason;
      *currentCycleAfterIssue = mCurrentCycle;
      *dpiState = kDpiStateFatalConfigError;
      writeDebugCompletionEvent(debugCompletionEventValid,
                                debugCompletionEventWakeExit,
                                debugCompletionEventBundleId,
                                debugCompletionEventWakeRelevant,
                                debugCompletionEventWarpBlocked,
                                debugCompletionEventCurrentWarpBlocked,
                                debugCompletionEventSmId,
                                debugCompletionEventSchedulerId,
                                debugCompletionEventWarpId,
                                debugCompletionEventCycle);
      return;
    }

    if (mState == State::Idle) {
      if (startRound && uploadReady) {
        mLoadedAccesses.clear();
        mLoadedAccesses.reserve(accessStoreCount);
        mAccessLoadCycle = mCurrentCycle;
        const bool loadToMaxResidentCycle =
            minIssueCycle == std::numeric_limits<std::uint64_t>::max();
        mAccessLoadEndCycle =
            loadToMaxResidentCycle ? accessStoreMaxCycle
                                   : std::min(accessStoreMaxCycle, minIssueCycle);
        resetAccessReadResponseConsumption();
        mBlockedWarpSet.clear();
        mIssuedQueue.clear();
        mCompletedBundleQueue.clear();
        mPendingBundleIdIdx = 0;
        mRoundCurrentCycle = mCurrentCycle;
        mRoundHasPendingWork = false;
        mRoundExitReason = kRoundExitScheduling;
        mRoundCapacityBounded = accessStoreHasMore;
        mRoundHasFutureIssueWork =
            accessStoreHasEntries && accessStoreMaxCycle > mAccessLoadEndCycle;
        mRoundMinIssueCycle = minIssueCycle;
        ++mRoundNumber;
        const bool skipAccessLoad =
            (loadToMaxResidentCycle && !accessStoreHasEntries) ||
            mAccessLoadCycle > mAccessLoadEndCycle;
        mState = skipAccessLoad ? State::PrepareBlockedQueries
                                : State::RequestAccess;
        *roundStarted = 1;
      }
      *dpiState = stateCode(mState);
      writeDebugCompletionEvent(debugCompletionEventValid,
                                debugCompletionEventWakeExit,
                                debugCompletionEventBundleId,
                                debugCompletionEventWakeRelevant,
                                debugCompletionEventWarpBlocked,
                                debugCompletionEventCurrentWarpBlocked,
                                debugCompletionEventSmId,
                                debugCompletionEventSchedulerId,
                                debugCompletionEventWarpId,
                                debugCompletionEventCycle);
      return;
    }

    switch (mState) {
    case State::RequestAccess:
      resetAccessReadResponseConsumption();
      *accessReadCycle = mAccessLoadCycle;
      if (accessReadReady) {
        *accessReadEn = 1;
        mState = State::WaitAccessAccepted;
      }
      break;
    case State::WaitAccessAccepted:
      *accessReadEn = 1;
      *accessReadCycle = mAccessLoadCycle;
      if (!accessReadReady) {
        mState = State::WaitAccess;
      }
      break;
    case State::WaitAccess: {
      *accessReadBatchReady = 1;
      if (!accessReadRespValid) {
        mAccessReadRespConsumed = false;
      }
      const bool newAccessReadResp =
          accessReadRespValid &&
          (!mAccessReadRespConsumed ||
           accessReadRespId != mLastConsumedAccessReadRespId);
      if (newAccessReadResp) {
        mLoadedAccesses.insert(
            mLoadedAccesses.end(), accessReadBatch.begin(), accessReadBatch.end());
        mAccessReadRespConsumed = true;
        mLastConsumedAccessReadRespId = accessReadRespId;
        if (accessReadBucketDone) {
          if (mAccessLoadCycle >= mAccessLoadEndCycle ||
              mAccessLoadCycle == std::numeric_limits<std::uint64_t>::max()) {
            resetAccessReadResponseConsumption();
            mState = State::PrepareBlockedQueries;
          } else {
            ++mAccessLoadCycle;
            resetAccessReadResponseConsumption();
            mState = State::RequestAccess;
          }
        } else {
          mState = State::WaitAccessReadyLow;
        }
      }
      break;
    }
    case State::WaitAccessReadyLow:
      if (!accessReadRespValid) {
        resetAccessReadResponseConsumption();
        mState = State::WaitAccess;
      }
      break;
    case State::PrepareBlockedQueries:
      buildBlockedWarpSet();
      logBlockedWarpSet();
      mState = State::RunRound;
      break;
    case State::RunRound:
      runRound();
      mState = State::DrainOutputs;
      break;
    case State::DrainOutputs: {
      bool anyOutstanding = false;
      if (!mIssuedQueue.empty()) {
        *issuedAccessValid = 1;
        *issuedAccess = mIssuedQueue.front();
        anyOutstanding = true;
        if (issuedAccessReady) {
          mIssuedQueue.erase(mIssuedQueue.begin());
        }
      }
      if (!mCompletedCountSent) {
        *completedBundleCountWriteEn = 1;
        *completedBundleCountWriteData =
            static_cast<std::uint32_t>(
                std::min(mCompletedBundleQueue.size(), kMaxCompletedBundleIds));
        mCompletedCountSent = true;
        anyOutstanding = true;
      } else if (mPendingBundleIdIdx < mCompletedBundleQueue.size() &&
                 mPendingBundleIdIdx < kMaxCompletedBundleIds) {
        *completedBundleIdWriteEn = 1;
        *completedBundleIdWriteIdx = static_cast<std::uint32_t>(mPendingBundleIdIdx);
        *completedBundleIdWriteData = mCompletedBundleQueue[mPendingBundleIdIdx];
        ++mPendingBundleIdIdx;
        anyOutstanding = true;
      }

      if (!anyOutstanding) {
        *currentCycleAfterIssue = mRoundCurrentCycle;
        *hasPendingWork = mRoundHasPendingWork ? 1 : 0;
        *roundComplete = 1;
        *roundExitReason = mRoundExitReason;
        mState = State::Idle;
        roundFinishedThisStep = true;
      }
      break;
    }
    case State::Idle:
      break;
    }

    if (roundFinishedThisStep) {
      *targetBusy = 0;
      *hasPendingWork = mRoundHasPendingWork ? 1 : 0;
      *currentCycleAfterIssue = mRoundCurrentCycle;
      *roundExitReason = mRoundExitReason;
    } else {
      *targetBusy = (mState != State::Idle) ? 1 : 0;
      *hasPendingWork = (!mInflight.empty() || !mPendingAccessesByCycle.empty() ||
                         accessStoreCount != 0 || mState != State::Idle) ? 1 : 0;
      *currentCycleAfterIssue = mCurrentCycle;
      *roundExitReason = mRoundExitReason;
    }
    *dpiState = stateCode(mState);
    writeDebugCompletionEvent(debugCompletionEventValid,
                              debugCompletionEventWakeExit,
                              debugCompletionEventBundleId,
                              debugCompletionEventWakeRelevant,
                              debugCompletionEventWarpBlocked,
                              debugCompletionEventCurrentWarpBlocked,
                              debugCompletionEventSmId,
                              debugCompletionEventSchedulerId,
                              debugCompletionEventWarpId,
                              debugCompletionEventCycle);
  }

private:
  enum class State {
    Idle,
    RequestAccess,
    WaitAccessAccepted,
    WaitAccess,
    WaitAccessReadyLow,
    PrepareBlockedQueries,
    RunRound,
    DrainOutputs,
  };

  static std::uint32_t stateCode(State state) {
    switch (state) {
    case State::Idle:
      return 0;
    case State::RequestAccess:
      return 1;
    case State::WaitAccessAccepted:
      return 2;
    case State::WaitAccess:
      return 3;
    case State::WaitAccessReadyLow:
      return 4;
    case State::PrepareBlockedQueries:
      return 5;
    case State::RunRound:
      return 10;
    case State::DrainOutputs:
      return 11;
    }
    return 0;
  }

  void resetAccessReadResponseConsumption() {
    mAccessReadRespConsumed = false;
    mLastConsumedAccessReadRespId = 0;
  }

  void buildBlockedWarpSet() {
    std::set<WarpKey> uniqueWarps;
    for (const auto &access : mLoadedAccesses) {
      if (access.warpBlocked) {
        uniqueWarps.insert(access.warpKey());
      }
    }
    for (const auto &[bundleId, bundleInfo] : mOutstandingBundles) {
      (void)bundleId;
      if (bundleInfo.warpBlocked) {
        uniqueWarps.insert(bundleInfo.warpKey);
      }
    }
    mBlockedWarpSet = std::move(uniqueWarps);
  }

  void logBlockedWarpSet() {
    auto &log = dpiLog();
    if (!log) {
      return;
    }

    log << "\n=== Blocked warp set ===\n"
        << "current_cycle=" << mCurrentCycle
        << " round_start_cycle=" << mRoundCurrentCycle
        << " loaded_accesses=" << mLoadedAccesses.size()
        << " outstanding_bundles=" << mOutstandingBundles.size()
        << " blocked_warps=" << mBlockedWarpSet.size() << "\n"
        << "entry,sm_id,scheduler_id,warp_id\n";

    std::size_t entry = 0;
    for (const auto &warp : mBlockedWarpSet) {
      log << entry << ','
          << warp.smId << ','
          << static_cast<unsigned>(warp.schedulerId) << ','
          << warp.warpId << '\n';
      ++entry;
    }
    log.flush();
  }

  void clearReservedSubpartition(std::uint64_t cycle, std::uint32_t subpartition) {
    auto cycleIt = mReservedSubpartitionsByCycle.find(cycle);
    if (cycleIt == mReservedSubpartitionsByCycle.end()) {
      return;
    }
    cycleIt->second.erase(static_cast<unsigned>(subpartition));
    if (cycleIt->second.empty()) {
      mReservedSubpartitionsByCycle.erase(cycleIt);
    }
  }

  void recordDebugCompletionEvent(std::uint64_t bundleId,
                                  const OutstandingBundleInfo &bundleInfo,
                                  bool wakeExit,
                                  bool currentWarpBlocked) {
    if (mDebugCompletionEvent.valid && !wakeExit) {
      return;
    }
    if (mDebugCompletionEvent.valid && mDebugCompletionEvent.wakeExit) {
      return;
    }

    mDebugCompletionEvent = DebugCompletionEvent{
        true,
        wakeExit,
        bundleId,
        bundleInfo.wakeRelevant,
        currentWarpBlocked,
        currentWarpBlocked,
        bundleInfo.warpKey.smId,
        bundleInfo.warpKey.schedulerId,
        bundleInfo.warpKey.warpId,
        mCurrentCycle,
    };
  }

  void writeDebugCompletionEvent(
      svBit *debugCompletionEventValid,
      svBit *debugCompletionEventWakeExit,
      std::uint64_t *debugCompletionEventBundleId,
      svBit *debugCompletionEventWakeRelevant,
      svBit *debugCompletionEventWarpBlocked,
      svBit *debugCompletionEventCurrentWarpBlocked,
      std::uint32_t *debugCompletionEventSmId,
      std::uint32_t *debugCompletionEventSchedulerId,
      std::uint32_t *debugCompletionEventWarpId,
      std::uint64_t *debugCompletionEventCycle) const {
    *debugCompletionEventValid = mDebugCompletionEvent.valid ? 1 : 0;
    *debugCompletionEventWakeExit = mDebugCompletionEvent.wakeExit ? 1 : 0;
    *debugCompletionEventBundleId = mDebugCompletionEvent.bundleId;
    *debugCompletionEventWakeRelevant =
        mDebugCompletionEvent.wakeRelevant ? 1 : 0;
    *debugCompletionEventWarpBlocked =
        mDebugCompletionEvent.warpBlocked ? 1 : 0;
    *debugCompletionEventCurrentWarpBlocked =
        mDebugCompletionEvent.currentWarpBlocked ? 1 : 0;
    *debugCompletionEventSmId = mDebugCompletionEvent.smId;
    *debugCompletionEventSchedulerId = mDebugCompletionEvent.schedulerId;
    *debugCompletionEventWarpId = mDebugCompletionEvent.warpId;
    *debugCompletionEventCycle = mDebugCompletionEvent.cycle;
  }

  void runRound() {
    for (const auto &access : mLoadedAccesses) {
      mReservedSubpartitionsByCycle[access.cycleCount].insert(access.subpartition);
    }
    try {
      logRoundInputSnapshots(mRoundNumber,
                             mReservedSubpartitionsByCycle,
                             mLoadedAccesses,
                             mBlockedWarpSet,
                             mRoundMinIssueCycle);
    } catch (const std::exception &ex) {
      dpiLog() << "[round log] failed to write inputs for round "
               << mRoundNumber << ": " << ex.what() << "\n";
      dpiLog().flush();
    }

    for (const auto &access : mLoadedAccesses) {
      if (mIssuedRequestIds.find(access.id) != mIssuedRequestIds.end()) {
        continue;
      }
      const auto [cycleIt, insertedCycle] =
          mPendingAccessCycles.emplace(access.id, access.cycleCount);
      if (insertedCycle) {
        mPendingAccessIds.insert(access.id);
        mPendingAccessesByCycle[access.cycleCount].push_back(access);
        continue;
      }

      auto bucketIt = mPendingAccessesByCycle.find(cycleIt->second);
      if (bucketIt == mPendingAccessesByCycle.end()) {
        cycleIt->second = access.cycleCount;
        mPendingAccessesByCycle[access.cycleCount].push_back(access);
        continue;
      }

      auto accessIt =
          std::find_if(bucketIt->second.begin(),
                       bucketIt->second.end(),
                       [&access](const Access &pendingAccess) {
                         return pendingAccess.id == access.id;
                       });
      if (accessIt == bucketIt->second.end()) {
        cycleIt->second = access.cycleCount;
        mPendingAccessesByCycle[access.cycleCount].push_back(access);
        continue;
      }

      if (cycleIt->second == access.cycleCount) {
        *accessIt = access;
      } else {
        bucketIt->second.erase(accessIt);
        if (bucketIt->second.empty()) {
          mPendingAccessesByCycle.erase(bucketIt);
        }
        cycleIt->second = access.cycleCount;
        mPendingAccessesByCycle[access.cycleCount].push_back(access);
      }
    }
    auto &pendingByCycle = mPendingAccessesByCycle;

    mIssuedQueue.clear();
    mIssuedAccessPoints.clear();
    mCompletedBundleQueue.clear();
    mCompletedCountSent = false;
    mPendingBundleIdIdx = 0;
    mRoundCurrentCycle = mCurrentCycle;

    const auto finalizeResult = [this, &pendingByCycle](bool schedulingExit) {
      mRoundCurrentCycle = mCurrentCycle;
      mRoundHasPendingWork =
          !pendingByCycle.empty() || mRoundHasFutureIssueWork ||
          mRoundCapacityBounded || !mInflight.empty();
      if (schedulingExit) {
        mRoundExitReason = kRoundExitScheduling;
      } else if (mRoundCapacityBounded && pendingByCycle.empty() &&
          mCurrentCycle < mRoundMinIssueCycle) {
        mRoundExitReason = kRoundExitCapacity;
      } else {
        mRoundExitReason = kRoundExitScheduling;
      }
      try {
        logRoundOutputSnapshots(mRoundNumber,
                                mIssuedAccessPoints,
                                mCompletedBundleQueue,
                                mRoundCurrentCycle);
      } catch (const std::exception &ex) {
        dpiLog() << "[round log] failed to write outputs for round "
                 << mRoundNumber << ": " << ex.what() << "\n";
        dpiLog().flush();
      }
    };

    const auto retireCompletedAccesses = [this]() {
      bool completedBlockedWarpBundle = false;
      for (auto accessIt = mInflight.begin(); accessIt != mInflight.end();) {
        if (accessIt->second.finishCycle <= mCurrentCycle) {
          const auto bundleId = accessIt->second.bundleId;
          const auto bundleIt = mOutstandingBundles.find(bundleId);
          if (bundleIt != mOutstandingBundles.end()) {
            if (bundleIt->second.remainingRequestCount > 0) {
              --bundleIt->second.remainingRequestCount;
            }
            if (bundleIt->second.remainingRequestCount == 0) {
              const bool currentWarpBlocked =
                  mBlockedWarpSet.find(bundleIt->second.warpKey) !=
                  mBlockedWarpSet.end();
              const bool wakeExit =
                  bundleIt->second.wakeRelevant && currentWarpBlocked;
              mCompletedBundleQueue.push_back(bundleId);
              recordDebugCompletionEvent(bundleId,
                                         bundleIt->second,
                                         wakeExit,
                                         currentWarpBlocked);
              if (wakeExit) {
                completedBlockedWarpBundle = true;
              }
              mOutstandingBundles.erase(bundleIt);
            }
          }
          accessIt = mInflight.erase(accessIt);
        } else {
          ++accessIt;
        }
      }
      return completedBlockedWarpBundle;
    };

    const auto advanceToMinIssueCycle = [this, &retireCompletedAccesses,
                                         &finalizeResult]() {
      while (mCurrentCycle < mRoundMinIssueCycle) {
        const auto nextFinishIt = std::min_element(
            mInflight.begin(),
            mInflight.end(),
            [](const auto &lhs, const auto &rhs) {
              return lhs.second.finishCycle < rhs.second.finishCycle;
            });
        if (nextFinishIt != mInflight.end() &&
            nextFinishIt->second.finishCycle <= mRoundMinIssueCycle &&
            nextFinishIt->second.finishCycle > mCurrentCycle) {
          mCurrentCycle = nextFinishIt->second.finishCycle;
        } else {
          mCurrentCycle = mRoundMinIssueCycle;
        }
        if (retireCompletedAccesses()) {
          finalizeResult(true);
          return true;
        }
      }
      return false;
    };

    if (pendingByCycle.empty() && !mInflight.empty() &&
        !mRoundHasFutureIssueWork && !mRoundCapacityBounded) {
      while (!mInflight.empty()) {
        const auto nextFinishIt = std::min_element(
            mInflight.begin(),
            mInflight.end(),
            [](const auto &lhs, const auto &rhs) {
              return lhs.second.finishCycle < rhs.second.finishCycle;
            });
        if (nextFinishIt != mInflight.end() &&
            nextFinishIt->second.finishCycle > mCurrentCycle) {
          mCurrentCycle = nextFinishIt->second.finishCycle;
        }
        if (retireCompletedAccesses()) {
          finalizeResult(true);
          return;
        }
      }
    }

    while (true) {
      if (pendingByCycle.empty()) {
        if (mRoundHasFutureIssueWork && advanceToMinIssueCycle()) {
          return;
        }
        break;
      }

      const auto pendingIt = pendingByCycle.find(mCurrentCycle);
      if (pendingIt != pendingByCycle.end()) {
        for (const auto &access : pendingIt->second) {
          if (mIssuedRequestIds.find(access.id) != mIssuedRequestIds.end()) {
            continue;
          }
          int accessTime = 0;
          if (!findAccessTime(access, accessTime)) {
            dpiLog() << "[Warning] No timing data found for access with UID "
                     << access.id << " at cycle " << mCurrentCycle << "\n";
            continue;
          }
          const std::uint64_t elapsedCycle =
              static_cast<std::uint64_t>(std::max(accessTime, 0));

          Access issuedAccess = access;
          issuedAccess.cycleCount = mCurrentCycle;
          mIssuedRequestIds.insert(access.id);
          mPendingAccessIds.erase(access.id);
          mPendingAccessCycles.erase(access.id);
          mIssuedQueue.push_back(issuedAccess);
          mIssuedAccessPoints.push_back(IssuedAccessPoint{
              access.id,
              mCurrentCycle,
              access.address,
              access.isWrite,
          });
          clearReservedSubpartition(access.cycleCount, access.subpartition);
          mInflight[access.id] = PendingAccessInfo{
              mCurrentCycle + elapsedCycle,
              access.warpKey(),
              access.bundleId,
          };

          auto &bundleInfo = mOutstandingBundles[access.bundleId];
          ++bundleInfo.remainingRequestCount;
          bundleInfo.warpKey = access.warpKey();
          bundleInfo.wakeRelevant = access.wakeRelevantBundle;
          bundleInfo.warpBlocked = bundleInfo.warpBlocked || access.warpBlocked;
        }
        mReservedSubpartitionsByCycle.erase(mCurrentCycle);
        pendingByCycle.erase(pendingIt);
      }

      if (retireCompletedAccesses()) {
        finalizeResult(true);
        return;
      }

      if (mCurrentCycle >= mRoundMinIssueCycle) {
        finalizeResult(true);
        return;
      }

      ++mCurrentCycle;
    }

    finalizeResult(false);
  }

  State mState = State::Idle;
  std::uint64_t mCurrentCycle = 0;
  std::map<std::uint64_t, PendingAccessInfo> mInflight;
  std::unordered_map<std::uint64_t, OutstandingBundleInfo> mOutstandingBundles;
  std::map<std::uint64_t, std::vector<Access>> mPendingAccessesByCycle;
  std::unordered_set<std::uint64_t> mPendingAccessIds;
  std::unordered_map<std::uint64_t, std::uint64_t> mPendingAccessCycles;

  std::vector<Access> mLoadedAccesses;
  std::uint64_t mAccessLoadCycle = 0;
  std::uint64_t mAccessLoadEndCycle = 0;
  bool mAccessReadRespConsumed = false;
  std::uint32_t mLastConsumedAccessReadRespId = 0;
  std::set<WarpKey> mBlockedWarpSet;

  std::vector<Access> mIssuedQueue;
  std::unordered_set<std::uint64_t> mIssuedRequestIds;
  std::vector<IssuedAccessPoint> mIssuedAccessPoints;
  std::vector<std::uint64_t> mCompletedBundleQueue;
  bool mCompletedCountSent = false;
  std::size_t mPendingBundleIdIdx = 0;
  std::uint64_t mRoundCurrentCycle = 0;
  std::uint64_t mRoundMinIssueCycle = 0;
  bool mRoundHasPendingWork = false;
  bool mRoundHasFutureIssueWork = false;
  bool mRoundCapacityBounded = false;
  std::uint32_t mRoundExitReason = kRoundExitScheduling;
  std::uint64_t mRoundNumber = 0;
  ReservedSubpartitionsByCycle mReservedSubpartitionsByCycle;
  DebugCompletionEvent mDebugCompletionEvent;
};

TrafficGenDPIModel &model() {
  static TrafficGenDPIModel instance;
  return instance;
}

} // namespace

extern "C" void trafficgen_dpi_step(
    svBit reset,
    svBit start_round,
    svBit upload_ready,
    unsigned int access_store_count,
    unsigned long long access_store_max_cycle,
    svBit access_store_has_entries,
    svBit access_store_has_more,
    unsigned long long min_issue_cycle,
    svBit access_read_resp_valid,
    unsigned int access_read_resp_id,
    unsigned int access_read_batch_lanes,
    const svBit *access_read_data_valid,
    svBit access_read_bucket_done,
    svBit access_read_ready,
    const unsigned long long *access_read_id,
    const unsigned long long *access_read_address,
    const unsigned long long *access_read_cycle_count,
    const unsigned int *access_read_subpartition,
    const unsigned int *access_read_set_index,
    const unsigned long long *access_read_tag,
    const unsigned int *access_read_mask,
    const unsigned int *access_read_sm_id,
    const unsigned char *access_read_scheduler_id,
    const unsigned int *access_read_warp_id,
    const unsigned long long *access_read_bundle_id,
    const svBit *access_read_wake_relevant_bundle,
    const svBit *access_read_is_write,
    const svBit *access_read_warp_blocked,
    svBit issued_access_writeback_ready,
    svBit *target_busy,
    svBit *has_pending_work,
    svBit *round_started,
    svBit *round_complete,
    unsigned int *round_exit_reason,
    unsigned long long *current_cycle_after_issue,
    unsigned int *dpi_state,
    svBit *access_read_en,
    unsigned long long *access_read_cycle,
    svBit *access_read_batch_ready,
    svBit *issued_access_writeback_valid,
    unsigned long long *issued_access_writeback_id,
    unsigned long long *issued_access_writeback_address,
    unsigned long long *issued_access_writeback_cycle_count,
    unsigned int *issued_access_writeback_subpartition,
    unsigned int *issued_access_writeback_set_index,
    unsigned long long *issued_access_writeback_tag,
    unsigned int *issued_access_writeback_mask,
    unsigned int *issued_access_writeback_sm_id,
    unsigned char *issued_access_writeback_scheduler_id,
    unsigned int *issued_access_writeback_warp_id,
    unsigned long long *issued_access_writeback_bundle_id,
    svBit *issued_access_writeback_wake_relevant_bundle,
    svBit *issued_access_writeback_is_write,
    svBit *issued_access_writeback_warp_blocked,
    svBit *completed_bundle_count_write_en,
    unsigned int *completed_bundle_count_write_data,
    svBit *completed_bundle_id_write_en,
    unsigned int *completed_bundle_id_write_idx,
    unsigned long long *completed_bundle_id_write_data,
    svBit *debug_completion_event_valid,
    svBit *debug_completion_event_wake_exit,
    unsigned long long *debug_completion_event_bundle_id,
    svBit *debug_completion_event_wake_relevant,
    svBit *debug_completion_event_warp_blocked,
    svBit *debug_completion_event_current_warp_blocked,
    unsigned int *debug_completion_event_sm_id,
    unsigned int *debug_completion_event_scheduler_id,
    unsigned int *debug_completion_event_warp_id,
    unsigned long long *debug_completion_event_cycle) {
  if (reset) {
    model().reset();
  }

  std::vector<Access> accessReadBatch;
  accessReadBatch.reserve(access_read_batch_lanes);
  if (access_read_resp_valid) {
    for (unsigned int i = 0; i < access_read_batch_lanes; ++i) {
      if (!dpiArrayBit(access_read_data_valid, i)) {
        continue;
      }
      accessReadBatch.push_back(Access{
          dpiArrayValue<std::uint64_t>(access_read_id, i),
          dpiArrayValue<std::uint64_t>(access_read_address, i),
          dpiArrayValue<std::uint64_t>(access_read_cycle_count, i),
          dpiArrayValue<std::uint32_t>(access_read_subpartition, i),
          dpiArrayValue<std::uint32_t>(access_read_set_index, i),
          dpiArrayValue<std::uint64_t>(access_read_tag, i),
          dpiArrayValue<std::uint32_t>(access_read_mask, i),
          dpiArrayValue<std::uint32_t>(access_read_sm_id, i),
          dpiArrayValue<std::uint8_t>(access_read_scheduler_id, i),
          dpiArrayValue<std::uint32_t>(access_read_warp_id, i),
          dpiArrayValue<std::uint64_t>(access_read_bundle_id, i),
          dpiArrayBit(access_read_wake_relevant_bundle, i),
          dpiArrayBit(access_read_is_write, i),
          dpiArrayBit(access_read_warp_blocked, i),
      });
    }
  }

  Access issuedAccess{};
  std::uint64_t currentCycleAfterIssueDpi = 0;
  std::uint64_t accessReadCycleDpi = 0;
  std::uint64_t completedBundleIdWriteDataDpi = 0;
  std::uint64_t debugCompletionEventBundleIdDpi = 0;
  std::uint64_t debugCompletionEventCycleDpi = 0;

  model().step(
      start_round,
      upload_ready,
      access_store_count,
      access_store_max_cycle,
      access_store_has_entries,
      access_store_has_more,
      min_issue_cycle,
      access_read_resp_valid,
      access_read_resp_id,
      accessReadBatch,
      access_read_bucket_done,
      access_read_ready,
      issued_access_writeback_ready,
      target_busy,
      has_pending_work,
      round_started,
      round_complete,
      round_exit_reason,
      &currentCycleAfterIssueDpi,
      dpi_state,
      access_read_en,
      &accessReadCycleDpi,
      access_read_batch_ready,
      issued_access_writeback_valid,
      &issuedAccess,
      completed_bundle_count_write_en,
      completed_bundle_count_write_data,
      completed_bundle_id_write_en,
      completed_bundle_id_write_idx,
      &completedBundleIdWriteDataDpi,
      debug_completion_event_valid,
      debug_completion_event_wake_exit,
      &debugCompletionEventBundleIdDpi,
      debug_completion_event_wake_relevant,
      debug_completion_event_warp_blocked,
      debug_completion_event_current_warp_blocked,
      debug_completion_event_sm_id,
      debug_completion_event_scheduler_id,
      debug_completion_event_warp_id,
      &debugCompletionEventCycleDpi);

  *current_cycle_after_issue = currentCycleAfterIssueDpi;
  *access_read_cycle = accessReadCycleDpi;
  *issued_access_writeback_id = issuedAccess.id;
  *issued_access_writeback_address = issuedAccess.address;
  *issued_access_writeback_cycle_count = issuedAccess.cycleCount;
  *issued_access_writeback_subpartition = issuedAccess.subpartition;
  *issued_access_writeback_set_index = issuedAccess.setIndex;
  *issued_access_writeback_tag = issuedAccess.tag;
  *issued_access_writeback_mask = issuedAccess.mask;
  *issued_access_writeback_sm_id = issuedAccess.smId;
  *issued_access_writeback_scheduler_id =
      static_cast<std::uint8_t>(issuedAccess.schedulerId);
  *issued_access_writeback_warp_id = issuedAccess.warpId;
  *issued_access_writeback_bundle_id = issuedAccess.bundleId;
  *issued_access_writeback_wake_relevant_bundle =
      issuedAccess.wakeRelevantBundle ? 1 : 0;
  *issued_access_writeback_is_write = issuedAccess.isWrite ? 1 : 0;
  *issued_access_writeback_warp_blocked = issuedAccess.warpBlocked ? 1 : 0;
  *completed_bundle_id_write_data = completedBundleIdWriteDataDpi;
  *debug_completion_event_bundle_id = debugCompletionEventBundleIdDpi;
  *debug_completion_event_cycle = debugCompletionEventCycleDpi;
}
