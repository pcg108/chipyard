`define TG_ACCESS_READ_PORTS(idx) \
  input  logic        access_read_data_valid_``idx, \
  input  logic [63:0] access_read_id_``idx, \
  input  logic [63:0] access_read_address_``idx, \
  input  logic [63:0] access_read_cycle_count_``idx, \
  input  logic [31:0] access_read_subpartition_``idx, \
  input  logic [31:0] access_read_set_index_``idx, \
  input  logic [63:0] access_read_tag_``idx, \
  input  logic [31:0] access_read_mask_``idx, \
  input  logic [31:0] access_read_sm_id_``idx, \
  input  logic [7:0]  access_read_scheduler_id_``idx, \
  input  logic [31:0] access_read_warp_id_``idx, \
  input  logic [63:0] access_read_bundle_id_``idx, \
  input  logic        access_read_wake_relevant_bundle_``idx, \
  input  logic        access_read_is_write_``idx, \
  input  logic        access_read_warp_blocked_``idx,

`define TG_ACCESS_READ_ASSIGN(idx) \
  assign access_read_data_valid_batch[idx] = access_read_data_valid_``idx; \
  assign access_read_id_batch[idx] = access_read_id_``idx; \
  assign access_read_address_batch[idx] = access_read_address_``idx; \
  assign access_read_cycle_count_batch[idx] = access_read_cycle_count_``idx; \
  assign access_read_subpartition_batch[idx] = access_read_subpartition_``idx; \
  assign access_read_set_index_batch[idx] = access_read_set_index_``idx; \
  assign access_read_tag_batch[idx] = access_read_tag_``idx; \
  assign access_read_mask_batch[idx] = access_read_mask_``idx; \
  assign access_read_sm_id_batch[idx] = access_read_sm_id_``idx; \
  assign access_read_scheduler_id_batch[idx] = access_read_scheduler_id_``idx; \
  assign access_read_warp_id_batch[idx] = access_read_warp_id_``idx; \
  assign access_read_bundle_id_batch[idx] = access_read_bundle_id_``idx; \
  assign access_read_wake_relevant_bundle_batch[idx] = access_read_wake_relevant_bundle_``idx; \
  assign access_read_is_write_batch[idx] = access_read_is_write_``idx; \
  assign access_read_warp_blocked_batch[idx] = access_read_warp_blocked_``idx;

