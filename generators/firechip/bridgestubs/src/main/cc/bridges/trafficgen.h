// See LICENSE for license details

#ifndef __TRAFFICGEN_H
#define __TRAFFICGEN_H

#include "core/bridge_driver.h"

#include <array>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

struct TRAFFICGENBRIDGEMODULE_struct {
  uint64_t target_busy;
  uint64_t start_trafficgen;
  uint64_t current_cycle_after_issue;
  uint64_t pause_target;
  uint64_t read_reserved_subpartitions;
  uint64_t read_completed_bundle_ids;
  uint64_t traffic_complete;
  uint64_t min_issue_cycle;
  uint64_t upload_count;
  uint64_t upload_start;
  uint64_t upload_done;
  uint64_t upload_overflow;
  uint64_t blocked_warp_upload_done;
  uint64_t reserved_subpartitions_snapshot_valid;
  uint64_t reserved_subpartitions_base_idx;
  uint64_t reserved_subpartitions_base_cycle_low;
  uint64_t reserved_subpartitions_base_cycle_high;
  uint64_t completed_bundle_ids_valid;
  uint64_t completed_bundle_count_valid;
  uint64_t completed_bundle_count;
};

using L2SubpartitionReservationsByCycle =
    std::unordered_map<std::uint64_t, std::unordered_set<unsigned>>;

struct trafficgen_l2_access_t {
  uint32_t id;
  uint32_t address;
  uint32_t cycle_count;
  uint32_t m_subpartition;
  uint32_t m_set_index;
  uint32_t m_tag;
  uint32_t m_mask;
  uint32_t sm_id;
  uint32_t scheduler_id;
  uint32_t warp_id;
  uint32_t m_bundle_id;
  uint32_t m_wake_relevant_bundle;
  bool m_is_write;
};

struct trafficgen_blocked_warp_t {
  uint32_t sm_id;
  uint32_t scheduler_id;
  uint32_t warp_id;
};

enum class trafficgen_upload_phase_t {
  l2_accesses,
  blocked_warp_bitmap,
  done,
};

class trafficgen_t final : public streaming_bridge_driver_t {
public:
  static char KIND;
  static constexpr size_t STREAM_WORD_BITS = 256;
  static constexpr size_t STREAM_WORD_COUNT = 1024;
  static constexpr size_t STREAM_BATCH_BYTES = (STREAM_WORD_COUNT * STREAM_WORD_BITS) / 8;
  static constexpr size_t STREAM_BATCH_BEATS = STREAM_BATCH_BYTES / STREAM_WIDTH_BYTES;
  static constexpr size_t STREAM_WORDS_PER_ENTRY = STREAM_WORD_BITS / 64;
  static constexpr size_t STREAM_WORDS_PER_BEAT = STREAM_WIDTH_BYTES / sizeof(uint64_t);
  static constexpr size_t L2_ACCESS_STREAM_BYTES = STREAM_WIDTH_BYTES;
  static constexpr size_t BLOCKED_WARP_SM_BITS = 4;
  static constexpr size_t BLOCKED_WARP_SCHEDULER_BITS = 3;
  static constexpr size_t BLOCKED_WARP_WARP_BITS = 6;
  static constexpr size_t BLOCKED_WARP_INDEX_BITS =
      BLOCKED_WARP_SM_BITS + BLOCKED_WARP_SCHEDULER_BITS +
      BLOCKED_WARP_WARP_BITS;
  static constexpr size_t BLOCKED_WARP_BITMAP_BITS = 1 << BLOCKED_WARP_INDEX_BITS;
  static constexpr size_t BLOCKED_WARP_BITMAP_BEATS =
      BLOCKED_WARP_BITMAP_BITS / (STREAM_WIDTH_BYTES * 8);
  static constexpr size_t BLOCKED_WARP_BITMAP_WORDS =
      BLOCKED_WARP_BITMAP_BITS / 64;
  static constexpr size_t COMPLETED_BUNDLE_ID_COUNT = 32;
  static constexpr size_t COMPLETED_BUNDLE_ID_BEATS = 2;

  trafficgen_t(simif_t &simif,
               StreamEngine &stream,
               const TRAFFICGENBRIDGEMODULE_struct &mmio_addrs,
               int trafficgenno,
               const std::vector<std::string> &args,
               int stream_to_host_idx,
               int stream_to_host_depth,
               int stream_from_host_idx,
               int stream_from_host_depth);

  ~trafficgen_t() override;

  void init() override;
  void tick() override;
  void finish() override;

private:
  const TRAFFICGENBRIDGEMODULE_struct mmio_addrs;
  const int stream_to_host_idx;
  const int stream_to_host_depth;
  const int stream_from_host_idx;
  const int stream_from_host_depth;
  uint32_t min_issue_cycle = 0;
  std::vector<trafficgen_l2_access_t> l2_accesses;
  std::array<uint64_t, BLOCKED_WARP_BITMAP_WORDS> blocked_warp_bitmap{};
  std::array<uint64_t, STREAM_WORD_COUNT * STREAM_WORDS_PER_ENTRY>
      reserved_subpartitions_words{};
  L2SubpartitionReservationsByCycle reserved_subpartitions_by_cycle;
  std::array<uint32_t, COMPLETED_BUNDLE_ID_COUNT> completed_bundle_ids{};
  uint32_t completed_bundle_count = 0;
  uint32_t reserved_subpartitions_base_idx = 0;
  std::uint64_t reserved_subpartitions_base_cycle = 0;
  size_t upload_cursor = 0;
  size_t blocked_warp_bitmap_upload_cursor = 0;
  trafficgen_upload_phase_t upload_phase = trafficgen_upload_phase_t::l2_accesses;
  bool target_busy = false;
  bool reserved_subpartitions_read_issued = false;
  bool completed_bundle_ids_read_issued = false;

  size_t process_reserved_subpartitions_stream();
  size_t process_completed_bundle_ids_stream();
  void push_upload_data();

  bool trafficGenActive = false;
};

#endif // __TRAFFICGEN_H
