// See LICENSE for license details

#ifndef __TRAFFICGEN_H
#define __TRAFFICGEN_H

#include "core/bridge_driver.h"

#include <array>
#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

struct TRAFFICGENBRIDGEMODULE_struct {
  uint64_t start_trafficgen;
  uint64_t target_busy;
  uint64_t has_pending_work;
  uint64_t trafficgen_done;
  uint64_t pause_target;
  uint64_t start_round;
  uint64_t current_round_low;
  uint64_t current_round_high;
  uint64_t read_reserved_subpartitions;
  uint64_t reserved_subpartitions_base_idx;
  uint64_t reserved_subpartitions_base_cycle_low;
  uint64_t reserved_subpartitions_base_cycle_high;
  uint64_t upload_count;
  uint64_t upload_start;
  uint64_t round_complete;
  uint64_t upload_done;
  uint64_t upload_overflow;
  uint64_t blocked_warp_upload_done;
  uint64_t min_issue_cycle_low;
  uint64_t min_issue_cycle_high;
  uint64_t completed_bundle_ids_valid;
  uint64_t completed_bundle_count_valid;
  uint64_t read_completed_bundle_ids;
  uint64_t read_issued_access_writeback;
  uint64_t current_cycle_after_issue_low;
  uint64_t current_cycle_after_issue_high;
  uint64_t dpi_state;
  uint64_t issued_access_writeback_count;
  uint64_t completed_bundle_count;
};

using L2SubpartitionReservationsByCycle =
    std::unordered_map<std::uint64_t, std::unordered_set<unsigned>>;

struct trafficgen_l2_access_t {
  uint64_t id;
  uint64_t address;
  uint64_t cycle_count;
  uint32_t m_subpartition;
  uint32_t m_set_index;
  uint64_t m_tag;
  uint32_t m_mask;
  uint32_t sm_id;
  uint8_t scheduler_id;
  uint32_t warp_id;
  uint64_t m_bundle_id;
  bool m_wake_relevant_bundle;
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

enum class trafficgen_state_t {
  IDLE,
  READ_RESERVED_PARTITIONS,
  UPLOAD_SCHEDULE,
  ISSUING_TRAFFIC,
  READING_TRAFFICGEN_OUTPUT,
};

class trafficgen_t final : public bridge_driver_t {
public:
  static char KIND;
  static constexpr size_t STREAM_WIDTH_BYTES = 64;
  static constexpr size_t STREAM_WORD_BITS = 256;
  static constexpr size_t STREAM_WORD_COUNT = 8192;
  static constexpr size_t STREAM_WORDS_PER_ENTRY = STREAM_WORD_BITS / 64;
  static constexpr size_t STREAM_WORDS_PER_BEAT = STREAM_WIDTH_BYTES / sizeof(uint64_t);
  static constexpr size_t STREAM_BATCH_BEATS = STREAM_WORD_COUNT;
  static constexpr size_t STREAM_BATCH_BYTES = STREAM_BATCH_BEATS * STREAM_WIDTH_BYTES;

  static constexpr size_t L2_ACCESS_STREAM_BYTES = STREAM_WIDTH_BYTES;

  // Given that each warp is identified by (sm, scheduler, warp), encode the
  // blocked warp set into a bitmap where each bit corresponds to a unique warp.
  // Accel-Sim dynamic_warp IDs can be much larger than the resident warp count.
  // 9 bits covers IDs 0-511 seen in the render traces.
  static constexpr size_t BLOCKED_WARP_SM_BITS = 8;
  static constexpr size_t BLOCKED_WARP_SCHEDULER_BITS = 2;
  static constexpr size_t BLOCKED_WARP_WARP_BITS = 9;
  static constexpr size_t BLOCKED_WARP_INDEX_BITS =
                                                BLOCKED_WARP_SM_BITS + 
                                                BLOCKED_WARP_SCHEDULER_BITS +
                                                BLOCKED_WARP_WARP_BITS;
  // number of distinct warps we can represent in the bitmap
  static constexpr size_t BLOCKED_WARP_BITMAP_BITS = 1 << BLOCKED_WARP_INDEX_BITS;
  // assuming we send in 8 64-bit words per beat
  static constexpr size_t BLOCKED_WARP_BITMAP_BEATS = BLOCKED_WARP_BITMAP_BITS / (STREAM_WIDTH_BYTES * 8);
  static constexpr size_t BLOCKED_WARP_BITMAP_WORDS = BLOCKED_WARP_BITMAP_BITS / 64;
  
