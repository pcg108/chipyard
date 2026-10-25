package chipyard.example

import chisel3._
import chisel3.experimental.UnlocatableSourceInfo
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import sifive.blocks.inclusivecache._

/** Drive actual MSHR plans/response handshakes, independently of counter equations. */
class L2PerfMSHRSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "L2 execution-plan performance taps"

  private def cacheParams(implicit p: Parameters): InclusiveCacheParameters = {
    val line = TransferSizes(128, 128)
    val access = TransferSizes(1, 128)
    val innerClients = TLClientPortParameters(Seq(
      TLClientParameters(name="Core 0 DCache", sourceId=IdRange(0,1), supportsProbe=line),
      TLClientParameters(name="trafficgenmem0", sourceId=IdRange(1,2))))
    def managers(region: RegionType.T) = TLManagerPortParameters(Seq(TLManagerParameters(
      address=Seq(AddressSet(0,0xffff)), regionType=region,
      supportsAcquireB=line, supportsAcquireT=line, supportsGet=access,
      supportsPutFull=access, supportsPutPartial=access, supportsHint=access,
      supportsArithmetic=TransferSizes(1,32), supportsLogical=TransferSizes(1,32),
      mayDenyGet=true, mayDenyPut=true)), beatBytes=32, endSinkId=8)
    val outerClients = TLClientPortParameters(Seq(TLClientParameters(
      name="L2", sourceId=IdRange(0,8), supportsProbe=line)))
    InclusiveCacheParameters(
      CacheParameters(level=2, ways=2, sets=4, blockBytes=128, beatBytes=32, hintsSkipProbe=false),
      InclusiveCacheMicroParameters(writeBytes=8, memCycles=8), control=true,
      new TLEdgeIn(innerClients, managers(RegionType.CACHED), p, UnlocatableSourceInfo),
      new TLEdgeOut(outerClients, managers(RegionType.UNCACHED), p, UnlocatableSourceInfo))
  }

  it should "classify bypasses and upgrades, distinguish probes, and sample dirty victims after probe data" in {
    implicit val p: Parameters = Parameters.empty.alterPartial { case L2PerfEnabled => true }
    test(new MSHR(cacheParams)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val perf = d.io.perf.get
      def initialize(): Unit = {
        d.io.allocate.valid.poke(false.B)
        d.io.allocate.bits.prio.zipWithIndex.foreach { case (b,i) => b.poke((i==0).B) }
        d.io.allocate.bits.control.poke(false.B); d.io.allocate.bits.opcode.poke(4.U)
        d.io.allocate.bits.param.poke(0.U); d.io.allocate.bits.size.poke(5.U)
        d.io.allocate.bits.source.poke(1.U); d.io.allocate.bits.tag.poke(2.U)
        d.io.allocate.bits.offset.poke(0.U); d.io.allocate.bits.put.poke(0.U)
        d.io.allocate.bits.set.poke(0.U); d.io.allocate.bits.repeat.poke(false.B)
        d.io.directory.valid.poke(false.B)
        d.io.directory.bits.dirty.poke(false.B); d.io.directory.bits.state.poke(0.U)
        d.io.directory.bits.clients.poke(0.U); d.io.directory.bits.tag.poke(0.U)
        d.io.directory.bits.hit.poke(false.B); d.io.directory.bits.way.poke(0.U)
        d.io.schedule.ready.poke(false.B)
        d.io.sinkc.valid.poke(false.B); d.io.sinkc.bits.last.poke(true.B)
        d.io.sinkc.bits.set.poke(0.U); d.io.sinkc.bits.tag.poke(1.U)
        d.io.sinkc.bits.source.poke(0.U); d.io.sinkc.bits.param.poke(1.U)
        d.io.sinkc.bits.data.poke(false.B)
        d.io.sinkd.valid.poke(false.B); d.io.sinkd.bits.last.poke(true.B)
        d.io.sinkd.bits.opcode.poke(5.U); d.io.sinkd.bits.param.poke(1.U)
        d.io.sinkd.bits.source.poke(0.U); d.io.sinkd.bits.sink.poke(0.U)
        d.io.sinkd.bits.denied.poke(false.B)
        d.io.sinke.valid.poke(false.B); d.io.sinke.bits.sink.poke(0.U)
        d.io.nestedwb.set.poke(0.U); d.io.nestedwb.tag.poke(0.U)
        d.io.nestedwb.b_toN.poke(false.B); d.io.nestedwb.b_toB.poke(false.B)
        d.io.nestedwb.b_clr_dirty.poke(false.B); d.io.nestedwb.c_set_dirty.poke(false.B)
      }
      def reset(): Unit = {
        initialize(); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step()
        perf.classified.expect(false.B); perf.cleanReplacement.expect(false.B)
        perf.dirtyReplacement.expect(false.B); perf.flush.expect(false.B)
      }
      def allocate(opcode:Int=4, tag:Int=2, control:Boolean=false, source:Int=1): Unit = {
        d.io.allocate.bits.opcode.poke(opcode.U); d.io.allocate.bits.tag.poke(tag.U)
        d.io.allocate.bits.control.poke(control.B); d.io.allocate.bits.source.poke(source.U)
        d.io.allocate.valid.poke(true.B); d.clock.step(); d.io.allocate.valid.poke(false.B)
        // An allocation alone does not classify: metadata has not yet returned.
        perf.classified.expect(false.B)
      }
      def directory(hit:Boolean, state:Int, clients:Int=0, dirty:Boolean=false, tag:Int=1): Unit = {
        d.io.directory.bits.hit.poke(hit.B); d.io.directory.bits.state.poke(state.U)
        d.io.directory.bits.clients.poke(clients.U); d.io.directory.bits.dirty.poke(dirty.B)
        d.io.directory.bits.tag.poke(tag.U); d.io.directory.valid.poke(true.B)
      }
      def commitPlan(): Unit = { d.clock.step(); d.io.directory.valid.poke(false.B); perf.classified.expect(false.B) }
      def scheduleOnce(): Unit = {
        d.io.schedule.valid.expect(true.B); d.io.schedule.ready.poke(true.B); d.clock.step()
        d.io.schedule.ready.poke(false.B)
      }
      def grant(denied:Boolean=false): Unit = {
        d.io.sinkd.bits.denied.poke(denied.B); d.io.sinkd.valid.poke(true.B)
        d.clock.step(); d.io.sinkd.valid.poke(false.B)
      }

      // One cold Get miss, then a same-tag queued Get whose plan bypasses Directory.
      reset(); allocate(); directory(hit=false,state=0)
      perf.classified.expect(true.B); perf.hit.expect(false.B); perf.upgrade.expect(false.B)
      perf.hitProbe.expect(false.B); perf.cleanReplacement.expect(false.B); commitPlan()
      perf.refillWait.expect(true.B); d.io.schedule.bits.a.valid.expect(true.B)
      scheduleOnce(); grant(); perf.refillWait.expect(false.B)
      d.io.schedule.bits.d.valid.expect(true.B); d.io.schedule.bits.reload.expect(true.B)
      d.io.schedule.ready.poke(true.B)
      d.io.allocate.valid.poke(true.B); d.io.allocate.bits.repeat.poke(true.B)
      d.io.allocate.bits.source.poke(0.U)
      d.io.directory.valid.expect(false.B)
      perf.classified.expect(true.B); perf.hit.expect(true.B); perf.source.expect(0.U)
      perf.upgrade.expect(false.B); perf.hitProbe.expect(false.B)
      d.clock.step(); d.io.allocate.valid.poke(false.B); d.io.allocate.bits.repeat.poke(false.B)
      perf.classified.expect(false.B); d.io.schedule.bits.d.valid.expect(true.B)
      d.clock.step(); d.io.schedule.ready.poke(false.B); d.io.status.valid.expect(false.B)

      // A BRANCH tag hit needing write permission is a hit plus an upgrade.
      reset(); allocate(opcode=0); directory(hit=true,state=1,tag=2)
      perf.classified.expect(true.B); perf.hit.expect(true.B); perf.upgrade.expect(true.B)
      perf.hitProbe.expect(false.B); commitPlan()
      d.io.schedule.bits.a.valid.expect(true.B); d.io.schedule.bits.a.bits.param.expect(2.U) // BtoT
      perf.refillWait.expect(true.B)

      // A non-owning Get hits a line held TRUNK by the CPU and requires a child probe.
      reset(); allocate(); directory(hit=true,state=2,clients=1,tag=2)
      perf.hit.expect(true.B); perf.hitProbe.expect(true.B); perf.upgrade.expect(false.B); commitPlan()
      perf.probeWait.expect(true.B); d.io.schedule.bits.b.valid.expect(true.B)
      d.io.schedule.bits.b.bits.param.expect(1.U) // toB
      scheduleOnce()
      d.io.sinkc.bits.tag.poke(2.U); d.io.sinkc.bits.param.poke(0.U) // TtoB
      d.io.sinkc.valid.poke(true.B); d.clock.step(); d.io.sinkc.valid.poke(false.B)
      perf.probeWait.expect(false.B); perf.cleanReplacement.expect(false.B); perf.dirtyReplacement.expect(false.B)
      d.io.schedule.bits.d.valid.expect(true.B)

      // A clean directory victim becomes dirty only when the child returns data.
      // The eviction event must use that later state, even with a multibeat response.
      reset(); allocate(); directory(hit=false,state=2,clients=1,dirty=false)
      perf.classified.expect(true.B); perf.hit.expect(false.B)
      perf.cleanReplacement.expect(false.B); perf.dirtyReplacement.expect(false.B); commitPlan()
      d.io.schedule.bits.b.valid.expect(true.B); scheduleOnce()
      d.io.schedule.bits.c.valid.expect(false.B)
      d.io.sinkc.bits.data.poke(true.B); d.io.sinkc.bits.last.poke(false.B)
      d.io.sinkc.valid.poke(true.B); d.clock.step(); d.io.sinkc.valid.poke(false.B)
      d.io.schedule.bits.c.valid.expect(true.B); d.io.schedule.bits.c.bits.opcode.expect(7.U) // ReleaseData
      perf.cleanReplacement.expect(false.B); perf.dirtyReplacement.expect(false.B) // not yet scheduled
      d.io.schedule.ready.poke(true.B)
      perf.dirtyReplacement.expect(true.B); perf.cleanReplacement.expect(false.B); perf.flush.expect(false.B)
      d.clock.step(); d.io.schedule.ready.poke(false.B)
      perf.dirtyReplacement.expect(false.B) // exactly one eviction event
      perf.probeWait.expect(true.B)
      d.io.sinkc.bits.last.poke(true.B); d.io.sinkc.valid.poke(true.B); d.clock.step()
      d.io.sinkc.valid.poke(false.B); perf.probeWait.expect(false.B)

      // A genuinely clean valid victim has a clean replacement, with no dirty count.
      reset(); allocate(); directory(hit=false,state=3,clients=0,dirty=false); commitPlan()
      d.io.schedule.bits.c.bits.opcode.expect(6.U) // Release
      d.io.schedule.ready.poke(true.B)
      perf.cleanReplacement.expect(true.B); perf.dirtyReplacement.expect(false.B); perf.flush.expect(false.B)
      d.clock.step(); perf.cleanReplacement.expect(false.B)

      // Flushing a dirty valid line is not a demand miss or a replacement.
      reset(); allocate(tag=1,control=true); directory(hit=true,state=3,dirty=true,tag=1)
      perf.classified.expect(false.B); commitPlan(); d.io.schedule.ready.poke(true.B)
      perf.flush.expect(true.B); perf.cleanReplacement.expect(false.B); perf.dirtyReplacement.expect(false.B)
      d.clock.step(); perf.flush.expect(false.B)
      reset(); allocate(tag=1,control=true); directory(hit=false,state=0)
      perf.classified.expect(false.B); commitPlan(); d.io.schedule.ready.poke(true.B)
      perf.flush.expect(false.B); d.io.schedule.bits.x.valid.expect(true.B)

      // Hint plans and incoming coherence releases never enter demand outcomes.
      reset(); allocate(opcode=5); directory(hit=true,state=1,tag=2)
      perf.classified.expect(false.B); perf.upgrade.expect(false.B); perf.hitProbe.expect(false.B); commitPlan()
      reset(); d.io.allocate.bits.prio(0).poke(false.B); d.io.allocate.bits.prio(2).poke(true.B)
      d.io.allocate.bits.param.poke(2.U); allocate(opcode=6,tag=1,source=0)
      directory(hit=true,state=1,clients=1,tag=1)
      perf.classified.expect(false.B); perf.upgrade.expect(false.B); perf.hitProbe.expect(false.B); commitPlan()

      // Denied memory responses retain the original miss classification and mark the
      // actual demand response bad; no second classification is produced on error.
      reset(); allocate(); directory(hit=false,state=0)
      perf.classified.expect(true.B); perf.hit.expect(false.B); commitPlan(); scheduleOnce(); grant(denied=true)
      perf.classified.expect(false.B); d.io.schedule.bits.d.valid.expect(true.B)
      d.io.schedule.bits.d.bits.bad.expect(true.B)
    }
  }

  it should "classify explicit client names without confusing vector or unknown clients" in {
    Seq("Core 0 DCache","Core 1 DCache MMIO","Core 2 MMIO","Core 3 ICache").foreach { n =>
      assert(L2PerfEvents.requester(n)=="rocket")
    }
    assert(L2PerfEvents.requester("Core 0 Vector Load")=="saturn")
    assert(L2PerfEvents.requester("stream-reader")=="gemmini_int8")
    assert(L2PerfEvents.requester("stream-writer")=="gemmini_int8")
    assert(L2PerfEvents.requester("trafficgenmem15")=="trafficgen")
    assert(L2PerfEvents.requester("serial_tl_0_0")=="other")
    assert(L2PerfEvents.requester("unrecognized-client")=="other")
  }
}
