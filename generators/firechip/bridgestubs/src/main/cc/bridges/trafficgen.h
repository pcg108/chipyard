// See LICENSE for license details

#ifndef __TRAFFICGEN_H
#define __TRAFFICGEN_H

#include "core/bridge_driver.h"
#include "trafficgen_bundle_recovery.h"
#include "trafficgen_bundle_tracking.h"
#include "trafficgen_socket_protocol.h"

#include <array>
#include <cstddef>
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
  uint64_t resume_target;
  uint64_t target_paused;
  uint64_t start_round;
  uint64_t current_round_low;
  uint64_t current_round_high;
  uint64_t upload_count;
  uint64_t upload_lane_count_index;
  uint64_t upload_lane_count_value;
  uint64_t upload_lane_count_write;
  uint64_t access_store_max_cycle_low;
  uint64_t access_store_max_cycle_high;
  uint64_t access_store_has_more;
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
  uint64_t issued_lane_count_index;
  uint64_t issued_lane_count_value;
  uint64_t completed_bundle_count;
  uint64_t bundle_table_full_lane_mask;
  uint64_t launch_slot_index;
  uint64_t launch_registry_id_low;
  uint64_t launch_registry_id_high;
  uint64_t launch_id_low;
  uint64_t launch_id_high;
  uint64_t launch_pending_mask;
  uint64_t close_submissions;
  uint64_t slot_status_id_low;
  uint64_t slot_status_id_high;
  uint64_t slot_status;
  uint64_t session_status;
  uint64_t commit_launch_status;
};

using L2SubpartitionReservationsByCycle =
    std::unordered_map<std::uint64_t, std::unordered_set<unsigned>>;
using OrderedL2SubpartitionReservationsByCycle =
    std::map<std::uint64_t, std::set<unsigned>>;

struct trafficgen_l2_access_t {
  uint64_t id;
  uint64_t launch_id = 0;
  uint64_t registry_id = 0;
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
  bool m_warp_blocked;
  uint16_t bundle_issue_count = 1;
  uint32_t bundle_generation = 0;
  std::size_t assigned_lane = 0;
};

struct trafficgen_issued_access_point_t {
  std::uint64_t request_uid = 0;
  std::uint64_t launch_id = 0;
  std::uint64_t cycle_issued = 0;
  std::uint64_t address = 0;
  bool is_write = false;
};

struct trafficgen_bundle_assignment_t {
  std::uint32_t generation = 0;
  std::uint64_t bundle_id = 0;
  std::size_t lane = 0;
  std::uint16_t member_count = 0;
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

  static constexpr size_t COMPLETED_BUNDLE_ID_COUNT = 32768;
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
               uint64_t raw_completed_bundle_ids_offset,
               uint64_t access_window_bytes,
               uint64_t completed_window_bytes,
               uint64_t use_rtl_engine,
               uint64_t lane_count);

  ~trafficgen_t() override;

  void init() override;
  void tick() override;
  void finish() override;
  bool terminate() override { return session_failed; }
  int exit_code() override { return session_failed ? 1 : 0; }