  static constexpr size_t COMPLETED_BUNDLE_ID_COUNT = 4096;
  static constexpr size_t COMPLETED_BUNDLE_ID_BEATS =
      COMPLETED_BUNDLE_ID_COUNT / STREAM_WORDS_PER_BEAT;
  static_assert(COMPLETED_BUNDLE_ID_COUNT % STREAM_WORDS_PER_BEAT == 0,
                "Completed bundle ID count must be stream-beat aligned");

  trafficgen_t(simif_t &simif,
               const TRAFFICGENBRIDGEMODULE_struct &mmio_addrs,
               int trafficgenno,
               const std::vector<std::string> &args,
               uint64_t bram_base,
               uint64_t semantic_access_upload_offset,
               uint64_t semantic_blocked_upload_offset,
               uint64_t raw_access_store_offset,
               uint64_t raw_issued_access_writeback_store_offset,
               uint64_t raw_reserved_subpartitions_offset,
               uint64_t raw_blocked_warp_bitmap_offset,
               uint64_t raw_completed_bundle_ids_offset,
               uint64_t access_window_bytes,
               uint64_t blocked_window_bytes,
               uint64_t reserved_window_bytes,
               uint64_t completed_window_bytes);

  ~trafficgen_t() override;

  void init() override;
  void tick() override;
  void finish() override;

private:
  class socket_client_t;

  const TRAFFICGENBRIDGEMODULE_struct mmio_addrs;
  const uint64_t bram_base;
  const uint64_t semantic_access_upload_offset;
  const uint64_t semantic_blocked_upload_offset;
  const uint64_t raw_access_store_offset;
  const uint64_t raw_issued_access_writeback_store_offset;
  const uint64_t raw_reserved_subpartitions_offset;
  const uint64_t raw_blocked_warp_bitmap_offset;
  const uint64_t raw_completed_bundle_ids_offset;
  const uint64_t access_window_bytes;
  const uint64_t blocked_window_bytes;
  const uint64_t reserved_window_bytes;
  const uint64_t completed_window_bytes;
  std::uint64_t min_issue_cycle = 0;
  std::vector<trafficgen_l2_access_t> l2_accesses;
  std::array<uint64_t, BLOCKED_WARP_BITMAP_WORDS> blocked_warp_bitmap{};
  std::array<uint64_t, STREAM_BATCH_BEATS * STREAM_WORDS_PER_BEAT>
      reserved_subpartitions_words{};
  L2SubpartitionReservationsByCycle reserved_subpartitions_by_cycle;
  std::array<uint64_t, COMPLETED_BUNDLE_ID_COUNT> completed_bundle_ids{};
  uint32_t completed_bundle_count = 0;
  std::array<uint8_t, COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES>
      completed_bundle_stream_bytes{};
  size_t completed_bundle_bytes_received = 0;
  bool completed_bundle_read_issued = false;
  std::vector<trafficgen_l2_access_t> issued_access_writeback_entries;
  std::vector<uint8_t> issued_access_writeback_stream_bytes;
  uint32_t issued_access_writeback_count = 0;
  size_t issued_access_writeback_bytes_received = 0;
  bool issued_access_writeback_read_issued = false;

  uint32_t reserved_subpartitions_base_idx = 0;
  std::uint64_t reserved_subpartitions_base_cycle = 0;
  size_t reserved_subpartition_bytes_received = 0;
  bool reserved_subpartitions_metadata_latched = false;
  
  bool upload_written_to_bram = false;
  trafficgen_upload_phase_t upload_phase = trafficgen_upload_phase_t::l2_accesses;
  bool target_busy = false;
  bool round_completion_pause_issued = false;
  bool reserved_subpartitions_read_issued = false;
  trafficgen_state_t state = trafficgen_state_t::IDLE;
  std::unique_ptr<socket_client_t> gpu_model_socket_client;

  size_t process_reserved_subpartitions_stream();
  size_t process_completed_bundle_ids_stream();
  size_t process_issued_access_writeback_stream();
  void push_upload_data();
  void write_schedule_to_bram();
  void connect_gpu_model_socket();
  bool receive_main_loop_complete_from_gpu_model() const;
  void send_reserved_subpartitions_snapshot() const;
  void receive_schedule_from_gpu_model();

  bool trafficGenActive = false;
};

#endif // __TRAFFICGEN_H
