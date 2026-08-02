package chipyard.example

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam

import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.{Config, Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.tilelink._

import chipyard.iobinders.TrafficGenPortPeripheralIO
import testchipip.util.ClockedIO
import firechip.bridgeinterfaces.{
  CompletedBundleIds,
  IssuedAccess,
  IssuedAccessBatch,
  L2Access,
  RTLL2Access,
  TrafficGenReplaySlots,
  TrafficGenRoundExitReason,
}

sealed trait TrafficGenBackend
case object TrafficGenDPIBackend extends TrafficGenBackend
case object TrafficGenRTLBackend extends TrafficGenBackend

sealed trait TrafficGenMemBackend
case object TrafficGenUncachedMemBackend extends TrafficGenMemBackend
case object TrafficGenL2MemBackend extends TrafficGenMemBackend

case class TrafficGenParams(
  address: BigInt = 0x5000,
  width: Int = 32,
  base: BigInt = 0x88000000L,
  size: BigInt = 500000000L,
  numGenerators: Int = 1,
  regionStride: BigInt = 0x400000L,
  maxL2AccessEntries: Int = 32768, // 2^18
  memOutstanding: Int = 4,
  accessReadResponseDepth: Int = 1024,
  backend: TrafficGenBackend = TrafficGenDPIBackend,
  memBackend: TrafficGenMemBackend = TrafficGenUncachedMemBackend,
  remapTraceAddresses: Boolean = false,
  remapBase: BigInt = 0x80000000L,
  remapSize: BigInt = 1L << 32
) {
  require(numGenerators >= 1, "TrafficGen requires at least one generator")
  require(maxL2AccessEntries % numGenerators == 0,
    "TrafficGen requires maxL2AccessEntries to divide evenly across generators")
}

case object TrafficGenKey extends Field[Option[TrafficGenParams]](None)

class TrafficGenTopIO(
  val w: Int,
  val nGenerators: Int,
  val memOutstanding: Int,
  val useRTL: Boolean
) extends Bundle {
  private val replaySlots = TrafficGenReplaySlots.totalSlots(nGenerators, useRTL)

  //// Target -> Host control/status

  // MMIO from target program to initiate traffic generation
  val startTrafficGen = Output(Bool())
  // TG currently issuing schedule
  val targetBusy = Output(Bool())
  // TG accepted the current round start request
  val roundStarted = Output(Bool())
  // TG reaches min_issue_cycle, unblocks a blocked warp, issues all accesses
  val roundComplete = Output(Bool())
  // why the last round completed: scheduling exit or access-store capacity exit
  val roundExitReason = Output(UInt(2.W))
  // TG still has a scheduling round active or memory requests in flight
  val hasPendingWork = Output(Bool())
  // Debug: current TrafficGen DPI state machine state.
  val dpiState = Output(UInt(32.W))

  //// Host -> Target control/status

  // L2 accesses have been written to the bridge module.
  val uploadReady = Input(Bool())
  // furthest the TG is allowed to issue
  val minIssueCycle = Input(UInt(64.W))
  // begin next round of issuing
  val startRound = Input(Bool())
  // GPU model main loop has completed; this is global completion, not per-round idle
  val trafficGenDone = Input(Bool())

  //// Target -> Host execution data

  // TG issue logic will enqueue the actual issued L2 accesses here with cycleCount updated to the real issue cycle.
  val issuedAccessBatch = Decoupled(new IssuedAccessBatch(nGenerators))

  // query the L2 access store for an L2 access
  val accessReadCycle = Output(UInt(64.W))
  val accessReadEn = Output(Bool())
  val accessReadBatchReady = Output(Bool())

  // write completed bundle IDs and counts to the bridge module as accesses return, to be used by future scheduling
  val completedBundleIdWriteEn = Output(Bool())
  val completedBundleIdWriteIdx = Output(UInt(CompletedBundleIds.idxWidth.W))
  val completedBundleIdWriteData = Output(UInt(64.W))
  val completedBundleCountWriteEn = Output(Bool())
  val completedBundleCountWriteData = Output(UInt(CompletedBundleIds.countWidth.W))

  // current cycle of TG, used for scheduling
  val currentCycleAfterIssue = Output(UInt(64.W))

  //// Host -> Target execution data

  // L2 access returned from access store in bridge module
  val accessReadRespValid = Input(Bool())
  val accessReadRespId = Input(UInt((2 * replaySlots).W))
  private val accessStreamWidth =
    if (useRTL) RTLL2Access.streamWidthBits else L2Access.streamWidthBits
  val accessReadData = Input(Vec(replaySlots, UInt(accessStreamWidth.W)))
  val accessReadDataValid = Input(Vec(replaySlots, Bool()))
  val accessReadBucketDone = Input(Bool())
  val accessReadReady = Input(Bool())
  val accessReadConsumeMask = Output(UInt(replaySlots.W))
  val accessReadLaneDoneMask = Input(UInt(nGenerators.W))
  val accessReadPrefetchPauseReq = Input(Bool())
  val accessReadPrefetchPauseAck = Output(Bool())
  val accessStoreCount = Input(UInt(32.W))
  val accessStoreMaxCycle = Input(UInt(64.W))
  val accessStoreHasEntries = Input(Bool())
  val accessStoreHasMore = Input(Bool())

  val memActive = Input(Bool())
  val memInflightAccesses = Input(Vec(nGenerators, UInt(log2Ceil(memOutstanding + 1).W)))
  val issue = Vec(nGenerators, Decoupled(new RTLL2Access))
  val completion = Vec(nGenerators, Flipped(Decoupled(new RTLL2Access)))
}

trait HasTrafficGenTopIO {
  def io: TrafficGenTopIO
}

class TrafficGenDPIBlackBox(val nGenerators: Int) extends BlackBox(Map("NGENERATORS" -> IntParam(nGenerators)))
    with HasBlackBoxResource {

  // Chisel black box that mirrors TrafficGenTopIO, but flattened

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val start_round = Input(Bool())
    val upload_ready = Input(Bool())
    val access_store_count = Input(UInt(32.W))
    val access_store_max_cycle = Input(UInt(64.W))
    val access_store_has_entries = Input(Bool())
    val access_store_has_more = Input(Bool())
    val min_issue_cycle = Input(UInt(64.W))

    val access_read_resp_valid = Input(Bool())
    val access_read_resp_id = Input(UInt(32.W))
    val access_read_data_valid = Input(UInt(nGenerators.W))
    val access_read_bucket_done = Input(Bool())
    val access_read_ready = Input(Bool())
    val access_read_id = Input(UInt((nGenerators * 64).W))
    val access_read_address = Input(UInt((nGenerators * 64).W))
    val access_read_cycle_count = Input(UInt((nGenerators * 64).W))
    val access_read_subpartition = Input(UInt((nGenerators * 32).W))
    val access_read_set_index = Input(UInt((nGenerators * 32).W))
    val access_read_tag = Input(UInt((nGenerators * 64).W))
    val access_read_mask = Input(UInt((nGenerators * 32).W))
    val access_read_sm_id = Input(UInt((nGenerators * 32).W))
    val access_read_scheduler_id = Input(UInt((nGenerators * 8).W))
    val access_read_warp_id = Input(UInt((nGenerators * 32).W))
    val access_read_bundle_id = Input(UInt((nGenerators * 64).W))
    val access_read_wake_relevant_bundle = Input(UInt(nGenerators.W))
    val access_read_is_write = Input(UInt(nGenerators.W))
    val access_read_warp_blocked = Input(UInt(nGenerators.W))

    val issued_access_writeback_ready = Input(Bool())

    val target_busy = Output(Bool())
    val has_pending_work = Output(Bool())
    val round_started = Output(Bool())
    val round_complete = Output(Bool())
    val round_exit_reason = Output(UInt(2.W))
    val current_cycle_after_issue = Output(UInt(64.W))
    val dpi_state = Output(UInt(32.W))

    val access_read_en = Output(Bool())
    val access_read_cycle = Output(UInt(64.W))
    val access_read_batch_ready = Output(Bool())
    val issued_access_writeback_valid = Output(Bool())
    val issued_access_writeback_id = Output(UInt(64.W))
    val issued_access_writeback_address = Output(UInt(64.W))
    val issued_access_writeback_cycle_count = Output(UInt(64.W))
    val issued_access_writeback_subpartition = Output(UInt(32.W))
    val issued_access_writeback_set_index = Output(UInt(32.W))
    val issued_access_writeback_tag = Output(UInt(64.W))
    val issued_access_writeback_mask = Output(UInt(32.W))
    val issued_access_writeback_sm_id = Output(UInt(32.W))
    val issued_access_writeback_scheduler_id = Output(UInt(8.W))
    val issued_access_writeback_warp_id = Output(UInt(32.W))
    val issued_access_writeback_bundle_id = Output(UInt(64.W))
    val issued_access_writeback_wake_relevant_bundle = Output(Bool())
    val issued_access_writeback_is_write = Output(Bool())
    val issued_access_writeback_warp_blocked = Output(Bool())

    val completed_bundle_count_write_en = Output(Bool())
    val completed_bundle_count_write_data = Output(UInt(CompletedBundleIds.countWidth.W))
    val completed_bundle_id_write_en = Output(Bool())
    val completed_bundle_id_write_idx = Output(UInt(CompletedBundleIds.idxWidth.W))
    val completed_bundle_id_write_data = Output(UInt(64.W))
    val debug_completion_event_valid = Output(Bool())
    val debug_completion_event_wake_exit = Output(Bool())
    val debug_completion_event_bundle_id = Output(UInt(64.W))
    val debug_completion_event_wake_relevant = Output(Bool())
    val debug_completion_event_warp_blocked = Output(Bool())
    val debug_completion_event_current_warp_blocked = Output(Bool())
    val debug_completion_event_sm_id = Output(UInt(32.W))
    val debug_completion_event_scheduler_id = Output(UInt(32.W))
    val debug_completion_event_warp_id = Output(UInt(32.W))
    val debug_completion_event_cycle = Output(UInt(64.W))

  })

  // pulls in SV wrapper resource  

  addResource("/vsrc/trafficgen_dpi.v")
}

/** Adapts the DPI single-record writeback contract to the target-wide batch
  * protocol. The surrounding DPI ABI carries a parameter-sized lane vector.
  */
class LegacyIssuedAccessBatchAdapter(numLanes: Int) extends Module {
  require(numLanes >= 1, "LegacyIssuedAccessBatchAdapter requires at least one lane")
  val io = IO(new Bundle {
    val legacy = Flipped(Decoupled(new IssuedAccess))
    val batch = Decoupled(new IssuedAccessBatch(numLanes))
  })

  val queue = Module(new Queue(new IssuedAccessBatch(numLanes), 2))
  val nextBatchId = RegInit(0.U(32.W))
  val nextLane = RegInit(0.U(log2Ceil(numLanes max 2).W))
  val packedLanes = Wire(Vec(numLanes, UInt(IssuedAccess.streamWidthBits.W)))
  packedLanes.foreach(_ := 0.U)
  packedLanes(nextLane) := IssuedAccess.pack(io.legacy.bits)
  queue.io.enq.valid := io.legacy.valid
  queue.io.enq.bits := 0.U.asTypeOf(new IssuedAccessBatch(numLanes))
  queue.io.enq.bits.batchId := nextBatchId
  queue.io.enq.bits.validMask := UIntToOH(nextLane, numLanes)
  queue.io.enq.bits.accesses := packedLanes.asUInt
  io.legacy.ready := queue.io.enq.ready
  io.batch <> queue.io.deq

