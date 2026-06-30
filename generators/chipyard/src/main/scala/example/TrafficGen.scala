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
import firechip.bridgeinterfaces.{BlockedWarpBitmap, CompletedBundleIds, L2Access, TrafficGenAccessBatch, TrafficGenRoundExitReason}

sealed trait TrafficGenBackend
case object TrafficGenDPIBackend extends TrafficGenBackend
case object TrafficGenRTLBackend extends TrafficGenBackend

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
  remapTraceAddresses: Boolean = false,
  remapBase: BigInt = 0x80000000L,
  remapSize: BigInt = 1L << 32
)

case object TrafficGenKey extends Field[Option[TrafficGenParams]](None)

class TrafficGenTopIO(val w: Int, val nGenerators: Int, val memOutstanding: Int) extends Bundle {

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

  // L2 accesses and blocked warp bitmap have been written to the bridge module.
  val uploadReady = Input(Bool())
  // furthest the TG is allowed to issue
  val minIssueCycle = Input(UInt(64.W))
  // begin next round of issuing
  val startRound = Input(Bool())
  // GPU model main loop has completed; this is global completion, not per-round idle
  val trafficGenDone = Input(Bool())

  //// Target -> Host execution data

  // TG issue logic will enqueue the actual issued L2 accesses here with cycleCount updated to the real issue cycle.
  val issuedAccessWriteback = Decoupled(new L2Access)

  // query the L2 access store for an L2 access
  val accessReadCycle = Output(UInt(64.W))
  val accessReadEn = Output(Bool())
  val accessReadBatchReady = Output(Bool())

  // query the blocked warp bitmap for whether a warp is blocked
  val blockedWarpQueryIdx = Output(UInt(BlockedWarpBitmap.indexBits.W))
  val blockedWarpQueryEn = Output(Bool())
  val blockedWarpQueryRespStored = Output(Bool())

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
  val accessReadRespId = Input(UInt(32.W))
  val accessReadData = Input(Vec(TrafficGenAccessBatch.lanes, new L2Access))
  val accessReadDataValid = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))
  val accessReadBucketDone = Input(Bool())
  val accessReadReady = Input(Bool())
  val accessStoreCount = Input(UInt(32.W))
  val accessStoreMaxCycle = Input(UInt(64.W))
  val accessStoreHasEntries = Input(Bool())

  // response to blocked warp bitmap query
  val blockedWarpQueryResp = Input(Bool())
  val blockedWarpQueryRespValid = Input(Bool())
  val blockedWarpQueryReady = Input(Bool())

  val memActive = Input(Bool())
  val memInflightAccesses = Input(Vec(nGenerators, UInt(log2Ceil(memOutstanding + 1).W)))
  val issue = Vec(nGenerators, Decoupled(new L2Access))
  val completion = Vec(nGenerators, Flipped(Decoupled(new L2Access)))
}

trait HasTrafficGenTopIO {
  def io: TrafficGenTopIO
}

class TrafficGenWakeQuery extends Bundle {
  val smId = UInt(32.W)
  val schedulerId = UInt(8.W)
  val warpId = UInt(32.W)
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
    val min_issue_cycle = Input(UInt(64.W))

    val access_read_resp_valid = Input(Bool())
    val access_read_resp_id = Input(UInt(32.W))
    val access_read_data_valid = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))
    val access_read_bucket_done = Input(Bool())
    val access_read_ready = Input(Bool())
    val access_read_id = Input(Vec(TrafficGenAccessBatch.lanes, UInt(64.W)))
    val access_read_address = Input(Vec(TrafficGenAccessBatch.lanes, UInt(64.W)))
    val access_read_cycle_count = Input(Vec(TrafficGenAccessBatch.lanes, UInt(64.W)))
    val access_read_subpartition = Input(Vec(TrafficGenAccessBatch.lanes, UInt(32.W)))
    val access_read_set_index = Input(Vec(TrafficGenAccessBatch.lanes, UInt(32.W)))
    val access_read_tag = Input(Vec(TrafficGenAccessBatch.lanes, UInt(64.W)))
    val access_read_mask = Input(Vec(TrafficGenAccessBatch.lanes, UInt(32.W)))
    val access_read_sm_id = Input(Vec(TrafficGenAccessBatch.lanes, UInt(32.W)))
    val access_read_scheduler_id = Input(Vec(TrafficGenAccessBatch.lanes, UInt(8.W)))
    val access_read_warp_id = Input(Vec(TrafficGenAccessBatch.lanes, UInt(32.W)))
    val access_read_bundle_id = Input(Vec(TrafficGenAccessBatch.lanes, UInt(64.W)))
    val access_read_wake_relevant_bundle = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))
    val access_read_is_write = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))

    val blocked_warp_query_resp_valid = Input(Bool())
    val blocked_warp_query_resp = Input(Bool())
    val blocked_warp_query_ready = Input(Bool())

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
    val blocked_warp_query_en = Output(Bool())
    val blocked_warp_query_idx = Output(UInt(BlockedWarpBitmap.indexBits.W))
    val blocked_warp_query_resp_stored = Output(Bool())

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

class TrafficGenDPIEngine(params: TrafficGenParams) extends Module with HasTrafficGenTopIO {
  require(params.numGenerators >= 1, "TrafficGenDPIEngine requires at least one generator")

  val io = IO(new TrafficGenTopIO(params.width, params.numGenerators, params.memOutstanding))

  val currentCycleAfterIssue = Wire(UInt(64.W))
  val roundStarted = Wire(Bool())
  val roundComplete = Wire(Bool())
  val dpi = Module(new TrafficGenDPIBlackBox(params.numGenerators))

  io.startTrafficGen := false.B

  dpi.io.clock := clock
  dpi.io.reset := reset.asBool

  dpi.io.start_round := io.startRound
  dpi.io.upload_ready := io.uploadReady
  dpi.io.access_store_count := io.accessStoreCount
  dpi.io.access_store_max_cycle := io.accessStoreMaxCycle
  dpi.io.access_store_has_entries := io.accessStoreHasEntries
  dpi.io.min_issue_cycle := io.minIssueCycle