private:
  class socket_client_t;

  const TRAFFICGENBRIDGEMODULE_struct mmio_addrs;
  const uint64_t bram_base;
  const uint64_t raw_access_store_offset;
  const uint64_t raw_issued_access_writeback_store_offset;
  const uint64_t raw_completed_bundle_ids_offset;
  const uint64_t access_window_bytes;
  const uint64_t completed_window_bytes;
  const bool use_rtl_engine;
  const std::size_t lane_count;
  std::uint16_t socket_port = 50051;
  std::uint64_t min_issue_cycle = 0;
  std::filesystem::path round_log_root;
  std::filesystem::path socket_round_log_root;
  // Optional host-side measurement; never reads extra target registers.
  std::filesystem::path boundary_log_path;
  std::uint64_t boundary_start_cycle = 0;
  std::uint64_t replay_min_issue_cycle = 0;
  std::uint64_t engine_round_number = 0;
  std::vector<trafficgen_l2_access_t> l2_accesses;
  std::map<std::uint64_t, std::vector<trafficgen_l2_access_t>>
      pending_accesses_by_cycle;
  std::unordered_map<trafficgen_request_key_t, std::uint64_t, trafficgen_request_key_hash> pending_access_cycle_by_id;
  using bundle_key_t = std::pair<std::uint32_t, std::uint64_t>;
  std::map<bundle_key_t, std::size_t> bundle_lane_owners;
  std::map<bundle_key_t, std::uint16_t> bundle_member_counts;
  trafficgen_bundle_recovery::promotions_t promoted_bundles;
  std::uint64_t recovery_number = 0;
  std::uint64_t hardware_boundary_number = 0;
  std::unordered_map<trafficgen_request_key_t, trafficgen_bundle_assignment_t, trafficgen_request_key_hash>
      bundle_assignment_by_request_uid;
  trafficgen_bundle_tracker bundle_tracker;
  OrderedL2SubpartitionReservationsByCycle round_input_reserved_subpartitions;
  std::array<uint64_t, COMPLETED_BUNDLE_ID_COUNT> completed_bundle_ids{};
  uint32_t completed_bundle_count = 0;
  bool completed_bundle_read_issued = false;
  std::vector<trafficgen_issued_access_point_t> issued_access_writeback_entries;
  std::vector<uint8_t> issued_access_writeback_stream_bytes;
  uint32_t issued_access_writeback_count = 0;
  size_t issued_access_writeback_bytes_received = 0;
  bool issued_access_writeback_read_issued = false;
  std::vector<trafficgen_issued_access_point_t> accumulated_issued_accesses;
  std::vector<std::uint64_t> accumulated_completed_bundle_ids;

  // Access BRAM is written only by this host driver; commit resets its read
  // cursors without changing payload bytes. Empty entries mean no cached write.
  std::vector<std::vector<std::uint8_t>> last_lane_upload_bytes;
  bool upload_written_to_bram = false;
  trafficgen_state_t state = trafficgen_state_t::IDLE;
  std::unique_ptr<socket_client_t> gpu_model_socket_client;

  enum slot_state_t : std::uint8_t {
    FREE = 0, QUEUED = 1, SUBMITTED = 2, ACCEPTED = 3, DISPATCHED = 4,
    COMPLETE = 5, REJECTED = 6, ABORTED = 7
  };
  struct launch_slot_t {
    std::uint64_t launch_id = 0, registry_id = 0;
    slot_state_t status = FREE;
  };
  struct launch_record_t {
    std::size_t slot = 0;
    std::uint64_t registry_id = 0;
  };
  std::array<launch_slot_t, 4> launch_slots{};
  std::map<std::uint64_t, launch_record_t> launch_records;
  trafficgen_socket::SchedulingRoundStateMessage scheduler_status;
  bool submissions_closed = false, close_sent = false;
  std::uint64_t legacy_registry_id = 0;
  bool legacy_bootstrap_active = false;
  bool session_complete = false, session_failed = false, session_truncated = false;
  std::uint64_t last_reported_cycle = 0;
  void tick_impl();
  std::uint64_t read_target_cycle();
  void capture_launch_requests();
  void receive_scheduler_status();
  void publish_launch_status();
  void fail_session(const std::string &reason);
  void finish_scheduler_boundary();
  void clean_completed_bundle_metadata();
  size_t process_completed_bundle_ids_stream();
  size_t process_issued_access_writeback_stream();
  void write_schedule_to_bram();
  void connect_gpu_model_socket();
  void reset_workload_state();
  void send_reserved_subpartitions_snapshot(
      const L2SubpartitionReservationsByCycle &reservations);
  void receive_schedule_from_gpu_model();
  void refresh_pending_access_blocked_annotations();
  void refresh_upload_access_blocked_annotations();
  void build_next_l2_access_chunk();
  L2SubpartitionReservationsByCycle build_reserved_subpartitions_from_pending() const;
  void depopulate_issued_accesses(
      const std::vector<trafficgen_issued_access_point_t> &issued_accesses,
      bool require_complete_upload);
  void recover_full_bundle_tables(std::uint32_t lane_mask,
                                  std::uint64_t current_cycle);
  void assign_replay_lanes_and_bundle_counts();
  void write_upload_lane_count(unsigned lane, std::uint32_t count);
  std::uint32_t read_issued_lane_count(unsigned lane);
  void log_engine_round_for_compare(
      const std::vector<trafficgen_issued_access_point_t> &issued_accesses,
      const std::vector<std::uint64_t> &completed_bundle_ids,
      std::uint64_t current_cycle_after_issue) const;
};

#endif // __TRAFFICGEN_H