  when(queue.io.enq.fire) {
    nextBatchId := nextBatchId + 1.U
    nextLane := Mux(nextLane === (numLanes - 1).U, 0.U, nextLane + 1.U)
  }

  val batchStalled = RegNext(io.batch.valid && !io.batch.ready, false.B)
  val batchBitsPrev = RegNext(io.batch.bits.asUInt)
  when(batchStalled) {
    assert(io.batch.valid, "LegacyIssuedAccessBatchAdapter dropped a batch under backpressure")
    assert(io.batch.bits.asUInt === batchBitsPrev,
      "LegacyIssuedAccessBatchAdapter changed a batch under backpressure")
  }
}

class TrafficGenDPIEngine(params: TrafficGenParams) extends Module with HasTrafficGenTopIO {
  require(params.numGenerators >= 1, "TrafficGenDPIEngine requires at least one generator")

  val io = IO(new TrafficGenTopIO(
    params.width, params.numGenerators, params.memOutstanding, useRTL = false))

  val currentCycleAfterIssue = Wire(UInt(64.W))
  val roundStarted = Wire(Bool())
  val dpi = Module(new TrafficGenDPIBlackBox(params.numGenerators))

  io.startTrafficGen := false.B

  dpi.io.clock := clock
  dpi.io.reset := reset.asBool

  dpi.io.start_round := io.startRound
  dpi.io.upload_ready := io.uploadReady
  dpi.io.access_store_count := io.accessStoreCount
  dpi.io.access_store_max_cycle := io.accessStoreMaxCycle
  dpi.io.access_store_has_entries := io.accessStoreHasEntries
  dpi.io.access_store_has_more := io.accessStoreHasMore
  dpi.io.min_issue_cycle := io.minIssueCycle

  val dpiCompatRequestActive = RegInit(false.B)
  val dpiCompatResponseSuppressed = RegInit(false.B)
  val dpiCompatRequestAccept =
    !dpiCompatRequestActive && dpi.io.access_read_en && io.accessReadReady
  when(dpiCompatRequestAccept) {
    dpiCompatRequestActive := true.B
    dpiCompatResponseSuppressed := false.B
  }
  val dpiCompatRespValid =
    dpiCompatRequestActive && io.accessReadRespValid && !dpiCompatResponseSuppressed
  dpi.io.access_read_resp_valid := dpiCompatRespValid
  dpi.io.access_read_resp_id := io.accessReadRespId
  val dpiAccessReadData = VecInit(io.accessReadData.map(L2Access.unpack))
  val dpiDueLaneMask = VecInit((0 until params.numGenerators).map { lane =>
    io.accessReadDataValid(lane) && dpiAccessReadData(lane).cycleCount <= dpi.io.access_read_cycle
  }).asUInt
  dpi.io.access_read_data_valid := dpiDueLaneMask
  // The DPI engine treats bucket_done as "no more accesses are due for
  // this query".  Repeated accepted snapshots expose additional overdue heads
  // through additional calls using the same parameter-sized ABI.
  dpi.io.access_read_bucket_done := !dpiDueLaneMask.orR
  val dpiCompatRespFire = dpiCompatRespValid && dpi.io.access_read_batch_ready
  when(dpiCompatRespFire && !dpiDueLaneMask.orR) {
    dpiCompatRequestActive := false.B
  }.elsewhen(dpiCompatRespFire) {
    dpiCompatResponseSuppressed := true.B
  }
  when(dpiCompatResponseSuppressed && !dpi.io.access_read_batch_ready) {
    dpiCompatResponseSuppressed := false.B
  }
  // Recreate the legacy ready-high/ready-low request handshake locally.  The
  // banked bridge continuously exposes lane heads instead of accepting a
  // discrete query transaction.
  dpi.io.access_read_ready := io.accessReadReady && !dpiCompatRequestActive
  dpi.io.access_read_id := VecInit(dpiAccessReadData.map(_.id)).asUInt
  dpi.io.access_read_address := VecInit(dpiAccessReadData.map(_.address)).asUInt
  dpi.io.access_read_cycle_count := VecInit(dpiAccessReadData.map(_.cycleCount)).asUInt
  dpi.io.access_read_subpartition := VecInit(dpiAccessReadData.map(_.mSubpartition)).asUInt
  dpi.io.access_read_set_index := VecInit(dpiAccessReadData.map(_.mSetIndex)).asUInt
  dpi.io.access_read_tag := VecInit(dpiAccessReadData.map(_.mTag)).asUInt
  dpi.io.access_read_mask := VecInit(dpiAccessReadData.map(_.mMask)).asUInt
  dpi.io.access_read_sm_id := VecInit(dpiAccessReadData.map(_.smId)).asUInt
  dpi.io.access_read_scheduler_id := VecInit(dpiAccessReadData.map(_.schedulerId)).asUInt
  dpi.io.access_read_warp_id := VecInit(dpiAccessReadData.map(_.warpId)).asUInt
  dpi.io.access_read_bundle_id := VecInit(dpiAccessReadData.map(_.mBundleId)).asUInt
  dpi.io.access_read_wake_relevant_bundle := VecInit(dpiAccessReadData.map(_.mWakeRelevantBundle)).asUInt
  dpi.io.access_read_is_write := VecInit(dpiAccessReadData.map(_.mIsWrite)).asUInt
  dpi.io.access_read_warp_blocked := VecInit(dpiAccessReadData.map(_.mWarpBlocked)).asUInt

  // Preserve the legacy single-access DPI contract by adapting each record to
  // a one-valid-lane issued batch. The external target/bridge interface remains
  // uniformly batch based for both backends.
  val issuedBatchAdapter = Module(new LegacyIssuedAccessBatchAdapter(params.numGenerators))
  val dpiIssuedAccess = Wire(new IssuedAccess)
  dpiIssuedAccess.requestUid := dpi.io.issued_access_writeback_id
  dpiIssuedAccess.address := dpi.io.issued_access_writeback_address
  dpiIssuedAccess.cycleIssued := dpi.io.issued_access_writeback_cycle_count
  dpiIssuedAccess.isWrite := dpi.io.issued_access_writeback_is_write

  issuedBatchAdapter.io.legacy.valid := dpi.io.issued_access_writeback_valid
  issuedBatchAdapter.io.legacy.bits := dpiIssuedAccess
  dpi.io.issued_access_writeback_ready := issuedBatchAdapter.io.legacy.ready
  io.issuedAccessBatch <> issuedBatchAdapter.io.batch

  io.currentCycleAfterIssue := currentCycleAfterIssue
  io.roundStarted := roundStarted
  io.roundExitReason := dpi.io.round_exit_reason

  io.accessReadCycle := dpi.io.access_read_cycle
  io.accessReadEn := dpi.io.access_read_en
  io.accessReadBatchReady := dpi.io.access_read_batch_ready
  io.accessReadConsumeMask := Mux(
    dpiCompatRespFire,
    dpiDueLaneMask,
    0.U)
  io.accessReadPrefetchPauseAck := io.accessReadPrefetchPauseReq
  io.hasPendingWork := dpi.io.has_pending_work
  roundStarted := dpi.io.round_started
  currentCycleAfterIssue := dpi.io.current_cycle_after_issue
  io.dpiState := dpi.io.dpi_state

  val issuedWritebackDraining =
    issuedBatchAdapter.io.batch.valid || dpi.io.issued_access_writeback_valid
  val roundCompletePending = RegInit(false.B)
  val roundCompleteReady = (roundCompletePending || dpi.io.round_complete) && !issuedWritebackDraining
  when(roundCompleteReady) {
    roundCompletePending := false.B
  }.elsewhen(dpi.io.round_complete) {
    roundCompletePending := true.B
  }
  io.roundComplete := roundCompleteReady
  io.targetBusy := dpi.io.target_busy || roundCompletePending || issuedWritebackDraining

  io.completedBundleIdWriteEn := dpi.io.completed_bundle_id_write_en
  io.completedBundleIdWriteIdx := dpi.io.completed_bundle_id_write_idx
  io.completedBundleIdWriteData := dpi.io.completed_bundle_id_write_data
  io.completedBundleCountWriteEn := dpi.io.completed_bundle_count_write_en
  io.completedBundleCountWriteData := dpi.io.completed_bundle_count_write_data

  val debugDpiCompletionEventValid = Wire(Bool())
  val debugDpiCompletionEventWakeExit = Wire(Bool())
  val debugDpiCompletionEventBundleId = Wire(UInt(64.W))
  val debugDpiCompletionEventWakeRelevant = Wire(Bool())
  val debugDpiCompletionEventWarpBlocked = Wire(Bool())
  val debugDpiCompletionEventCurrentWarpBlocked = Wire(Bool())
  val debugDpiCompletionEventSmId = Wire(UInt(32.W))
  val debugDpiCompletionEventSchedulerId = Wire(UInt(32.W))
  val debugDpiCompletionEventWarpId = Wire(UInt(32.W))
  val debugDpiCompletionEventCycle = Wire(UInt(64.W))
  debugDpiCompletionEventValid := dpi.io.debug_completion_event_valid
  debugDpiCompletionEventWakeExit := dpi.io.debug_completion_event_wake_exit
  debugDpiCompletionEventBundleId := dpi.io.debug_completion_event_bundle_id
  debugDpiCompletionEventWakeRelevant := dpi.io.debug_completion_event_wake_relevant
  debugDpiCompletionEventWarpBlocked := dpi.io.debug_completion_event_warp_blocked
  debugDpiCompletionEventCurrentWarpBlocked := dpi.io.debug_completion_event_current_warp_blocked
  debugDpiCompletionEventSmId := dpi.io.debug_completion_event_sm_id
  debugDpiCompletionEventSchedulerId := dpi.io.debug_completion_event_scheduler_id
  debugDpiCompletionEventWarpId := dpi.io.debug_completion_event_warp_id
  debugDpiCompletionEventCycle := dpi.io.debug_completion_event_cycle
  dontTouch(debugDpiCompletionEventValid)
  dontTouch(debugDpiCompletionEventWakeExit)
  dontTouch(debugDpiCompletionEventBundleId)
  dontTouch(debugDpiCompletionEventWakeRelevant)
  dontTouch(debugDpiCompletionEventWarpBlocked)
  dontTouch(debugDpiCompletionEventCurrentWarpBlocked)
  dontTouch(debugDpiCompletionEventSmId)
  dontTouch(debugDpiCompletionEventSchedulerId)
  dontTouch(debugDpiCompletionEventWarpId)
  dontTouch(debugDpiCompletionEventCycle)

  for (i <- 0 until params.numGenerators) {
    io.issue(i).valid := false.B
    io.issue(i).bits := 0.U.asTypeOf(new RTLL2Access)
    io.completion(i).ready := true.B
  }
}

class TrafficGenRTLEngine(params: TrafficGenParams) extends Module with HasTrafficGenTopIO {
  require(params.numGenerators >= 1, "TrafficGenRTLEngine requires at least one generator")