  dpi.io.access_read_resp_valid := io.accessReadRespValid
  dpi.io.access_read_resp_id := io.accessReadRespId
  dpi.io.access_read_data_valid := io.accessReadDataValid
  dpi.io.access_read_bucket_done := io.accessReadBucketDone
  dpi.io.access_read_ready := io.accessReadReady
  for (i <- 0 until TrafficGenAccessBatch.lanes) {
    dpi.io.access_read_id(i) := io.accessReadData(i).id
    dpi.io.access_read_address(i) := io.accessReadData(i).address
    dpi.io.access_read_cycle_count(i) := io.accessReadData(i).cycleCount
    dpi.io.access_read_subpartition(i) := io.accessReadData(i).mSubpartition
    dpi.io.access_read_set_index(i) := io.accessReadData(i).mSetIndex
    dpi.io.access_read_tag(i) := io.accessReadData(i).mTag
    dpi.io.access_read_mask(i) := io.accessReadData(i).mMask
    dpi.io.access_read_sm_id(i) := io.accessReadData(i).smId
    dpi.io.access_read_scheduler_id(i) := io.accessReadData(i).schedulerId
    dpi.io.access_read_warp_id(i) := io.accessReadData(i).warpId
    dpi.io.access_read_bundle_id(i) := io.accessReadData(i).mBundleId
    dpi.io.access_read_wake_relevant_bundle(i) := io.accessReadData(i).mWakeRelevantBundle
    dpi.io.access_read_is_write(i) := io.accessReadData(i).mIsWrite
  }

  dpi.io.blocked_warp_query_resp_valid := io.blockedWarpQueryRespValid
  dpi.io.blocked_warp_query_resp := io.blockedWarpQueryResp
  dpi.io.blocked_warp_query_ready := io.blockedWarpQueryReady

  val issuedAccessWritebackLanes = Wire(Vec(params.numGenerators, Decoupled(new L2Access)))

  val issuedAccessWritebackQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(new L2Access, 4))
  }
  val issuedAccessWritebackArb = Module(new RRArbiter(new L2Access, params.numGenerators))

  for (i <- 0 until params.numGenerators) {
    issuedAccessWritebackQueues(i).io.enq.valid := issuedAccessWritebackLanes(i).valid
    issuedAccessWritebackQueues(i).io.enq.bits := issuedAccessWritebackLanes(i).bits
    issuedAccessWritebackLanes(i).ready := issuedAccessWritebackQueues(i).io.enq.ready
    issuedAccessWritebackArb.io.in(i) <> issuedAccessWritebackQueues(i).io.deq
  }

  io.issuedAccessWriteback.valid := issuedAccessWritebackArb.io.out.valid
  io.issuedAccessWriteback.bits := issuedAccessWritebackArb.io.out.bits
  issuedAccessWritebackArb.io.out.ready := io.issuedAccessWriteback.ready

  dpi.io.issued_access_writeback_ready := issuedAccessWritebackLanes(0).ready

  io.currentCycleAfterIssue := currentCycleAfterIssue
  io.roundStarted := roundStarted
  io.roundComplete := roundComplete
  io.roundExitReason := dpi.io.round_exit_reason

  io.accessReadCycle := dpi.io.access_read_cycle
  io.accessReadEn := dpi.io.access_read_en
  io.accessReadBatchReady := dpi.io.access_read_batch_ready
  io.blockedWarpQueryIdx := dpi.io.blocked_warp_query_idx
  io.blockedWarpQueryEn := dpi.io.blocked_warp_query_en
  io.blockedWarpQueryRespStored := dpi.io.blocked_warp_query_resp_stored

  io.hasPendingWork := dpi.io.has_pending_work
  roundStarted := dpi.io.round_started
  roundComplete := dpi.io.round_complete
  currentCycleAfterIssue := dpi.io.current_cycle_after_issue
  io.targetBusy := dpi.io.target_busy
  io.dpiState := dpi.io.dpi_state

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
    io.issue(i).bits := 0.U.asTypeOf(new L2Access)
    io.completion(i).ready := true.B
    issuedAccessWritebackLanes(i).valid := (if (i == 0) dpi.io.issued_access_writeback_valid else false.B)
    issuedAccessWritebackLanes(i).bits.id := dpi.io.issued_access_writeback_id
    issuedAccessWritebackLanes(i).bits.address := dpi.io.issued_access_writeback_address
    issuedAccessWritebackLanes(i).bits.cycleCount := dpi.io.issued_access_writeback_cycle_count
    issuedAccessWritebackLanes(i).bits.mSubpartition := dpi.io.issued_access_writeback_subpartition
    issuedAccessWritebackLanes(i).bits.mSetIndex := dpi.io.issued_access_writeback_set_index
    issuedAccessWritebackLanes(i).bits.mTag := dpi.io.issued_access_writeback_tag
    issuedAccessWritebackLanes(i).bits.mMask := dpi.io.issued_access_writeback_mask
    issuedAccessWritebackLanes(i).bits.smId := dpi.io.issued_access_writeback_sm_id
    issuedAccessWritebackLanes(i).bits.schedulerId := dpi.io.issued_access_writeback_scheduler_id
    issuedAccessWritebackLanes(i).bits.warpId := dpi.io.issued_access_writeback_warp_id
    issuedAccessWritebackLanes(i).bits.mBundleId := dpi.io.issued_access_writeback_bundle_id
    issuedAccessWritebackLanes(i).bits.mWakeRelevantBundle := dpi.io.issued_access_writeback_wake_relevant_bundle
    issuedAccessWritebackLanes(i).bits.mIsWrite := dpi.io.issued_access_writeback_is_write
    issuedAccessWritebackLanes(i).bits.mWarpBlocked := false.B
  }
}

