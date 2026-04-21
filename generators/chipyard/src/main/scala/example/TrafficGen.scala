package chipyard.example

import chisel3._
import chisel3.util._

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
  // TG reaches min_issue_cycle, unblocks a blocked warp, issues all accesses
  val roundComplete = Output(Bool())
  // TG still has a scheduling round active or memory requests in flight
  val hasPendingWork = Output(Bool())

  //// Host -> Target control/status

  // L2 accesses have been written to bridge module access store
  val uploadDone = Input(Bool())
  // blocked warp bitmap has been written to bridge module
  val blockedWarpBitmapReady = Input(Bool())
  // furthest the TG is allowed to issue
  val minIssueCycle = Input(UInt(32.W))
  // begin next round of issuing
  val startRound = Input(Bool())

  //// Target -> Host execution data

  // TG issue logic will enqueue clear requests here to remove the issued subpartition from reservedSubPartitionsByCycle without stalling on bridge RMW latency.
  val reservationClear = Vec(nGenerators, Decoupled(new ReservationClearRequest))
  // TG issue logic will enqueue the actual issued L2 accesses here with cycleCount updated to the real issue cycle.
  val issuedAccessWriteback = Vec(nGenerators, Decoupled(new L2Access))

  // query the L2 access store for an L2 access
  val accessReadAddr = Output(UInt(32.W))
  val accessReadEn = Output(Bool())

  // query the blocked warp bitmap for whether a warp is blocked
  val blockedWarpQueryIdx = Output(UInt(BlockedWarpBitmap.indexBits.W))
  val blockedWarpQueryEn = Output(Bool())

  // write completed bundle IDs and counts to the bridge module as accesses return, to be used by future scheduling
  val completedBundleIdWriteEn = Output(Bool())
  val completedBundleIdWriteIdx = Output(UInt(5.W))
  val completedBundleIdWriteData = Output(UInt(32.W))
  val completedBundleCountWriteEn = Output(Bool())
  val completedBundleCountWriteData = Output(UInt(6.W))

  // current cycle of TG, used for scheduling
  val currentCycleAfterIssue = Output(UInt(32.W))

  //// Host -> Target execution data

  // L2 access returned from access store in bridge module
  val accessReadData = Input(new L2Access)
  val accessReadDataValid = Input(Bool())

  // response to blocked warp bitmap query
  val blockedWarpQueryResp = Input(Bool())
  val blockedWarpQueryRespValid = Input(Bool())
  

  val memActive = Input(Bool())
  val issue = Vec(nGenerators, Decoupled(new L2Access))
}

trait HasTrafficGenTopIO {
  def io: TrafficGenTopIO
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

      val currentCycleAfterIssue = RegInit(0.U(32.W))
      val roundActive = RegInit(false.B)
      val roundComplete = Wire(Bool())
      val memActivePrev = RegNext(io.memActive, false.B)
      val patternReady = RegInit(false.B)
      val blockedWarpDebugQueryIdx = RegInit(0.U(BlockedWarpBitmap.indexBits.W))
      val blockedWarpDebugQueryReq = WireInit(0.U(1.W))
      val blockedWarpDebugResp = RegInit(false.B)
      val blockedWarpDebugRespValid = RegInit(false.B)

      val completedBundleCountWriteEn = Wire(Bool())
      val completedBundleCountWriteData = Wire(UInt(6.W))
      val completedBundleIdWriteEn = Wire(Vec(32, Bool()))
      val completedBundleIdWriteData = Wire(Vec(32, UInt(32.W)))

      io.currentCycleAfterIssue := currentCycleAfterIssue
      io.roundComplete := roundComplete
      io.hasPendingWork := roundActive || io.memActive
      io.completedBundleIdWriteEn := completedBundleIdWriteEn.asUInt.orR
      io.completedBundleIdWriteIdx := PriorityEncoder(completedBundleIdWriteEn)
      io.completedBundleIdWriteData := Mux1H(completedBundleIdWriteEn, completedBundleIdWriteData)
      io.completedBundleCountWriteEn := completedBundleCountWriteEn
      io.completedBundleCountWriteData := completedBundleCountWriteData
      io.blockedWarpQueryIdx := blockedWarpDebugQueryIdx
      io.blockedWarpQueryEn := blockedWarpDebugQueryReq(0)
      io.accessReadAddr := 0.U
      io.accessReadEn := false.B
      completedBundleCountWriteEn := false.B
      completedBundleCountWriteData := 0.U
      completedBundleIdWriteEn.foreach(_ := false.B)
      completedBundleIdWriteData.foreach(_ := 0.U)

      trafficGenIdle := !io.memActive && !roundActive
      roundComplete := roundActive && memActivePrev && !io.memActive
      when(io.startRound) {
        roundActive := true.B
      }
      when(roundComplete) {
        roundActive := false.B
      }

