package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.RTLL2Access
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec

/** Exposes the real adapter's TileLink port to a programmable response agent. */
private class TrafficGenPutGetHarness(
  val transferBytes: Int = 32,
  val remap: Boolean = false,
) extends LazyModule()(Parameters.empty) {
  val params = TrafficGenParams(numGenerators = 1, memOutstanding = 8,
    remapTraceAddresses = remap, remapBase = BigInt("100000000", 16))
  val adapter = LazyModule(if (transferBytes == 64) {
    // Exercise the unchanged default constructor used by legacy configurations.
    new TrafficGenMem(3, 32, params)
  } else {
    new TrafficGenMem(3, 32, params, transferBytes = 32,
      serializeSameTransfer = true, requireAligned = true)
  })
  val clockSource = ClockSourceNode(Seq(ClockSourceParameters()))
  adapter.clockNode := clockSource
  val manager = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(0, (BigInt(1) << 36) - 1)),
      regionType = RegionType.UNCACHED,
      supportsGet = TransferSizes(32, 64),
      supportsPutFull = TransferSizes(32, 64),
      mayDenyGet = true,
      mayDenyPut = true,
      fifoId = None,
    )), beatBytes = 32,
  )))
  manager := adapter.node
  lazy val module = new TrafficGenPutGetHarnessImp(this)
}

private class TrafficGenPutGetHarnessImp(outer: TrafficGenPutGetHarness)
    extends LazyModuleImp(outer) {
  val io = IO(new TrafficGenMemIO(outer.params))
  io <> outer.adapter.module.io
  val tl = outer.manager.makeIOs().head
  outer.clockSource.out.foreach { case (bundle, _) =>
    bundle.clock := clock
    bundle.reset := reset
  }
}

class TrafficGenPutGetSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGenMem sector Put/Get protocol"

  private case class Access(id: Int, address: BigInt, write: Boolean = true) {
    val bundle: BigInt = id + 1000
    val cycle: BigInt = id + 37
  }
  private case class Packet(opcode: BigInt, source: BigInt, size: BigInt,
                            address: BigInt, mask: BigInt, data: BigInt)

  private def pokeAccess(bits: RTLL2Access, access: Access): Unit = {
    bits.id.poke(access.id.U)
    bits.address.poke(access.address.U)
    bits.cycleCount.poke(access.cycle.U)
    bits.mBundleId.poke(access.bundle.U)
    bits.mIsWrite.poke(access.write.B)
    bits.mWakeRelevantBundle.poke((!access.write).B)
    bits.mWarpBlocked.poke((!access.write).B)
    bits.bundleIssueCount.poke(7.U)
    bits.bundleGeneration.poke(2.U)
    bits.launchId.poke(23.U)
  }

  private def initialize(dut: TrafficGenPutGetHarnessImp): Unit = {
    dut.io.req.valid.poke(false.B)
    pokeAccess(dut.io.req.bits, Access(0, 0))
    dut.io.coreOffset.poke(0.U)
    dut.io.completion.ready.poke(false.B)
    dut.tl.a.ready.poke(false.B)
    // Get/Put negotiation removes B/C/E. Their convenience accessors construct
    // dummy hardware and therefore must not be called from the Scala tester.
    assert(!dut.tl.params.hasBCE)
    dut.tl.d.valid.poke(false.B)
    dut.tl.d.bits.opcode.poke(TLMessages.AccessAck)
    dut.tl.d.bits.param.poke(0.U)
    dut.tl.d.bits.size.poke(5.U)
    dut.tl.d.bits.source.poke(0.U)
    dut.tl.d.bits.sink.poke(0.U)
    dut.tl.d.bits.denied.poke(false.B)
    dut.tl.d.bits.data.poke(0.U)
    dut.tl.d.bits.corrupt.poke(false.B)
    dut.clock.step(2)
  }

  private def waitFor(dut: TrafficGenPutGetHarnessImp, condition: => Boolean): Unit = {
    var elapsed = 0
    while (!condition && elapsed < 100) {
      dut.clock.step()
      elapsed += 1
    }
    assert(condition, "protocol progress timed out after 100 cycles")
  }

  private def enqueue(dut: TrafficGenPutGetHarnessImp, access: Access): Unit = {
    pokeAccess(dut.io.req.bits, access)
    dut.io.req.valid.poke(true.B)
    waitFor(dut, dut.io.req.ready.peek().litToBoolean)
    dut.clock.step()
    dut.io.req.valid.poke(false.B)
  }

  private def peekPacket(dut: TrafficGenPutGetHarnessImp): Packet = {
    val a = dut.tl.a.bits
    Packet(a.opcode.peek().litValue, a.source.peek().litValue,
      a.size.peek().litValue, a.address.peek().litValue,
      a.mask.peek().litValue, a.data.peek().litValue)
  }

  private def acceptA(dut: TrafficGenPutGetHarnessImp): Packet = {
    waitFor(dut, dut.tl.a.valid.peek().litToBoolean)
    val packet = peekPacket(dut)
    dut.tl.a.ready.poke(true.B)
    dut.clock.step()
    dut.tl.a.ready.poke(false.B)
    packet
  }

  private def driveResponse(dut: TrafficGenPutGetHarnessImp, packet: Packet,
                            write: Boolean = true): Unit = {
    dut.tl.d.bits.opcode.poke(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
    dut.tl.d.bits.source.poke(packet.source.U)
    dut.tl.d.bits.size.poke(packet.size.U)
    dut.tl.d.bits.data.poke("h0123456789abcdef".U)
    dut.tl.d.valid.poke(true.B)
  }

  private def respond(dut: TrafficGenPutGetHarnessImp, packet: Packet,
                      write: Boolean = true): Unit = {
    driveResponse(dut, packet, write)
    waitFor(dut, dut.tl.d.ready.peek().litToBoolean)
    dut.clock.step()
    dut.tl.d.valid.poke(false.B)
  }

  private def consumeCompletion(dut: TrafficGenPutGetHarnessImp, access: Access): Unit = {
    waitFor(dut, dut.io.completion.valid.peek().litToBoolean)
    val bits = dut.io.completion.bits
    bits.id.expect(access.id.U)
    bits.address.expect(access.address.U)
    bits.cycleCount.expect(access.cycle.U)
    bits.mBundleId.expect(access.bundle.U)
    bits.mIsWrite.expect(access.write.B)
    bits.mWakeRelevantBundle.expect((!access.write).B)
    bits.mWarpBlocked.expect((!access.write).B)
    bits.bundleIssueCount.expect(7.U)
    bits.bundleGeneration.expect(2.U)
    bits.launchId.expect(23.U)
    dut.io.completion.ready.poke(true.B)
    dut.clock.step()
    dut.io.completion.ready.poke(false.B)
  }

  private def noA(dut: TrafficGenPutGetHarnessImp, cycles: Int): Unit = {
    for (_ <- 0 until cycles) {
      dut.tl.a.valid.expect(false.B)
      dut.clock.step()
    }
  }

  private def expectedData(access: Access, beat: Int): BigInt = {
    val seed = BigInt(access.id) ^ access.bundle ^ BigInt(3) ^
      BigInt(beat) ^ BigInt("9e3779b97f4a7c15", 16)
    (0 until 4).foldLeft(BigInt(0))((data, word) => data | ((seed ^ word) << (64 * word)))
  }

  it should "preserve all four sector offsets and allow them to be outstanding together" in {
    test(LazyModule(new TrafficGenPutGetHarness(remap = true)).module) { dut =>
      initialize(dut)
      // Remapping intentionally ignores coreOffset and keeps low 32 address bits.
      dut.io.coreOffset.poke(0x400.U)
      val accesses = (0 until 4).map(i => Access(10 + i, BigInt("500001000", 16) + 32 * i))
      val packets = accesses.map { access =>
        enqueue(dut, access)
        val packet = acceptA(dut)
        assert(packet.opcode == TLMessages.PutFullData.litValue)
        assert(packet.address == BigInt("100001000", 16) + (access.id - 10) * 32)
        assert(packet.size == 5 && packet.mask == BigInt("ffffffff", 16))
        assert(packet.data == expectedData(access, 0))
        packet
      }
      assert(packets.map(_.source).distinct.size == 4)
      dut.io.inflightAccessCount.expect(4.U)
      noA(dut, 3) // Every write was exactly one beat.
      for (i <- Seq(2, 0, 3, 1)) {
        respond(dut, packets(i))
        consumeCompletion(dut, accesses(i))
      }
      dut.io.inflightAccessCount.expect(0.U)
      dut.io.active.expect(false.B)
    }
  }

  it should "hold a Put stable under A backpressure and return a one-beat Get completion" in {
    test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
      initialize(dut)
      dut.io.coreOffset.poke(0x200.U)
      val write = Access(20, 0x1020)
      enqueue(dut, write)
      waitFor(dut, dut.tl.a.valid.peek().litToBoolean)
      val stalled = peekPacket(dut)
      assert(stalled.address == 0x1220)
      // Once offered, a packet must stay stable even if software changes the
      // offset for later requests while this A transfer is backpressured.
      dut.io.coreOffset.poke(0x600.U)
      for (_ <- 0 until 7) {
        dut.tl.a.valid.expect(true.B)
        assert(peekPacket(dut) == stalled)
        dut.clock.step()
      }
      val put = acceptA(dut)
      respond(dut, put)
      consumeCompletion(dut, write)

      dut.io.coreOffset.poke(0x200.U)
      val read = Access(21, 0x1060, write = false)
      enqueue(dut, read)
      val get = acceptA(dut)
      assert(get.opcode == TLMessages.Get.litValue)
      assert(get.address == 0x1260 && get.size == 5 && get.mask == BigInt("ffffffff", 16))
      noA(dut, 3)
      dut.io.completion.valid.expect(false.B)
      respond(dut, get, write = false)
      consumeCompletion(dut, read)
      dut.io.inflightAccessCount.expect(0.U)
    }
  }

  it should "exhaust eight sources and reuse only a source whose response was accepted" in {
    test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
      initialize(dut)
      val accesses = (0 until 8).map(i => Access(30 + i, 0x2000 + i * 32))
      val packets = accesses.map { access => enqueue(dut, access); acceptA(dut) }
      assert(packets.map(_.source).distinct.size == 8)
      dut.io.inflightAccessCount.expect(8.U)
      val ninth = Access(38, 0x3000)
      enqueue(dut, ninth)
      noA(dut, 7)
      respond(dut, packets(5))
      val replacement = acceptA(dut)
      assert(replacement.source == packets(5).source)
      consumeCompletion(dut, accesses(5))
      dut.io.inflightAccessCount.expect(8.U)
      for (i <- Seq(7, 0, 3, 1, 6, 2, 4)) {
        respond(dut, packets(i))
        consumeCompletion(dut, accesses(i))
      }
      respond(dut, replacement)
      consumeCompletion(dut, ninth)
      dut.io.active.expect(false.B)
    }
  }

  it should "serialize overlapping writes and reads until D acceptance, not completion consumption" in {
    test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
      initialize(dut)
      val writes = Seq(Access(50, 0x4020), Access(51, 0x4020))
      enqueue(dut, writes.head)
      val first = acceptA(dut)
      enqueue(dut, writes(1))
      noA(dut, 6)
      respond(dut, first)
      val second = acceptA(dut)
      dut.io.completion.valid.expect(true.B) // First completion is still buffered.
      val read = Access(52, 0x4020, write = false)
      enqueue(dut, read)
      noA(dut, 6)
      respond(dut, second)
      val get = acceptA(dut)
      assert(get.opcode == TLMessages.Get.litValue)
      respond(dut, get, write = false)
      (writes :+ read).foreach(consumeCompletion(dut, _))
      dut.io.active.expect(false.B)
    }
  }

  it should "hold the final D response and its sector ownership while the completion FIFO is full" in {
    test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
      initialize(dut)
      val buffered = (0 until 8).map(i => Access(60 + i, 0x5000 + i * 32))
      buffered.foreach { access =>
        enqueue(dut, access)
        respond(dut, acceptA(dut))
      }
      dut.io.inflightAccessCount.expect(0.U)
      dut.io.completion.valid.expect(true.B)
      val held = Access(68, 0x6000)
      enqueue(dut, held)
      val packet = acceptA(dut)
      val overlapping = Access(69, 0x6000, write = false)
      enqueue(dut, overlapping)
      driveResponse(dut, packet)
      for (_ <- 0 until 7) {
        dut.tl.d.ready.expect(false.B)
        dut.io.inflightAccessCount.expect(1.U)
        dut.io.completion.bits.id.expect(buffered.head.id.U)
        dut.tl.a.valid.expect(false.B)
        dut.clock.step()
      }
      consumeCompletion(dut, buffered.head)
      waitFor(dut, dut.tl.d.ready.peek().litToBoolean)
      dut.clock.step()
      dut.tl.d.valid.poke(false.B)
      val get = acceptA(dut)
      // Drain enough queued results before returning this last request.
      buffered.tail.foreach(consumeCompletion(dut, _))
      consumeCompletion(dut, held)
      respond(dut, get, write = false)
      consumeCompletion(dut, overlapping)
      dut.io.active.expect(false.B)
    }
  }

  it should "retain legacy default 64-byte alignment and two-beat Put/Get behavior" in {
    test(LazyModule(new TrafficGenPutGetHarness(transferBytes = 64)).module) { dut =>
      initialize(dut)
      val write = Access(80, 0x7060)
      enqueue(dut, write)
      val first = acceptA(dut)
      assert(first.size == 6 && first.address == 0x7040)
      assert(first.mask == BigInt("ffffffff", 16) && first.data == expectedData(write, 0))
      dut.tl.a.valid.expect(true.B)
      val second = peekPacket(dut)
      assert(second.source == first.source && second.address == first.address && second.size == 6)
      assert(second.data == expectedData(write, 1))
      dut.clock.step(3)
      assert(peekPacket(dut) == second)
      acceptA(dut)
      noA(dut, 2)
      respond(dut, first)
      consumeCompletion(dut, write)

      val read = Access(81, 0x7080, write = false)
      enqueue(dut, read)
      val get = acceptA(dut)
      assert(get.opcode == TLMessages.Get.litValue && get.size == 6)
      respond(dut, get, write = false)
      dut.io.inflightAccessCount.expect(1.U)
      dut.io.completion.valid.expect(false.B)
      dut.clock.step(3)
      respond(dut, get, write = false)
      consumeCompletion(dut, read)
      dut.io.active.expect(false.B)
    }
  }

  it should "finish an already-started legacy Put after an early AccessAck" in {
    test(LazyModule(new TrafficGenPutGetHarness(transferBytes = 64)).module) { dut =>
      initialize(dut)
      val write = Access(90, 0x8000)
      enqueue(dut, write)
      val first = acceptA(dut)
      val lastBeat = peekPacket(dut)
      // A manager may acknowledge a multibeat Put before its final data beat.
      respond(dut, first)
      consumeCompletion(dut, write)
      val next = Access(91, 0x8100)
      enqueue(dut, next)
      for (_ <- 0 until 4) {
        dut.tl.a.valid.expect(true.B)
        assert(peekPacket(dut) == lastBeat)
        dut.clock.step()
      }
      acceptA(dut)
      val nextFirst = acceptA(dut)
      assert(nextFirst.address == next.address && nextFirst.data == expectedData(next, 0))
      acceptA(dut)
      respond(dut, nextFirst)
      consumeCompletion(dut, next)
      dut.io.active.expect(false.B)
    }
  }

  for (fault <- Seq("opcode", "size", "denied", "corrupt")) {
    it should s"reject a D response with invalid $fault instead of completing its request" in {
      assertThrows[ChiselAssertionError] {
        test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
          initialize(dut)
          enqueue(dut, Access(100, 0x9000))
          val packet = acceptA(dut)
          driveResponse(dut, packet)
          fault match {
            case "opcode" => dut.tl.d.bits.opcode.poke(TLMessages.AccessAckData)
            case "size" => dut.tl.d.bits.size.poke(6.U)
            case "denied" => dut.tl.d.bits.denied.poke(true.B)
            case "corrupt" => dut.tl.d.bits.corrupt.poke(true.B)
          }
          dut.io.completion.valid.expect(false.B)
          dut.clock.step()
        }
      }
    }
  }

  it should "reject a misaligned sector address rather than silently writing the preceding bytes" in {
    assertThrows[ChiselAssertionError] {
      test(LazyModule(new TrafficGenPutGetHarness()).module) { dut =>
        initialize(dut)
        enqueue(dut, Access(110, 0xa010))
        dut.clock.step(3)
      }
    }
  }
}