  val io = IO(new TrafficGenTopIO(
    params.width, params.numGenerators, params.memOutstanding, useRTL = true))
  private val replaySlotsPerLane = TrafficGenReplaySlots.rtlSlotsPerLane
  private val replaySlots = TrafficGenReplaySlots.totalSlots(params.numGenerators, useRTL = true)
  private val replaySlotIdxWidth = log2Ceil(replaySlots)

  val rtlStates = Enum(7)
  val sIdle = rtlStates(0)
  val sDrainCompletions = rtlStates(1)
  val sRequestAccess = rtlStates(2)
  val sWaitAccessAccepted = rtlStates(3)
  val sWaitAccess = rtlStates(4)
  val sIssueBatch = rtlStates(5)
  val sDrainOutputs = rtlStates(6)

  val state = RegInit(sIdle)
  val targetCycle = RegInit(0.U(64.W))
  val targetTimeStarted = RegInit(false.B)
  val roundCapacityBounded = RegInit(false.B)
  val wakeExitPending = RegInit(false.B)
  val roundExitReasonReg = RegInit(TrafficGenRoundExitReason.scheduling)
  val lastHasPendingWork = RegInit(false.B)

  // Each physical replay lane has three independently acknowledged bridge
  // slots. The lane rotates through them in strict order so the two other
  // slots can issue while an earlier consume crosses HostPort.
  val lastAccessReadRespGenerations =
    RegInit(VecInit(Seq.fill(replaySlots)(0.U(2.W))))
  val accessReadConsumePendingMask =
    RegInit(0.U(replaySlots.W))
  val accessReadLaneSlot =
    RegInit(VecInit(Seq.fill(params.numGenerators)(0.U(2.W))))

  val bundleTableEntries = params.numGenerators * params.memOutstanding * 2 + params.numGenerators
  val bundleCountWidth = log2Ceil(params.numGenerators * params.memOutstanding * 2 + params.numGenerators + 1)
  val bundleValid = RegInit(VecInit(Seq.fill(bundleTableEntries)(false.B)))
  val bundleIds = Reg(Vec(bundleTableEntries, UInt(64.W)))
  val bundleRemainingToIssue = RegInit(VecInit(Seq.fill(bundleTableEntries)(0.U(16.W))))
  val bundleOutstanding = RegInit(VecInit(Seq.fill(bundleTableEntries)(0.U(bundleCountWidth.W))))
  val bundleWakeRelevant = RegInit(VecInit(Seq.fill(bundleTableEntries)(false.B)))
  val bundleWarpBlocked = RegInit(VecInit(Seq.fill(bundleTableEntries)(false.B)))
  // Bundle IDs intentionally alias across GPU schedulers. The driver stamps
  // each access with the GPU scheduling round that introduced it and retains
  // that stamp while the access remains pending. This is independent of host
  // round trips and BRAM-capacity refills.
  val bundleIssueRound = RegInit(VecInit(Seq.fill(bundleTableEntries)(0.U(32.W))))
  val bundleEntryIdxWidth = log2Ceil(bundleTableEntries max 2)