class TrafficGenRTLEngine(params: TrafficGenParams) extends Module with HasTrafficGenTopIO {
  require(params.numGenerators >= 1, "TrafficGenRTLEngine requires at least one generator")
  require(params.numGenerators <= TrafficGenAccessBatch.lanes,
    "TrafficGenRTLEngine cannot issue more lanes than the bridge access-read batch returns")

  val io = IO(new TrafficGenTopIO(params.width, params.numGenerators, params.memOutstanding))

  val rtlStates = Enum(10)
  val sIdle = rtlStates(0)
  val sDrainCompletions = rtlStates(1)
  val sRequestAccess = rtlStates(2)
  val sWaitAccessAccepted = rtlStates(3)
  val sWaitAccess = rtlStates(4)
  val sIssueBatch = rtlStates(5)
  val sDrainOutputs = rtlStates(6)
  val sRequestBlockedWarp = rtlStates(7)
  val sWaitBlockedWarp = rtlStates(8)
  val sAckBlockedWarp = rtlStates(9)

  val state = RegInit(sIdle)
  val modelCycle = RegInit(0.U(64.W))
  val targetCycle = RegInit(0.U(64.W))
  val roundLoadEndCycle = RegInit(0.U(64.W))
  val roundHasFutureIssueWork = RegInit(false.B)
  val roundCapacityBounded = RegInit(false.B)
  val wakeExitPending = RegInit(false.B)
  val roundExitReasonReg = RegInit(TrafficGenRoundExitReason.scheduling)
  val lastHasPendingWork = RegInit(false.B)

  val batchAccesses = Reg(Vec(TrafficGenAccessBatch.lanes, new L2Access))
  val batchValid = RegInit(VecInit(Seq.fill(TrafficGenAccessBatch.lanes)(false.B)))
  val batchBucketDone = RegInit(false.B)
  val batchIssueCursor = RegInit(0.U(log2Ceil(TrafficGenAccessBatch.lanes + params.numGenerators + 1).W))

  val bundleTableEntries = params.numGenerators * params.memOutstanding * 2 + TrafficGenAccessBatch.lanes
  val bundleCountWidth = log2Ceil(params.numGenerators * params.memOutstanding * 2 + TrafficGenAccessBatch.lanes + 1)
  val bundleValid = RegInit(VecInit(Seq.fill(bundleTableEntries)(false.B)))
  val bundleIds = Reg(Vec(bundleTableEntries, UInt(64.W)))
  val bundleOutstanding = RegInit(VecInit(Seq.fill(bundleTableEntries)(0.U(bundleCountWidth.W))))
  val bundleWakeRelevant = RegInit(VecInit(Seq.fill(bundleTableEntries)(false.B)))
  val bundleSmIds = Reg(Vec(bundleTableEntries, UInt(32.W)))
  val bundleSchedulerIds = Reg(Vec(bundleTableEntries, UInt(8.W)))
  val bundleWarpIds = Reg(Vec(bundleTableEntries, UInt(32.W)))

