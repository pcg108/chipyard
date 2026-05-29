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
import firechip.bridgeinterfaces.{BlockedWarpBitmap, CompletedBundleIds, L2Access, ReservationClearRequest}

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
  // TG still has a scheduling round active or memory requests in flight
  val hasPendingWork = Output(Bool())
  // Debug: current TrafficGen DPI state machine state.
  val dpiState = Output(UInt(32.W))

  //// Host -> Target control/status

  // L2 accesses have been written to bridge module access store
  val uploadDone = Input(Bool())
  // blocked warp bitmap has been written to bridge module
  val blockedWarpBitmapReady = Input(Bool())
  // furthest the TG is allowed to issue
  val minIssueCycle = Input(UInt(64.W))
  // begin next round of issuing
  val startRound = Input(Bool())
  // GPU model main loop has completed; this is global completion, not per-round idle
  val trafficGenDone = Input(Bool())

  //// Target -> Host execution data

  // TG issue logic will enqueue clear requests here to remove the issued subpartition from reservedSubPartitionsByCycle without stalling on bridge RMW latency.
  val reservationClear = Decoupled(new ReservationClearRequest)
  // TG issue logic will enqueue the actual issued L2 accesses here with cycleCount updated to the real issue cycle.
  val issuedAccessWriteback = Decoupled(new L2Access)

  // query the L2 access store for an L2 access
  val accessReadCycle = Output(UInt(64.W))
  val accessReadEn = Output(Bool())
  val accessReadDataReady = Output(Bool())
  val accessReadBucketDoneReady = Output(Bool())

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
  val accessReadData = Input(new L2Access)
  val accessReadDataValid = Input(Bool())
  val accessReadBucketDone = Input(Bool())
  val accessReadReady = Input(Bool())
  val accessStoreCount = Input(UInt(32.W))
  val accessStoreMaxCycle = Input(UInt(64.W))
  val accessStoreHasEntries = Input(Bool())

  // response to blocked warp bitmap query
  val blockedWarpQueryResp = Input(Bool())
  val blockedWarpQueryRespValid = Input(Bool())
  val blockedWarpQueryReady = Input(Bool())
  val reservationWindowAdvanceCycle = Output(UInt(64.W))
  val reservationWindowAdvanceEn = Output(Bool())
  

  val memActive = Input(Bool())
  val memInflightAccesses = Input(Vec(nGenerators, UInt(log2Ceil(memOutstanding + 1).W)))
  val issue = Vec(nGenerators, Decoupled(new L2Access))
  val completion = Vec(nGenerators, Flipped(Decoupled(new L2Access)))
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
    val upload_done = Input(Bool())
    val blocked_warp_bitmap_ready = Input(Bool())
    val access_store_count = Input(UInt(32.W))
    val access_store_max_cycle = Input(UInt(64.W))
    val access_store_has_entries = Input(Bool())
    val min_issue_cycle = Input(UInt(64.W))

    val access_read_data_valid = Input(Bool())
    val access_read_bucket_done = Input(Bool())
    val access_read_ready = Input(Bool())
    val access_read_id = Input(UInt(64.W))
    val access_read_address = Input(UInt(64.W))
    val access_read_cycle_count = Input(UInt(64.W))
    val access_read_subpartition = Input(UInt(32.W))
    val access_read_set_index = Input(UInt(32.W))
    val access_read_tag = Input(UInt(64.W))
    val access_read_mask = Input(UInt(32.W))
    val access_read_sm_id = Input(UInt(32.W))
    val access_read_scheduler_id = Input(UInt(8.W))
    val access_read_warp_id = Input(UInt(32.W))
    val access_read_bundle_id = Input(UInt(64.W))
    val access_read_wake_relevant_bundle = Input(Bool())
    val access_read_is_write = Input(Bool())

    val blocked_warp_query_resp_valid = Input(Bool())
    val blocked_warp_query_resp = Input(Bool())
    val blocked_warp_query_ready = Input(Bool())

    val issued_access_writeback_ready = Input(Bool())
    val reservation_clear_ready = Input(Bool())

    val target_busy = Output(Bool())
    val has_pending_work = Output(Bool())
    val round_started = Output(Bool())
    val round_complete = Output(Bool())
    val current_cycle_after_issue = Output(UInt(64.W))
    val dpi_state = Output(UInt(32.W))

    val access_read_en = Output(Bool())
    val access_read_cycle = Output(UInt(64.W))
    val access_read_data_ready = Output(Bool())
    val access_read_bucket_done_ready = Output(Bool())
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

    val reservation_clear_valid = Output(Bool())
    val reservation_clear_cycle = Output(UInt(64.W))
    val reservation_clear_subpartition = Output(UInt(32.W))

    val completed_bundle_count_write_en = Output(Bool())
    val completed_bundle_count_write_data = Output(UInt(CompletedBundleIds.countWidth.W))
    val completed_bundle_id_write_en = Output(Bool())
    val completed_bundle_id_write_idx = Output(UInt(CompletedBundleIds.idxWidth.W))
    val completed_bundle_id_write_data = Output(UInt(64.W))

    val reservation_window_advance_en = Output(Bool())
    val reservation_window_advance_cycle = Output(UInt(64.W))
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
  dpi.io.upload_done := io.uploadDone
  dpi.io.blocked_warp_bitmap_ready := io.blockedWarpBitmapReady
  dpi.io.access_store_count := io.accessStoreCount
  dpi.io.access_store_max_cycle := io.accessStoreMaxCycle
  dpi.io.access_store_has_entries := io.accessStoreHasEntries
  dpi.io.min_issue_cycle := io.minIssueCycle

  dpi.io.access_read_data_valid := io.accessReadDataValid
  dpi.io.access_read_bucket_done := io.accessReadBucketDone
  dpi.io.access_read_ready := io.accessReadReady
  dpi.io.access_read_id := io.accessReadData.id
  dpi.io.access_read_address := io.accessReadData.address
  dpi.io.access_read_cycle_count := io.accessReadData.cycleCount
  dpi.io.access_read_subpartition := io.accessReadData.mSubpartition
  dpi.io.access_read_set_index := io.accessReadData.mSetIndex
  dpi.io.access_read_tag := io.accessReadData.mTag
  dpi.io.access_read_mask := io.accessReadData.mMask
  dpi.io.access_read_sm_id := io.accessReadData.smId
  dpi.io.access_read_scheduler_id := io.accessReadData.schedulerId
  dpi.io.access_read_warp_id := io.accessReadData.warpId
  dpi.io.access_read_bundle_id := io.accessReadData.mBundleId
  dpi.io.access_read_wake_relevant_bundle := io.accessReadData.mWakeRelevantBundle
  dpi.io.access_read_is_write := io.accessReadData.mIsWrite

  dpi.io.blocked_warp_query_resp_valid := io.blockedWarpQueryRespValid
  dpi.io.blocked_warp_query_resp := io.blockedWarpQueryResp
  dpi.io.blocked_warp_query_ready := io.blockedWarpQueryReady

  val reservationClearLanes = Wire(Vec(params.numGenerators, Decoupled(new ReservationClearRequest)))
  val issuedAccessWritebackLanes = Wire(Vec(params.numGenerators, Decoupled(new L2Access)))

  val reservationClearQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(new ReservationClearRequest, 4))
  }
  val issuedAccessWritebackQueues = Seq.fill(params.numGenerators) {
    Module(new Queue(new L2Access, 4))
  }
  val reservationClearArb = Module(new RRArbiter(new ReservationClearRequest, params.numGenerators))
  val issuedAccessWritebackArb = Module(new RRArbiter(new L2Access, params.numGenerators))

  for (i <- 0 until params.numGenerators) {
    reservationClearQueues(i).io.enq.valid := reservationClearLanes(i).valid
    reservationClearQueues(i).io.enq.bits := reservationClearLanes(i).bits
    reservationClearLanes(i).ready := reservationClearQueues(i).io.enq.ready
    reservationClearArb.io.in(i) <> reservationClearQueues(i).io.deq

    issuedAccessWritebackQueues(i).io.enq.valid := issuedAccessWritebackLanes(i).valid
    issuedAccessWritebackQueues(i).io.enq.bits := issuedAccessWritebackLanes(i).bits
    issuedAccessWritebackLanes(i).ready := issuedAccessWritebackQueues(i).io.enq.ready
    issuedAccessWritebackArb.io.in(i) <> issuedAccessWritebackQueues(i).io.deq
  }

  io.reservationClear.valid := reservationClearArb.io.out.valid
  io.reservationClear.bits := reservationClearArb.io.out.bits
  reservationClearArb.io.out.ready := io.reservationClear.ready
  io.issuedAccessWriteback.valid := issuedAccessWritebackArb.io.out.valid
  io.issuedAccessWriteback.bits := issuedAccessWritebackArb.io.out.bits
  issuedAccessWritebackArb.io.out.ready := io.issuedAccessWriteback.ready

  dpi.io.issued_access_writeback_ready := issuedAccessWritebackLanes(0).ready
  dpi.io.reservation_clear_ready := reservationClearLanes(0).ready

  io.currentCycleAfterIssue := currentCycleAfterIssue
  io.roundStarted := roundStarted
  io.roundComplete := roundComplete

  io.accessReadCycle := dpi.io.access_read_cycle
  io.accessReadEn := dpi.io.access_read_en
  io.accessReadDataReady := dpi.io.access_read_data_ready
  io.accessReadBucketDoneReady := dpi.io.access_read_bucket_done_ready
  io.blockedWarpQueryIdx := dpi.io.blocked_warp_query_idx
  io.blockedWarpQueryEn := dpi.io.blocked_warp_query_en
  io.blockedWarpQueryRespStored := dpi.io.blocked_warp_query_resp_stored
  io.reservationWindowAdvanceCycle := dpi.io.reservation_window_advance_cycle
  io.reservationWindowAdvanceEn := dpi.io.reservation_window_advance_en

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

  for (i <- 0 until params.numGenerators) {
    io.issue(i).valid := false.B
    io.issue(i).bits := 0.U.asTypeOf(new L2Access)
    io.completion(i).ready := true.B
    reservationClearLanes(i).valid := (if (i == 0) dpi.io.reservation_clear_valid else false.B)
    reservationClearLanes(i).bits.cycle := dpi.io.reservation_clear_cycle
    reservationClearLanes(i).bits.subpartition := dpi.io.reservation_clear_subpartition
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
  }
}