  val issuedBatchQueue = Module(new Queue(new IssuedAccessBatch(params.numGenerators), 2))
  val issuedBatchId = RegInit(0.U(32.W))
  val completedBundleQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(UInt(64.W), params.memOutstanding * 2 + params.numGenerators))
  }
  val completedBundleArb = Module(new RRArbiter(UInt(64.W), params.numGenerators))
  val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))

  io.startTrafficGen := false.B
  io.targetBusy := state =/= sIdle
  io.roundStarted := false.B
  io.roundComplete := false.B
  io.roundExitReason := roundExitReasonReg
  io.dpiState := state

  for (i <- 0 until params.numGenerators) {
    completedBundleQueues(i).io.enq.valid := false.B
    completedBundleQueues(i).io.enq.bits := 0.U
    completedBundleArb.io.in(i) <> completedBundleQueues(i).io.deq
  }

  issuedBatchQueue.io.enq.valid := false.B
  issuedBatchQueue.io.enq.bits := 0.U.asTypeOf(new IssuedAccessBatch(params.numGenerators))
  io.issuedAccessBatch <> issuedBatchQueue.io.deq

  io.accessReadCycle := 0.U
  io.accessReadEn := false.B
  io.accessReadBatchReady := false.B
  io.accessReadConsumeMask := 0.U
  io.accessReadPrefetchPauseAck := io.accessReadPrefetchPauseReq
  io.completedBundleIdWriteEn := completedBundleArb.io.out.valid
  io.completedBundleIdWriteIdx := 0.U
  io.completedBundleIdWriteData := completedBundleArb.io.out.bits
  io.completedBundleCountWriteEn := false.B
  io.completedBundleCountWriteData := 0.U
  completedBundleArb.io.out.ready := true.B

  when(completedBundleArb.io.out.fire) {
    assert(completedBundleCount =/= CompletedBundleIds.capacity.U,
      "TrafficGenRTLEngine completed bundle ID capacity exceeded")
    io.completedBundleIdWriteIdx := completedBundleCount(CompletedBundleIds.idxWidth - 1, 0)
    completedBundleCount := completedBundleCount + 1.U
  }

  io.currentCycleAfterIssue := targetCycle

  // A valid entry can have no request in the cache while it waits for the
  // remaining members of a bundle to arrive in a later GPU scheduler round.
  // Only the outstanding counters describe real RTL L2 work.  In particular,
  // using bundleValid here deadlocks a round whose minIssueCycle is the
  // UINT64_MAX "no next access" sentinel: targetCycle would count forever
  // waiting for a cache response that cannot exist.
  val bundleTableHasInflight =
    VecInit(bundleOutstanding.map(_ =/= 0.U)).asUInt.orR
  val outputQueuesHaveWork =
    issuedBatchQueue.io.deq.valid || completedBundleArb.io.out.valid
  val roundHasFutureIssueWork =
    io.accessStoreHasEntries && !io.accessReadLaneDoneMask.andR
  val livePendingWork =
    state =/= sIdle ||
    io.memActive ||
    outputQueuesHaveWork ||
    roundHasFutureIssueWork ||
    io.accessStoreHasMore
  io.hasPendingWork := Mux(
    state === sIdle || state === sDrainOutputs,
    lastHasPendingWork,
    livePendingWork,
  )

  for (i <- 0 until params.numGenerators) {
    io.issue(i).valid := false.B
    io.issue(i).bits := 0.U.asTypeOf(new RTLL2Access)
    io.completion(i).ready := false.B
  }

  val completedQueuesCanAccept = completedBundleQueues.map(_.io.enq.ready).reduce(_ && _)
  val completionAnyValid = VecInit((0 until params.numGenerators).map(i => io.completion(i).valid)).asUInt.orR
  val drainCompletionsThisCycle = WireDefault(false.B)
  val completionCanFire = drainCompletionsThisCycle && completionAnyValid && completedQueuesCanAccept
  val completionFires = Wire(Vec(params.numGenerators, Bool()))
  for (i <- 0 until params.numGenerators) {
    io.completion(i).ready := completionCanFire
    completionFires(i) := io.completion(i).valid && io.completion(i).ready
  }

  val completeEventValid = Wire(Vec(params.numGenerators, Bool()))
  val completeEventId = Wire(Vec(params.numGenerators, UInt(64.W)))
  val completeEventWakeExit = Wire(Vec(params.numGenerators, Bool()))
  val completeEventWakeRelevant = Wire(Vec(params.numGenerators, Bool()))
  val completeEventWarpBlocked = Wire(Vec(params.numGenerators, Bool()))

  // Forward-declared issue reduction.  Completion reduction starts from this
  // state so simultaneous events are applied in deterministic issue-before-
  // return order.
  val issueCanFireForReducer = WireDefault(false.B)
  val issueBundleValidNext = Wire(Vec(bundleTableEntries, Bool()))
  val issueBundleCountNext = Wire(Vec(bundleTableEntries, UInt(bundleCountWidth.W)))
  val issueBundleRemainingNext = Wire(Vec(bundleTableEntries, UInt(16.W)))
  val issueBundleIdNext = Wire(Vec(bundleTableEntries, UInt(64.W)))
  val issueBundleWakeNext = Wire(Vec(bundleTableEntries, Bool()))
  val issueBundleBlockedNext = Wire(Vec(bundleTableEntries, Bool()))
  val issueBundleRoundNext = Wire(Vec(bundleTableEntries, UInt(32.W)))

  var completionValidExpr: Seq[Bool] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleValidNext(e), bundleValid(e))
  }
  var completionCountExpr: Seq[UInt] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleCountNext(e), bundleOutstanding(e))
  }
  var completionRemainingExpr: Seq[UInt] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleRemainingNext(e), bundleRemainingToIssue(e))
  }
  var completionIdExpr: Seq[UInt] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleIdNext(e), bundleIds(e))
  }
  var completionWakeExpr: Seq[Bool] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleWakeNext(e), bundleWakeRelevant(e))
  }
  var completionBlockedExpr: Seq[Bool] = (0 until bundleTableEntries).map { e =>
    Mux(issueCanFireForReducer, issueBundleBlockedNext(e), bundleWarpBlocked(e))
  }

  for (lane <- 0 until params.numGenerators) {
    val active = completionFires(lane)
    // The memory engines carry a target-private bundle-table index in
    // mBundleId.  The bridge-visible, scheduler-owned ID remains in bundleIds.
    val bundleEntry = io.completion(lane).bits.mBundleId(bundleEntryIdxWidth - 1, 0)
    val bundleEntryInRange = io.completion(lane).bits.mBundleId < bundleTableEntries.U
    val matchVec = (0 until bundleTableEntries).map { e =>
      bundleEntryInRange && completionValidExpr(e) && bundleEntry === e.U
    }
    val matchAny = VecInit(matchVec).asUInt.orR
    val oldCount = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> completionCountExpr(e)
    } :+ (true.B -> 0.U(bundleCountWidth.W)))
    val oldWakeRelevant = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> completionWakeExpr(e)
    } :+ (true.B -> false.B))
    val oldWarpBlocked = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> completionBlockedExpr(e)
    } :+ (true.B -> false.B))
    val oldRemaining = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> completionRemainingExpr(e)
    } :+ (true.B -> 0.U(16.W)))
    val oldBundleId = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> completionIdExpr(e)
    } :+ (true.B -> 0.U(64.W)))
    val willComplete = active && matchAny && oldCount === 1.U && oldRemaining === 0.U

    completeEventValid(lane) := willComplete
    completeEventId(lane) := oldBundleId
    completeEventWakeExit(lane) := willComplete && oldWakeRelevant && oldWarpBlocked
    completeEventWakeRelevant(lane) := oldWakeRelevant
    completeEventWarpBlocked(lane) := oldWarpBlocked

    completionCountExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && matchVec(e), oldCount - 1.U, completionCountExpr(e))
    }
    completionValidExpr = (0 until bundleTableEntries).map { e =>
      Mux(willComplete && matchVec(e), false.B, completionValidExpr(e))
    }

    when(active) {
      assert(bundleEntryInRange,
        "TrafficGenRTLEngine completed an access with an invalid bundle-table index")
      assert(matchAny, "TrafficGenRTLEngine completed an access for an unknown bundle")
      assert(oldCount =/= 0.U, "TrafficGenRTLEngine bundle outstanding count underflow")
    }
  }

  val debugRtlCompletionEventValid = Wire(Bool())
  val debugRtlCompletionEventWakeExit = Wire(Bool())
  val debugRtlCompletionEventBundleId = Wire(UInt(64.W))
  val debugRtlCompletionEventWakeRelevant = Wire(Bool())
  val debugRtlCompletionEventWarpBlocked = Wire(Bool())
  val debugRtlCompletionEventCycle = Wire(UInt(64.W))
  val debugRtlAnyCompletionEvent = completeEventValid.asUInt.orR
  val debugRtlAnyWakeExit = completeEventWakeExit.asUInt.orR
  val debugRtlEventSelect = Mux(debugRtlAnyWakeExit,
    PriorityEncoderOH(completeEventWakeExit.asUInt),
    PriorityEncoderOH(completeEventValid.asUInt))
  debugRtlCompletionEventValid := debugRtlAnyCompletionEvent
  debugRtlCompletionEventWakeExit :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventWakeExit(_)))
  debugRtlCompletionEventBundleId :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventId(_)))
  debugRtlCompletionEventWakeRelevant :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventWakeRelevant(_)))
  debugRtlCompletionEventWarpBlocked :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventWarpBlocked(_)))
  debugRtlCompletionEventCycle := targetCycle
  dontTouch(debugRtlCompletionEventValid)
  dontTouch(debugRtlCompletionEventWakeExit)
  dontTouch(debugRtlCompletionEventBundleId)
  dontTouch(debugRtlCompletionEventWakeRelevant)
  dontTouch(debugRtlCompletionEventWarpBlocked)
  dontTouch(debugRtlCompletionEventCycle)

  val completionBundleValidNext = Wire(Vec(bundleTableEntries, Bool()))
  val completionBundleCountNext = Wire(Vec(bundleTableEntries, UInt(bundleCountWidth.W)))
  val completionBundleRemainingNext = Wire(Vec(bundleTableEntries, UInt(16.W)))
  for (e <- 0 until bundleTableEntries) {
    completionBundleValidNext(e) := completionValidExpr(e)
    completionBundleCountNext(e) := completionCountExpr(e)
    completionBundleRemainingNext(e) := completionRemainingExpr(e)
  }

  for (lane <- 0 until params.numGenerators) {
    completedBundleQueues(lane).io.enq.valid := completeEventValid(lane)
    completedBundleQueues(lane).io.enq.bits := completeEventId(lane)
  }

  val rtlAccessReadData = VecInit(io.accessReadData.map(RTLL2Access.unpack))
  val currentIssueAccess = Wire(Vec(params.numGenerators, new RTLL2Access))
  val currentIssueValid = Wire(Vec(params.numGenerators, Bool()))
  val roundReachedMin = targetCycle >= io.minIssueCycle
  val issueWindowOpen = !roundReachedMin
  val accessReadRespGenerations = VecInit((0 until replaySlots).map { slot =>
    io.accessReadRespId(2 * slot + 1, 2 * slot)
  })
  val accessReadGenerationChangedMask =
    VecInit((0 until replaySlots).map { slot =>
      accessReadRespGenerations(slot) =/= lastAccessReadRespGenerations(slot)
    }).asUInt
  val accessReadAckNowMask = Mux(
    io.accessReadRespValid,
    accessReadConsumePendingMask & accessReadGenerationChangedMask,
    0.U)
  val accessReadPendingAfterAck =
    accessReadConsumePendingMask & ~accessReadAckNowMask
  dontTouch(accessReadAckNowMask)
  val selectedAccessReadSlot = Wire(Vec(params.numGenerators, UInt(replaySlotIdxWidth.W)))
  for (lane <- 0 until params.numGenerators) {
    val selectedSlot = Wire(UInt(replaySlotIdxWidth.W))
    selectedSlot :=
      (lane * replaySlotsPerLane).U(replaySlotIdxWidth.W) +
        accessReadLaneSlot(lane)
    selectedAccessReadSlot(lane) := selectedSlot
    val issuedAccess = Wire(new RTLL2Access)
    issuedAccess := rtlAccessReadData(selectedSlot)
    // Record when this access is actually issued by the target.
    issuedAccess.cycleCount := targetCycle
    currentIssueAccess(lane) := issuedAccess
    currentIssueValid(lane) := io.accessReadDataValid(selectedSlot) &&
      (!accessReadConsumePendingMask(selectedSlot) || accessReadAckNowMask(selectedSlot)) &&
      rtlAccessReadData(selectedSlot).cycleCount <= targetCycle
  }

  // Assign every visible lane head to a concrete table entry before driving
  // the memory engines.  Later lanes see earlier virtual allocations, so
  // same-generation aliases share one entry while distinct bundles cannot
  // collide even when an earlier lane is backpressured.
  val issueAssignedEntry = Wire(Vec(params.numGenerators, UInt(bundleEntryIdxWidth.W)))
  val issueAllocatesEntry = Wire(Vec(params.numGenerators, Bool()))
  var claimedValidExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleValid(_))
  var claimedIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleIds(_))
  var claimedRoundExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleIssueRound(_))
  for (lane <- 0 until params.numGenerators) {
    val access = currentIssueAccess(lane)
    val matchVec = (0 until bundleTableEntries).map { e =>
      claimedValidExpr(e) &&
      claimedIdExpr(e) === access.mBundleId &&
      claimedRoundExpr(e) === access.bundleGeneration
    }
    val matchOH = PriorityEncoderOH(VecInit(matchVec).asUInt)
    val matchAny = VecInit(matchVec).asUInt.orR
    val freeOH = VecInit(claimedValidExpr.map(v => !v)).asUInt
    val allocOH = UIntToOH(PriorityEncoder(freeOH), bundleTableEntries).asUInt &
      Fill(bundleTableEntries, currentIssueValid(lane) && !matchAny)
    val selectedOH = Mux(matchAny, matchOH, allocOH)

    issueAssignedEntry(lane) := OHToUInt(selectedOH)
    issueAllocatesEntry(lane) := currentIssueValid(lane) && !matchAny

    claimedValidExpr = (0 until bundleTableEntries).map { e =>
      Mux(currentIssueValid(lane) && (matchOH(e) || allocOH(e)),
        true.B, claimedValidExpr(e))
    }
    claimedIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(currentIssueValid(lane) && (matchOH(e) || allocOH(e)),
        access.mBundleId, claimedIdExpr(e))
    }
    claimedRoundExpr = (0 until bundleTableEntries).map { e =>
      Mux(currentIssueValid(lane) && (matchOH(e) || allocOH(e)),
        access.bundleGeneration, claimedRoundExpr(e))
    }
  }
  val freeBundleEntries = PopCount(VecInit(bundleValid.map(v => !v)).asUInt)
  val neededNewBundleEntries = PopCount(issueAllocatesEntry)
  val chunkHasValid = currentIssueValid.asUInt.orR
  val issueResourcesAvailable =
    state === sIssueBatch &&
    issueWindowOpen &&
    chunkHasValid &&
    !io.accessReadPrefetchPauseReq &&
    freeBundleEntries >= neededNewBundleEntries &&
    issuedBatchQueue.io.enq.ready

  val issueFires = Wire(Vec(params.numGenerators, Bool()))
  for (lane <- 0 until params.numGenerators) {
    io.issue(lane).valid := issueResourcesAvailable && currentIssueValid(lane)
    io.issue(lane).bits := currentIssueAccess(lane)
    io.issue(lane).bits.mBundleId := issueAssignedEntry(lane)
    issueFires(lane) := io.issue(lane).valid && io.issue(lane).ready
  }
  val issuedBatchMask = issueFires.asUInt
  val issueCanFire = issueFires.asUInt.orR
  issueCanFireForReducer := issueCanFire
  drainCompletionsThisCycle := state === sDrainCompletions || state === sIssueBatch
  issuedBatchQueue.io.enq.valid := issueCanFire
  issuedBatchQueue.io.enq.bits.batchId := issuedBatchId
  issuedBatchQueue.io.enq.bits.validMask := issuedBatchMask
  val issuedBatchPackedAccesses = VecInit((0 until params.numGenerators).map { lane =>
    val issued = Wire(new IssuedAccess)
    issued.requestUid := currentIssueAccess(lane).id
    issued.cycleIssued := currentIssueAccess(lane).cycleCount
    issued.address := currentIssueAccess(lane).address
    issued.isWrite := currentIssueAccess(lane).mIsWrite
    IssuedAccess.pack(issued)
  })
  issuedBatchQueue.io.enq.bits.accesses := issuedBatchPackedAccesses.asUInt
  val accessReadSlotConsumeMask = (0 until params.numGenerators).map { lane =>
    UIntToOH(selectedAccessReadSlot(lane), replaySlots) &
      Fill(replaySlots, issueFires(lane))
  }.reduce(_ | _)
  io.accessReadConsumeMask := accessReadSlotConsumeMask
  accessReadConsumePendingMask := accessReadPendingAfterAck | accessReadSlotConsumeMask
  for (slot <- 0 until replaySlots) {
    when(accessReadAckNowMask(slot)) {
      lastAccessReadRespGenerations(slot) := accessReadRespGenerations(slot)
    }
  }
  assert((accessReadAckNowMask & ~accessReadConsumePendingMask) === 0.U,
    "TrafficGenRTLEngine acknowledged a lane without a pending consume")
  for (lane <- 0 until params.numGenerators) {
    val selectedSlot = selectedAccessReadSlot(lane)
    when(issueFires(lane) && accessReadConsumePendingMask(selectedSlot)) {
      assert(accessReadAckNowMask(selectedSlot),
        "TrafficGenRTLEngine issued a pending replay slot without its acknowledgement")
    }
    when(issueFires(lane)) {
      accessReadLaneSlot(lane) :=
        Mux(accessReadLaneSlot(lane) === (replaySlotsPerLane - 1).U,
          0.U, accessReadLaneSlot(lane) + 1.U)
    }
  }
  for (lane <- 0 until params.numGenerators) {
    val laneConsume = io.accessReadConsumeMask(
      (lane + 1) * replaySlotsPerLane - 1,
      lane * replaySlotsPerLane)
    assert(PopCount(laneConsume) <= 1.U,
      "TrafficGenRTLEngine consumed more than one slot from a replay lane")
  }
  when(roundReachedMin) {
    assert(!issueCanFire,
      "TrafficGenRTLEngine issued an access at or beyond minIssueCycle")
    assert(io.accessReadConsumeMask === 0.U,
      "TrafficGenRTLEngine consumed an access at or beyond minIssueCycle")
  }
  when(issuedBatchQueue.io.enq.fire) {
    assert(issuedBatchMask.orR,
      "TrafficGenRTLEngine enqueued an empty issued-access batch")
    issuedBatchId := issuedBatchId + 1.U
  }
  when(state === sIssueBatch) {
    assert(issuedBatchQueue.io.enq.fire === issueCanFire,
      "TrafficGenRTLEngine did not enqueue exactly one batch for an issue fire")
  }
  when(issuedBatchQueue.io.enq.fire) {
    assert(PopCount(issuedBatchMask) === PopCount(issueFires.asUInt),
      "TrafficGenRTLEngine issued batch population did not match firing lanes")
  }
  val rtlIssuedBatchStalled = RegNext(io.issuedAccessBatch.valid && !io.issuedAccessBatch.ready, false.B)
  val rtlIssuedBatchBitsPrev = RegNext(io.issuedAccessBatch.bits.asUInt)
  when(rtlIssuedBatchStalled) {
    assert(io.issuedAccessBatch.valid,
      "TrafficGenRTLEngine dropped an issued batch under backpressure")
    assert(io.issuedAccessBatch.bits.asUInt === rtlIssuedBatchBitsPrev,
      "TrafficGenRTLEngine changed an issued batch under backpressure")
  }

  var issueValidExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleValid(_))
  var issueCountExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleOutstanding(_))
  var issueRemainingExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleRemainingToIssue(_))
  var issueIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleIds(_))
  var issueWakeExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleWakeRelevant(_))
  var issueBlockedExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleWarpBlocked(_))
  var issueRoundExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleIssueRound(_))

  for (lane <- 0 until params.numGenerators) {
    val active = issueFires(lane)
    val access = currentIssueAccess(lane)
    val assignedEntry = issueAssignedEntry(lane)
    val matchVec = (0 until bundleTableEntries).map { e =>
      issueValidExpr(e) && assignedEntry === e.U
    }
    val matchAny = VecInit(matchVec).asUInt.orR
    val allocOH = UIntToOH(assignedEntry, bundleTableEntries).asUInt &
      Fill(bundleTableEntries, active && !matchAny)
    val oldRemaining = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> issueRemainingExpr(e)
    } :+ (true.B -> 0.U(16.W)))
    val oldWakeRelevant = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> issueWakeExpr(e)
    } :+ (true.B -> false.B))
    val oldWarpBlocked = PriorityMux((0 until bundleTableEntries).map { e =>
      matchVec(e) -> issueBlockedExpr(e)
    } :+ (true.B -> false.B))

    when(active) {
      assert(access.bundleIssueCount =/= 0.U,
        "TrafficGenRTLEngine issued an access with zero bundleIssueCount")
      assert(assignedEntry < bundleTableEntries.U,
        "TrafficGenRTLEngine assigned an invalid bundle-table index")
      when(matchAny) {
        assert(VecInit(issueIdExpr)(assignedEntry) === access.mBundleId,
          "TrafficGenRTLEngine bundle-table ID mismatch")
        assert(VecInit(issueRoundExpr)(assignedEntry) === access.bundleGeneration,
          "TrafficGenRTLEngine bundle-table generation mismatch")
        assert(oldRemaining =/= 0.U,
          "TrafficGenRTLEngine issued more members than bundleIssueCount in one round")
      }
    }

    issueValidExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), true.B, issueValidExpr(e))
    }
    issueCountExpr = (0 until bundleTableEntries).map { e =>
      val priorCount = Mux(matchVec(e), issueCountExpr(e), 0.U)
      Mux(active && (matchVec(e) || allocOH(e)), priorCount + 1.U, issueCountExpr(e))
    }
    issueRemainingExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && allocOH(e), access.bundleIssueCount - 1.U,
        Mux(active && matchVec(e),
          issueRemainingExpr(e) - 1.U,
          issueRemainingExpr(e)))
    }
    issueIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && allocOH(e), access.mBundleId, issueIdExpr(e))
    }
    issueWakeExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && allocOH(e), access.mWakeRelevantBundle,
        Mux(active && matchVec(e), oldWakeRelevant || access.mWakeRelevantBundle,
          issueWakeExpr(e)))
    }
    issueBlockedExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && allocOH(e), access.mWarpBlocked,
        Mux(active && matchVec(e), oldWarpBlocked || access.mWarpBlocked,
          issueBlockedExpr(e)))
    }
    issueRoundExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && allocOH(e), access.bundleGeneration, issueRoundExpr(e))
    }
  }

  for (e <- 0 until bundleTableEntries) {
    issueBundleValidNext(e) := issueValidExpr(e)
    issueBundleCountNext(e) := issueCountExpr(e)
    issueBundleRemainingNext(e) := issueRemainingExpr(e)
    issueBundleIdNext(e) := issueIdExpr(e)
    issueBundleWakeNext(e) := issueWakeExpr(e)
    issueBundleBlockedNext(e) := issueBlockedExpr(e)
    issueBundleRoundNext(e) := issueRoundExpr(e)
  }

  val outputQueuesEmpty =
    !issuedBatchQueue.io.deq.valid &&
    !completedBundleArb.io.out.valid
  val allAccessLanesDone = io.accessReadLaneDoneMask.andR
  val roundMemoryHasWork = bundleTableHasInflight || io.memActive
  val pendingAfterExit =
    !allAccessLanesDone ||
    roundMemoryHasWork ||
    io.accessStoreHasMore
  val minExitReady =
    roundReachedMin &&
    !accessReadPendingAfterAck.orR
  val acceptingRound =
    state === sIdle && io.startRound && io.uploadReady
  val startingWorkload =
    acceptingRound && !targetTimeStarted

  io.accessReadCycle := targetCycle
  io.accessReadEn := state === sIssueBatch && issueWindowOpen
  io.accessReadBatchReady := state === sIssueBatch && issueWindowOpen

  // The workload's target-time epoch begins on the first accepted round. Until
  // then the socket scheduler's initialization cycle and the RTL both remain
  // at zero even while the rest of the target boots. Once armed, target time is
  // free-running: HostPort fire gating is the sole authority over whether a
  // target edge occurs, and engine state or scheduling boundaries never stop
  // it.
  when(targetTimeStarted || startingWorkload) {
    targetCycle := targetCycle + 1.U
  }
  when(startingWorkload) {
    targetTimeStarted := true.B
  }
  dontTouch(targetTimeStarted)

  when(state === sIdle) {
    when(acceptingRound) {
      roundCapacityBounded := io.accessStoreHasMore
      roundExitReasonReg := TrafficGenRoundExitReason.scheduling
      wakeExitPending := false.B
      completedBundleCount := 0.U
      accessReadConsumePendingMask := 0.U
      accessReadLaneSlot.foreach(_ := 0.U)
      for (slot <- 0 until replaySlots) {
        lastAccessReadRespGenerations(slot) := accessReadRespGenerations(slot)
      }
      lastHasPendingWork := false.B
      io.roundStarted := true.B
      state := sDrainCompletions
    }
  }.elsewhen(state === sDrainCompletions) {
    when(completionAnyValid) {
      when(completionCanFire) {
        for (e <- 0 until bundleTableEntries) {
          bundleValid(e) := completionBundleValidNext(e)
          bundleOutstanding(e) := completionBundleCountNext(e)
          bundleRemainingToIssue(e) := completionBundleRemainingNext(e)
        }
        when(completeEventWakeExit.asUInt.orR) {
          wakeExitPending := true.B
        }
      }
    }.otherwise {
      when(wakeExitPending) {
        roundExitReasonReg := TrafficGenRoundExitReason.scheduling
        lastHasPendingWork := pendingAfterExit
        state := sDrainOutputs
      }.elsewhen(minExitReady) {
        roundExitReasonReg := TrafficGenRoundExitReason.scheduling
        lastHasPendingWork := pendingAfterExit
        state := sDrainOutputs
      }.elsewhen(allAccessLanesDone && !accessReadPendingAfterAck.orR && roundCapacityBounded) {
        roundExitReasonReg := TrafficGenRoundExitReason.capacity
        lastHasPendingWork := true.B
        state := sDrainOutputs
      }.elsewhen(allAccessLanesDone && !accessReadPendingAfterAck.orR && !roundMemoryHasWork) {
        roundExitReasonReg := TrafficGenRoundExitReason.scheduling
        lastHasPendingWork := pendingAfterExit
        state := sDrainOutputs
      }.otherwise {
        state := sIssueBatch
      }
    }
  }.elsewhen(state === sIssueBatch) {
    when(issueCanFire) {
      for (e <- 0 until bundleTableEntries) {
        bundleValid(e) := Mux(completionCanFire,
          completionBundleValidNext(e), issueBundleValidNext(e))
        bundleOutstanding(e) := Mux(completionCanFire,
          completionBundleCountNext(e), issueBundleCountNext(e))
        bundleRemainingToIssue(e) := Mux(completionCanFire,
          completionBundleRemainingNext(e), issueBundleRemainingNext(e))
        bundleIds(e) := issueBundleIdNext(e)
        bundleWakeRelevant(e) := issueBundleWakeNext(e)
        bundleWarpBlocked(e) := issueBundleBlockedNext(e)
        bundleIssueRound(e) := issueBundleRoundNext(e)
      }
      when(completionCanFire && completeEventWakeExit.asUInt.orR) {
        wakeExitPending := true.B
      }
    }.elsewhen(completionCanFire) {
      for (e <- 0 until bundleTableEntries) {
        bundleValid(e) := completionBundleValidNext(e)
        bundleOutstanding(e) := completionBundleCountNext(e)
        bundleRemainingToIssue(e) := completionBundleRemainingNext(e)
      }
      when(completeEventWakeExit.asUInt.orR) {
        wakeExitPending := true.B
      }
    }.elsewhen(wakeExitPending) {
      roundExitReasonReg := TrafficGenRoundExitReason.scheduling
      lastHasPendingWork := pendingAfterExit
      state := sDrainOutputs
    }.elsewhen(minExitReady) {
      roundExitReasonReg := TrafficGenRoundExitReason.scheduling
      lastHasPendingWork := pendingAfterExit
      state := sDrainOutputs
    }.elsewhen(allAccessLanesDone && !accessReadPendingAfterAck.orR && roundCapacityBounded) {
      roundExitReasonReg := TrafficGenRoundExitReason.capacity
      lastHasPendingWork := true.B
        state := sDrainOutputs
    }.elsewhen(allAccessLanesDone && !accessReadPendingAfterAck.orR && !roundMemoryHasWork) {
      roundExitReasonReg := TrafficGenRoundExitReason.scheduling
      lastHasPendingWork := pendingAfterExit
      state := sDrainOutputs
    }
  }.elsewhen(state === sDrainOutputs) {
    when(outputQueuesEmpty) {
      io.completedBundleCountWriteEn := true.B
      io.completedBundleCountWriteData := completedBundleCount
      io.roundComplete := true.B
      state := sIdle
    }
  }

  // The bridge asserts trafficGenDone only after the scheduler has consumed
  // the final round outputs and reported global workload completion. Reset all
  // workload-scoped state while the level remains asserted so a later kernel
  // can establish a fresh cycle-zero epoch without resetting the machine.
  when(io.trafficGenDone) {
    assert(state === sIdle,
      "TrafficGenRTLEngine workload completed while a round was active")
    assert(!io.memActive && !bundleTableHasInflight,
      "TrafficGenRTLEngine workload completed with memory requests in flight")
    assert(outputQueuesEmpty,
      "TrafficGenRTLEngine workload completed with undrained outputs")

    state := sIdle
    targetCycle := 0.U
    targetTimeStarted := false.B
    roundCapacityBounded := false.B
    wakeExitPending := false.B
    roundExitReasonReg := TrafficGenRoundExitReason.scheduling
    lastHasPendingWork := false.B
    accessReadConsumePendingMask := 0.U
    accessReadLaneSlot.foreach(_ := 0.U)
    for (slot <- 0 until replaySlots) {
      lastAccessReadRespGenerations(slot) := accessReadRespGenerations(slot)
    }
    issuedBatchId := 0.U
    completedBundleCount := 0.U
    for (e <- 0 until bundleTableEntries) {
      bundleValid(e) := false.B
      bundleIds(e) := 0.U
      bundleRemainingToIssue(e) := 0.U
      bundleOutstanding(e) := 0.U
      bundleWakeRelevant(e) := false.B
      bundleWarpBlocked(e) := false.B
      bundleIssueRound(e) := 0.U
    }
  }
}

