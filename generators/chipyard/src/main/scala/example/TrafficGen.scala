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
import firechip.bridgeinterfaces.{BlockedWarpBitmap, L2Access, ReservationClearRequest}

case class TrafficGenParams(
  address: BigInt = 0x5000,
  width: Int = 32,
  base: BigInt = 0x88000000L,
  size: BigInt = 500000000L,
  numGenerators: Int = 4,
  regionStride: BigInt = 0x400000L,
  maxL2AccessEntries: Int = 262144 // 2^18
)

case object TrafficGenKey extends Field[Option[TrafficGenParams]](None)

class TrafficGenTopIO(val w: Int, val nGenerators: Int) extends Bundle {

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
  val reservationClear = Vec(nGenerators, Decoupled(new ReservationClearRequest))
  // TG issue logic will enqueue the actual issued L2 accesses here with cycleCount updated to the real issue cycle.
  val issuedAccessWriteback = Vec(nGenerators, Decoupled(new L2Access))

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
  val completedBundleIdWriteIdx = Output(UInt(7.W))
  val completedBundleIdWriteData = Output(UInt(64.W))
  val completedBundleCountWriteEn = Output(Bool())
  val completedBundleCountWriteData = Output(UInt(8.W))

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
  val issue = Vec(nGenerators, Decoupled(new L2Access))
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
    val completed_bundle_count_write_data = Output(UInt(8.W))
    val completed_bundle_id_write_en = Output(Bool())
    val completed_bundle_id_write_idx = Output(UInt(7.W))
    val completed_bundle_id_write_data = Output(UInt(64.W))