class TrafficGenRTLEngine(params: TrafficGenParams) extends Module with HasTrafficGenTopIO {
  require(params.numGenerators >= 1, "TrafficGenRTLEngine requires at least one generator")

  /*
    RTL implementation of the traffic generator engine. We have the same IO as DPI, but unlike DPI, 
    we issue accesses directly to TL-C issue nodes rather than a functional SW model with offline L2 access timings.

    The behavior is the same as the software model:
    1. Read accesses for current GPU model cycle from the bridge module store
    2. Issue all accesses for the current model cycle
    3. Check if any accesses completed- if a wake-relevant bundle completed that unblocked a warp, complete the round
  */

  val io = IO(new TrafficGenTopIO(params.width, params.numGenerators, params.memOutstanding))
  dontTouch(io.memInflightAccesses)

  // overall state machine for traffic generator round
  val sIdle :: sRead :: sIssue :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // peer state machine that retires returned memory completions while the main issue FSM keeps running
  val cDequeue :: cRequestBlockedWarp :: cWaitBlockedWarp :: Nil = Enum(3)
  val completionState = RegInit(cDequeue)
  
  // track the current cycle in the GPU model. There is a disconnect between target cycle and real cycle
  // because of the overhead of the traffic generator bridge/RTL logic. Additionally, it is easier 
  // to track a model cycle rather than adjust for target cycle offsets in the bridge driver 
  val modelCycle = RegInit(0.U(64.W))
  val targetCycle = RegInit(0.U(64.W))
  targetCycle := targetCycle + 1.U 