class TrafficGenTL(params: TrafficGenParams, beatBytes: Int)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("TrafficGenTL", Seq("ucbbar,TrafficGenTL"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address, 4096 - 1)),
    device,
    "reg/control",
    beatBytes = beatBytes)

  // TL client shell waits for bridge upload completion and exposes ready/status state.

  override lazy val module = new TrafficGenImpl

  class TrafficGenImpl extends Impl with HasTrafficGenTopIO {
    val io = IO(new TrafficGenTopIO(
      params.width,
      params.numGenerators,
      params.memOutstanding,
      useRTL = params.backend == TrafficGenRTLBackend,
    ))

    withClockAndReset(clock, reset) {

      val trafficGenIdle = Wire(Bool())

      // start traffic generator when target program writes to start register
      val startTrafficGenPulse = Wire(Bool())
      startTrafficGenPulse := false.B
      io.startTrafficGen := startTrafficGenPulse

      val patternReady = RegInit(false.B)
      val engine = params.backend match {
        case TrafficGenDPIBackend => Module(new TrafficGenDPIEngine(params))
        case TrafficGenRTLBackend => Module(new TrafficGenRTLEngine(params))
      }

      engine.io.uploadReady := io.uploadReady
      engine.io.minIssueCycle := io.minIssueCycle
      engine.io.startRound := io.startRound
      engine.io.trafficGenDone := io.trafficGenDone
      engine.io.accessReadData := io.accessReadData
      engine.io.accessReadDataValid := io.accessReadDataValid
      engine.io.accessReadRespValid := io.accessReadRespValid
      engine.io.accessReadRespId := io.accessReadRespId
      engine.io.accessReadBucketDone := io.accessReadBucketDone
      engine.io.accessReadReady := io.accessReadReady
      io.accessReadConsumeMask := engine.io.accessReadConsumeMask
      engine.io.accessReadLaneDoneMask := io.accessReadLaneDoneMask
      engine.io.accessReadPrefetchPauseReq := io.accessReadPrefetchPauseReq
      io.accessReadPrefetchPauseAck := engine.io.accessReadPrefetchPauseAck
      engine.io.accessStoreCount := io.accessStoreCount
      engine.io.accessStoreMaxCycle := io.accessStoreMaxCycle
      engine.io.accessStoreHasEntries := io.accessStoreHasEntries
      engine.io.accessStoreHasMore := io.accessStoreHasMore
      engine.io.memActive := io.memActive
      engine.io.memInflightAccesses := io.memInflightAccesses
      for (i <- 0 until params.numGenerators) {
        engine.io.completion(i).valid := io.completion(i).valid
        engine.io.completion(i).bits := io.completion(i).bits
        io.completion(i).ready := engine.io.completion(i).ready
      }

      io.targetBusy := engine.io.targetBusy
      io.hasPendingWork := engine.io.hasPendingWork
      io.roundStarted := engine.io.roundStarted
      io.roundComplete := engine.io.roundComplete
      io.roundExitReason := engine.io.roundExitReason
      io.currentCycleAfterIssue := engine.io.currentCycleAfterIssue
      io.dpiState := engine.io.dpiState
      io.issuedAccessBatch <> engine.io.issuedAccessBatch
      io.completedBundleIdWriteEn := engine.io.completedBundleIdWriteEn
      io.completedBundleIdWriteIdx := engine.io.completedBundleIdWriteIdx
      io.completedBundleIdWriteData := engine.io.completedBundleIdWriteData
      io.completedBundleCountWriteEn := engine.io.completedBundleCountWriteEn
      io.completedBundleCountWriteData := engine.io.completedBundleCountWriteData
      io.accessReadCycle := engine.io.accessReadCycle
      io.accessReadEn := engine.io.accessReadEn
      io.accessReadBatchReady := engine.io.accessReadBatchReady
      trafficGenIdle := !engine.io.targetBusy

      for (i <- 0 until params.numGenerators) {
        io.issue(i).valid := engine.io.issue(i).valid
        io.issue(i).bits := engine.io.issue(i).bits
        engine.io.issue(i).ready := io.issue(i).ready
      }

      when(io.uploadReady) {
        patternReady := true.B
      }

      node.regmap(
        0x00 -> Seq(RegField.r(1, trafficGenIdle)),
        0x04 -> Seq(RegField.w(1, startTrafficGenPulse)),
        0x08 -> Seq(RegField.r(1, io.targetBusy)),
        0x0C -> Seq(RegField.r(1, io.uploadReady)),
        0x10 -> Seq(RegField.r(1, io.trafficGenDone)),
        0x18 -> Seq(RegField.r(1, patternReady)),
        0x20 -> Seq(RegField.r(32, io.minIssueCycle(31, 0))),
        0x24 -> Seq(RegField.r(32, io.minIssueCycle(63, 32))),
        0x38 -> Seq(RegField.r(32, io.currentCycleAfterIssue(31, 0))),
        0x3C -> Seq(RegField.r(32, io.currentCycleAfterIssue(63, 32))),
        0x40 -> Seq(RegField.r(32, io.dpiState)),
        0x44 -> Seq(RegField.r(2, io.roundExitReason))
      )
    }
  }
}