    val reservation_window_advance_en = Output(Bool())
    val reservation_window_advance_cycle = Output(UInt(64.W))
  })

  // pulls in SV wrapper resource  

  addResource("/vsrc/trafficgen_dpi.v")
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
    val io = IO(new TrafficGenTopIO(params.width, params.numGenerators))

    withClockAndReset(clock, reset) {

      val trafficGenIdle = Wire(Bool())

      // start traffic generator when target program writes to start register
      val startTrafficGenPulse = Wire(Bool())
      startTrafficGenPulse := false.B
      io.startTrafficGen := startTrafficGenPulse

      val currentCycleAfterIssue = RegInit(0.U(64.W))
      val roundStarted = Wire(Bool())
      val roundComplete = Wire(Bool())
      val patternReady = RegInit(false.B)
      val completedBundleCountWriteEn = Wire(Bool())
      val completedBundleCountWriteData = Wire(UInt(8.W))
      val completedBundleIdWriteEn = Wire(Vec(128, Bool())) // one-hot vector for enable signal
      val completedBundleIdWriteData = Wire(Vec(128, UInt(64.W)))
      val dpi = Module(new TrafficGenDPIBlackBox(params.numGenerators))

      dpi.io.clock := clock
      dpi.io.reset := reset.asBool

      // target state into DPI black box
      dpi.io.start_round := io.startRound
      dpi.io.upload_done := io.uploadDone
      dpi.io.blocked_warp_bitmap_ready := io.blockedWarpBitmapReady
      dpi.io.access_store_count := io.accessStoreCount
      dpi.io.access_store_max_cycle := io.accessStoreMaxCycle
      dpi.io.access_store_has_entries := io.accessStoreHasEntries
      dpi.io.min_issue_cycle := io.minIssueCycle

      // access store read response into DPI
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

      // blocked warp query response into DPI
      dpi.io.blocked_warp_query_resp_valid := io.blockedWarpQueryRespValid
      dpi.io.blocked_warp_query_resp := io.blockedWarpQueryResp
      dpi.io.blocked_warp_query_ready := io.blockedWarpQueryReady

      // backpressure from hw queues into DPI
      dpi.io.issued_access_writeback_ready := io.issuedAccessWriteback(0).ready
      dpi.io.reservation_clear_ready := io.reservationClear(0).ready

      io.currentCycleAfterIssue := currentCycleAfterIssue
      io.roundStarted := roundStarted
      io.roundComplete := roundComplete

      // DPI outputs connected to bridge-facing interface

      // query requests
      io.accessReadCycle := dpi.io.access_read_cycle
      io.accessReadEn := dpi.io.access_read_en
      io.accessReadDataReady := dpi.io.access_read_data_ready
      io.accessReadBucketDoneReady := dpi.io.access_read_bucket_done_ready
      io.blockedWarpQueryIdx := dpi.io.blocked_warp_query_idx
      io.blockedWarpQueryEn := dpi.io.blocked_warp_query_en
      io.blockedWarpQueryRespStored := dpi.io.blocked_warp_query_resp_stored
      
      // cycle-window advancement
      io.reservationWindowAdvanceCycle := dpi.io.reservation_window_advance_cycle
      io.reservationWindowAdvanceEn := dpi.io.reservation_window_advance_en
      
      // status
      io.hasPendingWork := dpi.io.has_pending_work
      roundStarted := dpi.io.round_started
      roundComplete := dpi.io.round_complete
      trafficGenIdle := !dpi.io.target_busy
      currentCycleAfterIssue := dpi.io.current_cycle_after_issue
      io.targetBusy := dpi.io.target_busy
      io.dpiState := dpi.io.dpi_state
      
      io.completedBundleIdWriteEn := completedBundleIdWriteEn.asUInt.orR
      //recover index from one-hot
      io.completedBundleIdWriteIdx := PriorityEncoder(completedBundleIdWriteEn) 
      //select data based on one-hot
      io.completedBundleIdWriteData := Mux1H(completedBundleIdWriteEn, completedBundleIdWriteData) 
      io.completedBundleCountWriteEn := completedBundleCountWriteEn
      io.completedBundleCountWriteData := completedBundleCountWriteData
      
      // these need to be pulsed so give a default 
      completedBundleCountWriteEn := false.B
      completedBundleCountWriteData := 0.U
      when(dpi.io.completed_bundle_count_write_en) {
        completedBundleCountWriteEn := true.B
        completedBundleCountWriteData := dpi.io.completed_bundle_count_write_data
      }
      
      completedBundleIdWriteEn.foreach(_ := false.B)
      completedBundleIdWriteData.foreach(_ := 0.U)
      when(dpi.io.completed_bundle_id_write_en) {
        completedBundleIdWriteEn(dpi.io.completed_bundle_id_write_idx) := true.B
        completedBundleIdWriteData(dpi.io.completed_bundle_id_write_idx) := dpi.io.completed_bundle_id_write_data
      }


      for (i <- 0 until params.numGenerators) {
        io.issue(i).valid := false.B
        io.issue(i).bits := 0.U.asTypeOf(new L2Access)
        io.reservationClear(i).valid := (if (i == 0) dpi.io.reservation_clear_valid else false.B)
        io.reservationClear(i).bits.cycle := dpi.io.reservation_clear_cycle
        io.reservationClear(i).bits.subpartition := dpi.io.reservation_clear_subpartition
        io.issuedAccessWriteback(i).valid := (if (i == 0) dpi.io.issued_access_writeback_valid else false.B)
        io.issuedAccessWriteback(i).bits.id := dpi.io.issued_access_writeback_id
        io.issuedAccessWriteback(i).bits.address := dpi.io.issued_access_writeback_address
        io.issuedAccessWriteback(i).bits.cycleCount := dpi.io.issued_access_writeback_cycle_count
        io.issuedAccessWriteback(i).bits.mSubpartition := dpi.io.issued_access_writeback_subpartition
        io.issuedAccessWriteback(i).bits.mSetIndex := dpi.io.issued_access_writeback_set_index
        io.issuedAccessWriteback(i).bits.mTag := dpi.io.issued_access_writeback_tag
        io.issuedAccessWriteback(i).bits.mMask := dpi.io.issued_access_writeback_mask
        io.issuedAccessWriteback(i).bits.smId := dpi.io.issued_access_writeback_sm_id
        io.issuedAccessWriteback(i).bits.schedulerId := dpi.io.issued_access_writeback_scheduler_id
        io.issuedAccessWriteback(i).bits.warpId := dpi.io.issued_access_writeback_warp_id
        io.issuedAccessWriteback(i).bits.mBundleId := dpi.io.issued_access_writeback_bundle_id
        io.issuedAccessWriteback(i).bits.mWakeRelevantBundle := dpi.io.issued_access_writeback_wake_relevant_bundle
        io.issuedAccessWriteback(i).bits.mIsWrite := dpi.io.issued_access_writeback_is_write
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

class TrafficGenMem(id: Int, beatBytes: Int)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLClientParameters(
      name = s"trafficgenmem$id",
      sourceId = IdRange(0, 4),
      supportsProbe = TransferSizes(64, 64),
      supportsGet = TransferSizes(64, 64),
      supportsPutFull = TransferSizes(64, 64)
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
      val coreOffset = Input(UInt(32.W))
      val req = Flipped(Decoupled(new L2Access))
    })

    withClockAndReset(clock, reset) {
      val (mem, _) = outer.node.out(0)
      dontTouch(io.coreOffset)

      mem.a.valid := false.B
      mem.a.bits := DontCare
      mem.b.ready := true.B
      mem.c.valid := false.B
      mem.c.bits := DontCare
      mem.d.ready := true.B
      mem.e.valid := false.B
      mem.e.bits := DontCare

      io.req.ready := false.B
      io.active := false.B
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
      val trafficGenMem = LazyModule(new TrafficGenMem(i, sbus.beatBytes)(p))
      trafficGenMem.clockNode := sbus.fixedClockNode
      sbus.coupleFrom(s"trafficgen-mem-$i") { _ := trafficGenMem.node }
      trafficGenMem
    }

    InModuleBody {
      val outerIO = IO(new ClockedIO(new TrafficGenPortPeripheralIO(params.numGenerators))).suggestName("trafficgen")
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
      generators.zipWithIndex.foreach { case (generator, i) =>
        generator.module.io.coreOffset := (BigInt(i) * params.regionStride).U
        generator.module.io.req <> trafficGenTL.module.io.issue(i)
      }

      outerIO
    }
  }
}

class WithTrafficGen extends Config((site, here, up) => {
  case TrafficGenKey => Some(TrafficGenParams())
})