  // we track if the cycle bucket completed, because all accesses for the cycle have to issue before
  // we check for round completion conditions 
  val bucketDoneSeen = RegInit(false.B)
  // have we sent a read request for next access or bucket done
  val readReqPending = RegInit(false.B)
  // track held access-read responses so the RTL engine consumes each access ID once
  val accessReadDataConsumed = RegInit(false.B)
  val lastConsumedAccessReadId = RegInit(0.U(64.W))
  val accessReadRequestActive = RegInit(false.B)
  val accessReadBucketDoneConsumed = RegInit(false.B)
  val pendingAccessReadBucketDone = RegInit(false.B)
  // the L2 access received from bridge module to be sent to issue node
  val pendingIssue = Reg(new L2Access)
  val pendingIssueValid = RegInit(false.B)
  // upon receiving a completion, should the round be stopped (after draining current bucket)
  val roundStopPending = RegInit(false.B)
  // track accesses issued but not completed
  val inflightAccessCount = RegInit(0.U(32.W))
  // completed bundle count to write back to bridge module 
  val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))

  /*
    In-flight bundle table (~mOutstandingBundles in DPI) to track the number of issued but not completed accesses per bundle, to know when a bundle completes. 
    Currently provisioning for 128 in-flight bundles, which is likely too much
  */
  val bundleTableDepth = 128
  val bundleIdxWidth = log2Ceil(bundleTableDepth)
  // Content-addressable memory table structures
  // whether each table entry is occupied (with bundle count)
  val bundleTableValid = RegInit(VecInit(Seq.fill(bundleTableDepth)(false.B)))
  // bundle ID for each entry, to match completions to entries
  val bundleTableId = Reg(Vec(bundleTableDepth, UInt(64.W)))
  // number of outstanding accesses for each bundle, to know when the bundle completes
  val bundleTableCount = RegInit(VecInit(Seq.fill(bundleTableDepth)(0.U(32.W))))
  // whether this bundle had a wake-relevant access
  val bundleTableWakeRelevant = RegInit(VecInit(Seq.fill(bundleTableDepth)(false.B)))

  // remember which warp the bundle belonged to, to query blocked warp bitmap on completion if needed
  val bundleTableSmId = Reg(Vec(bundleTableDepth, UInt(32.W)))
  val bundleTableSchedulerId = Reg(Vec(bundleTableDepth, UInt(8.W)))
  val bundleTableWarpId = Reg(Vec(bundleTableDepth, UInt(32.W)))
  // temporary registers to hold warp metadata during blocked query 
  val completionQuerySmId = Reg(UInt(32.W))
  val completionQuerySchedulerId = Reg(UInt(8.W))
  val completionQueryWarpId = Reg(UInt(32.W))

  // queues to store issued accesses and completion clears to send to bridge module
  val issuedAccessWritebackQueue = Module(new Queue(new L2Access, 4))
  val reservationClearQueue = Module(new Queue(new ReservationClearRequest, 4))

  // queue to immediately capture access read responses from the bridge module.
  // Store packed accesses so the queue backs onto one wide memory instead of one
  // memory per L2Access field. Bucket-done is tracked separately.
  val accessReadResponseQueue = Module(new Queue(UInt(L2Access.streamWidthBits.W), params.accessReadResponseDepth))
  val accessReadResponseDeqAccess = L2Access.unpack(accessReadResponseQueue.io.deq.bits)

  // queue and arbiter to serialize completions from multiple issue nodes
  val completionArb = Module(new RRArbiter(new L2Access, params.numGenerators))

  // completion queue definition
  val completionQueueDepth = math.max(2, params.numGenerators * params.memOutstanding)
  val completionQueue = Module(new Queue(new L2Access, completionQueueDepth))

  // arbitrate and enqueue completions from issue nodes
  for (i <- 0 until params.numGenerators) {
    completionArb.io.in(i) <> io.completion(i)
  }
  completionQueue.io.enq <> completionArb.io.out

  val issuedAccess = Wire(new L2Access)
  issuedAccess := pendingIssue
  // set the issued access cycle as targetCycle because this is used just for plotting and correlation
  issuedAccess.cycleCount := targetCycle // modelCycle

  val reservationClear = Wire(new ReservationClearRequest)
  reservationClear.cycle := pendingIssue.cycleCount
  reservationClear.subpartition := pendingIssue.mSubpartition

  // determine if we can issue the pending access to any of the issue nodes, and if so which one
  // conditions: ready issue node, pending issue is valid, issuedAccess/reservationClear queues have space 
  val issueReadyVec = VecInit((0 until params.numGenerators).map(i => io.issue(i).ready))
  val issueLaneReady = issueReadyVec.asUInt.orR
  val issueLaneOH = PriorityEncoderOH(issueReadyVec.asUInt)

  // for each table entry, check if entry is valid and matches pending issue bundle ID
  val issueBundleMatchVec = VecInit((0 until bundleTableDepth).map { i =>
    bundleTableValid(i) && bundleTableId(i) === pendingIssue.mBundleId
  })
  // each bit marks free/unused table entry 
  val issueBundleFreeVec = VecInit((0 until bundleTableDepth).map { i =>
    !bundleTableValid(i)
  })
  val issueBundleMatch = issueBundleMatchVec.asUInt.orR // reduce - at least one matching entry
  val issueBundleHasFree = issueBundleFreeVec.asUInt.orR // reduce - at least one free entry
  val issueBundleMatchIdx = PriorityEncoder(issueBundleMatchVec) // index of first matching entry
  val issueBundleFreeIdx = PriorityEncoder(issueBundleFreeVec) // index of first free entry
  val issueBundleIdx = Mux(issueBundleMatch, issueBundleMatchIdx, issueBundleFreeIdx)
  val issueBundleCanTrack = issueBundleMatch || issueBundleHasFree
  
  // for each table entry, check if entry is valid and matches completion bundle ID
  val completionBundleMatchVec = VecInit((0 until bundleTableDepth).map { i =>
    bundleTableValid(i) && bundleTableId(i) === completionQueue.io.deq.bits.mBundleId
  })
  val completionBundleMatch = completionBundleMatchVec.asUInt.orR // reduce - at least one matching entry
  val completionBundleIdx = PriorityEncoder(completionBundleMatchVec) // index of first matching entry
  val completionRetireActive = state =/= sIdle
  val completionWillRetire =
    completionRetireActive && completionState === cDequeue && completionQueue.io.deq.valid
  val completionBundleCompleted =
    completionBundleMatch && bundleTableCount(completionBundleIdx) === 1.U
  val issueCompletionBundleHazard =
    completionWillRetire && pendingIssueValid &&
      completionQueue.io.deq.bits.mBundleId === pendingIssue.mBundleId
  val issueSideEffectsReady =
    issueLaneReady &&
      issuedAccessWritebackQueue.io.enq.ready
  val issueCanFire = state === sIssue &&
    pendingIssueValid &&
    issueSideEffectsReady &&
    issueBundleCanTrack &&
    !issueCompletionBundleHazard
  val completionPathDrained =
    completionState === cDequeue &&
      !completionQueue.io.deq.valid &&
      !completionQueue.io.enq.valid

  // issued access queue and reservation clear queues need to be empty for round to complete
  val sideEffectQueuesEmpty =
    !issuedAccessWritebackQueue.io.deq.valid && !reservationClearQueue.io.deq.valid
  // fully completed cycle bucket 
  val bucketDrained = bucketDoneSeen && !pendingIssueValid && sideEffectQueuesEmpty &&
    !pendingAccessReadBucketDone &&
    !accessReadResponseQueue.io.deq.valid

  // a round ends with either we the access store is empty, 
  // or we reach min issue cycle (if finite) or the max cycle in the store (we issued everything)
  val accessStoreEndReached = !io.accessStoreHasEntries || modelCycle >= io.accessStoreMaxCycle
  val stopCondition = roundStopPending || modelCycle >= io.minIssueCycle || accessStoreEndReached
  val roundCompletePulse = WireDefault(false.B)

  // The bridge can hold a read response valid across multiple target steps; consume each ID once.
  val newAccessReadData =
    io.accessReadDataValid &&
    (!accessReadDataConsumed || io.accessReadData.id =/= lastConsumedAccessReadId)
  val newAccessReadCount = RegInit(0.U(32.W))
  val duplicateAccessReadAckCount = RegInit(0.U(32.W))
  val issuedAccessCount = RegInit(0.U(32.W))
  dontTouch(newAccessReadCount)
  dontTouch(duplicateAccessReadAckCount)
  dontTouch(issuedAccessCount)


  io.startTrafficGen := false.B // tie this off since we are reusing the IO interface
  io.targetBusy := (state =/= sIdle) && !roundCompletePulse
  // we still have pending work if we have either not issued all accesses, or all issued accesses haven't completed
  io.hasPendingWork := state =/= sIdle ||
    inflightAccessCount =/= 0.U ||
    io.memActive ||
    io.accessStoreHasEntries
  io.roundStarted := false.B
  io.roundComplete := roundCompletePulse
  io.dpiState := state.asUInt // exposes current state through DPI state signal 
  io.currentCycleAfterIssue := modelCycle

  // connect bridge module reservation clear to reservation clear queue
  io.reservationClear.valid := reservationClearQueue.io.deq.valid
  io.reservationClear.bits := reservationClearQueue.io.deq.bits
  reservationClearQueue.io.deq.ready := io.reservationClear.ready
  // connect bridge module issued access writeback to issued access queue
  io.issuedAccessWriteback.valid := issuedAccessWritebackQueue.io.deq.valid
  io.issuedAccessWriteback.bits := issuedAccessWritebackQueue.io.deq.bits
  issuedAccessWritebackQueue.io.deq.ready := io.issuedAccessWriteback.ready

  // default values for issued access and reservation clear queues 
  issuedAccessWritebackQueue.io.enq.valid := false.B
  issuedAccessWritebackQueue.io.enq.bits := issuedAccess
  reservationClearQueue.io.enq.valid := false.B
  reservationClearQueue.io.enq.bits := reservationClear

  // default values for access read response queue 
  accessReadResponseQueue.io.enq.valid := false.B
  accessReadResponseQueue.io.enq.bits := L2Access.pack(io.accessReadData)
  accessReadResponseQueue.io.deq.ready := false.B
  completionQueue.io.deq.ready := false.B

  // default values for access read interface to bridge module
  io.accessReadCycle := modelCycle
  io.accessReadEn := false.B
  io.accessReadDataReady := false.B
  io.accessReadBucketDoneReady := false.B

  // default blocked warp query interface values
  io.blockedWarpQueryIdx := 0.U
  io.blockedWarpQueryEn := false.B
  io.blockedWarpQueryRespStored := false.B

  // default completed bundle ID and count write interface values
  io.completedBundleIdWriteEn := false.B
  io.completedBundleIdWriteIdx := 0.U
  io.completedBundleIdWriteData := 0.U
  io.completedBundleCountWriteEn := false.B
  io.completedBundleCountWriteData := 0.U

  // default reservation window advance interface values
  io.reservationWindowAdvanceCycle := 0.U
  io.reservationWindowAdvanceEn := false.B

  // drive the issue node with the pending issue when issueCanFire
  for (i <- 0 until params.numGenerators) {
    io.issue(i).valid := issueCanFire && issueLaneOH(i)
    io.issue(i).bits := issuedAccess
  }

  val completionRetireFire = completionQueue.io.deq.fire
  val completionCountRetire = completionRetireFire && completionBundleMatch

  val bundleTableValidNext = Wire(Vec(bundleTableDepth, Bool()))
  val bundleTableIdNext = Wire(Vec(bundleTableDepth, UInt(64.W)))
  val bundleTableCountNext = Wire(Vec(bundleTableDepth, UInt(32.W)))
  val bundleTableWakeRelevantNext = Wire(Vec(bundleTableDepth, Bool()))
  val bundleTableSmIdNext = Wire(Vec(bundleTableDepth, UInt(32.W)))
  val bundleTableSchedulerIdNext = Wire(Vec(bundleTableDepth, UInt(8.W)))
  val bundleTableWarpIdNext = Wire(Vec(bundleTableDepth, UInt(32.W)))

  for (i <- 0 until bundleTableDepth) {
    bundleTableValidNext(i) := bundleTableValid(i)
    bundleTableIdNext(i) := bundleTableId(i)
    bundleTableCountNext(i) := bundleTableCount(i)
    bundleTableWakeRelevantNext(i) := bundleTableWakeRelevant(i)
    bundleTableSmIdNext(i) := bundleTableSmId(i)
    bundleTableSchedulerIdNext(i) := bundleTableSchedulerId(i)
    bundleTableWarpIdNext(i) := bundleTableWarpId(i)
  }

  when(completionRetireFire && completionBundleMatch) {
    when(completionBundleCompleted) {
      bundleTableValidNext(completionBundleIdx) := false.B
      bundleTableCountNext(completionBundleIdx) := 0.U
    }.otherwise {
      bundleTableCountNext(completionBundleIdx) := bundleTableCount(completionBundleIdx) - 1.U
    }
  }

  when(issueCanFire) {
    when(issueBundleMatch) {
      bundleTableCountNext(issueBundleIdx) := bundleTableCount(issueBundleIdx) + 1.U
    }.otherwise {
      bundleTableValidNext(issueBundleIdx) := true.B
      bundleTableIdNext(issueBundleIdx) := pendingIssue.mBundleId
      bundleTableCountNext(issueBundleIdx) := 1.U
      bundleTableWakeRelevantNext(issueBundleIdx) := pendingIssue.mWakeRelevantBundle
      bundleTableSmIdNext(issueBundleIdx) := pendingIssue.smId
      bundleTableSchedulerIdNext(issueBundleIdx) := pendingIssue.schedulerId
      bundleTableWarpIdNext(issueBundleIdx) := pendingIssue.warpId
    }
  }

  for (i <- 0 until bundleTableDepth) {
    bundleTableValid(i) := bundleTableValidNext(i)
    bundleTableId(i) := bundleTableIdNext(i)
    bundleTableCount(i) := bundleTableCountNext(i)
    bundleTableWakeRelevant(i) := bundleTableWakeRelevantNext(i)
    bundleTableSmId(i) := bundleTableSmIdNext(i)
    bundleTableSchedulerId(i) := bundleTableSchedulerIdNext(i)
    bundleTableWarpId(i) := bundleTableWarpIdNext(i)
  }

  val inflightAccessCountNext = WireDefault(inflightAccessCount)
  when(issueCanFire && !completionCountRetire) {
    inflightAccessCountNext := inflightAccessCount + 1.U
  }.elsewhen(!issueCanFire && completionCountRetire && inflightAccessCount =/= 0.U) {
    inflightAccessCountNext := inflightAccessCount - 1.U
  }
  inflightAccessCount := inflightAccessCountNext

  // clear "already consumed" flags when the read response is no longer valid, to allow new responses to be consumed
  when(!io.accessReadDataValid) {
    accessReadDataConsumed := false.B
  }
  when(!io.accessReadBucketDone) {
    accessReadBucketDoneConsumed := false.B
  }

  /*
    Bridge-response intake logic: safely accept access-read responses from bridge, enqeue each real access once
    We added a queue here because we were dropping L2 accesses from bridge module 
    Bridge can potentially produce valid access while RTL is issuing, servicing completions, etc. and not latching the next access
  */
  val enqueueNewAccessReadData = state =/= sIdle && io.accessReadDataValid && newAccessReadData
  val newAccessReadBucketDone = state =/= sIdle && io.accessReadBucketDone && !accessReadBucketDoneConsumed // duplicate filter
  val accessReadDataAccepted = state =/= sIdle && io.accessReadDataValid && io.accessReadDataReady 
  val accessReadBucketDoneAccepted = state =/= sIdle && !io.accessReadDataValid && io.accessReadBucketDone && io.accessReadBucketDoneReady // fresh bucket-done response
  // push new access read data or bucket done signals into access read response queue to be processed by state machine
  accessReadResponseQueue.io.enq.valid := enqueueNewAccessReadData
  accessReadResponseQueue.io.enq.bits := L2Access.pack(io.accessReadData)
  // we are ready for new data if queue has space for a new access, or it is not new data (need to acknowledge for module to move on)
  io.accessReadDataReady := state =/= sIdle &&
    (accessReadResponseQueue.io.enq.ready || !newAccessReadData)
  // we are ready for new bucket done if queue has space for a new bucket done signal, or it is not a new bucket done (need to acknowledge for module to move on)
  io.accessReadBucketDoneReady := state =/= sIdle &&
    !enqueueNewAccessReadData &&
    !io.accessReadDataValid &&
    (!newAccessReadBucketDone || !pendingAccessReadBucketDone || bucketDoneSeen)

  assert(accessReadResponseQueue.io.enq.ready || !enqueueNewAccessReadData,
    "TrafficGenRTLEngine access read response queue overflow")
  assert(!(state === sIssue && pendingIssueValid && issueSideEffectsReady &&
    !issueCompletionBundleHazard && !issueBundleCanTrack && completionPathDrained),
    "TrafficGenRTLEngine bundle table capacity exceeded")

  // for new data, remember that ID was consumed and store it for duplicate detectio, mark bridge read request as complete
  when(accessReadDataAccepted) {
    when(newAccessReadData) {
      accessReadDataConsumed := true.B
      lastConsumedAccessReadId := io.accessReadData.id
      accessReadRequestActive := false.B
    }.otherwise {
      duplicateAccessReadAckCount := duplicateAccessReadAckCount + 1.U
    }
  }

  when(accessReadResponseQueue.io.enq.fire && enqueueNewAccessReadData) {
    newAccessReadCount := newAccessReadCount + 1.U
  }

  // same as above, for bucket done handshake
  when(accessReadBucketDoneAccepted) {
    when(newAccessReadBucketDone) {
      when(!bucketDoneSeen && !pendingAccessReadBucketDone) {
        pendingAccessReadBucketDone := true.B
      }
      accessReadBucketDoneConsumed := true.B
      accessReadRequestActive := false.B
    }
  }

  switch(state) {

    // start condition for a round to begin: host signals startRound, all accesses are uploaded to bridge module,
    // blocked warp bitmap is uploaded, and access read interface is ready 
    is(sIdle) {
      when(io.startRound && io.uploadDone && io.blockedWarpBitmapReady && io.accessReadReady) {
        assert(!accessReadResponseQueue.io.deq.valid,
          "TrafficGenRTLEngine started a round with stale access read responses")
        bucketDoneSeen := false.B
        readReqPending := true.B
        accessReadRequestActive := false.B
        accessReadDataConsumed := false.B
        lastConsumedAccessReadId := 0.U
        accessReadBucketDoneConsumed := false.B
        pendingAccessReadBucketDone := false.B
        newAccessReadCount := 0.U
        duplicateAccessReadAckCount := 0.U
        issuedAccessCount := 0.U
        pendingIssueValid := false.B
        roundStopPending := false.B
        completedBundleCount := 0.U
        completionState := cDequeue
        io.roundStarted := true.B
        state := sRead
      }
    }

    // state to read accesses for current model cycle
    is(sRead) {

      when(accessReadResponseQueue.io.deq.valid) {
        accessReadResponseQueue.io.deq.ready := true.B
        // Latch the buffered access before servicing more read-request bookkeeping.
        // The bridge can deliver the final access just after bucket done is observed.
        pendingIssue := accessReadResponseDeqAccess
        pendingIssueValid := true.B
        state := sIssue
      }.elsewhen(pendingAccessReadBucketDone && !bucketDoneSeen && !enqueueNewAccessReadData) {
        // A bucket-done response completes the active read request. Retire it
        // before the read-pending path can keep reasserting accessReadEn.
        bucketDoneSeen := true.B
        pendingAccessReadBucketDone := false.B
        readReqPending := false.B
        accessReadRequestActive := false.B
        // advance the reservation window to retire the old bucket
        io.reservationWindowAdvanceCycle := modelCycle
        io.reservationWindowAdvanceEn := true.B
      }.elsewhen(readReqPending) { // if we have a pending read request, keep it asserted until the bridge returns a response
        when(accessReadRequestActive) {
          io.accessReadEn := true.B
        }.elsewhen(io.accessReadReady && !io.accessReadDataValid && !io.accessReadBucketDone) {
          io.accessReadEn := true.B
          accessReadRequestActive := true.B
          accessReadDataConsumed := false.B
          lastConsumedAccessReadId := 0.U
          accessReadBucketDoneConsumed := false.B
        }
        when(accessReadDataAccepted || accessReadBucketDoneAccepted) {
          readReqPending := false.B
        }
      }.elsewhen(!bucketDoneSeen) { // we are still processing a current cycle bucket
        readReqPending := true.B
      }.elsewhen(pendingAccessReadBucketDone) {
        // A duplicate bucket-done response may be acknowledged after the bucket was
        // already marked complete; do not let it block the drain condition.
        pendingAccessReadBucketDone := false.B
        readReqPending := false.B
        accessReadRequestActive := false.B
      }.elsewhen(bucketDrained) { // cycle bucket fully issued 
        // Preserve the original bucket-boundary behavior: returned completions are
        // retired before evaluating whether the next model cycle can begin.
        when(completionPathDrained) {
          when(stopCondition) { // if round completion, then write completed bundle count and signal round complete
            io.completedBundleCountWriteEn := true.B
            io.completedBundleCountWriteData := completedBundleCount
            roundCompletePulse := true.B
            state := sIdle
          }.otherwise { // advance to next cycle 
            modelCycle := modelCycle + 1.U
            bucketDoneSeen := false.B
            readReqPending := true.B
            accessReadDataConsumed := false.B
            lastConsumedAccessReadId := 0.U
          }
        }
      }
    }

    is(sIssue) {
      // send the pending issue to the appropriate issue node and enqueue the side effect requests to the bridge module, 
      // then go back to read for next access or bucket done

      issuedAccessWritebackQueue.io.enq.valid := issueCanFire

      when(issueCanFire) {

        // increment inflight access count and return to read state
        issuedAccessCount := issuedAccessCount + 1.U
        pendingIssueValid := false.B
        state := sRead
      }
    }
  }

  switch(completionState) {
    is(cDequeue) {
      completionQueue.io.deq.ready := completionRetireActive

      when(completionRetireFire) {
        assert(completionBundleMatch, "TrafficGenRTLEngine completed an unknown bundle")
        assert(inflightAccessCount =/= 0.U, "TrafficGenRTLEngine completed with no inflight accesses")

        when(completionBundleMatch) {
          when(completionBundleCompleted) {
            assert(completedBundleCount < CompletedBundleIds.capacity.U,
              "TrafficGenRTLEngine completed bundle queue capacity exceeded")
            when(completedBundleCount < CompletedBundleIds.capacity.U) {
              io.completedBundleIdWriteEn := true.B
              io.completedBundleIdWriteIdx := completedBundleCount(CompletedBundleIds.idxWidth - 1, 0)
              io.completedBundleIdWriteData := completionQueue.io.deq.bits.mBundleId
              completedBundleCount := completedBundleCount + 1.U
            }

            completionQuerySmId := bundleTableSmId(completionBundleIdx)
            completionQuerySchedulerId := bundleTableSchedulerId(completionBundleIdx)
            completionQueryWarpId := bundleTableWarpId(completionBundleIdx)

            when(bundleTableWakeRelevant(completionBundleIdx)) {
              completionState := cRequestBlockedWarp
            }
          }
        }
      }
    }

    // A completed wake-relevant bundle may unblock a warp; query the bridge bitmap
    // in the completion path without taking over the main issue FSM.
    is(cRequestBlockedWarp) {
      io.blockedWarpQueryIdx := BlockedWarpBitmap.indexFromFields(
        completionQuerySmId,
        completionQuerySchedulerId,
        completionQueryWarpId)
      when(io.blockedWarpQueryReady) {
        io.blockedWarpQueryEn := true.B
        completionState := cWaitBlockedWarp
      }
    }

    is(cWaitBlockedWarp) {
      io.blockedWarpQueryIdx := BlockedWarpBitmap.indexFromFields(
        completionQuerySmId,
        completionQuerySchedulerId,
        completionQueryWarpId)
      when(io.blockedWarpQueryRespValid) {
        io.blockedWarpQueryRespStored := true.B
        when(io.blockedWarpQueryResp) {
          roundStopPending := true.B
        }
        completionState := cDequeue
      }
    }
  }

  when(roundCompletePulse) {
    assert(bucketDrained, "TrafficGenRTLEngine completed a round before draining the current bucket")
    assert(completionPathDrained, "TrafficGenRTLEngine completed a round before draining completions")
    assert(newAccessReadCount === issuedAccessCount,
      "TrafficGenRTLEngine dropped a read access before issue")
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

      engine.io.uploadDone := io.uploadDone
      engine.io.blockedWarpBitmapReady := io.blockedWarpBitmapReady
      engine.io.minIssueCycle := io.minIssueCycle
      engine.io.startRound := io.startRound
      engine.io.trafficGenDone := io.trafficGenDone
      engine.io.accessReadData := io.accessReadData
      engine.io.accessReadDataValid := io.accessReadDataValid
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
      io.currentCycleAfterIssue := engine.io.currentCycleAfterIssue
      io.dpiState := engine.io.dpiState
      io.reservationClear.valid := engine.io.reservationClear.valid
      io.reservationClear.bits := engine.io.reservationClear.bits
      engine.io.reservationClear.ready := io.reservationClear.ready
      io.issuedAccessWriteback.valid := engine.io.issuedAccessWriteback.valid
      io.issuedAccessWriteback.bits := engine.io.issuedAccessWriteback.bits
      engine.io.issuedAccessWriteback.ready := io.issuedAccessWriteback.ready
      io.completedBundleIdWriteEn := engine.io.completedBundleIdWriteEn
      io.completedBundleIdWriteIdx := engine.io.completedBundleIdWriteIdx
      io.completedBundleIdWriteData := engine.io.completedBundleIdWriteData
      io.completedBundleCountWriteEn := engine.io.completedBundleCountWriteEn
      io.completedBundleCountWriteData := engine.io.completedBundleCountWriteData
      io.blockedWarpQueryIdx := engine.io.blockedWarpQueryIdx
      io.blockedWarpQueryEn := engine.io.blockedWarpQueryEn
      io.blockedWarpQueryRespStored := engine.io.blockedWarpQueryRespStored
      io.accessReadCycle := engine.io.accessReadCycle
      io.accessReadEn := engine.io.accessReadEn
      io.accessReadDataReady := engine.io.accessReadDataReady
      io.accessReadBucketDoneReady := engine.io.accessReadBucketDoneReady
      io.reservationWindowAdvanceCycle := engine.io.reservationWindowAdvanceCycle
      io.reservationWindowAdvanceEn := engine.io.reservationWindowAdvanceEn
      trafficGenIdle := !engine.io.targetBusy

      for (i <- 0 until params.numGenerators) {
        io.issue(i).valid := engine.io.issue(i).valid
        io.issue(i).bits := engine.io.issue(i).bits
        engine.io.issue(i).ready := io.issue(i).ready
      }

      when(io.uploadDone && io.blockedWarpBitmapReady) {
        patternReady := true.B
      }

      node.regmap(
        0x00 -> Seq(RegField.r(1, trafficGenIdle)),
        0x04 -> Seq(RegField.w(1, startTrafficGenPulse)),
        0x08 -> Seq(RegField.r(1, io.targetBusy)),
        0x0C -> Seq(RegField.r(1, io.uploadDone)),
        0x10 -> Seq(RegField.r(1, io.trafficGenDone)),
        0x18 -> Seq(RegField.r(1, patternReady)),
        0x20 -> Seq(RegField.r(32, io.minIssueCycle(31, 0))),
        0x24 -> Seq(RegField.r(32, io.minIssueCycle(63, 32))),
        0x28 -> Seq(RegField.r(1, io.blockedWarpBitmapReady)),
        0x38 -> Seq(RegField.r(32, io.currentCycleAfterIssue(31, 0))),
        0x3C -> Seq(RegField.r(32, io.currentCycleAfterIssue(63, 32))),
        0x40 -> Seq(RegField.r(32, io.dpiState))
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
  //- issue accesses and depopulate reservedSubPartitionsByCycle, set baseCycle/Idx
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
      outerIO.bits.currentCycleAfterIssue <> trafficGenTL.module.io.currentCycleAfterIssue
      outerIO.bits.dpiState <> trafficGenTL.module.io.dpiState
      outerIO.bits.reservationClear <> trafficGenTL.module.io.reservationClear
      outerIO.bits.issuedAccessWriteback <> trafficGenTL.module.io.issuedAccessWriteback
      outerIO.bits.completedBundleIdWriteEn <> trafficGenTL.module.io.completedBundleIdWriteEn
      outerIO.bits.completedBundleIdWriteIdx <> trafficGenTL.module.io.completedBundleIdWriteIdx
      outerIO.bits.completedBundleIdWriteData <> trafficGenTL.module.io.completedBundleIdWriteData
      outerIO.bits.completedBundleCountWriteEn <> trafficGenTL.module.io.completedBundleCountWriteEn
      outerIO.bits.completedBundleCountWriteData <> trafficGenTL.module.io.completedBundleCountWriteData
      outerIO.bits.startRound <> trafficGenTL.module.io.startRound
      outerIO.bits.trafficGenDone <> trafficGenTL.module.io.trafficGenDone
      outerIO.bits.minIssueCycle <> trafficGenTL.module.io.minIssueCycle
      outerIO.bits.blockedWarpBitmapReady <> trafficGenTL.module.io.blockedWarpBitmapReady
      outerIO.bits.blockedWarpQueryIdx <> trafficGenTL.module.io.blockedWarpQueryIdx
      outerIO.bits.blockedWarpQueryEn <> trafficGenTL.module.io.blockedWarpQueryEn
      outerIO.bits.blockedWarpQueryRespStored <> trafficGenTL.module.io.blockedWarpQueryRespStored
      outerIO.bits.blockedWarpQueryResp <> trafficGenTL.module.io.blockedWarpQueryResp
      outerIO.bits.blockedWarpQueryRespValid <> trafficGenTL.module.io.blockedWarpQueryRespValid
      outerIO.bits.blockedWarpQueryReady <> trafficGenTL.module.io.blockedWarpQueryReady
      outerIO.bits.accessReadCycle <> trafficGenTL.module.io.accessReadCycle
      outerIO.bits.accessReadEn <> trafficGenTL.module.io.accessReadEn
      outerIO.bits.accessReadDataReady <> trafficGenTL.module.io.accessReadDataReady
      outerIO.bits.accessReadBucketDoneReady <> trafficGenTL.module.io.accessReadBucketDoneReady
      outerIO.bits.accessReadData <> trafficGenTL.module.io.accessReadData
      outerIO.bits.accessReadDataValid <> trafficGenTL.module.io.accessReadDataValid
      outerIO.bits.accessReadBucketDone <> trafficGenTL.module.io.accessReadBucketDone
      outerIO.bits.accessReadReady <> trafficGenTL.module.io.accessReadReady
      outerIO.bits.accessStoreCount <> trafficGenTL.module.io.accessStoreCount
      outerIO.bits.accessStoreMaxCycle <> trafficGenTL.module.io.accessStoreMaxCycle
      outerIO.bits.accessStoreHasEntries <> trafficGenTL.module.io.accessStoreHasEntries
      outerIO.bits.reservationWindowAdvanceCycle <> trafficGenTL.module.io.reservationWindowAdvanceCycle
      outerIO.bits.reservationWindowAdvanceEn <> trafficGenTL.module.io.reservationWindowAdvanceEn
      outerIO.bits.uploadDone <> trafficGenTL.module.io.uploadDone

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