class TrafficGenMemIO(params: TrafficGenParams) extends Bundle {
  val active = Output(Bool())
  val inflightAccessCount = Output(UInt(log2Ceil(params.memOutstanding + 1).W))
  val coreOffset = Input(UInt(32.W))
  val req = Flipped(Decoupled(new RTLL2Access))
  val completion = Decoupled(new RTLL2Access)
}

abstract class TrafficGenMemBase(id: Int, beatBytes: Int, params: TrafficGenParams)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val node: TLClientNode

  override lazy val module: TrafficGenMemBaseModuleImp = new TrafficGenMemBaseModuleImp(this)
  class TrafficGenMemBaseModuleImp(outer: TrafficGenMemBase) extends Impl {
    val io = IO(new TrafficGenMemIO(params))
  }
}

class TrafficGenMem(id: Int, beatBytes: Int, params: TrafficGenParams)(implicit p: Parameters)
    extends TrafficGenMemBase(id, beatBytes, params)(p) {
  require(beatBytes <= 64 && 64 % beatBytes == 0,
    "TrafficGenMem assumes 64-byte accesses split into an integral number of TL beats")
  require(params.memOutstanding >= 1, "TrafficGenMem requires at least one outstanding access slot")
  if (params.remapTraceAddresses) {
    require(params.remapSize == (1L << 32),
      "TrafficGenMem trace-address remapping currently assumes a 4 GiB window")
    require(params.remapBase % 64 == 0,
      "TrafficGenMem trace-address remap base must be 64-byte aligned")
  }

  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v2(
      name = s"trafficgenmem$id",
      sourceId = IdRange(0, params.memOutstanding),
      emits = TLMasterToSlaveTransferSizes(
        get = TransferSizes(64, 64),
        putFull = TransferSizes(64, 64)
      )
    ))
  )))

  // traffic generator should:
  //- continuously read from the L2 access store
  //- issue accesses and advance the bridge access window
  //- track when issued acceses return and populate completedBundleIds
  //- stop at min_issue_cycle or when unblocking a blocked warp
  //- report currentCycle after issue

  override lazy val module = new TrafficGenMemModuleImp(this)

  class TrafficGenMemModuleImp(outer: TrafficGenMem) extends TrafficGenMemBaseModuleImp(outer) {
    withClockAndReset(clock, reset) {
      val (mem, edge) = outer.node.out(0)
      dontTouch(io.coreOffset)

      val issueQueue = Module(new Queue(new RTLL2Access, params.memOutstanding))
      val completionQueue = Module(new Queue(new RTLL2Access, params.memOutstanding))
      val inflightValid = RegInit(VecInit(Seq.fill(params.memOutstanding)(false.B)))
      val inflightAccesses = Reg(Vec(params.memOutstanding, new RTLL2Access))
      val senderValid = RegInit(false.B)
      val senderAccess = Reg(new RTLL2Access)
      val sourceIdxWidth = log2Ceil(params.memOutstanding max 2)
      val senderSource = Reg(UInt(sourceIdxWidth.W))
      val freeSourceOH = VecInit(inflightValid.map(v => !v)).asUInt
      val hasFreeSource = freeSourceOH.orR
      val nextSource = PriorityEncoder(freeSourceOH)
      val activeAccess = senderAccess
      val rawAddr = activeAccess.address + io.coreOffset
      val remappedAddr = params.remapBase.U(64.W) + activeAccess.address(31, 0)
      val selectedAddr = Mux(params.remapTraceAddresses.B, remappedAddr, rawAddr)
      val addr = Cat(selectedAddr(63, 6), 0.U(6.W))
      val size = log2Ceil(64).U

      issueQueue.io.enq <> io.req

      val canStartRequest = !senderValid && hasFreeSource
      issueQueue.io.deq.ready := canStartRequest

      when(issueQueue.io.deq.fire) {
        val allocatedSource = nextSource(sourceIdxWidth - 1, 0)
        assert(hasFreeSource, "TrafficGenMem allocated a request with no free source slots")
        senderValid := true.B
        senderAccess := issueQueue.io.deq.bits
        senderSource := allocatedSource
        inflightValid(allocatedSource) := true.B
        inflightAccesses(allocatedSource) := issueQueue.io.deq.bits
      }

      val (putLegal, putBits) = edge.Put(senderSource, addr, size, 0.U((beatBytes * 8).W))
      val (getLegal, getBits) = edge.Get(senderSource, addr, size)

      mem.a.valid := senderValid
      mem.a.bits := Mux(activeAccess.mIsWrite, putBits, getBits)
      val (_, aLast, aDone, aBeat) = edge.count(mem.a)

      val beatIndex = Wire(UInt(64.W))
      beatIndex := aBeat
      val writePatternSeed = activeAccess.id ^
        activeAccess.mBundleId ^
        id.U(64.W) ^
        beatIndex ^
        "h9e3779b97f4a7c15".U(64.W)
      val writePattern = if (beatBytes * 8 <= 64) {
        writePatternSeed((beatBytes * 8) - 1, 0)
      } else {
        Cat(Seq.tabulate((beatBytes * 8 + 63) / 64) { i =>
          writePatternSeed ^ i.U(64.W)
        }.reverse)(beatBytes * 8 - 1, 0)
      }
      when(activeAccess.mIsWrite) {
        mem.a.bits.data := writePattern
      }

      val dLast = edge.last(mem.d.bits, mem.d.fire)
      val dSourceInRange = mem.d.bits.source < params.memOutstanding.U
      val dSource = mem.d.bits.source(sourceIdxWidth - 1, 0)
      val dSourceValid = dSourceInRange && inflightValid(dSource)

      mem.b.ready := false.B
      mem.c.valid := false.B
      mem.c.bits := DontCare
      mem.d.ready := dSourceValid && (!dLast || completionQueue.io.enq.ready)
      mem.e.valid := false.B
      mem.e.bits := DontCare

      completionQueue.io.enq.valid := mem.d.valid && dSourceValid && dLast
      completionQueue.io.enq.bits := inflightAccesses(dSource)
      io.completion <> completionQueue.io.deq
      io.active := senderValid ||
        issueQueue.io.deq.valid ||
        inflightValid.asUInt.orR ||
        completionQueue.io.deq.valid
      io.inflightAccessCount := PopCount(inflightValid)

      when(mem.a.fire) {
        assert(Mux(activeAccess.mIsWrite, putLegal, getLegal), "TrafficGenMem issued illegal TL access")
      }

      when(mem.a.fire && aLast) {
        senderValid := false.B
      }

      when(mem.d.valid) {
        assert(dSourceValid, "TrafficGenMem received a D response for an invalid source slot")
      }
      when(mem.d.fire && dLast) {
        inflightValid(dSource) := false.B
      }
    }
  }
}