      for (i <- 0 until params.numGenerators) {
        io.issue(i).valid := false.B
        io.issue(i).bits := 0.U.asTypeOf(new L2Access)
        io.reservationClear(i).valid := false.B
        io.reservationClear(i).bits := 0.U.asTypeOf(new ReservationClearRequest)
        io.issuedAccessWriteback(i).valid := false.B
        io.issuedAccessWriteback(i).bits := 0.U.asTypeOf(new L2Access)
      }

      when(io.uploadDone && io.blockedWarpBitmapReady) {
        patternReady := true.B
      }

      when(blockedWarpDebugQueryReq(0)) {
        blockedWarpDebugRespValid := false.B
      }

      when(io.blockedWarpQueryRespValid) {
        blockedWarpDebugResp := io.blockedWarpQueryResp
        blockedWarpDebugRespValid := true.B
      }

      io.targetBusy := io.memActive

      def writeCompletedBundleCount(valid: Bool, data: UInt): Bool = {
        when(valid) {
          completedBundleCountWriteEn := true.B
          completedBundleCountWriteData := data
        }
        true.B
      }

      def writeCompletedBundleId(idx: Int)(valid: Bool, data: UInt): Bool = {
        when(valid) {
          completedBundleIdWriteEn(idx) := true.B
          completedBundleIdWriteData(idx) := data
        }
        true.B
      }

      node.regmap(
        0x00 -> Seq(RegField.r(1, trafficGenIdle)),
        0x04 -> Seq(RegField.w(1, startTrafficGenPulse)),
        0x08 -> Seq(RegField.r(1, io.targetBusy)),
        0x0C -> Seq(RegField.r(1, io.uploadDone)),
        0x18 -> Seq(RegField.r(1, patternReady)),
        0x20 -> Seq(RegField.r(32, io.minIssueCycle)),
        0x24 -> Seq(RegField.r(1, io.blockedWarpBitmapReady)),
        0x28 -> Seq(RegField(BlockedWarpBitmap.indexBits, blockedWarpDebugQueryIdx)),
        0x2C -> Seq(RegField.w(1, blockedWarpDebugQueryReq)),
        0x30 -> Seq(RegField.r(1, blockedWarpDebugRespValid)),
        0x34 -> Seq(RegField.r(1, blockedWarpDebugResp)),
        0x38 -> Seq(RegField.r(32, io.currentCycleAfterIssue)),
        0xC0 -> Seq(RegField.w(6, writeCompletedBundleCount(_, _))),
        0xC4 -> (0 until 32).map(idx => RegField.w(32, writeCompletedBundleId(idx)(_, _)))
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
      outerIO.bits.roundComplete <> trafficGenTL.module.io.roundComplete
      outerIO.bits.currentCycleAfterIssue <> trafficGenTL.module.io.currentCycleAfterIssue
      outerIO.bits.reservationClear <> trafficGenTL.module.io.reservationClear
      outerIO.bits.issuedAccessWriteback <> trafficGenTL.module.io.issuedAccessWriteback
      outerIO.bits.completedBundleIdWriteEn <> trafficGenTL.module.io.completedBundleIdWriteEn
      outerIO.bits.completedBundleIdWriteIdx <> trafficGenTL.module.io.completedBundleIdWriteIdx
      outerIO.bits.completedBundleIdWriteData <> trafficGenTL.module.io.completedBundleIdWriteData
      outerIO.bits.completedBundleCountWriteEn <> trafficGenTL.module.io.completedBundleCountWriteEn
      outerIO.bits.completedBundleCountWriteData <> trafficGenTL.module.io.completedBundleCountWriteData
      outerIO.bits.startRound <> trafficGenTL.module.io.startRound
      outerIO.bits.minIssueCycle <> trafficGenTL.module.io.minIssueCycle
      outerIO.bits.blockedWarpBitmapReady <> trafficGenTL.module.io.blockedWarpBitmapReady
      outerIO.bits.blockedWarpQueryIdx <> trafficGenTL.module.io.blockedWarpQueryIdx
      outerIO.bits.blockedWarpQueryEn <> trafficGenTL.module.io.blockedWarpQueryEn
      outerIO.bits.blockedWarpQueryResp <> trafficGenTL.module.io.blockedWarpQueryResp
      outerIO.bits.blockedWarpQueryRespValid <> trafficGenTL.module.io.blockedWarpQueryRespValid
      outerIO.bits.accessReadAddr <> trafficGenTL.module.io.accessReadAddr
      outerIO.bits.accessReadEn <> trafficGenTL.module.io.accessReadEn
      outerIO.bits.accessReadData <> trafficGenTL.module.io.accessReadData
      outerIO.bits.accessReadDataValid <> trafficGenTL.module.io.accessReadDataValid
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
