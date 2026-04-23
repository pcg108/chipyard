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
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr const char *kTraceRoot = "/home/prashanth/gpu_model/accel-sim-data-render/l2_trace";
constexpr std::uint32_t kBlockedWarpSchedulerBits = 2;
constexpr std::uint32_t kBlockedWarpWarpBits = 4;

struct WarpKey {
  std::uint32_t smId = 0;
  std::uint32_t schedulerId = 0;
  std::uint32_t warpId = 0;

  bool operator<(const WarpKey &other) const {
    return std::tie(smId, schedulerId, warpId) <
           std::tie(other.smId, other.schedulerId, other.warpId);
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
  std::uint8_t schedulerId = 0;
  std::uint32_t warpId = 0;
  std::uint64_t bundleId = 0;
  bool wakeRelevantBundle = false;
  bool isWrite = false;

  WarpKey warpKey() const { return WarpKey{smId, schedulerId, warpId}; }
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
using SchedulerKey = std::pair<unsigned, unsigned>;

std::vector<std::filesystem::path> findTimingFiles(unsigned smId,
                                                   unsigned schedulerId) {
  static std::map<SchedulerKey, std::vector<std::filesystem::path>> cache;
  const SchedulerKey key{smId, schedulerId};
  const auto found = cache.find(key);
  if (found != cache.end()) {
    return found->second;
  }

  std::vector<std::filesystem::path> matches;
  std::error_code ec;
  const std::filesystem::path root(kTraceRoot);
  if (!std::filesystem::exists(root, ec)) {
    cache.emplace(key, matches);
    return matches;
  }

  for (const auto &entry : std::filesystem::directory_iterator(root, ec)) {
    if (ec || !entry.is_directory()) {
      continue;
    }
    const auto timingPath =
        entry.path() / ("shader_" + std::to_string(smId)) /
        ("scheduler_" + std::to_string(schedulerId)) / "l2_to_icnt_timing.txt";
    if (std::filesystem::exists(timingPath, ec) &&
        std::filesystem::is_regular_file(timingPath, ec)) {
      matches.push_back(timingPath);
    }
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

int findAccessTime(const Access &access) {
  const auto timingFiles = findTimingFiles(access.smId, access.schedulerId);
  for (const auto &timingFile : timingFiles) {
    const auto &timings = loadTimingData(timingFile);
    const auto it = timings.find(access.id);
    if (it != timings.end()) {
      return it->second;
    }
  }
  return 0;
}

std::uint32_t blockedWarpIndex(const WarpKey &warpKey) {
  return (warpKey.smId << (kBlockedWarpSchedulerBits + kBlockedWarpWarpBits)) |
         (warpKey.schedulerId << kBlockedWarpWarpBits) | warpKey.warpId;
}

class TrafficGenDPIModel {
public:
  void reset() {
    mState = State::Idle;
    mCurrentCycle = 0;
    mInflight.clear();
    mOutstandingBundles.clear();
    mLoadedAccesses.clear();
    mExpectedAccessCount = 0;
    mBlockedWarpQueryList.clear();
    mBlockedWarpSet.clear();
    mIssuedQueue.clear();
    mReservationClearQueue.clear();
    mCompletedBundleQueue.clear();
    mPendingBundleIdIdx = 0;
    mConsumeCount = 0;
    mRoundCurrentCycle = 0;
    mRoundHasPendingWork = false;
    mAccessLoadIdx = 0;
    mBlockedWarpQueryIdx = 0;
  }

  void step(
      svBit startRound,
      svBit uploadDone,
      svBit blockedWarpBitmapReady,
      std::uint32_t accessStoreCount,
      std::uint64_t minIssueCycle,
      svBit accessReadDataValid,
      const Access &accessReadData,
      svBit blockedWarpQueryRespValid,
      svBit blockedWarpQueryResp,
      svBit issuedAccessReady,
      svBit reservationClearReady,
      svBit *targetBusy,
      svBit *hasPendingWork,
      svBit *roundComplete,
      std::uint64_t *currentCycleAfterIssue,
      svBit *accessReadEn,
      std::uint32_t *accessReadAddr,
      svBit *blockedWarpQueryEn,
      std::uint32_t *blockedWarpQueryIdx,
      svBit *issuedAccessValid,
      Access *issuedAccess,
      svBit *reservationClearValid,
      ReservationClear *reservationClear,
      svBit *completedBundleCountWriteEn,
      std::uint32_t *completedBundleCountWriteData,
      svBit *completedBundleIdWriteEn,
      std::uint32_t *completedBundleIdWriteIdx,
      std::uint64_t *completedBundleIdWriteData,
      svBit *accessStoreConsumeEn,
      std::uint32_t *accessStoreConsumeCount,
      svBit *reservationWindowAdvanceEn,
      std::uint64_t *reservationWindowAdvanceCycle) {
    bool roundFinishedThisStep = false;
    *targetBusy = (mState != State::Idle) ? 1 : 0;
    *hasPendingWork = (!mInflight.empty() || accessStoreCount != 0 || mState != State::Idle) ? 1 : 0;
    *roundComplete = 0;
    *currentCycleAfterIssue = mCurrentCycle;
    *accessReadEn = 0;
    *accessReadAddr = 0;
    *blockedWarpQueryEn = 0;
    *blockedWarpQueryIdx = 0;
    *issuedAccessValid = 0;
    *issuedAccess = Access{};
    *reservationClearValid = 0;
    *reservationClear = ReservationClear{};
    *completedBundleCountWriteEn = 0;
    *completedBundleCountWriteData = 0;
    *completedBundleIdWriteEn = 0;
    *completedBundleIdWriteIdx = 0;
    *completedBundleIdWriteData = 0;
    *accessStoreConsumeEn = 0;
    *accessStoreConsumeCount = 0;
    *reservationWindowAdvanceEn = 0;
    *reservationWindowAdvanceCycle = 0;

    if (mState == State::Idle) {
      if (startRound && uploadDone && blockedWarpBitmapReady) {
        mLoadedAccesses.clear();
        mLoadedAccesses.reserve(accessStoreCount);
        mExpectedAccessCount = accessStoreCount;
        mAccessLoadIdx = 0;
        mBlockedWarpQueryIdx = 0;
        mBlockedWarpSet.clear();
        mBlockedWarpQueryList.clear();
        mIssuedQueue.clear();
        mReservationClearQueue.clear();
        mCompletedBundleQueue.clear();
        mPendingBundleIdIdx = 0;
        mConsumeCount = 0;
        mRoundCurrentCycle = mCurrentCycle;
        mRoundHasPendingWork = false;
        mRoundMinIssueCycle = minIssueCycle;
        mState = (accessStoreCount == 0) ? State::PrepareBlockedQueries : State::RequestAccess;
      }
      return;
    }

    switch (mState) {
    case State::RequestAccess:
      *accessReadEn = 1;
      *accessReadAddr = mAccessLoadIdx;
      mState = State::WaitAccess;
      break;
    case State::WaitAccess:
      if (accessReadDataValid) {
        mLoadedAccesses.push_back(accessReadData);
        ++mAccessLoadIdx;
        mState = (mAccessLoadIdx >= mExpectedAccessCount)
                     ? State::PrepareBlockedQueries
                     : State::RequestAccess;
      }
      break;
    case State::PrepareBlockedQueries:
      buildBlockedWarpQueryList();
      mBlockedWarpQueryIdx = 0;
      mState = mBlockedWarpQueryList.empty() ? State::RunRound : State::RequestBlockedQuery;
      break;
    case State::RequestBlockedQuery:
      *blockedWarpQueryEn = 1;
      *blockedWarpQueryIdx = blockedWarpIndex(mBlockedWarpQueryList[mBlockedWarpQueryIdx]);
      mState = State::WaitBlockedQuery;
      break;
    case State::WaitBlockedQuery:
      if (blockedWarpQueryRespValid) {
        if (blockedWarpQueryResp) {
          mBlockedWarpSet.insert(mBlockedWarpQueryList[mBlockedWarpQueryIdx]);
        }
        ++mBlockedWarpQueryIdx;
        mState = (mBlockedWarpQueryIdx >= mBlockedWarpQueryList.size())
                     ? State::RunRound
                     : State::RequestBlockedQuery;
      }
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
            static_cast<std::uint32_t>(std::min<std::size_t>(mCompletedBundleQueue.size(), 32));
        mCompletedCountSent = true;
        anyOutstanding = true;
      } else if (mPendingBundleIdIdx < mCompletedBundleQueue.size() &&
                 mPendingBundleIdIdx < 32) {
        *completedBundleIdWriteEn = 1;
        *completedBundleIdWriteIdx = static_cast<std::uint32_t>(mPendingBundleIdIdx);
        *completedBundleIdWriteData = mCompletedBundleQueue[mPendingBundleIdIdx];
        ++mPendingBundleIdIdx;
        anyOutstanding = true;
      }

      if (!anyOutstanding) {
        *accessStoreConsumeEn = (mConsumeCount != 0) ? 1 : 0;
        *accessStoreConsumeCount = mConsumeCount;
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
  }

private:
  enum class State {
    Idle,
    RequestAccess,
    WaitAccess,
    PrepareBlockedQueries,
    RequestBlockedQuery,
    WaitBlockedQuery,
    RunRound,
    DrainOutputs,
  };

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
  }

  void runRound() {
    std::map<std::uint64_t, std::vector<Access>> pendingByCycle;
    for (const auto &access : mLoadedAccesses) {
      pendingByCycle[access.cycleCount].push_back(access);
    }

    mIssuedQueue.clear();
    mReservationClearQueue.clear();
    mCompletedBundleQueue.clear();
    mCompletedCountSent = false;
    mPendingBundleIdIdx = 0;
    mConsumeCount = 0;
    mRoundCurrentCycle = mCurrentCycle;

    const auto finalizeResult = [this, &pendingByCycle]() {
      mRoundCurrentCycle = mCurrentCycle;
      mRoundHasPendingWork = !pendingByCycle.empty() || !mInflight.empty();
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

    if (pendingByCycle.empty() && !mInflight.empty()) {
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
        break;
      }

      const auto pendingIt = pendingByCycle.find(mCurrentCycle);
      if (pendingIt != pendingByCycle.end()) {
        for (const auto &access : pendingIt->second) {
          const std::uint64_t elapsedCycle = static_cast<std::uint64_t>(std::max(findAccessTime(access), 0));

          Access issuedAccess = access;
          issuedAccess.cycleCount = mCurrentCycle;
          mIssuedQueue.push_back(issuedAccess);
          mReservationClearQueue.push_back(
              ReservationClear{mCurrentCycle, access.subpartition});
          ++mConsumeCount;

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
  std::size_t mExpectedAccessCount = 0;
  std::size_t mAccessLoadIdx = 0;
  std::vector<WarpKey> mBlockedWarpQueryList;
  std::size_t mBlockedWarpQueryIdx = 0;
  std::set<WarpKey> mBlockedWarpSet;

  std::vector<Access> mIssuedQueue;
  std::vector<ReservationClear> mReservationClearQueue;
  std::vector<std::uint64_t> mCompletedBundleQueue;
  bool mCompletedCountSent = false;
  std::size_t mPendingBundleIdIdx = 0;
  std::uint32_t mConsumeCount = 0;
  std::uint64_t mRoundCurrentCycle = 0;
  std::uint64_t mRoundMinIssueCycle = 0;
  bool mRoundHasPendingWork = false;
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
    std::uint64_t min_issue_cycle,
    svBit access_read_data_valid,
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
    svBit issued_access_writeback_ready,
    svBit reservation_clear_ready,
    svBit *target_busy,
    svBit *has_pending_work,
    svBit *round_complete,
    std::uint64_t *current_cycle_after_issue,
    svBit *access_read_en,
    std::uint32_t *access_read_addr,
    svBit *blocked_warp_query_en,
    std::uint32_t *blocked_warp_query_idx,
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
    svBit *access_store_consume_en,
    std::uint32_t *access_store_consume_count,
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
      min_issue_cycle,
      access_read_data_valid,
      accessReadData,
      blocked_warp_query_resp_valid,
      blocked_warp_query_resp,
      issued_access_writeback_ready,
      reservation_clear_ready,
      target_busy,
      has_pending_work,
      round_complete,
      current_cycle_after_issue,
      access_read_en,
      access_read_addr,
      blocked_warp_query_en,
      blocked_warp_query_idx,
      issued_access_writeback_valid,
      &issuedAccess,
      reservation_clear_valid,
      &reservationClear,
      completed_bundle_count_write_en,
      completed_bundle_count_write_data,
      completed_bundle_id_write_en,
      completed_bundle_id_write_idx,
      completed_bundle_id_write_data,
      access_store_consume_en,
      access_store_consume_count,
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
  *issued_access_writeback_scheduler_id = issuedAccess.schedulerId;
  *issued_access_writeback_warp_id = issuedAccess.warpId;
  *issued_access_writeback_bundle_id = issuedAccess.bundleId;
  *issued_access_writeback_wake_relevant_bundle =
      issuedAccess.wakeRelevantBundle ? 1 : 0;
  *issued_access_writeback_is_write = issuedAccess.isWrite ? 1 : 0;

  *reservation_clear_cycle = reservationClear.cycle;
  *reservation_clear_subpartition = reservationClear.subpartition;
}
