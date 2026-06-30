// See LICENSE for license details

#ifndef __TRAFFICGEN_H
#define __TRAFFICGEN_H

#include "core/bridge_driver.h"

#include <array>
#include <cstdint>
#include <filesystem>
#include <map>
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
  uint64_t upload_count;
  uint64_t access_store_max_cycle_low;
  uint64_t access_store_max_cycle_high;
  uint64_t commit_upload;
  uint64_t round_complete;
  uint64_t upload_ready;
  uint64_t min_issue_cycle_low;
  uint64_t min_issue_cycle_high;
  uint64_t current_cycle_after_issue_low;
  uint64_t current_cycle_after_issue_high;
  uint64_t round_exit_reason;
  uint64_t dpi_state;
  uint64_t issued_access_writeback_count;
  uint64_t completed_bundle_count;
};

using L2SubpartitionReservationsByCycle =
    std::unordered_map<std::uint64_t, std::unordered_set<unsigned>>;
using OrderedL2SubpartitionReservationsByCycle =
    std::map<std::uint64_t, std::set<unsigned>>;

struct trafficgen_l2_access_t {
  uint64_t id;
  uint64_t address;
  uint64_t cycle_count;
  uint64_t l1_to_l2_cycle;
  uint64_t elapsed_cycle;
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
  bool m_warp_blocked;
};

struct trafficgen_issued_access_point_t {
  std::uint64_t request_uid = 0;
  std::uint64_t address = 0;
  std::uint64_t cycle_issued = 0;
  std::uint64_t l1_to_l2_cycle = 0;
  std::uint64_t elapsed_cycle = 0;
  unsigned sm_id = 0;
  unsigned scheduler_id = 0;
  unsigned warp_id = 0;
  bool is_write = false;
};

enum class trafficgen_state_t {
  IDLE,
  SEND_RESERVED_PARTITIONS,
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
               uint64_t raw_access_store_offset,
               uint64_t raw_issued_access_writeback_store_offset,
               uint64_t raw_blocked_warp_bitmap_offset,
               uint64_t raw_completed_bundle_ids_offset,
               uint64_t access_window_bytes,
               uint64_t blocked_window_bytes,
               uint64_t completed_window_bytes);

  ~trafficgen_t() override;

  void init() override;
  void tick() override;
  void finish() override;

private:
  class socket_client_t;

  const TRAFFICGENBRIDGEMODULE_struct mmio_addrs;
  const uint64_t bram_base;
  const uint64_t raw_access_store_offset;
  const uint64_t raw_issued_access_writeback_store_offset;
  const uint64_t raw_blocked_warp_bitmap_offset;
  const uint64_t raw_completed_bundle_ids_offset;
  const uint64_t access_window_bytes;
  const uint64_t blocked_window_bytes;
  const uint64_t completed_window_bytes;
  std::uint64_t min_issue_cycle = 0;
  double memory_issue_stretch_scale = 1.0;
  bool has_first_issue_cycle = false;
  std::uint64_t first_issue_cycle = 0;
  std::string trace_root;
  std::string kernel_name;
  std::filesystem::path round_log_root;
  std::uint64_t logical_round_number = 0;
  std::vector<trafficgen_l2_access_t> l2_accesses;
  std::map<std::uint64_t, std::vector<trafficgen_l2_access_t>>
      pending_accesses_by_cycle;
  std::unordered_map<std::uint64_t, std::uint64_t> pending_access_cycle_by_id;
  std::unordered_map<std::uint64_t, std::uint64_t> pending_access_l1_to_l2_by_id;
  std::unordered_map<std::uint64_t, std::uint64_t> pending_access_elapsed_by_id;
  std::array<uint64_t, BLOCKED_WARP_BITMAP_WORDS> blocked_warp_bitmap{};
  OrderedL2SubpartitionReservationsByCycle round_input_reserved_subpartitions;
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
  std::vector<trafficgen_issued_access_point_t> accumulated_issued_accesses;
  std::vector<std::uint64_t> accumulated_completed_bundle_ids;

  bool upload_written_to_bram = false;
  bool round_completion_pause_issued = false;
  trafficgen_state_t state = trafficgen_state_t::IDLE;
  std::unique_ptr<socket_client_t> gpu_model_socket_client;

  size_t process_completed_bundle_ids_stream();
  size_t process_issued_access_writeback_stream();
  void push_upload_data();
  void write_schedule_to_bram();
  void connect_gpu_model_socket();
  bool receive_main_loop_complete_from_gpu_model() const;
  void send_reserved_subpartitions_snapshot(
      const L2SubpartitionReservationsByCycle &reservations) const;
  void receive_schedule_from_gpu_model();
  void build_next_l2_access_chunk();
  L2SubpartitionReservationsByCycle build_reserved_subpartitions_from_pending() const;
  void depopulate_issued_accesses(
      const std::vector<trafficgen_l2_access_t> &issued_accesses);
  void log_logical_round_for_compare(
      const std::vector<trafficgen_issued_access_point_t> &issued_accesses,
      const std::vector<std::uint64_t> &completed_bundle_ids,
      std::uint64_t current_cycle_after_issue) const;
};

#endif // __TRAFFICGEN_H