  val issuedWritebackQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(new L2Access, params.memOutstanding * 2 + TrafficGenAccessBatch.lanes))
  }
  val issuedWritebackArb = Module(new RRArbiter(new L2Access, params.numGenerators))
  val completedBundleQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(UInt(64.W), params.memOutstanding * 2 + TrafficGenAccessBatch.lanes))
  }
  val completedBundleArb = Module(new RRArbiter(UInt(64.W), params.numGenerators))
  val wakeQueryQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(new TrafficGenWakeQuery, bundleTableEntries))
  }
  val wakeQueryArb = Module(new RRArbiter(new TrafficGenWakeQuery, params.numGenerators))
  val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))
  val activeWakeQuery = Reg(new TrafficGenWakeQuery)
  val blockedWarpQueryRespReg = RegInit(false.B)
  val resumeAfterBlockedQuery = RegInit(sDrainCompletions)
  val backgroundDrainActive = RegInit(false.B)
  val roundOutputAwaitingHost = RegInit(false.B)

  def batchAccessAt(idx: UInt): L2Access = {
    val zero = 0.U.asTypeOf(new L2Access)
    PriorityMux((0 until TrafficGenAccessBatch.lanes).map { i =>
      (idx === i.U) -> batchAccesses(i)
    } :+ (true.B -> zero))
  }

  def batchValidAt(idx: UInt): Bool =
    PriorityMux((0 until TrafficGenAccessBatch.lanes).map { i =>
      (idx === i.U) -> batchValid(i)
    } :+ (true.B -> false.B))

  io.startTrafficGen := false.B
  io.targetBusy := state =/= sIdle
  io.roundStarted := false.B
  io.roundComplete := false.B
  io.roundExitReason := roundExitReasonReg
  io.dpiState := state

  for (i <- 0 until params.numGenerators) {
    issuedWritebackQueues(i).io.enq.valid := false.B
    issuedWritebackQueues(i).io.enq.bits := 0.U.asTypeOf(new L2Access)
    issuedWritebackArb.io.in(i) <> issuedWritebackQueues(i).io.deq
    completedBundleQueues(i).io.enq.valid := false.B
    completedBundleQueues(i).io.enq.bits := 0.U
    completedBundleArb.io.in(i) <> completedBundleQueues(i).io.deq
    wakeQueryQueues(i).io.enq.valid := false.B
    wakeQueryQueues(i).io.enq.bits := 0.U.asTypeOf(new TrafficGenWakeQuery)
    wakeQueryArb.io.in(i) <> wakeQueryQueues(i).io.deq
  }
  wakeQueryArb.io.out.ready := false.B

  io.issuedAccessWriteback.valid := issuedWritebackArb.io.out.valid
  io.issuedAccessWriteback.bits := issuedWritebackArb.io.out.bits
  issuedWritebackArb.io.out.ready := io.issuedAccessWriteback.ready

  io.accessReadCycle := 0.U
  io.accessReadEn := false.B
  io.accessReadBatchReady := false.B
  io.blockedWarpQueryIdx := 0.U
  io.blockedWarpQueryEn := false.B
  io.blockedWarpQueryRespStored := false.B

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

  io.currentCycleAfterIssue := modelCycle

  val bundleTableHasOutstanding = bundleValid.asUInt.orR
  val wakeQueryQueuesHaveWork = wakeQueryArb.io.out.valid
  val outputQueuesHaveWork =
    issuedWritebackArb.io.out.valid || completedBundleArb.io.out.valid
  val cleanupWorkPending =
    io.memActive ||
    bundleTableHasOutstanding ||
    wakeQueryQueuesHaveWork ||
    outputQueuesHaveWork
  val livePendingWork =
    state =/= sIdle ||
    io.memActive ||
    bundleTableHasOutstanding ||
    wakeQueryQueuesHaveWork ||
    outputQueuesHaveWork ||
    roundHasFutureIssueWork
  io.hasPendingWork := livePendingWork

  for (i <- 0 until params.numGenerators) {
    io.issue(i).valid := false.B
    io.issue(i).bits := 0.U.asTypeOf(new L2Access)
    io.completion(i).ready := false.B
  }

  val completedQueuesCanAccept = completedBundleQueues.map(_.io.enq.ready).reduce(_ && _)
  val wakeQueryQueuesCanAccept = wakeQueryQueues.map(_.io.enq.ready).reduce(_ && _)
  val completionAnyValid = VecInit((0 until params.numGenerators).map(i => io.completion(i).valid)).asUInt.orR
  val drainCompletionsThisCycle = Wire(Bool())
  val completionCanFire = drainCompletionsThisCycle && completionAnyValid && completedQueuesCanAccept && wakeQueryQueuesCanAccept
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
  val completeEventSmId = Wire(Vec(params.numGenerators, UInt(32.W)))
  val completeEventSchedulerId = Wire(Vec(params.numGenerators, UInt(32.W)))
  val completeEventWarpId = Wire(Vec(params.numGenerators, UInt(32.W)))
  val completeEventNeedsWakeQuery = Wire(Vec(params.numGenerators, Bool()))

  val completionEntryMatches = Seq.tabulate(bundleTableEntries, params.numGenerators) { (e, lane) =>
    completionFires(lane) &&
      bundleValid(e) &&
      bundleIds(e) === io.completion(lane).bits.mBundleId
  }
  val completionEntryCounts = Seq.tabulate(bundleTableEntries) { e =>
    PopCount(VecInit((0 until params.numGenerators).map(lane => completionEntryMatches(e)(lane))).asUInt)
  }
  val completionEntryWillComplete = Seq.tabulate(bundleTableEntries) { e =>
    bundleValid(e) &&
      completionEntryCounts(e) =/= 0.U &&
      completionEntryCounts(e) === bundleOutstanding(e)
  }

  for (lane <- 0 until params.numGenerators) {
    val matchAny = VecInit((0 until bundleTableEntries).map(e => completionEntryMatches(e)(lane))).asUInt.orR
    val representativeCompleteVec = (0 until bundleTableEntries).map { e =>
      val earlierLaneSameEntry = if (lane == 0) {
        false.B
      } else {
        VecInit((0 until lane).map(earlier => completionEntryMatches(e)(earlier))).asUInt.orR
      }
      completionEntryMatches(e)(lane) && !earlierLaneSameEntry && completionEntryWillComplete(e)
    }
    val representativeComplete = VecInit(representativeCompleteVec).asUInt.orR

    completeEventValid(lane) := representativeComplete
    completeEventId(lane) := PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleIds(e)
    } :+ (true.B -> 0.U(64.W)))
    completeEventWakeExit(lane) := false.B
    completeEventNeedsWakeQuery(lane) := representativeComplete && PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleWakeRelevant(e)
    } :+ (true.B -> false.B))
    completeEventWakeRelevant(lane) := PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleWakeRelevant(e)
    } :+ (true.B -> false.B))
    completeEventWarpBlocked(lane) := false.B
    completeEventSmId(lane) := PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleSmIds(e)
    } :+ (true.B -> 0.U(32.W)))
    completeEventSchedulerId(lane) := Cat(0.U(24.W), PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleSchedulerIds(e)
    } :+ (true.B -> 0.U(8.W))))
    completeEventWarpId(lane) := PriorityMux((0 until bundleTableEntries).map { e =>
      representativeCompleteVec(e) -> bundleWarpIds(e)
    } :+ (true.B -> 0.U(32.W)))

    when(completionFires(lane)) {
      assert(matchAny, "TrafficGenRTLEngine completed an access for an unknown bundle")
    }
  }

  val debugRtlCompletionEventValid = Wire(Bool())
  val debugRtlCompletionEventWakeExit = Wire(Bool())
  val debugRtlCompletionEventBundleId = Wire(UInt(64.W))
  val debugRtlCompletionEventWakeRelevant = Wire(Bool())
  val debugRtlCompletionEventWarpBlocked = Wire(Bool())
  val debugRtlCompletionEventSmId = Wire(UInt(32.W))
  val debugRtlCompletionEventSchedulerId = Wire(UInt(32.W))
  val debugRtlCompletionEventWarpId = Wire(UInt(32.W))
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
  debugRtlCompletionEventSmId :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventSmId(_)))
  debugRtlCompletionEventSchedulerId :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventSchedulerId(_)))
  debugRtlCompletionEventWarpId :=
    Mux1H(debugRtlEventSelect, (0 until params.numGenerators).map(completeEventWarpId(_)))
  debugRtlCompletionEventCycle := modelCycle
  dontTouch(debugRtlCompletionEventValid)
  dontTouch(debugRtlCompletionEventWakeExit)
  dontTouch(debugRtlCompletionEventBundleId)
  dontTouch(debugRtlCompletionEventWakeRelevant)
  dontTouch(debugRtlCompletionEventWarpBlocked)
  dontTouch(debugRtlCompletionEventSmId)
  dontTouch(debugRtlCompletionEventSchedulerId)
  dontTouch(debugRtlCompletionEventWarpId)
  dontTouch(debugRtlCompletionEventCycle)

  val completionBundleValidNext = Wire(Vec(bundleTableEntries, Bool()))
  val completionBundleCountNext = Wire(Vec(bundleTableEntries, UInt(bundleCountWidth.W)))
  for (e <- 0 until bundleTableEntries) {
    val hasCompletions = completionEntryCounts(e) =/= 0.U
    when(hasCompletions) {
      assert(completionEntryCounts(e) <= bundleOutstanding(e),
        "TrafficGenRTLEngine completed more accesses for a bundle than were outstanding")
    }
    completionBundleValidNext(e) := Mux(completionEntryWillComplete(e), false.B, bundleValid(e))
    completionBundleCountNext(e) := Mux(hasCompletions, bundleOutstanding(e) - completionEntryCounts(e), bundleOutstanding(e))
  }

  for (lane <- 0 until params.numGenerators) {
    completedBundleQueues(lane).io.enq.valid := completeEventValid(lane)
    completedBundleQueues(lane).io.enq.bits := completeEventId(lane)
    wakeQueryQueues(lane).io.enq.valid := completeEventNeedsWakeQuery(lane)
    wakeQueryQueues(lane).io.enq.bits.smId := completeEventSmId(lane)
    wakeQueryQueues(lane).io.enq.bits.schedulerId := completeEventSchedulerId(lane)(7, 0)
    wakeQueryQueues(lane).io.enq.bits.warpId := completeEventWarpId(lane)
  }

  val currentIssueAccess = Wire(Vec(params.numGenerators, new L2Access))
  val currentIssueValid = Wire(Vec(params.numGenerators, Bool()))
  for (lane <- 0 until params.numGenerators) {
    val batchIdx = batchIssueCursor + lane.U
    val issuedAccess = Wire(new L2Access)
    issuedAccess := batchAccessAt(batchIdx)
    issuedAccess.cycleCount := modelCycle
    currentIssueAccess(lane) := issuedAccess
    currentIssueValid(lane) := batchIdx < TrafficGenAccessBatch.lanes.U && batchValidAt(batchIdx)
  }

  val issueExistingMatches = Wire(Vec(params.numGenerators, Bool()))
  val issueFirstNewBundles = Wire(Vec(params.numGenerators, Bool()))
  for (lane <- 0 until params.numGenerators) {
    issueExistingMatches(lane) := VecInit((0 until bundleTableEntries).map { e =>
      bundleValid(e) && bundleIds(e) === currentIssueAccess(lane).mBundleId
    }).asUInt.orR
    val earlierLaneHasSameBundle = if (lane == 0) {
      false.B
    } else {
      VecInit((0 until lane).map { earlier =>
        currentIssueValid(earlier) &&
        currentIssueAccess(earlier).mBundleId === currentIssueAccess(lane).mBundleId
      }).asUInt.orR
    }
    issueFirstNewBundles(lane) :=
      currentIssueValid(lane) &&
      !issueExistingMatches(lane) &&
      !earlierLaneHasSameBundle
  }
  val freeBundleEntries = PopCount(VecInit(bundleValid.map(v => !v)).asUInt)
  val neededNewBundleEntries = PopCount(issueFirstNewBundles)
  val issueReady = VecInit((0 until params.numGenerators).map { lane =>
    !currentIssueValid(lane) || (io.issue(lane).ready && issuedWritebackQueues(lane).io.enq.ready)
  }).asUInt.andR
  val chunkHasValid = currentIssueValid.asUInt.orR
  val issueBlockedByControl =
    completionAnyValid ||
    wakeQueryQueuesHaveWork
  val issueCanFire =
    state === sIssueBatch &&
    chunkHasValid &&
    issueReady &&
    freeBundleEntries >= neededNewBundleEntries &&
    !issueBlockedByControl
  drainCompletionsThisCycle := state === sDrainCompletions ||
    (state === sIssueBatch && completionAnyValid)

  val issueFires = Wire(Vec(params.numGenerators, Bool()))
  for (lane <- 0 until params.numGenerators) {
    io.issue(lane).valid := issueCanFire && currentIssueValid(lane)
    io.issue(lane).bits := currentIssueAccess(lane)
    issueFires(lane) := io.issue(lane).valid && io.issue(lane).ready
    issuedWritebackQueues(lane).io.enq.valid := issueFires(lane)
    issuedWritebackQueues(lane).io.enq.bits := currentIssueAccess(lane)
  }

  var issueValidExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleValid(_))
  var issueCountExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleOutstanding(_))
  var issueIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleIds(_))
  var issueWakeExpr: Seq[Bool] = (0 until bundleTableEntries).map(bundleWakeRelevant(_))
  var issueSmIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleSmIds(_))
  var issueSchedulerIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleSchedulerIds(_))
  var issueWarpIdExpr: Seq[UInt] = (0 until bundleTableEntries).map(bundleWarpIds(_))

  for (lane <- 0 until params.numGenerators) {
    val active = issueFires(lane)
    val access = currentIssueAccess(lane)
    val matchVec = (0 until bundleTableEntries).map { e =>
      issueValidExpr(e) && issueIdExpr(e) === access.mBundleId
    }
    val matchAny = VecInit(matchVec).asUInt.orR
    val freeVec = (0 until bundleTableEntries).map(e => !issueValidExpr(e))
    val freeOH = VecInit(freeVec).asUInt
    val allocOH = UIntToOH(PriorityEncoder(freeOH), bundleTableEntries).asUInt &
      Fill(bundleTableEntries, active && !matchAny)

    issueValidExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), true.B, issueValidExpr(e))
    }
    issueCountExpr = (0 until bundleTableEntries).map { e =>
      val priorCount = Mux(matchVec(e), issueCountExpr(e), 0.U)
      Mux(active && (matchVec(e) || allocOH(e)), priorCount + 1.U, issueCountExpr(e))
    }
    issueIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), access.mBundleId, issueIdExpr(e))
    }
    issueWakeExpr = (0 until bundleTableEntries).map { e =>
      val priorWake = Mux(matchVec(e), issueWakeExpr(e), false.B)
      Mux(active && (matchVec(e) || allocOH(e)), priorWake || access.mWakeRelevantBundle, issueWakeExpr(e))
    }
    issueSmIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), access.smId, issueSmIdExpr(e))
    }
    issueSchedulerIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), access.schedulerId, issueSchedulerIdExpr(e))
    }
    issueWarpIdExpr = (0 until bundleTableEntries).map { e =>
      Mux(active && (matchVec(e) || allocOH(e)), access.warpId, issueWarpIdExpr(e))
    }
  }

  val issueBundleValidNext = Wire(Vec(bundleTableEntries, Bool()))
  val issueBundleCountNext = Wire(Vec(bundleTableEntries, UInt(bundleCountWidth.W)))
  val issueBundleIdNext = Wire(Vec(bundleTableEntries, UInt(64.W)))
  val issueBundleWakeNext = Wire(Vec(bundleTableEntries, Bool()))
  val issueBundleSmIdNext = Wire(Vec(bundleTableEntries, UInt(32.W)))
  val issueBundleSchedulerIdNext = Wire(Vec(bundleTableEntries, UInt(8.W)))
  val issueBundleWarpIdNext = Wire(Vec(bundleTableEntries, UInt(32.W)))
  for (e <- 0 until bundleTableEntries) {
    issueBundleValidNext(e) := issueValidExpr(e)
    issueBundleCountNext(e) := issueCountExpr(e)
    issueBundleIdNext(e) := issueIdExpr(e)
    issueBundleWakeNext(e) := issueWakeExpr(e)
    issueBundleSmIdNext(e) := issueSmIdExpr(e)
    issueBundleSchedulerIdNext(e) := issueSchedulerIdExpr(e)
    issueBundleWarpIdNext(e) := issueWarpIdExpr(e)
  }

  val nextBatchIssueCursor = batchIssueCursor + params.numGenerators.U
  val batchFinishedAfterCurrentChunk = nextBatchIssueCursor >= TrafficGenAccessBatch.lanes.U
  val maxUInt64 = Fill(64, true.B)
  val shouldFetchModelCycle =
    io.accessStoreHasEntries &&
    modelCycle <= roundLoadEndCycle &&
    !wakeExitPending
  val outputQueuesEmpty =
    !issuedWritebackArb.io.out.valid &&
    !completedBundleArb.io.out.valid
  val anyWakeQueryEnqueued = completeEventNeedsWakeQuery.asUInt.orR
  val canAdvanceModelCycleForMemWait =
    io.memActive && !completionAnyValid && !outputQueuesHaveWork && !wakeQueryQueuesHaveWork

  def latchAccessReadResponse(): Unit = {
    for (i <- 0 until TrafficGenAccessBatch.lanes) {
      batchAccesses(i) := io.accessReadData(i)
      batchValid(i) := io.accessReadDataValid(i)
    }
    batchBucketDone := io.accessReadBucketDone
    batchIssueCursor := 0.U
    state := sIssueBatch
  }

  def blockedWarpQueryIndex(query: TrafficGenWakeQuery): UInt =
    BlockedWarpBitmap.indexFromFields(query.smId, query.schedulerId, query.warpId)

  when(state =/= sIdle) {
    targetCycle := targetCycle + 1.U
  }

  when(state === sIdle) {
    when(io.startRound && io.uploadReady) {
      val loadToMaxResidentCycle = io.minIssueCycle === maxUInt64
      val loadEndCycle = Mux(loadToMaxResidentCycle,
        io.accessStoreMaxCycle,
        Mux(io.accessStoreMaxCycle < io.minIssueCycle, io.accessStoreMaxCycle, io.minIssueCycle))

      roundLoadEndCycle := loadEndCycle
      roundHasFutureIssueWork := io.accessStoreHasEntries && io.accessStoreMaxCycle > loadEndCycle
      roundCapacityBounded := !loadToMaxResidentCycle &&
        io.accessStoreHasEntries &&
        io.accessStoreMaxCycle < io.minIssueCycle
      roundExitReasonReg := TrafficGenRoundExitReason.scheduling
      wakeExitPending := false.B
      backgroundDrainActive := false.B
      roundOutputAwaitingHost := false.B
      completedBundleCount := 0.U
      batchValid.foreach(_ := false.B)
      batchBucketDone := false.B
      batchIssueCursor := 0.U
      lastHasPendingWork := false.B
      io.roundStarted := true.B
      state := sDrainCompletions
    }.elsewhen(!roundOutputAwaitingHost && (completionAnyValid || cleanupWorkPending)) {
      backgroundDrainActive := true.B
      state := sDrainCompletions
    }
  }.elsewhen(state === sDrainCompletions) {
    when(completionAnyValid) {
      when(completionCanFire) {
        for (e <- 0 until bundleTableEntries) {
          bundleValid(e) := completionBundleValidNext(e)
          bundleOutstanding(e) := completionBundleCountNext(e)
        }
        when(anyWakeQueryEnqueued) {
          resumeAfterBlockedQuery := sDrainCompletions
          state := sRequestBlockedWarp
        }
      }
    }.otherwise {
      when(wakeQueryQueuesHaveWork) {
        resumeAfterBlockedQuery := sDrainCompletions
        state := sRequestBlockedWarp
      }.elsewhen(wakeExitPending) {
        roundExitReasonReg := TrafficGenRoundExitReason.scheduling
        state := sDrainOutputs
      }.elsewhen(backgroundDrainActive) {
        when(cleanupWorkPending) {
          when(canAdvanceModelCycleForMemWait) {
            modelCycle := modelCycle + 1.U
          }
          state := sDrainCompletions
        }.otherwise {
          state := sDrainOutputs
        }
      }.elsewhen(shouldFetchModelCycle) {
        state := sRequestAccess
      }.elsewhen(cleanupWorkPending) {
        when(canAdvanceModelCycleForMemWait) {
          modelCycle := modelCycle + 1.U
        }
        state := sDrainCompletions
      }.otherwise {
        roundExitReasonReg := Mux(roundCapacityBounded && !roundHasFutureIssueWork,
          TrafficGenRoundExitReason.capacity,
          TrafficGenRoundExitReason.scheduling)
        lastHasPendingWork := roundHasFutureIssueWork || bundleTableHasOutstanding || io.memActive
        state := sDrainOutputs
      }
    }
  }.elsewhen(state === sRequestAccess) {
    io.accessReadCycle := modelCycle
    when(io.accessReadReady) {
      io.accessReadEn := true.B
      state := sWaitAccessAccepted
    }
  }.elsewhen(state === sWaitAccessAccepted) {
    io.accessReadCycle := modelCycle
    io.accessReadEn := true.B
    when(!io.accessReadReady) {
      state := sWaitAccess
    }
  }.elsewhen(state === sWaitAccess) {
    io.accessReadBatchReady := true.B
    when(io.accessReadRespValid) {
      latchAccessReadResponse()
    }.elsewhen(io.accessReadReady) {
      state := sRequestAccess
    }
  }.elsewhen(state === sIssueBatch) {
    when(completionCanFire) {
        for (e <- 0 until bundleTableEntries) {
          bundleValid(e) := completionBundleValidNext(e)
          bundleOutstanding(e) := completionBundleCountNext(e)
        }
      when(anyWakeQueryEnqueued) {
        resumeAfterBlockedQuery := sIssueBatch
        state := sRequestBlockedWarp
      }
    }.elsewhen(wakeQueryQueuesHaveWork) {
      resumeAfterBlockedQuery := sIssueBatch
      state := sRequestBlockedWarp
    }.elsewhen(!chunkHasValid) {
      when(batchFinishedAfterCurrentChunk) {
        batchValid.foreach(_ := false.B)
        when(batchBucketDone) {
          modelCycle := modelCycle + 1.U
          state := sDrainCompletions
        }.otherwise {
          state := sWaitAccess
        }
      }.otherwise {
        batchIssueCursor := nextBatchIssueCursor
      }
    }.elsewhen(issueCanFire) {
      for (e <- 0 until bundleTableEntries) {
        bundleValid(e) := issueBundleValidNext(e)
        bundleOutstanding(e) := issueBundleCountNext(e)
        bundleIds(e) := issueBundleIdNext(e)
        bundleWakeRelevant(e) := issueBundleWakeNext(e)
        bundleSmIds(e) := issueBundleSmIdNext(e)
        bundleSchedulerIds(e) := issueBundleSchedulerIdNext(e)
        bundleWarpIds(e) := issueBundleWarpIdNext(e)
      }
      when(batchFinishedAfterCurrentChunk) {
        batchValid.foreach(_ := false.B)
        when(batchBucketDone) {
          modelCycle := modelCycle + 1.U
          state := sDrainCompletions
        }.otherwise {
          state := sWaitAccess
        }
      }.otherwise {
        batchIssueCursor := nextBatchIssueCursor
      }
    }.otherwise {
      when(chunkHasValid && canAdvanceModelCycleForMemWait) {
        modelCycle := modelCycle + 1.U
      }
    }
  }.elsewhen(state === sRequestBlockedWarp) {
    when(wakeQueryArb.io.out.valid) {
      io.blockedWarpQueryIdx := blockedWarpQueryIndex(wakeQueryArb.io.out.bits)
      when(io.blockedWarpQueryReady) {
        io.blockedWarpQueryEn := true.B
        activeWakeQuery := wakeQueryArb.io.out.bits
        wakeQueryArb.io.out.ready := true.B
        state := sWaitBlockedWarp
      }
    }.otherwise {
      state := resumeAfterBlockedQuery
    }
  }.elsewhen(state === sWaitBlockedWarp) {
    io.blockedWarpQueryIdx := blockedWarpQueryIndex(activeWakeQuery)
    when(io.blockedWarpQueryRespValid) {
      blockedWarpQueryRespReg := io.blockedWarpQueryResp
      state := sAckBlockedWarp
    }
  }.elsewhen(state === sAckBlockedWarp) {
    io.blockedWarpQueryRespStored := true.B
    when(blockedWarpQueryRespReg && !backgroundDrainActive) {
      wakeExitPending := true.B
    }
    state := Mux(wakeQueryQueuesHaveWork, sRequestBlockedWarp, resumeAfterBlockedQuery)
  }.elsewhen(state === sDrainOutputs) {
    when(outputQueuesEmpty) {
      io.completedBundleCountWriteEn := true.B
      io.completedBundleCountWriteData := completedBundleCount
      io.roundComplete := !backgroundDrainActive
      when(!backgroundDrainActive) {
        roundOutputAwaitingHost := true.B
      }
      backgroundDrainActive := false.B
      state := sIdle
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
    val io = IO(new TrafficGenTopIO(params.width, params.numGenerators, params.memOutstanding))

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
      engine.io.accessStoreCount := io.accessStoreCount
      engine.io.accessStoreMaxCycle := io.accessStoreMaxCycle
      engine.io.accessStoreHasEntries := io.accessStoreHasEntries
      engine.io.blockedWarpQueryResp := io.blockedWarpQueryResp
      engine.io.blockedWarpQueryRespValid := io.blockedWarpQueryRespValid
      engine.io.blockedWarpQueryReady := io.blockedWarpQueryReady
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
      io.issuedAccessWriteback.valid := engine.io.issuedAccessWriteback.valid
      io.issuedAccessWriteback.bits := engine.io.issuedAccessWriteback.bits
      engine.io.issuedAccessWriteback.ready := io.issuedAccessWriteback.ready
      io.completedBundleIdWriteEn := engine.io.completedBundleIdWriteEn
      io.completedBundleIdWriteIdx := engine.io.completedBundleIdWriteIdx
      io.completedBundleIdWriteData := engine.io.completedBundleIdWriteData
      io.completedBundleCountWriteEn := engine.io.completedBundleCountWriteEn
      io.completedBundleCountWriteData := engine.io.completedBundleCountWriteData
      io.accessReadCycle := engine.io.accessReadCycle
      io.accessReadEn := engine.io.accessReadEn
      io.accessReadBatchReady := engine.io.accessReadBatchReady
      io.blockedWarpQueryIdx := engine.io.blockedWarpQueryIdx
      io.blockedWarpQueryEn := engine.io.blockedWarpQueryEn
      io.blockedWarpQueryRespStored := engine.io.blockedWarpQueryRespStored
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

class TrafficGenMem(id: Int, beatBytes: Int, params: TrafficGenParams)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
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

  class TrafficGenMemModuleImp(outer: TrafficGenMem) extends Impl {
    val io = IO(new Bundle {
      val active = Output(Bool())
      val inflightAccessCount = Output(UInt(log2Ceil(params.memOutstanding + 1).W))
      val coreOffset = Input(UInt(32.W))
      val req = Flipped(Decoupled(new L2Access))
      val completion = Decoupled(new L2Access)
      val currentCycle = Input(UInt(64.W))
    })

    withClockAndReset(clock, reset) {
      val (mem, edge) = outer.node.out(0)
      dontTouch(io.coreOffset)

      val issueQueue = Module(new Queue(new L2Access, params.memOutstanding))
      val completionQueue = Module(new Queue(new L2Access, params.memOutstanding))
      val inflightValid = RegInit(VecInit(Seq.fill(params.memOutstanding)(false.B)))
      val inflightAccesses = Reg(Vec(params.memOutstanding, new L2Access))
      val senderValid = RegInit(false.B)
      val senderAccess = Reg(new L2Access)
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
      val completionElapsed = Cat(0.U(32.W), completionQueue.io.deq.bits.mSetIndex)
      val completionReadyCycle = completionQueue.io.deq.bits.cycleCount + completionElapsed
      val modeledCompletionReady =
        completionQueue.io.deq.valid && completionReadyCycle <= io.currentCycle
      io.completion.valid := modeledCompletionReady
      io.completion.bits := completionQueue.io.deq.bits
      completionQueue.io.deq.ready := modeledCompletionReady && io.completion.ready
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
      val trafficGenMem = LazyModule(new TrafficGenMem(i, sbus.beatBytes, params)(p))
      trafficGenMem.clockNode := sbus.fixedClockNode
      sbus.coupleFrom(s"trafficgen-mem-$i") { _ := trafficGenMem.node }
      trafficGenMem
    }

    InModuleBody {
      val outerIO = IO(new ClockedIO(new TrafficGenPortPeripheralIO)).suggestName("trafficgen")
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
      outerIO.bits.issuedAccessWriteback <> trafficGenTL.module.io.issuedAccessWriteback
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
      outerIO.bits.blockedWarpQueryIdx <> trafficGenTL.module.io.blockedWarpQueryIdx
      outerIO.bits.blockedWarpQueryEn <> trafficGenTL.module.io.blockedWarpQueryEn
      outerIO.bits.blockedWarpQueryRespStored <> trafficGenTL.module.io.blockedWarpQueryRespStored
      outerIO.bits.accessReadRespValid <> trafficGenTL.module.io.accessReadRespValid
      outerIO.bits.accessReadRespId <> trafficGenTL.module.io.accessReadRespId
      outerIO.bits.accessReadData <> trafficGenTL.module.io.accessReadData
      outerIO.bits.accessReadDataValid <> trafficGenTL.module.io.accessReadDataValid
      outerIO.bits.accessReadBucketDone <> trafficGenTL.module.io.accessReadBucketDone
      outerIO.bits.accessReadReady <> trafficGenTL.module.io.accessReadReady
      outerIO.bits.blockedWarpQueryResp <> trafficGenTL.module.io.blockedWarpQueryResp
      outerIO.bits.blockedWarpQueryRespValid <> trafficGenTL.module.io.blockedWarpQueryRespValid
      outerIO.bits.blockedWarpQueryReady <> trafficGenTL.module.io.blockedWarpQueryReady
      outerIO.bits.accessStoreCount <> trafficGenTL.module.io.accessStoreCount
      outerIO.bits.accessStoreMaxCycle <> trafficGenTL.module.io.accessStoreMaxCycle
      outerIO.bits.accessStoreHasEntries <> trafficGenTL.module.io.accessStoreHasEntries
      outerIO.bits.uploadReady <> trafficGenTL.module.io.uploadReady

      trafficGenTL.module.io.memActive := generators.map(_.module.io.active).foldLeft(false.B)(_ || _)
      trafficGenTL.module.io.memInflightAccesses := VecInit(generators.map(_.module.io.inflightAccessCount))
      generators.zipWithIndex.foreach { case (generator, i) =>
        generator.module.io.coreOffset := (BigInt(i) * params.regionStride).U
        generator.module.io.currentCycle := trafficGenTL.module.io.currentCycleAfterIssue
        generator.module.io.req <> trafficGenTL.module.io.issue(i)
        trafficGenTL.module.io.completion(i) <> generator.module.io.completion
      }

      outerIO
    }
  }
}

class WithTrafficGen extends Config((site, here, up) => {
  case TrafficGenKey => Some(TrafficGenParams())
})

class WithRTLTrafficGen extends Config((site, here, up) => {
  case TrafficGenKey => Some(TrafficGenParams(
    numGenerators = 4,
    memOutstanding = 4,
    backend = TrafficGenRTLBackend,
    remapTraceAddresses = true,
    remapBase = 0x100000000L,
    remapSize = 1L << 32))
})
