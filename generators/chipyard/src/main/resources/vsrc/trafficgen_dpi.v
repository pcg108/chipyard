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
  input  logic [63:0] min_issue_cycle,
  input  logic        access_read_data_valid,
  input  logic        access_read_bucket_done,
  input  logic        access_read_ready,
  input  logic [63:0] access_read_id,
  input  logic [63:0] access_read_address,
  input  logic [63:0] access_read_cycle_count,
  input  logic [31:0] access_read_subpartition,
  input  logic [31:0] access_read_set_index,
  input  logic [63:0] access_read_tag,
  input  logic [31:0] access_read_mask,
  input  logic [31:0] access_read_sm_id,
  input  logic [7:0]  access_read_scheduler_id,
  input  logic [31:0] access_read_warp_id,
  input  logic [63:0] access_read_bundle_id,
  input  logic        access_read_wake_relevant_bundle,
  input  logic        access_read_is_write,
  input  logic        blocked_warp_query_resp_valid,
  input  logic        blocked_warp_query_resp,
  input  logic        blocked_warp_query_ready,
  input  logic        issued_access_writeback_ready,
  input  logic        reservation_clear_ready,
  output logic        target_busy,
  output logic        has_pending_work,
  output logic        round_started,
  output logic        round_complete,
  output logic [1:0]  round_exit_reason,
  output logic [63:0] current_cycle_after_issue,
  output logic [31:0] dpi_state,
  output logic        access_read_en,
  output logic [63:0] access_read_cycle,
  output logic        access_read_data_ready,
  output logic        access_read_bucket_done_ready,
  output logic        blocked_warp_query_en,
  output logic [18:0] blocked_warp_query_idx,
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
  output logic        reservation_clear_valid,
  output logic [63:0] reservation_clear_cycle,
  output logic [31:0] reservation_clear_subpartition,
  output logic        completed_bundle_count_write_en,
  output logic [12:0] completed_bundle_count_write_data,
  output logic        completed_bundle_id_write_en,
  output logic [11:0] completed_bundle_id_write_idx,
  output logic [63:0] completed_bundle_id_write_data
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
  logic        access_read_data_ready_dpi;
  logic        access_read_bucket_done_ready_dpi;
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
  logic        reservation_clear_valid_dpi;
  logic [63:0] reservation_clear_cycle_dpi;
  logic [31:0] reservation_clear_subpartition_dpi;
  logic        completed_bundle_count_write_en_dpi;
  logic [31:0] completed_bundle_count_write_data_dpi;
  logic        completed_bundle_id_write_en_dpi;
  logic [31:0] completed_bundle_id_write_idx_dpi;
  logic [63:0] completed_bundle_id_write_data_dpi;

  import "DPI-C" function void trafficgen_dpi_step(
    input  bit                reset,
    input  bit                start_round,
    input  bit                upload_ready,
    input  int unsigned       access_store_count,
    input  longint unsigned   access_store_max_cycle,
    input  bit                access_store_has_entries,
    input  longint unsigned   min_issue_cycle,
    input  bit                access_read_data_valid,
    input  bit                access_read_bucket_done,
    input  bit                access_read_ready,
    input  longint unsigned   access_read_id,
    input  longint unsigned   access_read_address,
    input  longint unsigned   access_read_cycle_count,
    input  int unsigned       access_read_subpartition,
    input  int unsigned       access_read_set_index,
    input  longint unsigned   access_read_tag,
    input  int unsigned       access_read_mask,
    input  int unsigned       access_read_sm_id,
    input  byte unsigned      access_read_scheduler_id,
    input  int unsigned       access_read_warp_id,
    input  longint unsigned   access_read_bundle_id,
    input  bit                access_read_wake_relevant_bundle,
    input  bit                access_read_is_write,
    input  bit                blocked_warp_query_resp_valid,
    input  bit                blocked_warp_query_resp,
    input  bit                blocked_warp_query_ready,
    input  bit                issued_access_writeback_ready,
    input  bit                reservation_clear_ready,
    output bit                target_busy,
    output bit                has_pending_work,
    output bit                round_started,
    output bit                round_complete,
    output int unsigned       round_exit_reason,
    output longint unsigned   current_cycle_after_issue,
    output int unsigned       dpi_state,
    output bit                access_read_en,
    output longint unsigned   access_read_cycle,
    output bit                access_read_data_ready,
    output bit                access_read_bucket_done_ready,
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
    output bit                reservation_clear_valid,
    output longint unsigned   reservation_clear_cycle,
    output int unsigned       reservation_clear_subpartition,
    output bit                completed_bundle_count_write_en,
    output int unsigned       completed_bundle_count_write_data,
    output bit                completed_bundle_id_write_en,
    output int unsigned       completed_bundle_id_write_idx,
    output longint unsigned   completed_bundle_id_write_data
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
      access_read_data_valid,
      access_read_bucket_done,
      access_read_ready,
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
      access_read_wake_relevant_bundle,
      access_read_is_write,
      blocked_warp_query_resp_valid,
      blocked_warp_query_resp,
      blocked_warp_query_ready,
      issued_access_writeback_ready,
      reservation_clear_ready,
      target_busy_dpi,
      has_pending_work_dpi,
      round_started_dpi,
      round_complete_dpi,
      round_exit_reason_dpi,
      current_cycle_after_issue_dpi,
      dpi_state_dpi,
      access_read_en_dpi,
      access_read_cycle_dpi,
      access_read_data_ready_dpi,
      access_read_bucket_done_ready_dpi,
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
      reservation_clear_valid_dpi,
      reservation_clear_cycle_dpi,
      reservation_clear_subpartition_dpi,
      completed_bundle_count_write_en_dpi,
      completed_bundle_count_write_data_dpi,
      completed_bundle_id_write_en_dpi,
      completed_bundle_id_write_idx_dpi,
      completed_bundle_id_write_data_dpi
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
    access_read_data_ready <= access_read_data_ready_dpi;
    access_read_bucket_done_ready <= access_read_bucket_done_ready_dpi;
    blocked_warp_query_en <= blocked_warp_query_en_dpi;
    blocked_warp_query_idx <= blocked_warp_query_idx_dpi[18:0];
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
    reservation_clear_valid <= reservation_clear_valid_dpi;
    reservation_clear_cycle <= reservation_clear_cycle_dpi;
    reservation_clear_subpartition <= reservation_clear_subpartition_dpi;
    completed_bundle_count_write_en <= completed_bundle_count_write_en_dpi;
    completed_bundle_count_write_data <= completed_bundle_count_write_data_dpi[12:0];
    completed_bundle_id_write_en <= completed_bundle_id_write_en_dpi;
    completed_bundle_id_write_idx <= completed_bundle_id_write_idx_dpi[11:0];
    completed_bundle_id_write_data <= completed_bundle_id_write_data_dpi;
  end

endmodule
