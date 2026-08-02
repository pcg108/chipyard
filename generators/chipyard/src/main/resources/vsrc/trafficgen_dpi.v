module TrafficGenDPIBlackBox #(
  parameter int NGENERATORS = 1
) (
  input  logic        clock,
  input  logic        reset,
  input  logic        start_round,
  input  logic        upload_ready,
  input  logic [31:0] access_store_count,
  input  logic [63:0] access_store_max_cycle,
  input  logic        access_store_has_entries,
  input  logic        access_store_has_more,
  input  logic [63:0] min_issue_cycle,
  input  logic        access_read_resp_valid,
  input  logic [31:0] access_read_resp_id,
  input  logic        access_read_bucket_done,
  input  logic        access_read_ready,
  input  logic [NGENERATORS-1:0]    access_read_data_valid,
  input  logic [NGENERATORS*64-1:0] access_read_id,
  input  logic [NGENERATORS*64-1:0] access_read_address,
  input  logic [NGENERATORS*64-1:0] access_read_cycle_count,
  input  logic [NGENERATORS*32-1:0] access_read_subpartition,
  input  logic [NGENERATORS*32-1:0] access_read_set_index,
  input  logic [NGENERATORS*64-1:0] access_read_tag,
  input  logic [NGENERATORS*32-1:0] access_read_mask,
  input  logic [NGENERATORS*32-1:0] access_read_sm_id,
  input  logic [NGENERATORS*8-1:0]  access_read_scheduler_id,
  input  logic [NGENERATORS*32-1:0] access_read_warp_id,
  input  logic [NGENERATORS*64-1:0] access_read_bundle_id,
  input  logic [NGENERATORS-1:0]    access_read_wake_relevant_bundle,
  input  logic [NGENERATORS-1:0]    access_read_is_write,
  input  logic [NGENERATORS-1:0]    access_read_warp_blocked,
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

  bit              access_read_data_valid_batch [NGENERATORS];
  longint unsigned access_read_id_batch [NGENERATORS];
  longint unsigned access_read_address_batch [NGENERATORS];
  longint unsigned access_read_cycle_count_batch [NGENERATORS];
  int unsigned     access_read_subpartition_batch [NGENERATORS];
  int unsigned     access_read_set_index_batch [NGENERATORS];
  longint unsigned access_read_tag_batch [NGENERATORS];
  int unsigned     access_read_mask_batch [NGENERATORS];
  int unsigned     access_read_sm_id_batch [NGENERATORS];
  byte unsigned    access_read_scheduler_id_batch [NGENERATORS];
  int unsigned     access_read_warp_id_batch [NGENERATORS];
  longint unsigned access_read_bundle_id_batch [NGENERATORS];
  bit              access_read_wake_relevant_bundle_batch [NGENERATORS];
  bit              access_read_is_write_batch [NGENERATORS];
  bit              access_read_warp_blocked_batch [NGENERATORS];

  for (genvar lane = 0; lane < NGENERATORS; lane++) begin : gen_access_read_unpack
    assign access_read_data_valid_batch[lane] = access_read_data_valid[lane];
    assign access_read_id_batch[lane] = access_read_id[lane*64 +: 64];
    assign access_read_address_batch[lane] = access_read_address[lane*64 +: 64];
    assign access_read_cycle_count_batch[lane] = access_read_cycle_count[lane*64 +: 64];
    assign access_read_subpartition_batch[lane] = access_read_subpartition[lane*32 +: 32];
    assign access_read_set_index_batch[lane] = access_read_set_index[lane*32 +: 32];
    assign access_read_tag_batch[lane] = access_read_tag[lane*64 +: 64];
    assign access_read_mask_batch[lane] = access_read_mask[lane*32 +: 32];
    assign access_read_sm_id_batch[lane] = access_read_sm_id[lane*32 +: 32];
    assign access_read_scheduler_id_batch[lane] = access_read_scheduler_id[lane*8 +: 8];
    assign access_read_warp_id_batch[lane] = access_read_warp_id[lane*32 +: 32];
    assign access_read_bundle_id_batch[lane] = access_read_bundle_id[lane*64 +: 64];
    assign access_read_wake_relevant_bundle_batch[lane] = access_read_wake_relevant_bundle[lane];
    assign access_read_is_write_batch[lane] = access_read_is_write[lane];
    assign access_read_warp_blocked_batch[lane] = access_read_warp_blocked[lane];
  end

  import "DPI-C" function void trafficgen_dpi_step(
    input  bit                reset,
    input  bit                start_round,
    input  bit                upload_ready,
    input  int unsigned       access_store_count,
    input  longint unsigned   access_store_max_cycle,
    input  bit                access_store_has_entries,
    input  bit                access_store_has_more,
    input  longint unsigned   min_issue_cycle,
    input  bit                access_read_resp_valid,
    input  int unsigned       access_read_resp_id,
    input  int unsigned       access_read_batch_lanes,
    input  bit                access_read_data_valid [NGENERATORS],
    input  bit                access_read_bucket_done,
    input  bit                access_read_ready,
    input  longint unsigned   access_read_id [NGENERATORS],
    input  longint unsigned   access_read_address [NGENERATORS],
    input  longint unsigned   access_read_cycle_count [NGENERATORS],
    input  int unsigned       access_read_subpartition [NGENERATORS],
    input  int unsigned       access_read_set_index [NGENERATORS],
    input  longint unsigned   access_read_tag [NGENERATORS],
    input  int unsigned       access_read_mask [NGENERATORS],
    input  int unsigned       access_read_sm_id [NGENERATORS],
    input  byte unsigned      access_read_scheduler_id [NGENERATORS],
    input  int unsigned       access_read_warp_id [NGENERATORS],
    input  longint unsigned   access_read_bundle_id [NGENERATORS],
    input  bit                access_read_wake_relevant_bundle [NGENERATORS],
    input  bit                access_read_is_write [NGENERATORS],
    input  bit                access_read_warp_blocked [NGENERATORS],
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
      access_store_has_more,
      min_issue_cycle,
      access_read_resp_valid,
      access_read_resp_id,
      NGENERATORS,
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