class TrafficGenMemL2(id: Int, beatBytes: Int, params: TrafficGenParams)(implicit p: Parameters)
    extends TrafficGenMemBase(id, beatBytes, params)(p) {
  private val blockBytes = p(CacheBlockBytes)
  private val blockBeats = blockBytes / beatBytes
  private val accessBytes = 64
  private val accessBeats = accessBytes / beatBytes

  require(blockBytes >= accessBytes && isPow2(blockBytes) && blockBytes % accessBytes == 0,
    "TrafficGenMemL2 requires cache blocks that are a power-of-two multiple of 64 bytes")
  require(beatBytes <= accessBytes && accessBytes % beatBytes == 0,
    "TrafficGenMemL2 assumes 64-byte accesses split into an integral number of TL beats")
  require(blockBytes % beatBytes == 0,
    "TrafficGenMemL2 requires cache blocks split into an integral number of TL beats")
  require(params.memOutstanding >= 1, "TrafficGenMemL2 requires at least one outstanding access slot")
  if (params.remapTraceAddresses) {
    require(params.remapSize == (1L << 32),
      "TrafficGenMemL2 trace-address remapping currently assumes a 4 GiB window")
    require(params.remapBase % accessBytes == 0,
      "TrafficGenMemL2 trace-address remap base must be 64-byte aligned")
  }

  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v2(
      name = s"trafficgenmeml2$id",
      sourceId = IdRange(0, params.memOutstanding),
      supports = TLSlaveToMasterTransferSizes(
        probe = TransferSizes(blockBytes, blockBytes)
      ),
      emits = TLMasterToSlaveTransferSizes(
        acquireB = TransferSizes(blockBytes, blockBytes),
        acquireT = TransferSizes(blockBytes, blockBytes)
      )
    ))
  )))

  override lazy val module = new TrafficGenMemL2ModuleImp(this)

  class TrafficGenMemL2ModuleImp(outer: TrafficGenMemL2) extends TrafficGenMemBaseModuleImp(outer) {
    withClockAndReset(clock, reset) {
      val (mem, edge) = outer.node.out(0)
      dontTouch(io.coreOffset)

      val issueQueue = Module(new Queue(new RTLL2Access, params.memOutstanding))
      val completionQueue = Module(new Queue(new RTLL2Access, params.memOutstanding))
      val sourceIdxWidth = log2Ceil(params.memOutstanding max 2)
      val beatIdxWidth = log2Ceil(blockBeats max 2)
      val beatBits = beatBytes * 8
      val blockOffsetBits = log2Ceil(blockBytes)
      val beatOffsetBits = log2Ceil(beatBytes)
      val blockSize = log2Ceil(blockBytes).U

      val sInvalid :: sAcquire :: sWaitGrant :: sReleaseClean :: sReleaseDirty :: sWaitReleaseAck :: Nil = Enum(6)
      val sourceStates = RegInit(VecInit(Seq.fill(params.memOutstanding)(sInvalid)))
      val inflightValid = RegInit(VecInit(Seq.fill(params.memOutstanding)(false.B)))
      val inflightAccesses = Reg(Vec(params.memOutstanding, new RTLL2Access))
      val inflightLineAddrs = Reg(Vec(params.memOutstanding, UInt(64.W)))
      val inflightAccessBeatBases = Reg(Vec(params.memOutstanding, UInt(beatIdxWidth.W)))
      val lineData = Reg(Vec(params.memOutstanding, Vec(blockBeats, UInt(beatBits.W))))

      val senderValid = RegInit(false.B)
      val senderAccess = Reg(new RTLL2Access)
      val senderSource = Reg(UInt(sourceIdxWidth.W))
      val senderLineAddr = Reg(UInt(64.W))

      def selectedAddress(access: RTLL2Access): UInt = {
        val rawAddr = access.address + io.coreOffset
        val remappedAddr = params.remapBase.U(64.W) + access.address(31, 0)
        Mux(params.remapTraceAddresses.B, remappedAddr, rawAddr)
      }

      def patternFromSeed(seed: UInt): UInt = {
        if (beatBits <= 64) {
          seed(beatBits - 1, 0)
        } else {
          Cat(Seq.tabulate((beatBits + 63) / 64) { i =>
            seed ^ i.U(64.W)
          }.reverse)(beatBits - 1, 0)
        }
      }

      def writePatternForLineBeat(source: UInt, lineBeat: UInt): UInt = {
        val relativeBeat = lineBeat - inflightAccessBeatBases(source)
        val relativeBeat64 = Wire(UInt(64.W))
        relativeBeat64 := relativeBeat
        val seed = inflightAccesses(source).id ^
          inflightAccesses(source).mBundleId ^
          id.U(64.W) ^
          relativeBeat64 ^
          "h9e3779b97f4a7c15".U(64.W)
        patternFromSeed(seed)
      }

      def lineBeatIsAccessBeat(source: UInt, lineBeat: UInt): Bool = {
        val base = inflightAccessBeatBases(source)
        lineBeat >= base && lineBeat < (base + accessBeats.U)
      }

      issueQueue.io.enq <> io.req

      val freeSourceOH = VecInit(inflightValid.map(v => !v)).asUInt
      val hasFreeSource = freeSourceOH.orR
      val nextSource = PriorityEncoder(freeSourceOH)
      val canStartRequest = !senderValid && hasFreeSource
      issueQueue.io.deq.ready := canStartRequest

      when(issueQueue.io.deq.fire) {
        val allocatedSource = nextSource(sourceIdxWidth - 1, 0)
        val accessAddr = selectedAddress(issueQueue.io.deq.bits)
        val lineAddr = Cat(accessAddr(63, blockOffsetBits), 0.U(blockOffsetBits.W))
        val accessBeatBase =
          if (blockBeats == 1) 0.U(beatIdxWidth.W) else accessAddr(blockOffsetBits - 1, beatOffsetBits)
        assert(hasFreeSource, "TrafficGenMemL2 allocated a request with no free source slots")
        senderValid := true.B
        senderAccess := issueQueue.io.deq.bits
        senderSource := allocatedSource
        senderLineAddr := lineAddr
        inflightValid(allocatedSource) := true.B
        sourceStates(allocatedSource) := sAcquire
        inflightAccesses(allocatedSource) := issueQueue.io.deq.bits
        inflightLineAddrs(allocatedSource) := lineAddr
        inflightAccessBeatBases(allocatedSource) := accessBeatBase
      }

      val activeAccess = senderAccess
      val (acquireBLegal, acquireBBits) =
        edge.AcquireBlock(senderSource, senderLineAddr, blockSize, TLPermissions.NtoB)
      val (acquireTLegal, acquireTBits) =
        edge.AcquireBlock(senderSource, senderLineAddr, blockSize, TLPermissions.NtoT)

      mem.a.valid := senderValid
      mem.a.bits := Mux(activeAccess.mIsWrite, acquireTBits, acquireBBits)
      val (_, aLast, _, _) = edge.count(mem.a)

      when(mem.a.fire) {
        assert(Mux(activeAccess.mIsWrite, acquireTLegal, acquireBLegal),
          "TrafficGenMemL2 issued illegal TL-C AcquireBlock")
      }
      when(mem.a.fire && aLast) {
        senderValid := false.B
        sourceStates(senderSource) := sWaitGrant
      }

      val (dFirst, dLast, _, dBeat) = edge.count(mem.d)
      val dSourceInRange = mem.d.bits.source < params.memOutstanding.U
      val dSource = mem.d.bits.source(sourceIdxWidth - 1, 0)
      val dSourceValid = dSourceInRange && inflightValid(dSource)
      val dIsGrant = mem.d.bits.opcode === TLMessages.Grant || mem.d.bits.opcode === TLMessages.GrantData
      val dIsReleaseAck = mem.d.bits.opcode === TLMessages.ReleaseAck
      val dHasData = edge.hasData(mem.d.bits)
      val dGrantReadyNoE = dSourceValid && sourceStates(dSource) === sWaitGrant
      val dReleaseAckReady = dSourceValid &&
        sourceStates(dSource) === sWaitReleaseAck &&
        completionQueue.io.enq.ready

      mem.e.valid := mem.d.valid && dIsGrant && dFirst && dGrantReadyNoE
      mem.e.bits := edge.GrantAck(mem.d.bits)
      mem.d.ready := Mux(dIsGrant,
        dGrantReadyNoE && (!dFirst || mem.e.ready),
        dIsReleaseAck && dReleaseAckReady)

      when(mem.d.fire && dIsGrant) {
        assert(!mem.d.bits.denied, "TrafficGenMemL2 received a denied Grant")
        assert(dSourceValid, "TrafficGenMemL2 received a Grant for an invalid source slot")
        when(dHasData) {
          lineData(dSource)(dBeat(beatIdxWidth - 1, 0)) := mem.d.bits.data
        }
        when(dLast) {
          when(inflightAccesses(dSource).mIsWrite) {
            for (i <- 0 until blockBeats) {
              val beat = i.U(beatIdxWidth.W)
              when(lineBeatIsAccessBeat(dSource, beat)) {
                lineData(dSource)(i) := writePatternForLineBeat(dSource, beat)
              }
            }
            sourceStates(dSource) := sReleaseDirty
          }.otherwise {
            sourceStates(dSource) := sReleaseClean
          }
        }
      }

      val releaseSourceOH = VecInit(sourceStates.map(s => s === sReleaseClean || s === sReleaseDirty)).asUInt
      val hasRelease = releaseSourceOH.orR
      val releaseSource = PriorityEncoder(releaseSourceOH)(sourceIdxWidth - 1, 0)
      val releaseDirty = hasRelease && sourceStates(releaseSource) === sReleaseDirty

      val probeValid = RegInit(false.B)
      val probeBits = Reg(new TLBundleB(edge.bundle))
      mem.b.ready := !probeValid
      when(mem.b.fire) {
        probeValid := true.B
        probeBits := mem.b.bits
      }

      val probeAddressBits = probeBits.address.getWidth
      val probeLineAddr = Cat(
        0.U((64 - probeAddressBits).W),
        probeBits.address(probeAddressBits - 1, blockOffsetBits),
        0.U(blockOffsetBits.W))
      val probeSourceOH = VecInit((0 until params.memOutstanding).map { i =>
        inflightValid(i) &&
        (sourceStates(i) === sReleaseClean || sourceStates(i) === sReleaseDirty) &&
        inflightLineAddrs(i) === probeLineAddr
      }).asUInt
      val probeSource = PriorityEncoder(probeSourceOH)(sourceIdxWidth - 1, 0)
      val probeOwned = probeValid && probeSourceOH.orR
      val probeDirty = probeOwned && sourceStates(probeSource) === sReleaseDirty
      val probeParam = Mux(probeOwned,
        Mux(probeDirty, TLPermissions.TtoN, TLPermissions.BtoN),
        TLPermissions.NtoN)

      val cActive = RegInit(false.B)
      val cActiveIsProbe = Reg(Bool())
      val cActiveSource = Reg(UInt(sourceIdxWidth.W))
      val cActiveReleaseDirty = Reg(Bool())
      val cActiveProbeOwned = Reg(Bool())
      val cActiveProbeDirty = Reg(Bool())
      val cActiveProbeBits = Reg(new TLBundleB(edge.bundle))

      when(!cActive) {
        when(probeValid) {
          cActive := true.B
          cActiveIsProbe := true.B
          cActiveSource := probeSource
          cActiveReleaseDirty := false.B
          cActiveProbeOwned := probeOwned
          cActiveProbeDirty := probeDirty
          cActiveProbeBits := probeBits
        }.elsewhen(hasRelease) {
          cActive := true.B
          cActiveIsProbe := false.B
          cActiveSource := releaseSource
          cActiveReleaseDirty := releaseDirty
          cActiveProbeOwned := false.B
          cActiveProbeDirty := false.B
          cActiveProbeBits := DontCare
        }
      }

      val cBeatWire = Wire(UInt(beatIdxWidth.W))
      val probeData = lineData(cActiveSource)(cBeatWire)
      val releaseData = lineData(cActiveSource)(cBeatWire)
      val activeProbeParam = Mux(cActiveProbeOwned,
        Mux(cActiveProbeDirty, TLPermissions.TtoN, TLPermissions.BtoN),
        TLPermissions.NtoN)
      val probeAck = edge.ProbeAck(cActiveProbeBits, activeProbeParam)
      val probeAckData = edge.ProbeAck(cActiveProbeBits, activeProbeParam, probeData)
      val (releaseCleanLegal, releaseCleanBits) =
        edge.Release(cActiveSource, inflightLineAddrs(cActiveSource), blockSize, TLPermissions.BtoN)
      val (releaseDirtyLegal, releaseDirtyBits) =
        edge.Release(cActiveSource, inflightLineAddrs(cActiveSource), blockSize, TLPermissions.TtoN, releaseData)

      mem.c.bits := Mux(cActiveIsProbe,
        Mux(cActiveProbeDirty, probeAckData, probeAck),
        Mux(cActiveReleaseDirty, releaseDirtyBits, releaseCleanBits))
      val (_, cLast, cDone, cBeat) = edge.count(mem.c)
      cBeatWire := cBeat(beatIdxWidth - 1, 0)
      val cProbeCompletesAccess = cActiveIsProbe && cActiveProbeOwned
      val dReleaseAckCompletesAccess = mem.d.valid && dIsReleaseAck && dReleaseAckReady
      mem.c.valid := cActive &&
        (!cProbeCompletesAccess || !cLast || (completionQueue.io.enq.ready && !dReleaseAckCompletesAccess))

      when(mem.c.fire && !cActiveIsProbe) {
        assert(Mux(cActiveReleaseDirty, releaseDirtyLegal, releaseCleanLegal),
          "TrafficGenMemL2 issued illegal TL-C Release")
      }

      when(cDone) {
        cActive := false.B
        when(cActiveIsProbe) {
          probeValid := false.B
          when(cActiveProbeOwned) {
            inflightValid(cActiveSource) := false.B
            sourceStates(cActiveSource) := sInvalid
          }
        }.otherwise {
          sourceStates(cActiveSource) := sWaitReleaseAck
        }
      }

      val dCompletionFire = mem.d.fire && dIsReleaseAck && dSourceValid
      val cProbeCompletionFire = cDone && cActiveIsProbe && cActiveProbeOwned
      completionQueue.io.enq.valid := dCompletionFire || cProbeCompletionFire
      completionQueue.io.enq.bits := Mux(dCompletionFire,
        inflightAccesses(dSource),
        inflightAccesses(cActiveSource))

      when(dCompletionFire) {
        inflightValid(dSource) := false.B
        sourceStates(dSource) := sInvalid
      }
      when(mem.d.valid && dIsReleaseAck) {
        assert(dSourceValid, "TrafficGenMemL2 received a ReleaseAck for an invalid source slot")
      }

      io.completion <> completionQueue.io.deq
      io.active := senderValid ||
        issueQueue.io.deq.valid ||
        inflightValid.asUInt.orR ||
        completionQueue.io.deq.valid
      io.inflightAccessCount := PopCount(inflightValid)
    }
  }
}

