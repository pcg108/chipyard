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
#include <unordered_map>
#include <utility>
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
constexpr const char *kDefaultRoundLogDir = "rodinia_1_bridge";
constexpr const char *kRoundLogBase = "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0";
constexpr std::uint32_t kBlockedWarpSchedulerBits = 2;
constexpr std::uint32_t kBlockedWarpWarpBits = 9;
constexpr std::uint32_t kDpiStateFatalConfigError = 0xdead0001;
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
  std::uint64_t l1ToL2Cycle = 0;
  std::string kernelFolder;

  WarpKey warpKey() const { return WarpKey{smId, schedulerId, warpId}; }

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & id;
    ar & address;
    ar & cycleCount;
    ar & l1ToL2Cycle;
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

struct ReservationClear {
  std::uint64_t cycle = 0;
  std::uint32_t subpartition = 0;
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
};

using TimingMap = std::unordered_map<std::uint64_t, int>;
struct AccessMetadata {
  std::uint64_t l1ToL2Cycle = 0;
  std::string kernelFolder;
};
using AccessMetadataMap = std::unordered_map<std::uint64_t, AccessMetadata>;
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

struct IssuedAccessPoint {
  std::uint64_t requestUid = 0;
  std::uint64_t address = 0;
  std::uint64_t cycleIssued = 0;
  std::uint64_t l1ToL2Cycle = 0;
  std::uint64_t elapsedCycle = 0;
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;
  bool isWrite = false;

private:
  friend class boost::serialization::access;
  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & requestUid;
    ar & address;
    ar & cycleIssued;
    ar & l1ToL2Cycle;
    ar & elapsedCycle;
    ar & smId;
    ar & schedulerId;
    ar & warpId;
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

