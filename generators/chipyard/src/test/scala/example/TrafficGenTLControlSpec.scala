package chipyard.example

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec

/** Drive the production target register map through real TileLink requests. */
private class TrafficGenTLControlHarness extends LazyModule()(Parameters.empty) {
  val params = TrafficGenParams(numGenerators = 1, memOutstanding = 1,
    backend = TrafficGenRTLBackend)
  val control = LazyModule(new TrafficGenTL(params, beatBytes = 4))
  val clockSource = ClockSourceNode(Seq(ClockSourceParameters()))
  control.clockNode := clockSource
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "trafficgen-control-test", sourceId = IdRange(0, 1))))))
  control.node := client
  lazy val module = new TrafficGenTLControlHarnessImp(this)
}

private class TrafficGenTLControlHarnessImp(outer: TrafficGenTLControlHarness)
    extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val done = Input(Bool())
    val startPulses = Output(UInt(32.W))
    val pending = Output(UInt(4.W))
    val closed = Output(Bool())
    val launchIds = Output(UInt(256.W))
    val registryIds = Output(UInt(256.W))
  })
  val tl = outer.client.makeIOs().head
  outer.clockSource.out.foreach { case (bundle, _) =>
    bundle.clock := clock
    bundle.reset := reset
  }
  val control = outer.control.module.io
  control.launchStatusIds := 0.U
  control.launchStatuses := 0.U
  control.sessionStatus := 0.U
  control.controlPending := false.B
  control.uploadReady := false.B
  control.minIssueCycle := 0.U
  control.startRound := false.B
  control.trafficGenDone := io.done
  control.issuedAccessBatch.ready := true.B
  control.accessReadRespValid := false.B
  control.accessReadRespId := 0.U
  control.accessReadData.foreach(_ := 0.U)
  control.accessReadDataValid.foreach(_ := false.B)
  control.accessReadBucketDone := true.B
  control.accessReadReady := true.B
  control.accessReadLaneDoneMask := 1.U
  control.accessReadPrefetchPauseReq := false.B
  control.accessStoreCount := 0.U
  control.accessStoreMaxCycle := 0.U
  control.accessStoreHasEntries := false.B
  control.accessStoreHasMore := false.B
  control.memActive := false.B
  control.memInflightAccesses.foreach(_ := 0.U)
  control.issue.foreach(_.ready := true.B)
  control.completion.foreach { port =>
    port.valid := false.B
    port.bits := 0.U.asTypeOf(port.bits)
  }
  val startPulses = RegInit(0.U(32.W))
  when(control.startTrafficGen) { startPulses := startPulses + 1.U }
  io.startPulses := startPulses
  io.pending := control.launchPendingMask
  io.closed := control.closeSubmissions
  io.launchIds := control.launchIds
  io.registryIds := control.launchRegistryIds
}

class TrafficGenTLControlSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGen target control register map"

  private def initialize(dut: TrafficGenTLControlHarnessImp): Unit = {
    dut.io.done.poke(false.B)
    dut.tl.a.valid.poke(false.B)
    dut.tl.a.bits.opcode.poke(TLMessages.Get)
    dut.tl.a.bits.param.poke(0.U)
    dut.tl.a.bits.size.poke(2.U)
    dut.tl.a.bits.source.poke(0.U)
    dut.tl.a.bits.address.poke(0x5000.U)
    dut.tl.a.bits.mask.poke(15.U)
    dut.tl.a.bits.data.poke(0.U)
    dut.tl.a.bits.corrupt.poke(false.B)
    dut.tl.d.ready.poke(true.B)
    dut.clock.step(2)
  }

  private def access(dut: TrafficGenTLControlHarnessImp, offset: Int,
                     write: Option[BigInt] = None): BigInt = {
    dut.tl.a.bits.address.poke((0x5000 + offset).U)
    dut.tl.a.bits.opcode.poke(if (write.isDefined) TLMessages.PutFullData else TLMessages.Get)
    dut.tl.a.bits.data.poke(write.getOrElse(BigInt(0)).U)
    dut.tl.a.valid.poke(true.B)
    var sent = false
    var received = false
    var data = BigInt(0)
    var remaining = 30
    while (!received && remaining > 0) {
      if (!sent && dut.tl.a.ready.peek().litToBoolean) sent = true
      if (dut.tl.d.valid.peek().litToBoolean) {
        dut.tl.d.bits.denied.expect(false.B)
        dut.tl.d.bits.corrupt.expect(false.B)
        data = dut.tl.d.bits.data.peek().litValue
        received = true
      }
      dut.clock.step()
      if (sent) dut.tl.a.valid.poke(false.B)
      remaining -= 1
    }
    assert(sent && received, s"TileLink register access at $offset did not complete")
    data
  }

  it should "retain the old RTL start and done registers without allocating launch slots" in {
    test(LazyModule(new TrafficGenTLControlHarness).module) { dut =>
      initialize(dut)
      assert(access(dut, 0x48) == 3)
      assert(access(dut, 0x00) == 1)
      access(dut, 0x04, Some(BigInt(1)))
      dut.io.startPulses.expect(1.U)
      dut.io.pending.expect(0.U)
      dut.io.launchIds.expect(0.U)
      dut.io.closed.expect(false.B)
      dut.clock.step(3)
      dut.io.startPulses.expect(1.U)
      dut.io.done.poke(true.B)
      assert(access(dut, 0x10) == 1)
    }
  }

  it should "atomically submit and close through the slot doorbell while preserving the legacy pulse" in {
    test(LazyModule(new TrafficGenTLControlHarness).module) { dut =>
      initialize(dut)
      access(dut, 0x100, Some(BigInt(1106)))
      access(dut, 0x104, Some(BigInt(1)))
      access(dut, 0x11c, Some(BigInt(1)))
      dut.io.startPulses.expect(0.U)
      dut.io.pending.expect(1.U)
      dut.io.closed.expect(true.B)
      dut.io.launchIds.expect(1.U)
      dut.io.registryIds.expect(((BigInt(1) << 32) | 1106).U)
      assert(access(dut, 0x10c) == 1)
      assert(access(dut, 0x110) == 1)
      assert(access(dut, 0x114) == 0)
      access(dut, 0x128, Some(BigInt(1)))
      assert(access(dut, 0x138) == 2) // The ordinary submit rejects a closed session.
      dut.io.launchIds.expect(1.U)
    }
  }
}