module TrafficGenDPIBlackBox #(
  parameter int NGENERATORS = 1,
  parameter int ACCESS_READ_BATCH_LANES = 16
) (
  input  logic        clock,
  input  logic        reset,
  input  logic        start_round,
  input  logic        upload_ready,
  input  logic [31:0] access_store_count,
  input  logic [63:0] access_store_max_cycle,
  input  logic        access_store_has_entries,
  input  logic [63:0] min_issue_cycle,
  input  logic        access_read_resp_valid,
  input  logic [31:0] access_read_resp_id,
  input  logic        access_read_bucket_done,
  input  logic        access_read_ready,
  `TG_ACCESS_READ_PORTS(0)
  `TG_ACCESS_READ_PORTS(1)
  `TG_ACCESS_READ_PORTS(2)
  `TG_ACCESS_READ_PORTS(3)
  `TG_ACCESS_READ_PORTS(4)
  `TG_ACCESS_READ_PORTS(5)
  `TG_ACCESS_READ_PORTS(6)
  `TG_ACCESS_READ_PORTS(7)
  `TG_ACCESS_READ_PORTS(8)
  `TG_ACCESS_READ_PORTS(9)
  `TG_ACCESS_READ_PORTS(10)
  `TG_ACCESS_READ_PORTS(11)
  `TG_ACCESS_READ_PORTS(12)
  `TG_ACCESS_READ_PORTS(13)
  `TG_ACCESS_READ_PORTS(14)
  `TG_ACCESS_READ_PORTS(15)
  input  logic        blocked_warp_query_resp_valid,
  input  logic        blocked_warp_query_resp,
  input  logic        blocked_warp_query_ready,
  input  logic        issued_access_writeback_ready,
  output logic        target_busy,
  output logic        has_pending_work,
  output logic        round_started,
  output logic        round_complete,
  output logic [1:0]  round_exit_reason,
  output logic [63:0] current_cycle_after_issue,
  output logic [31:0] dpi_state,
  output logic        access_read_en,
  output logic [63:0] access_read_cycle,
  output logic        access_read_batch_ready,
  output logic        blocked_warp_query_en,
  output logic [24:0] blocked_warp_query_idx,
  output logic        blocked_warp_query_resp_stored,
  output logic        issued_access_writeback_valid,
  output logic [63:0] issued_access_writeback_id,
  output logic [63:0] issued_access_writeback_address,
  output logic [63:0] issued_access_writeback_cycle_count,
  output logic [31:0] issued_access_writeback_subpartition,
  output logic [31:0] issued_access_writeback_set_index,
  output logic [63:0] issued_access_writeback_tag,
  output logic [31:0] issued_access_writeback_mask,
  output logic [31:0] issued_access_writeback_sm_id,
  output logic [7:0]  issued_access_writeback_scheduler_id,
  output logic [31:0] issued_access_writeback_warp_id,
  output logic [63:0] issued_access_writeback_bundle_id,
  output logic        issued_access_writeback_wake_relevant_bundle,
  output logic        issued_access_writeback_is_write,
  output logic        issued_access_writeback_warp_blocked,
  output logic        completed_bundle_count_write_en,
  output logic [15:0] completed_bundle_count_write_data,
  output logic        completed_bundle_id_write_en,
  output logic [14:0] completed_bundle_id_write_idx,
  output logic [63:0] completed_bundle_id_write_data,
  output logic        debug_completion_event_valid,
  output logic        debug_completion_event_wake_exit,
  output logic [63:0] debug_completion_event_bundle_id,
  output logic        debug_completion_event_wake_relevant,
  output logic        debug_completion_event_warp_blocked,
  output logic        debug_completion_event_current_warp_blocked,
  output logic [31:0] debug_completion_event_sm_id,
  output logic [31:0] debug_completion_event_scheduler_id,
  output logic [31:0] debug_completion_event_warp_id,
  output logic [63:0] debug_completion_event_cycle
);

  if (NGENERATORS < 1) begin : gen_invalid_ngenerators
    initial $error("TrafficGenDPIBlackBox requires at least one generator");
  end

  logic        target_busy_dpi;
  logic        has_pending_work_dpi;
  logic        round_started_dpi;
  logic        round_complete_dpi;
  logic [31:0] round_exit_reason_dpi;
  logic [63:0] current_cycle_after_issue_dpi;
  logic [31:0] dpi_state_dpi;
  logic        access_read_en_dpi;
  logic [63:0] access_read_cycle_dpi;
  logic        access_read_batch_ready_dpi;
  logic        blocked_warp_query_en_dpi;
  logic [31:0] blocked_warp_query_idx_dpi;
  logic        blocked_warp_query_resp_stored_dpi;
  logic        issued_access_writeback_valid_dpi;
  logic [63:0] issued_access_writeback_id_dpi;
  logic [63:0] issued_access_writeback_address_dpi;
  logic [63:0] issued_access_writeback_cycle_count_dpi;
  logic [31:0] issued_access_writeback_subpartition_dpi;
  logic [31:0] issued_access_writeback_set_index_dpi;
  logic [63:0] issued_access_writeback_tag_dpi;
  logic [31:0] issued_access_writeback_mask_dpi;
  logic [31:0] issued_access_writeback_sm_id_dpi;
  logic [7:0]  issued_access_writeback_scheduler_id_dpi;
  logic [31:0] issued_access_writeback_warp_id_dpi;
  logic [63:0] issued_access_writeback_bundle_id_dpi;
  logic        issued_access_writeback_wake_relevant_bundle_dpi;
  logic        issued_access_writeback_is_write_dpi;
  logic        issued_access_writeback_warp_blocked_dpi;
  logic        completed_bundle_count_write_en_dpi;
  logic [31:0] completed_bundle_count_write_data_dpi;
  logic        completed_bundle_id_write_en_dpi;
  logic [31:0] completed_bundle_id_write_idx_dpi;
  logic [63:0] completed_bundle_id_write_data_dpi;
  logic        debug_completion_event_valid_dpi;
  logic        debug_completion_event_wake_exit_dpi;
  logic [63:0] debug_completion_event_bundle_id_dpi;
  logic        debug_completion_event_wake_relevant_dpi;
  logic        debug_completion_event_warp_blocked_dpi;
  logic        debug_completion_event_current_warp_blocked_dpi;
  logic [31:0] debug_completion_event_sm_id_dpi;
  logic [31:0] debug_completion_event_scheduler_id_dpi;
  logic [31:0] debug_completion_event_warp_id_dpi;
  logic [63:0] debug_completion_event_cycle_dpi;

  bit              access_read_data_valid_batch [ACCESS_READ_BATCH_LANES];
  longint unsigned access_read_id_batch [ACCESS_READ_BATCH_LANES];
  longint unsigned access_read_address_batch [ACCESS_READ_BATCH_LANES];
  longint unsigned access_read_cycle_count_batch [ACCESS_READ_BATCH_LANES];
  int unsigned     access_read_subpartition_batch [ACCESS_READ_BATCH_LANES];
  int unsigned     access_read_set_index_batch [ACCESS_READ_BATCH_LANES];
  longint unsigned access_read_tag_batch [ACCESS_READ_BATCH_LANES];
  int unsigned     access_read_mask_batch [ACCESS_READ_BATCH_LANES];
  int unsigned     access_read_sm_id_batch [ACCESS_READ_BATCH_LANES];
  byte unsigned    access_read_scheduler_id_batch [ACCESS_READ_BATCH_LANES];
  int unsigned     access_read_warp_id_batch [ACCESS_READ_BATCH_LANES];
  longint unsigned access_read_bundle_id_batch [ACCESS_READ_BATCH_LANES];
  bit              access_read_wake_relevant_bundle_batch [ACCESS_READ_BATCH_LANES];
  bit              access_read_is_write_batch [ACCESS_READ_BATCH_LANES];
  bit              access_read_warp_blocked_batch [ACCESS_READ_BATCH_LANES];

  `TG_ACCESS_READ_ASSIGN(0)
  `TG_ACCESS_READ_ASSIGN(1)
  `TG_ACCESS_READ_ASSIGN(2)
  `TG_ACCESS_READ_ASSIGN(3)
  `TG_ACCESS_READ_ASSIGN(4)
  `TG_ACCESS_READ_ASSIGN(5)
  `TG_ACCESS_READ_ASSIGN(6)
  `TG_ACCESS_READ_ASSIGN(7)
  `TG_ACCESS_READ_ASSIGN(8)
  `TG_ACCESS_READ_ASSIGN(9)
  `TG_ACCESS_READ_ASSIGN(10)
  `TG_ACCESS_READ_ASSIGN(11)
  `TG_ACCESS_READ_ASSIGN(12)
  `TG_ACCESS_READ_ASSIGN(13)
  `TG_ACCESS_READ_ASSIGN(14)
  `TG_ACCESS_READ_ASSIGN(15)

  import "DPI-C" function void trafficgen_dpi_step(
    input  bit                reset,
    input  bit                start_round,
    input  bit                upload_ready,
    input  int unsigned       access_store_count,
    input  longint unsigned   access_store_max_cycle,
    input  bit                access_store_has_entries,
    input  longint unsigned   min_issue_cycle,
    input  bit                access_read_resp_valid,
    input  int unsigned       access_read_resp_id,
    input  bit                access_read_data_valid [ACCESS_READ_BATCH_LANES],
    input  bit                access_read_bucket_done,
    input  bit                access_read_ready,
    input  longint unsigned   access_read_id [ACCESS_READ_BATCH_LANES],
    input  longint unsigned   access_read_address [ACCESS_READ_BATCH_LANES],
    input  longint unsigned   access_read_cycle_count [ACCESS_READ_BATCH_LANES],
    input  int unsigned       access_read_subpartition [ACCESS_READ_BATCH_LANES],
    input  int unsigned       access_read_set_index [ACCESS_READ_BATCH_LANES],
    input  longint unsigned   access_read_tag [ACCESS_READ_BATCH_LANES],
    input  int unsigned       access_read_mask [ACCESS_READ_BATCH_LANES],
    input  int unsigned       access_read_sm_id [ACCESS_READ_BATCH_LANES],
    input  byte unsigned      access_read_scheduler_id [ACCESS_READ_BATCH_LANES],
    input  int unsigned       access_read_warp_id [ACCESS_READ_BATCH_LANES],
    input  longint unsigned   access_read_bundle_id [ACCESS_READ_BATCH_LANES],
    input  bit                access_read_wake_relevant_bundle [ACCESS_READ_BATCH_LANES],
    input  bit                access_read_is_write [ACCESS_READ_BATCH_LANES],
    input  bit                access_read_warp_blocked [ACCESS_READ_BATCH_LANES],
    input  bit                blocked_warp_query_resp_valid,
    input  bit                blocked_warp_query_resp,
    input  bit                blocked_warp_query_ready,
    input  bit                issued_access_writeback_ready,
    output bit                target_busy,
    output bit                has_pending_work,
    output bit                round_started,
    output bit                round_complete,
    output int unsigned       round_exit_reason,
    output longint unsigned   current_cycle_after_issue,
    output int unsigned       dpi_state,
    output bit                access_read_en,
    output longint unsigned   access_read_cycle,
    output bit                access_read_batch_ready,
    output bit                blocked_warp_query_en,
    output int unsigned       blocked_warp_query_idx,
    output bit                blocked_warp_query_resp_stored,
    output bit                issued_access_writeback_valid,
    output longint unsigned   issued_access_writeback_id,
    output longint unsigned   issued_access_writeback_address,
    output longint unsigned   issued_access_writeback_cycle_count,
    output int unsigned       issued_access_writeback_subpartition,
    output int unsigned       issued_access_writeback_set_index,
    output longint unsigned   issued_access_writeback_tag,
    output int unsigned       issued_access_writeback_mask,
    output int unsigned       issued_access_writeback_sm_id,
    output byte unsigned      issued_access_writeback_scheduler_id,
    output int unsigned       issued_access_writeback_warp_id,
    output longint unsigned   issued_access_writeback_bundle_id,
    output bit                issued_access_writeback_wake_relevant_bundle,
    output bit                issued_access_writeback_is_write,
    output bit                issued_access_writeback_warp_blocked,
    output bit                completed_bundle_count_write_en,
    output int unsigned       completed_bundle_count_write_data,
    output bit                completed_bundle_id_write_en,
    output int unsigned       completed_bundle_id_write_idx,
    output longint unsigned   completed_bundle_id_write_data,
    output bit                debug_completion_event_valid,
    output bit                debug_completion_event_wake_exit,
    output longint unsigned   debug_completion_event_bundle_id,
    output bit                debug_completion_event_wake_relevant,
    output bit                debug_completion_event_warp_blocked,
    output bit                debug_completion_event_current_warp_blocked,
    output int unsigned       debug_completion_event_sm_id,
    output int unsigned       debug_completion_event_scheduler_id,
    output int unsigned       debug_completion_event_warp_id,
    output longint unsigned   debug_completion_event_cycle
  );

  always_ff @(posedge clock) begin
    trafficgen_dpi_step(
      reset,
      start_round,
      upload_ready,
      access_store_count,
      access_store_max_cycle,
      access_store_has_entries,
      min_issue_cycle,
      access_read_resp_valid,
      access_read_resp_id,
      access_read_data_valid_batch,
      access_read_bucket_done,
      access_read_ready,
      access_read_id_batch,
      access_read_address_batch,
      access_read_cycle_count_batch,
      access_read_subpartition_batch,
      access_read_set_index_batch,
      access_read_tag_batch,
      access_read_mask_batch,
      access_read_sm_id_batch,
      access_read_scheduler_id_batch,
      access_read_warp_id_batch,
      access_read_bundle_id_batch,
      access_read_wake_relevant_bundle_batch,
      access_read_is_write_batch,
      access_read_warp_blocked_batch,
      blocked_warp_query_resp_valid,
      blocked_warp_query_resp,
      blocked_warp_query_ready,
      issued_access_writeback_ready,
      target_busy_dpi,
      has_pending_work_dpi,
      round_started_dpi,
      round_complete_dpi,
      round_exit_reason_dpi,
      current_cycle_after_issue_dpi,
      dpi_state_dpi,
      access_read_en_dpi,
      access_read_cycle_dpi,
      access_read_batch_ready_dpi,
      blocked_warp_query_en_dpi,
      blocked_warp_query_idx_dpi,
      blocked_warp_query_resp_stored_dpi,
      issued_access_writeback_valid_dpi,
      issued_access_writeback_id_dpi,
      issued_access_writeback_address_dpi,
      issued_access_writeback_cycle_count_dpi,
      issued_access_writeback_subpartition_dpi,
      issued_access_writeback_set_index_dpi,
      issued_access_writeback_tag_dpi,
      issued_access_writeback_mask_dpi,
      issued_access_writeback_sm_id_dpi,
      issued_access_writeback_scheduler_id_dpi,
      issued_access_writeback_warp_id_dpi,
      issued_access_writeback_bundle_id_dpi,
      issued_access_writeback_wake_relevant_bundle_dpi,
      issued_access_writeback_is_write_dpi,
      issued_access_writeback_warp_blocked_dpi,
      completed_bundle_count_write_en_dpi,
      completed_bundle_count_write_data_dpi,
      completed_bundle_id_write_en_dpi,
      completed_bundle_id_write_idx_dpi,
      completed_bundle_id_write_data_dpi,
      debug_completion_event_valid_dpi,
      debug_completion_event_wake_exit_dpi,
      debug_completion_event_bundle_id_dpi,
      debug_completion_event_wake_relevant_dpi,
      debug_completion_event_warp_blocked_dpi,
      debug_completion_event_current_warp_blocked_dpi,
      debug_completion_event_sm_id_dpi,
      debug_completion_event_scheduler_id_dpi,
      debug_completion_event_warp_id_dpi,
      debug_completion_event_cycle_dpi
    );

    target_busy <= target_busy_dpi;
    has_pending_work <= has_pending_work_dpi;
    round_started <= round_started_dpi;
    round_complete <= round_complete_dpi;
    round_exit_reason <= round_exit_reason_dpi[1:0];
    current_cycle_after_issue <= current_cycle_after_issue_dpi;
    dpi_state <= dpi_state_dpi;
    access_read_en <= access_read_en_dpi;
    access_read_cycle <= access_read_cycle_dpi;
    access_read_batch_ready <= access_read_batch_ready_dpi;
    blocked_warp_query_en <= blocked_warp_query_en_dpi;
    blocked_warp_query_idx <= blocked_warp_query_idx_dpi[24:0];
    blocked_warp_query_resp_stored <= blocked_warp_query_resp_stored_dpi;
    issued_access_writeback_valid <= issued_access_writeback_valid_dpi;
    issued_access_writeback_id <= issued_access_writeback_id_dpi;
    issued_access_writeback_address <= issued_access_writeback_address_dpi;
    issued_access_writeback_cycle_count <= issued_access_writeback_cycle_count_dpi;
    issued_access_writeback_subpartition <= issued_access_writeback_subpartition_dpi;
    issued_access_writeback_set_index <= issued_access_writeback_set_index_dpi;
    issued_access_writeback_tag <= issued_access_writeback_tag_dpi;
    issued_access_writeback_mask <= issued_access_writeback_mask_dpi;
    issued_access_writeback_sm_id <= issued_access_writeback_sm_id_dpi;
    issued_access_writeback_scheduler_id <= issued_access_writeback_scheduler_id_dpi;
    issued_access_writeback_warp_id <= issued_access_writeback_warp_id_dpi;
    issued_access_writeback_bundle_id <= issued_access_writeback_bundle_id_dpi;
    issued_access_writeback_wake_relevant_bundle <= issued_access_writeback_wake_relevant_bundle_dpi;
    issued_access_writeback_is_write <= issued_access_writeback_is_write_dpi;
    issued_access_writeback_warp_blocked <= issued_access_writeback_warp_blocked_dpi;
    completed_bundle_count_write_en <= completed_bundle_count_write_en_dpi;
    completed_bundle_count_write_data <= completed_bundle_count_write_data_dpi[15:0];
    completed_bundle_id_write_en <= completed_bundle_id_write_en_dpi;
    completed_bundle_id_write_idx <= completed_bundle_id_write_idx_dpi[14:0];
    completed_bundle_id_write_data <= completed_bundle_id_write_data_dpi;
    debug_completion_event_valid <= debug_completion_event_valid_dpi;
    debug_completion_event_wake_exit <= debug_completion_event_wake_exit_dpi;
    debug_completion_event_bundle_id <= debug_completion_event_bundle_id_dpi;
    debug_completion_event_wake_relevant <= debug_completion_event_wake_relevant_dpi;
    debug_completion_event_warp_blocked <= debug_completion_event_warp_blocked_dpi;
    debug_completion_event_current_warp_blocked <= debug_completion_event_current_warp_blocked_dpi;
    debug_completion_event_sm_id <= debug_completion_event_sm_id_dpi;
    debug_completion_event_scheduler_id <= debug_completion_event_scheduler_id_dpi;
    debug_completion_event_warp_id <= debug_completion_event_warp_id_dpi;
    debug_completion_event_cycle <= debug_completion_event_cycle_dpi;
  end

endmodule

`undef TG_ACCESS_READ_PORTS
`undef TG_ACCESS_READ_ASSIGN