trait CanHaveTrafficGen { this: BaseSubsystem =>
  private val portName = "TrafficGenTL"
  private val pbus = locateTLBusWrapper(PBUS)
  private val sbus = locateTLBusWrapper(SBUS)

  val trafficGenIO = p(TrafficGenKey).map { params =>
    val trafficGenTL = LazyModule(new TrafficGenTL(params, pbus.beatBytes)(p))
    trafficGenTL.clockNode := pbus.fixedClockNode
    pbus.coupleTo(portName) {
      trafficGenTL.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
    }

    val generators = (0 until params.numGenerators).map { i =>
      val trafficGenMem = params.memBackend match {
        case TrafficGenUncachedMemBackend => LazyModule(new TrafficGenMem(i, sbus.beatBytes, params)(p))
        case TrafficGenL2MemBackend => LazyModule(new TrafficGenMemL2(i, sbus.beatBytes, params)(p))
      }
      trafficGenMem.clockNode := sbus.fixedClockNode
      sbus.coupleFrom(s"trafficgen-mem-$i") { _ := trafficGenMem.node }
      trafficGenMem
    }

    InModuleBody {
      val outerIO = IO(new ClockedIO(new TrafficGenPortPeripheralIO(
        params.numGenerators, params.backend == TrafficGenRTLBackend))).suggestName("trafficgen")
      dontTouch(outerIO)

      outerIO.clock := trafficGenTL.module.clock
      outerIO.bits.targetBusy <> trafficGenTL.module.io.targetBusy
      outerIO.bits.hasPendingWork <> trafficGenTL.module.io.hasPendingWork
      outerIO.bits.startTrafficGen <> trafficGenTL.module.io.startTrafficGen
      outerIO.bits.roundStarted <> trafficGenTL.module.io.roundStarted
      outerIO.bits.roundComplete <> trafficGenTL.module.io.roundComplete
      outerIO.bits.roundExitReason <> trafficGenTL.module.io.roundExitReason
      outerIO.bits.currentCycleAfterIssue <> trafficGenTL.module.io.currentCycleAfterIssue
      outerIO.bits.dpiState <> trafficGenTL.module.io.dpiState
      outerIO.bits.issuedAccessBatch <> trafficGenTL.module.io.issuedAccessBatch
      outerIO.bits.completedBundleIdWriteEn <> trafficGenTL.module.io.completedBundleIdWriteEn
      outerIO.bits.completedBundleIdWriteIdx <> trafficGenTL.module.io.completedBundleIdWriteIdx
      outerIO.bits.completedBundleIdWriteData <> trafficGenTL.module.io.completedBundleIdWriteData
      outerIO.bits.completedBundleCountWriteEn <> trafficGenTL.module.io.completedBundleCountWriteEn
      outerIO.bits.completedBundleCountWriteData <> trafficGenTL.module.io.completedBundleCountWriteData
      outerIO.bits.startRound <> trafficGenTL.module.io.startRound
      outerIO.bits.trafficGenDone <> trafficGenTL.module.io.trafficGenDone
      outerIO.bits.minIssueCycle <> trafficGenTL.module.io.minIssueCycle
      outerIO.bits.accessReadCycle <> trafficGenTL.module.io.accessReadCycle
      outerIO.bits.accessReadEn <> trafficGenTL.module.io.accessReadEn
      outerIO.bits.accessReadBatchReady <> trafficGenTL.module.io.accessReadBatchReady
      outerIO.bits.accessReadRespValid <> trafficGenTL.module.io.accessReadRespValid
      outerIO.bits.accessReadRespId <> trafficGenTL.module.io.accessReadRespId
      outerIO.bits.accessReadData <> trafficGenTL.module.io.accessReadData
      outerIO.bits.accessReadDataValid <> trafficGenTL.module.io.accessReadDataValid
      outerIO.bits.accessReadBucketDone <> trafficGenTL.module.io.accessReadBucketDone
      outerIO.bits.accessReadReady <> trafficGenTL.module.io.accessReadReady
      outerIO.bits.accessReadConsumeMask <> trafficGenTL.module.io.accessReadConsumeMask
      outerIO.bits.accessReadLaneDoneMask <> trafficGenTL.module.io.accessReadLaneDoneMask
      outerIO.bits.accessReadPrefetchPauseReq <> trafficGenTL.module.io.accessReadPrefetchPauseReq
      outerIO.bits.accessReadPrefetchPauseAck <> trafficGenTL.module.io.accessReadPrefetchPauseAck
      outerIO.bits.accessStoreCount <> trafficGenTL.module.io.accessStoreCount
      outerIO.bits.accessStoreMaxCycle <> trafficGenTL.module.io.accessStoreMaxCycle
      outerIO.bits.accessStoreHasEntries <> trafficGenTL.module.io.accessStoreHasEntries
      outerIO.bits.accessStoreHasMore <> trafficGenTL.module.io.accessStoreHasMore
      outerIO.bits.uploadReady <> trafficGenTL.module.io.uploadReady

      trafficGenTL.module.io.memActive := generators.map(_.module.io.active).foldLeft(false.B)(_ || _)
      trafficGenTL.module.io.memInflightAccesses := VecInit(generators.map(_.module.io.inflightAccessCount))
      generators.zipWithIndex.foreach { case (generator, i) =>
        generator.module.io.coreOffset := (BigInt(i) * params.regionStride).U
        generator.module.io.req <> trafficGenTL.module.io.issue(i)
        trafficGenTL.module.io.completion(i) <> generator.module.io.completion
      }

      outerIO
    }
  }
}

class WithTrafficGen(numGenerators: Int = 1) extends Config((site, here, up) => {
  case TrafficGenKey => Some(TrafficGenParams(numGenerators = numGenerators))
})

class WithTrafficGenMemL2 extends Config((site, here, up) => {
  case TrafficGenKey => up(TrafficGenKey, site).map(_.copy(memBackend = TrafficGenL2MemBackend))
})

class WithRTLTrafficGen(numGenerators: Int = 16) extends Config((site, here, up) => {
  case TrafficGenKey => Some(TrafficGenParams(
    numGenerators = numGenerators,
    memOutstanding = 8,
    backend = TrafficGenRTLBackend,
    remapTraceAddresses = true,
    remapBase = 0x100000000L,
    remapSize = 1L << 32))
})
