package chipyard.example

import chisel3._
import chisel3.util._
import chiseltest._
import firechip.bridgeinterfaces.RTLL2Access
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import sifive.blocks.inclusivecache._

import scala.collection.mutable.ArrayBuffer

private class PutGetL2AObservation extends Bundle {
  val fire = Bool()
  val opcode = UInt(3.W)
  val size = UInt(4.W)
  val address = UInt(64.W)
  val data = UInt(256.W)
  val mask = UInt(32.W)
  val beat = UInt(4.W)
}

private class PutGetL2DObservation extends Bundle {
  val fire = Bool()
  val opcode = UInt(3.W)
  val size = UInt(4.W)
  val data = UInt(256.W)
}

/** Exercise the production adapter and cache, with passive observations at the
  * cache input and below its CacheCork. A quiescent coherent client keeps the
  * cache's firstLevel=false elaboration path, as in the Rocket system.
  */
private class TrafficGenPutGetL2Harness(implicit p: Parameters) extends LazyModule {
  val traffic = LazyModule(new TrafficGenMem(0, 32,
    TrafficGenParams(memOutstanding = 8), transferBytes = 32,
    serializeSameTransfer = true, requireAligned = true))
  val clockSource = ClockSourceNode(Seq(ClockSourceParameters()))
  traffic.clockNode := clockSource

  val coherentClient = TLClientNode(Seq(TLClientPortParameters(Seq(
    TLClientParameters(name = "quiescent-coherent-client", sourceId = IdRange(0, 1),
      supportsProbe = TransferSizes(128, 128))))))
  val xbar = LazyModule(new TLXbar)
  val cacheInput = TLIdentityNode()
  val cacheOutput = TLIdentityNode()
  val memoryInput = TLIdentityNode()
  // Small capacity and resource counts make eviction practical in a unit test.
  // memCycles=8 gives two normal outer sources plus two reserved MSHRs. The
  // production Scheduler therefore warns that its 1-bit SinkD source indexes
  // a four-entry status vector; the reserved entries are not refill sources.
  // The deployed target retains its original full-size cache configuration.
  val cache = LazyModule(new InclusiveCache(
    CacheParameters(level = 2, ways = 2, sets = 2, blockBytes = 128,
      beatBytes = 32, hintsSkipProbe = false),
    InclusiveCacheMicroParameters(writeBytes = 8, memCycles = 8)))
  val cork = LazyModule(new TLCacheCork)
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xffff), beatBytes = 32))

  xbar.node := traffic.node
  xbar.node := coherentClient
  cache.node := cacheInput := xbar.node
  cork.node := cacheOutput := cache.node
  ram.node := TLFragmenter(32, 128) := memoryInput := cork.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val req = Flipped(Decoupled(new RTLL2Access))
      val completion = Decoupled(new RTLL2Access)
      val generatorA = Output(new PutGetL2AObservation)
      val generatorD = Output(new PutGetL2DObservation)
      val outerA = Output(new PutGetL2AObservation)
      val outerD = Output(new PutGetL2DObservation)
      val memoryA = Output(new PutGetL2AObservation)
      val generatorC = Output(Bool())
      val generatorE = Output(Bool())
      val outerC = Output(Bool())
    })

    clockSource.out.foreach { case (bundle, _) =>
      bundle.clock := clock
      bundle.reset := reset
    }
    traffic.module.io.coreOffset := 0.U
    traffic.module.io.req <> io.req
    io.completion <> traffic.module.io.completion

    val (quiet, _) = coherentClient.out.head
    quiet.a.valid := false.B
    quiet.a.bits := 0.U.asTypeOf(quiet.a.bits)
    quiet.b.ready := false.B
    quiet.c.valid := false.B
    quiet.c.bits := 0.U.asTypeOf(quiet.c.bits)
    quiet.d.ready := true.B
    quiet.e.valid := false.B
    quiet.e.bits := 0.U.asTypeOf(quiet.e.bits)
    assert(!quiet.b.valid, "A client which never acquired a line must not be probed")
    assert(!quiet.d.valid, "The quiescent client must not receive responses")

    def observeA(observation: PutGetL2AObservation, bus: TLBundle, edge: TLEdgeIn): Unit = {
      observation.fire := bus.a.fire
      observation.opcode := bus.a.bits.opcode
      observation.size := bus.a.bits.size
      observation.address := bus.a.bits.address
      observation.data := bus.a.bits.data
      observation.mask := bus.a.bits.mask
      observation.beat := edge.count(bus.a)._4
    }
    def observeD(observation: PutGetL2DObservation, bus: TLBundle): Unit = {
      observation.fire := bus.d.fire
      observation.opcode := bus.d.bits.opcode
      observation.size := bus.d.bits.size
      observation.data := bus.d.bits.data
    }
    val (inner, innerEdge) = cacheInput.in.head
    val (outer, outerEdge) = cacheOutput.in.head
    val (memory, memoryEdge) = memoryInput.in.head
    observeA(io.generatorA, inner, innerEdge)
    observeD(io.generatorD, inner)
    observeA(io.outerA, outer, outerEdge)
    observeD(io.outerD, outer)
    observeA(io.memoryA, memory, memoryEdge)
    io.generatorC := inner.c.fire
    io.generatorE := inner.e.fire
    io.outerC := outer.c.fire
  }
}