    std::string roundLogDir = plusargValue(args, "trafficgen-round-log-dir");
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

std::vector<std::filesystem::path> findL1RequestFiles(unsigned smId,
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

  const auto requestPath =
      root / config.kernelName / ("shader_" + std::to_string(smId)) /
      ("scheduler_" + std::to_string(schedulerId)) / "l1_to_l2_requests.txt";
  if (std::filesystem::exists(requestPath, ec) &&
      std::filesystem::is_regular_file(requestPath, ec)) {
    matches.push_back(requestPath);
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

bool parseL1RequestLine(const std::string &line,
                        std::uint64_t &requestUidOut,
                        std::uint64_t &l1ToL2CycleOut) {
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
  const auto l1ToL2CycleIt = fields.find("l1_to_l2_cycle");
  if (requestUidIt == fields.end() || l1ToL2CycleIt == fields.end()) {
    return false;
  }

  requestUidOut = static_cast<std::uint64_t>(
      std::strtoull(requestUidIt->second.c_str(), nullptr, 0));
  l1ToL2CycleOut = static_cast<std::uint64_t>(
      std::strtoull(l1ToL2CycleIt->second.c_str(), nullptr, 0));
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

const AccessMetadataMap &loadAccessMetadata(
    const std::filesystem::path &requestPath) {
  static std::unordered_map<std::string, AccessMetadataMap> metadataCache;
  const std::string key = requestPath.lexically_normal().string();
  const auto found = metadataCache.find(key);
  if (found != metadataCache.end()) {
    return found->second;
  }

  const std::string kernelFolder =
      requestPath.parent_path().parent_path().parent_path().filename().string();
  AccessMetadataMap metadata;
  std::ifstream ifs(requestPath);
  std::string line;
  while (std::getline(ifs, line)) {
    std::uint64_t requestUid = 0;
    std::uint64_t l1ToL2Cycle = 0;
    if (parseL1RequestLine(line, requestUid, l1ToL2Cycle)) {
      metadata[requestUid] = AccessMetadata{l1ToL2Cycle, kernelFolder};
    }
  }

  return metadataCache.emplace(key, std::move(metadata)).first->second;
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

Access enrichAccessMetadata(Access access) {
  const auto requestFiles = findL1RequestFiles(access.smId, access.schedulerId);
  for (const auto &requestFile : requestFiles) {
    const auto &metadata = loadAccessMetadata(requestFile);
    const auto it = metadata.find(access.id);
    if (it != metadata.end()) {
      access.l1ToL2Cycle = it->second.l1ToL2Cycle;
      access.kernelFolder = it->second.kernelFolder;
      break;
    }
  }
  return access;
}

std::uint32_t blockedWarpIndex(const WarpKey &warpKey) {
  return (warpKey.smId << (kBlockedWarpSchedulerBits + kBlockedWarpWarpBits)) |
         (warpKey.schedulerId << kBlockedWarpWarpBits) | warpKey.warpId;
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
    mLoadedAccesses.clear();
    mReservedSubpartitionsByCycle.clear();
    mBlockedWarpQueryList.clear();
    mBlockedWarpSet.clear();
    mIssuedQueue.clear();
    mIssuedAccessPoints.clear();
    mReservationClearQueue.clear();
    mCompletedBundleQueue.clear();
    mPendingBundleIdIdx = 0;
    mRoundCurrentCycle = 0;
    mRoundHasPendingWork = false;
    mAccessLoadCycle = 0;
    mAccessLoadEndCycle = 0;
    mAccessReadDataConsumed = false;
    mLastConsumedAccessReadId = 0;
    mAccessReadBucketDoneConsumed = false;
    mBlockedWarpQueryIdx = 0;
    mRoundNumber = 0;
  }

  void step(
      svBit startRound,
      svBit uploadDone,
      svBit blockedWarpBitmapReady,
      std::uint32_t accessStoreCount,
      std::uint64_t accessStoreMaxCycle,
      svBit accessStoreHasEntries,
      std::uint64_t minIssueCycle,
      svBit accessReadDataValid,
      svBit accessReadBucketDone,
      svBit accessReadReady,
      const Access &accessReadData,
      svBit blockedWarpQueryRespValid,
      svBit blockedWarpQueryResp,
      svBit blockedWarpQueryReady,
      svBit issuedAccessReady,
      svBit reservationClearReady,
      svBit *targetBusy,
      svBit *hasPendingWork,
      svBit *roundStarted,
      svBit *roundComplete,
      std::uint64_t *currentCycleAfterIssue,
      std::uint32_t *dpiState,
      svBit *accessReadEn,
      std::uint64_t *accessReadCycle,
      svBit *accessReadDataReady,
      svBit *accessReadBucketDoneReady,
      svBit *blockedWarpQueryEn,
      std::uint32_t *blockedWarpQueryIdx,
      svBit *blockedWarpQueryRespStored,
      svBit *issuedAccessValid,
      Access *issuedAccess,
      svBit *reservationClearValid,
      ReservationClear *reservationClear,
      svBit *completedBundleCountWriteEn,
      std::uint32_t *completedBundleCountWriteData,
      svBit *completedBundleIdWriteEn,
      std::uint32_t *completedBundleIdWriteIdx,
      std::uint64_t *completedBundleIdWriteData,
      svBit *reservationWindowAdvanceEn,
      std::uint64_t *reservationWindowAdvanceCycle) {
    const auto &config = runtimeConfig();
    bool roundFinishedThisStep = false;
    *targetBusy = (mState != State::Idle) ? 1 : 0;
    *hasPendingWork = (!mInflight.empty() || accessStoreCount != 0 || mState != State::Idle) ? 1 : 0;
    *roundStarted = 0;
    *roundComplete = 0;
    *currentCycleAfterIssue = mCurrentCycle;
    *dpiState = stateCode(mState);
    *accessReadEn = 0;
    *accessReadCycle = 0;
    *accessReadDataReady = 0;
    *accessReadBucketDoneReady = 0;
    *blockedWarpQueryEn = 0;
    *blockedWarpQueryIdx = 0;
    *blockedWarpQueryRespStored = 0;
    *issuedAccessValid = 0;
    *issuedAccess = Access{};
    *reservationClearValid = 0;
    *reservationClear = ReservationClear{};
    *completedBundleCountWriteEn = 0;
    *completedBundleCountWriteData = 0;
    *completedBundleIdWriteEn = 0;
    *completedBundleIdWriteIdx = 0;
    *completedBundleIdWriteData = 0;
    *reservationWindowAdvanceEn = 0;
    *reservationWindowAdvanceCycle = 0;

    if (!config.valid) {
      *targetBusy = 0;
      *hasPendingWork = 0;
      *roundStarted = 0;
      *roundComplete = 0;
      *currentCycleAfterIssue = mCurrentCycle;
      *dpiState = kDpiStateFatalConfigError;
      return;
    }

    if (mState == State::Idle) {
      if (startRound && uploadDone && blockedWarpBitmapReady) {
        mLoadedAccesses.clear();
        mLoadedAccesses.reserve(accessStoreCount);
        mAccessLoadCycle = mCurrentCycle;
        const bool loadToMaxResidentCycle =
            minIssueCycle == std::numeric_limits<std::uint64_t>::max();
        mAccessLoadEndCycle = loadToMaxResidentCycle ? accessStoreMaxCycle : minIssueCycle;
        resetAccessReadResponseConsumption();
        mBlockedWarpQueryIdx = 0;
        mBlockedWarpSet.clear();
        mBlockedWarpQueryList.clear();
        mIssuedQueue.clear();
        mReservationClearQueue.clear();
        mCompletedBundleQueue.clear();
        mPendingBundleIdIdx = 0;
        mRoundCurrentCycle = mCurrentCycle;
        mRoundHasPendingWork = false;
        mRoundHasFutureIssueWork =
            accessStoreHasEntries && accessStoreMaxCycle > mAccessLoadEndCycle;
        mRoundMinIssueCycle = minIssueCycle;
        ++mRoundNumber;
        const bool skipAccessLoad =
            (loadToMaxResidentCycle && !accessStoreHasEntries) ||
            mAccessLoadCycle > mAccessLoadEndCycle;
        mState = skipAccessLoad
                     ? State::PrepareBlockedQueries
                     : State::RequestAccess;
        *roundStarted = 1;
      }
      *dpiState = stateCode(mState);
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
      *accessReadDataReady = 1;
      *accessReadBucketDoneReady = 1;
      if (!accessReadDataValid) {
        mAccessReadDataConsumed = false;
      }
      if (!accessReadBucketDone) {
        mAccessReadBucketDoneConsumed = false;
      }
      const bool newAccessReadData =
          accessReadDataValid &&
          (!mAccessReadDataConsumed ||
           accessReadData.id != mLastConsumedAccessReadId);
      if (newAccessReadData) {
        mLoadedAccesses.push_back(enrichAccessMetadata(accessReadData));
        mAccessReadDataConsumed = true;
        mLastConsumedAccessReadId = accessReadData.id;
      } else if (accessReadBucketDone && !mAccessReadBucketDoneConsumed) {
        mAccessReadBucketDoneConsumed = true;
        if (mAccessLoadCycle >= mAccessLoadEndCycle ||
            mAccessLoadCycle == std::numeric_limits<std::uint64_t>::max()) {
          resetAccessReadResponseConsumption();
          mState = State::PrepareBlockedQueries;
        } else {
          ++mAccessLoadCycle;
          resetAccessReadResponseConsumption();
          mState = State::RequestAccess;
        }
      }
      break;
    }
    case State::PrepareBlockedQueries:
      buildBlockedWarpQueryList();
      mBlockedWarpQueryIdx = 0;
      mState = mBlockedWarpQueryList.empty() ? State::RunRound : State::RequestBlockedQuery;
      break;
    case State::RequestBlockedQuery:
      *blockedWarpQueryIdx = blockedWarpIndex(mBlockedWarpQueryList[mBlockedWarpQueryIdx]);
      if (blockedWarpQueryReady) {
        *blockedWarpQueryEn = 1;
        mState = State::WaitBlockedQuery;
      }
      break;
    case State::WaitBlockedQueryAccepted:
      *blockedWarpQueryEn = 1;
      *blockedWarpQueryIdx = blockedWarpIndex(mBlockedWarpQueryList[mBlockedWarpQueryIdx]);
      if (!blockedWarpQueryReady) {
        mState = State::WaitBlockedQuery;
      }
      break;
    case State::WaitBlockedQuery:
      if (blockedWarpQueryRespValid) {
        if (blockedWarpQueryResp) {
          mBlockedWarpSet.insert(mBlockedWarpQueryList[mBlockedWarpQueryIdx]);
        }
        mState = State::AcknowledgeBlockedQuery;
      }
      break;
    case State::AcknowledgeBlockedQuery:
      *blockedWarpQueryRespStored = 1;
      advanceBlockedWarpQuery();
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
      if (!mReservationClearQueue.empty()) {
        *reservationClearValid = 1;
        *reservationClear = mReservationClearQueue.front();
        anyOutstanding = true;
        if (reservationClearReady) {
          mReservationClearQueue.erase(mReservationClearQueue.begin());
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
        *reservationWindowAdvanceEn = 1;
        *reservationWindowAdvanceCycle = mRoundCurrentCycle;
        *currentCycleAfterIssue = mRoundCurrentCycle;
        *hasPendingWork = mRoundHasPendingWork ? 1 : 0;
        *roundComplete = 1;
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
    } else {
      *targetBusy = (mState != State::Idle) ? 1 : 0;
      *hasPendingWork = (!mInflight.empty() || accessStoreCount != 0 || mState != State::Idle) ? 1 : 0;
      *currentCycleAfterIssue = mCurrentCycle;
    }
    *dpiState = stateCode(mState);
  }

private:
  enum class State {
    Idle,
    RequestAccess,
    WaitAccessAccepted,
    WaitAccess,
    PrepareBlockedQueries,
    RequestBlockedQuery,
    WaitBlockedQueryAccepted,
    WaitBlockedQuery,
    AcknowledgeBlockedQuery,
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
    case State::PrepareBlockedQueries:
      return 4;
    case State::RequestBlockedQuery:
      return 5;
    case State::WaitBlockedQueryAccepted:
      return 6;
    case State::WaitBlockedQuery:
      return 7;
    case State::AcknowledgeBlockedQuery:
      return 8;
    case State::RunRound:
      return 9;
    case State::DrainOutputs:
      return 10;
    }
    return 0;
  }

  void resetAccessReadResponseConsumption() {
    mAccessReadDataConsumed = false;
    mLastConsumedAccessReadId = 0;
    mAccessReadBucketDoneConsumed = false;
  }

  void buildBlockedWarpQueryList() {
    std::set<WarpKey> uniqueWarps;
    for (const auto &access : mLoadedAccesses) {
      uniqueWarps.insert(access.warpKey());
    }
    for (const auto &[bundleId, bundleInfo] : mOutstandingBundles) {
      (void)bundleId;
      uniqueWarps.insert(bundleInfo.warpKey);
    }
    mBlockedWarpQueryList.assign(uniqueWarps.begin(), uniqueWarps.end());
    logBlockedWarpQueryList();
  }

  void advanceBlockedWarpQuery() {
    ++mBlockedWarpQueryIdx;
    if (mBlockedWarpQueryIdx >= mBlockedWarpQueryList.size()) {
      logBlockedWarpQueryResult();
      mState = State::RunRound;
    } else {
      mState = State::RequestBlockedQuery;
    }
  }

  void logBlockedWarpQueryList() {
    auto &log = dpiLog();
    if (!log) {
      return;
    }

    log << "\n=== Blocked warp query list ===\n"
        << "current_cycle=" << mCurrentCycle
        << " round_start_cycle=" << mRoundCurrentCycle
        << " loaded_accesses=" << mLoadedAccesses.size()
        << " outstanding_bundles=" << mOutstandingBundles.size()
        << " unique_warps=" << mBlockedWarpQueryList.size() << "\n"
        << "entry,blocked_warp_query_idx,sm_id,scheduler_id,warp_id\n";

    for (std::size_t idx = 0; idx < mBlockedWarpQueryList.size(); ++idx) {
      const auto &warp = mBlockedWarpQueryList[idx];
      log << idx << ','
          << blockedWarpIndex(warp) << ','
          << warp.smId << ','
          << static_cast<unsigned>(warp.schedulerId) << ','
          << warp.warpId << '\n';
    }
    log.flush();
  }

  void logBlockedWarpQueryResult() {
    auto &log = dpiLog();
    if (!log) {
      return;
    }

    log << "\n=== Blocked warp query result ===\n"
        << "current_cycle=" << mCurrentCycle
        << " queried_warps=" << mBlockedWarpQueryList.size()
        << " blocked_warps=" << mBlockedWarpSet.size() << "\n"
        << "entry,blocked_warp_query_idx,sm_id,scheduler_id,warp_id\n";

    std::size_t entry = 0;
    for (const auto &warp : mBlockedWarpSet) {
      log << entry << ','
          << blockedWarpIndex(warp) << ','
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

    std::map<std::uint64_t, std::vector<Access>> pendingByCycle;
    for (const auto &access : mLoadedAccesses) {
      pendingByCycle[access.cycleCount].push_back(access);
    }

    mIssuedQueue.clear();
    mIssuedAccessPoints.clear();
    mReservationClearQueue.clear();
    mCompletedBundleQueue.clear();
    mCompletedCountSent = false;
    mPendingBundleIdIdx = 0;
    mRoundCurrentCycle = mCurrentCycle;

    const auto finalizeResult = [this, &pendingByCycle]() {
      mRoundCurrentCycle = mCurrentCycle;
      mRoundHasPendingWork =
          !pendingByCycle.empty() || mRoundHasFutureIssueWork || !mInflight.empty();
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
              mCompletedBundleQueue.push_back(bundleId);
              if (bundleIt->second.wakeRelevant &&
                  mBlockedWarpSet.find(bundleIt->second.warpKey) != mBlockedWarpSet.end()) {
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
          finalizeResult();
          return true;
        }
      }
      return false;
    };

    if (pendingByCycle.empty() && !mInflight.empty() &&
        !mRoundHasFutureIssueWork) {
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
          finalizeResult();
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
          mIssuedQueue.push_back(issuedAccess);
          mIssuedAccessPoints.push_back(IssuedAccessPoint{
              access.id,
              access.address,
              mCurrentCycle,
              access.l1ToL2Cycle,
              elapsedCycle,
              static_cast<unsigned>(access.smId),
              static_cast<unsigned>(access.schedulerId),
              static_cast<unsigned>(access.warpId),
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
        }
        mReservedSubpartitionsByCycle.erase(mCurrentCycle);
        pendingByCycle.erase(pendingIt);
      }

      if (retireCompletedAccesses()) {
        finalizeResult();
        return;
      }

      if (mCurrentCycle >= mRoundMinIssueCycle) {
        break;
      }

      ++mCurrentCycle;
    }

    finalizeResult();
  }

  State mState = State::Idle;
  std::uint64_t mCurrentCycle = 0;
  std::map<std::uint64_t, PendingAccessInfo> mInflight;
  std::unordered_map<std::uint64_t, OutstandingBundleInfo> mOutstandingBundles;

  std::vector<Access> mLoadedAccesses;
  std::uint64_t mAccessLoadCycle = 0;
  std::uint64_t mAccessLoadEndCycle = 0;
  bool mAccessReadDataConsumed = false;
  std::uint64_t mLastConsumedAccessReadId = 0;
  bool mAccessReadBucketDoneConsumed = false;
  std::vector<WarpKey> mBlockedWarpQueryList;
  std::size_t mBlockedWarpQueryIdx = 0;
  std::set<WarpKey> mBlockedWarpSet;

  std::vector<Access> mIssuedQueue;
  std::vector<IssuedAccessPoint> mIssuedAccessPoints;
  std::vector<ReservationClear> mReservationClearQueue;
  std::vector<std::uint64_t> mCompletedBundleQueue;
  bool mCompletedCountSent = false;
  std::size_t mPendingBundleIdIdx = 0;
  std::uint64_t mRoundCurrentCycle = 0;
  std::uint64_t mRoundMinIssueCycle = 0;
  bool mRoundHasPendingWork = false;
  bool mRoundHasFutureIssueWork = false;
  std::uint64_t mRoundNumber = 0;
  ReservedSubpartitionsByCycle mReservedSubpartitionsByCycle;
};

TrafficGenDPIModel &model() {
  static TrafficGenDPIModel instance;
  return instance;
}

} // namespace

extern "C" void trafficgen_dpi_step(
    svBit reset,
    svBit start_round,
    svBit upload_done,
    svBit blocked_warp_bitmap_ready,
    std::uint32_t access_store_count,
    std::uint64_t access_store_max_cycle,
    svBit access_store_has_entries,
    std::uint64_t min_issue_cycle,
    svBit access_read_data_valid,
    svBit access_read_bucket_done,
    svBit access_read_ready,
    std::uint64_t access_read_id,
    std::uint64_t access_read_address,
    std::uint64_t access_read_cycle_count,
    std::uint32_t access_read_subpartition,
    std::uint32_t access_read_set_index,
    std::uint64_t access_read_tag,
    std::uint32_t access_read_mask,
    std::uint32_t access_read_sm_id,
    std::uint8_t access_read_scheduler_id,
    std::uint32_t access_read_warp_id,
    std::uint64_t access_read_bundle_id,
    svBit access_read_wake_relevant_bundle,
    svBit access_read_is_write,
    svBit blocked_warp_query_resp_valid,
    svBit blocked_warp_query_resp,
    svBit blocked_warp_query_ready,
    svBit issued_access_writeback_ready,
    svBit reservation_clear_ready,
    svBit *target_busy,
    svBit *has_pending_work,
    svBit *round_started,
    svBit *round_complete,
    std::uint64_t *current_cycle_after_issue,
    std::uint32_t *dpi_state,
    svBit *access_read_en,
    std::uint64_t *access_read_cycle,
    svBit *access_read_data_ready,
    svBit *access_read_bucket_done_ready,
    svBit *blocked_warp_query_en,
    std::uint32_t *blocked_warp_query_idx,
    svBit *blocked_warp_query_resp_stored,
    svBit *issued_access_writeback_valid,
    std::uint64_t *issued_access_writeback_id,
    std::uint64_t *issued_access_writeback_address,
    std::uint64_t *issued_access_writeback_cycle_count,
    std::uint32_t *issued_access_writeback_subpartition,
    std::uint32_t *issued_access_writeback_set_index,
    std::uint64_t *issued_access_writeback_tag,
    std::uint32_t *issued_access_writeback_mask,
    std::uint32_t *issued_access_writeback_sm_id,
    std::uint8_t *issued_access_writeback_scheduler_id,
    std::uint32_t *issued_access_writeback_warp_id,
    std::uint64_t *issued_access_writeback_bundle_id,
    svBit *issued_access_writeback_wake_relevant_bundle,
    svBit *issued_access_writeback_is_write,
    svBit *reservation_clear_valid,
    std::uint64_t *reservation_clear_cycle,
    std::uint32_t *reservation_clear_subpartition,
    svBit *completed_bundle_count_write_en,
    std::uint32_t *completed_bundle_count_write_data,
    svBit *completed_bundle_id_write_en,
    std::uint32_t *completed_bundle_id_write_idx,
    std::uint64_t *completed_bundle_id_write_data,
    svBit *reservation_window_advance_en,
    std::uint64_t *reservation_window_advance_cycle) {
  if (reset) {
    model().reset();
  }

  const Access accessReadData{
      access_read_id,
      access_read_address,
      access_read_cycle_count,
      access_read_subpartition,
      access_read_set_index,
      access_read_tag,
      access_read_mask,
      access_read_sm_id,
      access_read_scheduler_id,
      access_read_warp_id,
      access_read_bundle_id,
      access_read_wake_relevant_bundle != 0,
      access_read_is_write != 0,
  };

  Access issuedAccess{};
  ReservationClear reservationClear{};

  model().step(
      start_round,
      upload_done,
      blocked_warp_bitmap_ready,
      access_store_count,
      access_store_max_cycle,
      access_store_has_entries,
      min_issue_cycle,
      access_read_data_valid,
      access_read_bucket_done,
      access_read_ready,
      accessReadData,
      blocked_warp_query_resp_valid,
      blocked_warp_query_resp,
      blocked_warp_query_ready,
      issued_access_writeback_ready,
      reservation_clear_ready,
      target_busy,
      has_pending_work,
      round_started,
      round_complete,
      current_cycle_after_issue,
      dpi_state,
      access_read_en,
      access_read_cycle,
      access_read_data_ready,
      access_read_bucket_done_ready,
      blocked_warp_query_en,
      blocked_warp_query_idx,
      blocked_warp_query_resp_stored,
      issued_access_writeback_valid,
      &issuedAccess,
      reservation_clear_valid,
      &reservationClear,
      completed_bundle_count_write_en,
      completed_bundle_count_write_data,
      completed_bundle_id_write_en,
      completed_bundle_id_write_idx,
      completed_bundle_id_write_data,
      reservation_window_advance_en,
      reservation_window_advance_cycle);

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

  *reservation_clear_cycle = reservationClear.cycle;
  *reservation_clear_subpartition = reservationClear.subpartition;
}