class TrafficGenPutGetL2Spec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGen 32-byte Put/Get through inclusive L2"

  private case class AEvent(opcode: Int, size: Int, address: BigInt,
    data: BigInt, mask: BigInt, beat: Int)
  private case class DEvent(opcode: Int, size: Int, data: BigInt)

  it should "allocate a cold Put, retain dirty sectors for hits, and write them back on eviction" in {
    implicit val p: Parameters = Parameters.empty
    test(LazyModule(new TrafficGenPutGetL2Harness).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.req.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.req.bits.id.poke(0.U)
      dut.io.req.bits.address.poke(0.U)
      dut.io.req.bits.cycleCount.poke(0.U)
      dut.io.req.bits.mBundleId.poke(0.U)
      dut.io.req.bits.mWakeRelevantBundle.poke(false.B)
      dut.io.req.bits.mIsWrite.poke(false.B)
      dut.io.req.bits.mWarpBlocked.poke(false.B)
      dut.io.req.bits.bundleIssueCount.poke(1.U)
      dut.io.req.bits.bundleGeneration.poke(1.U)
      dut.io.req.bits.launchId.poke(23.U)
      dut.clock.step(16) // Allow the real directory to finish its reset wipe.

      val generatorA = ArrayBuffer.empty[AEvent]
      val generatorD = ArrayBuffer.empty[DEvent]
      val outerA = ArrayBuffer.empty[AEvent]
      val outerD = ArrayBuffer.empty[DEvent]
      val memoryA = ArrayBuffer.empty[AEvent]
      val completions = ArrayBuffer.empty[BigInt]
      var outerReleaseBeats = 0

      def sampleA(port: PutGetL2AObservation, events: ArrayBuffer[AEvent]): Unit = {
        if (port.fire.peek().litToBoolean) {
          events += AEvent(port.opcode.peek().litValue.toInt, port.size.peek().litValue.toInt,
            port.address.peek().litValue, port.data.peek().litValue,
            port.mask.peek().litValue, port.beat.peek().litValue.toInt)
        }
      }
      def sampleD(port: PutGetL2DObservation, events: ArrayBuffer[DEvent]): Unit = {
        if (port.fire.peek().litToBoolean) {
          events += DEvent(port.opcode.peek().litValue.toInt,
            port.size.peek().litValue.toInt, port.data.peek().litValue)
        }
      }
      def step(): Unit = {
        sampleA(dut.io.generatorA, generatorA)
        sampleD(dut.io.generatorD, generatorD)
        sampleA(dut.io.outerA, outerA)
        sampleD(dut.io.outerD, outerD)
        sampleA(dut.io.memoryA, memoryA)
        dut.io.generatorC.expect(false.B)
        dut.io.generatorE.expect(false.B)
        if (dut.io.outerC.peek().litToBoolean) outerReleaseBeats += 1
        if (dut.io.completion.valid.peek().litToBoolean) {
          val access = dut.io.completion.bits
          completions += access.id.peek().litValue
          access.mBundleId.expect(17.U)
          access.bundleGeneration.expect(3.U)
          access.launchId.expect(23.U)
        }
        dut.clock.step()
      }
      def idle(cycles: Int): Unit = (0 until cycles).foreach(_ => step())
      var nextId = 100
      def transact(address: BigInt, write: Boolean): DEvent = {
        val id = nextId
        nextId += 1
        val previousResponses = generatorD.size
        dut.io.req.bits.id.poke(id.U)
        dut.io.req.bits.address.poke(address.U)
        dut.io.req.bits.mBundleId.poke(17.U)
        dut.io.req.bits.bundleGeneration.poke(3.U)
        dut.io.req.bits.launchId.poke(23.U)
        dut.io.req.bits.mIsWrite.poke(write.B)
        dut.io.req.valid.poke(true.B)
        var waited = 0
        while (!dut.io.req.ready.peek().litToBoolean && waited < 2000) {
          step()
          waited += 1
        }
        assert(waited < 2000, "Adapter did not accept the test request")
        step()
        dut.io.req.valid.poke(false.B)
        waited = 0
        while (!completions.contains(BigInt(id)) && waited < 2000) {
          step()
          waited += 1
        }
        assert(waited < 2000, s"Request $id did not complete")
        assert(generatorD.size == previousResponses + 1,
          "A 32-byte request must return exactly one D response beat")
        val result = generatorD.last
        assert(result.opcode == (if (write) 0 else 1)) // AccessAck / AccessAckData
        assert(result.size == 5)
        idle(16) // Drain directory writes before checking for unintended outer traffic.
        result
      }

      val targetLine = BigInt(0x400)
      transact(targetLine, write = true)
      assert(generatorA.size == 1)
      assert(generatorA.head.opcode == 0 && generatorA.head.size == 5)
      assert(generatorA.head.address == targetLine)
      assert(generatorA.head.mask == BigInt("ffffffff", 16))
      assert(outerA.size == 1 && outerA.head.opcode == 6 && outerA.head.size == 7,
        "A cold sector Put must acquire the entire 128-byte line inside L2")
      assert(memoryA.size == 1 && memoryA.head.opcode == 4 && memoryA.head.size == 7,
        "The cache's cold Put refill must become a full-line Get below CacheCork")
      assert(memoryA.head.address == targetLine)
      assert(outerD.count(e => e.opcode == 5 && e.size == 7) == 4,
        "The cold Put must receive four GrantData beats from the backing path")
      assert(outerReleaseBeats == 0)

      val outerAfterCold = (outerA.size, memoryA.size, outerReleaseBeats)
      (1 until 4).foreach(sector => transact(targetLine + sector * 32, write = true))
      val sectorData = generatorA.take(4).map(_.data).toVector
      assert(sectorData.distinct.size == 4, "Use distinct data in all four sectors")
      assert(generatorA.take(4).map(_.address).toVector ==
        (0 until 4).map(sector => targetLine + sector * 32).toVector)
      assert(generatorA.take(4).forall(e => e.opcode == 0 && e.size == 5 &&
        e.mask == BigInt("ffffffff", 16)))
      (0 until 4).foreach { sector =>
        assert(transact(targetLine + sector * 32, write = false).data == sectorData(sector),
          s"Read hit did not return the written data for sector $sector")
      }
      assert((outerA.size, memoryA.size, outerReleaseBeats) == outerAfterCold,
        "Write and read hits must use retained L2 data without backing traffic")

      // Two sets give a 256-byte conflict stride. Replacement is the production
      // LFSR policy, so request conflicts until the dirty target is observed,
      // rather than assuming that a particular way is evicted first.
      def targetWriteback: Seq[AEvent] = memoryA.filter(e =>
        e.opcode == 0 && e.address == targetLine).toVector
      var conflict = 1
      while (targetWriteback.isEmpty && conflict <= 128) {
        transact(targetLine + conflict * 256, write = false)
        conflict += 1
      }
      assert(targetWriteback.size == 4, "The dirty target must evict as four data beats")
      assert(targetWriteback.forall(_.size == 7), "Dirty eviction must cover 128 bytes")
      assert(targetWriteback.map(_.beat) == Seq(0, 1, 2, 3))
      assert(targetWriteback.map(_.data) == sectorData,
        "L2 eviction must write all four independently updated sectors to backing RAM")
      assert(outerReleaseBeats >= 4, "The writeback must originate on L2's outer C channel")

      val refillsBeforeReload = memoryA.count(_.opcode == 4)
      (0 until 4).foreach { sector =>
        assert(transact(targetLine + sector * 32, write = false).data == sectorData(sector),
          s"Reloaded backing data does not preserve sector $sector")
      }
      assert(memoryA.count(_.opcode == 4) == refillsBeforeReload + 1,
        "Reload after eviction must fetch exactly one full line, then hit on the remaining sectors")
      assert(completions.distinct.size == completions.size)
      assert(completions.size == nextId - 100)
    }
  }
}
