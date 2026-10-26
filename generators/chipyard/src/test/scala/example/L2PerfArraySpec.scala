package chipyard.example

import chisel3._
import chisel3.util._
import chisel3.experimental.UnlocatableSourceInfo
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import sifive.blocks.inclusivecache._

/** Test seam around the real scheduler's data-array clients and arbitration.
  * Production event assignments, SourceD/SinkD/SourceC and BankedStore are intact.
  * Control-plane MSHR allocation is disabled because the test supplies the
  * scheduled microoperations directly. A test-only highest-priority reservation
  * occupies SRAM slice zero without modifying its contents. Counter expectations
  * come from this independently controlled resource occupancy and port progress.
  */
private class L2PerfArrayHarness(params: InclusiveCacheParameters)
    extends InclusiveCacheBankScheduler(params) {
  val dRequest = IO(Flipped(Decoupled(new SourceDRequest(params))))
  val cRequest = IO(Flipped(Decoupled(new SourceCRequest(params))))
  val reserveSliceZero = IO(Input(Bool()))
  val reservationAccepted = IO(Output(Bool()))
  val arrayValid = IO(Output(Vec(5, Bool())))
  val arrayReady = IO(Output(Vec(5, Bool())))
  val arrayNoop = IO(Output(Vec(5, Bool())))
  val arrayBeat = IO(Output(Vec(5, UInt(params.innerBeatBits.W))))
  val writeIsDemand = IO(Output(Bool()))
  val putList = IO(Output(UInt(params.putBits.W)))
  val releaseList = IO(Output(UInt(params.putBits.W)))

  // Replace only the test stimulus at legal component input boundaries.
  request.valid := false.B
  mshrs.foreach { m =>
    m.io.allocate.valid := false.B
    m.io.directory.valid := false.B
    m.io.sinkc.valid := false.B
    m.io.sinkd.valid := false.B
    m.io.sinke.valid := false.B
  }
  sinkA.io.req.ready := true.B
  sinkC.io.req.ready := true.B
  sourceD.io.req.valid := dRequest.valid
  sourceD.io.req.bits := dRequest.bits
  dRequest.ready := sourceD.io.req.ready
  sourceC.io.req.valid := cRequest.valid
  sourceC.io.req.bits := cRequest.bits
  cRequest.ready := sourceC.io.req.ready
  sourceC.io.evict_safe := true.B
  sinkD.io.grant_safe := true.B
  sinkD.io.way := 0.U
  sinkD.io.set := 0.U
  sinkC.io.way := 0.U

  putList := RegEnable(sinkA.io.req.bits.put, 0.U, sinkA.io.req.fire)
  releaseList := RegEnable(sinkC.io.req.bits.put, 0.U, sinkC.io.req.fire)
  // Real arbitration: the lowest numbered SRAM slice has no capacity left for
  // lower-priority ports. The reservation is a noop, so it cannot alter data.
  bankedStore.io.sinkC_adr.valid := sinkC.io.bs_adr.valid || reserveSliceZero
  when(reserveSliceZero) {
    bankedStore.io.sinkC_adr.bits.noop := true.B
    bankedStore.io.sinkC_adr.bits.way := 0.U
    bankedStore.io.sinkC_adr.bits.set := 0.U
    bankedStore.io.sinkC_adr.bits.beat := 0.U
    bankedStore.io.sinkC_adr.bits.mask := Fill(params.innerMaskBits, 1.U(1.W))
  }
  reservationAccepted := reserveSliceZero && bankedStore.io.sinkC_adr.fire
  val addresses: Seq[BankedStoreAddress] = Seq(sourceD.io.bs_radr.bits,
    sourceD.io.bs_wadr.bits, sinkD.io.bs_adr.bits, sourceC.io.bs_adr.bits, sinkC.io.bs_adr.bits)
  arrayValid := VecInit(Seq(sourceD.io.bs_radr.valid, sourceD.io.bs_wadr.valid,
    sinkD.io.bs_adr.valid, sourceC.io.bs_adr.valid, sinkC.io.bs_adr.valid))
  arrayReady := VecInit(Seq(sourceD.io.bs_radr.ready, sourceD.io.bs_wadr.ready,
    sinkD.io.bs_adr.ready, sourceC.io.bs_adr.ready, sinkC.io.bs_adr.ready))
  arrayNoop := VecInit(addresses.map(_.noop))
  arrayBeat := VecInit(addresses.map(_.beat))
  writeIsDemand := sourceD.io.perfWriteIsDemand.get
}

class L2PerfArraySpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Production L2 data-array pressure counters"

  private def cacheParams(implicit p: Parameters): InclusiveCacheParameters = {
    val line = TransferSizes(128,128)
    val access = TransferSizes(1,128)
    val inner = TLClientPortParameters(Seq(TLClientParameters(
      name="Core 0 DCache", sourceId=IdRange(0,8), supportsProbe=line)))
    def managers(region:RegionType.T) = TLManagerPortParameters(Seq(TLManagerParameters(
      address=Seq(AddressSet(0,0xffff)), regionType=region,
      supportsAcquireB=line, supportsAcquireT=line, supportsGet=access,
      supportsPutFull=access, supportsPutPartial=access, supportsHint=access,
      supportsArithmetic=TransferSizes(1,32), supportsLogical=TransferSizes(1,32))),
      beatBytes=32,endSinkId=8)
    val outer=TLClientPortParameters(Seq(TLClientParameters(
      name="L2",sourceId=IdRange(0,8),supportsProbe=line)))
    InclusiveCacheParameters(
      CacheParameters(level=2,ways=2,sets=4,blockBytes=128,beatBytes=32,hintsSkipProbe=false),
      InclusiveCacheMicroParameters(writeBytes=8,memCycles=8,portFactor=4), control=true,
      new TLEdgeIn(inner,managers(RegionType.CACHED),p,UnlocatableSourceInfo),
      new TLEdgeOut(outer,managers(RegionType.UNCACHED),p,UnlocatableSourceInfo))
  }

  it should "distinguish all five contended array clients and ignore nonoverlap and noop reservations" in {
    implicit val p:Parameters=Parameters.empty.alterPartial { case L2PerfEnabled => true }
    test(new L2PerfArrayHarness(cacheParams)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(10000)
      val names=Seq("demand_read_array_stall_cycles","demand_write_array_stall_cycles",
        "refill_write_array_stall_cycles","eviction_read_array_stall_cycles","coherence_write_array_stall_cycles")
      var arrayFires=Array.fill(5)(0)
      var eventTotals=Array.fill(5)(BigInt(0))
      var innerResponses=0
      var outerReleases=0
      def zero(x:Data):Unit=x match {
        case b:Bool => b.poke(false.B)
        case u:UInt => u.poke(0.U)
        case v:Vec[_] => v.foreach(zero)
        case r:Record => r.elements.values.foreach(zero)
        case other => throw new IllegalArgumentException(s"Unsupported test input ${other.getClass}")
      }
      def step():Unit={
        for(i<-0 until 5) eventTotals(i)+=d.io.perf.get.events(L2PerfEvents.index(names(i))).peek().litValue
        for(i<-0 until 5)
          if(d.arrayValid(i).peek().litToBoolean && d.arrayReady(i).peek().litToBoolean && !d.arrayNoop(i).peek().litToBoolean)
            arrayFires(i)+=1
        if(d.io.in.d.valid.peek().litToBoolean && d.io.in.d.ready.peek().litToBoolean) innerResponses+=1
        if(d.io.out.c.valid.peek().litToBoolean && d.io.out.c.ready.peek().litToBoolean) outerReleases+=1
        d.clock.step()
      }
      def until(pred: =>Boolean, what:String):Unit={
        var elapsed=0
        while(!pred && elapsed<160){step();elapsed+=1}
        assert(pred,s"Timed out waiting for $what")
      }
      def eventVector(expected:Int):Unit=names.zipWithIndex.foreach { case(name,i) =>
        d.io.perf.get.events(L2PerfEvents.index(name)).expect((if(i==expected)1 else 0).U)
      }
      def initialize():Unit={
        d.reserveSliceZero.poke(false.B)
        d.dRequest.valid.poke(false.B);zero(d.dRequest.bits)
        d.cRequest.valid.poke(false.B);zero(d.cRequest.bits)
        d.io.in.a.valid.poke(false.B);zero(d.io.in.a.bits)
        d.io.in.c.valid.poke(false.B);zero(d.io.in.c.bits)
        d.io.in.e.valid.poke(false.B);zero(d.io.in.e.bits)
        d.io.in.b.ready.poke(true.B);d.io.in.d.ready.poke(true.B)
        d.io.out.a.ready.poke(true.B);d.io.out.c.ready.poke(true.B);d.io.out.e.ready.poke(true.B)
        d.io.out.b.valid.poke(false.B);zero(d.io.out.b.bits)
        d.io.out.d.valid.poke(false.B);zero(d.io.out.d.bits)
        d.io.req.valid.poke(false.B);zero(d.io.req.bits);d.io.resp.ready.poke(true.B)
        d.io.ways.foreach(_.poke(3.U));d.io.divs.foreach(_.poke(0.U))
        d.reset.poke(true.B);d.clock.step(3);d.reset.poke(false.B)
        (0 until 12).foreach(_=>step())
        arrayFires=Array.fill(5)(0);eventTotals=Array.fill(5)(BigInt(0));innerResponses=0;outerReleases=0
      }
      def issueD(opcode:Int,coherence:Boolean=false,size:Int=5,offset:Int=0,put:BigInt=0):Unit={
        d.dRequest.bits.prio.zipWithIndex.foreach{case(b,i)=>b.poke((i==(if(coherence)2 else 0)).B)}
        d.dRequest.bits.opcode.poke(opcode.U);d.dRequest.bits.size.poke(size.U)
        d.dRequest.bits.offset.poke(offset.U);d.dRequest.bits.put.poke(put.U)
        d.dRequest.valid.poke(true.B)
        until(d.dRequest.ready.peek().litToBoolean,"SourceD request acceptance");step()
        d.dRequest.valid.poke(false.B)
      }
      def putBeat():Unit={
        val a=d.io.in.a
        a.bits.opcode.poke(0.U);a.bits.size.poke(5.U);a.bits.mask.poke("hffffffff".U)
        a.bits.data.poke("h1234".U);a.valid.poke(true.B)
        until(a.ready.peek().litToBoolean,"SinkA write beat");step();a.valid.poke(false.B)
        (0 until 3).foreach(_=>step())
      }
      def releaseBeat():Unit={
        val c=d.io.in.c
        c.bits.opcode.poke(7.U);c.bits.param.poke(1.U);c.bits.size.poke(7.U)
        c.bits.data.poke("h4321".U);c.valid.poke(true.B)
        until(c.ready.peek().litToBoolean,"SinkC release data");step();c.valid.poke(false.B)
      }
      def refillBeat(data:Boolean=true):Unit={
        val response=d.io.out.d
        response.bits.opcode.poke((if(data)5 else 4).U)
        response.bits.param.poke(0.U);response.bits.size.poke(7.U)
        response.bits.data.poke("habcd".U);response.valid.poke(true.B)
        until(response.ready.peek().litToBoolean,"outer refill beat");step();response.valid.poke(false.B)
      }
      def sevenBlockedCycles(port:Int,counter:Int,noop:Boolean=false):Unit={
        until(d.arrayValid(port).peek().litToBoolean,"pending SRAM operation")
        d.arrayBeat(port).expect(0.U)
        val before=arrayFires(port)
        for(_<-0 until 7){
          d.reservationAccepted.expect(true.B)
          d.arrayValid(port).expect(true.B);d.arrayReady(port).expect(false.B)
          d.arrayNoop(port).expect(noop.B)
          eventVector(if(noop)-1 else counter)
          step()
        }
        assert(arrayFires(port)==before,"contended operation advanced despite unavailable SRAM slice")
        d.reserveSliceZero.poke(false.B)
        d.arrayValid(port).expect(true.B);d.arrayReady(port).expect(true.B)
        eventVector(-1);step()
        if(!noop) assert(arrayFires(port)==before+1,"unblocked SRAM operation did not complete")
      }

      initialize();d.reserveSliceZero.poke(true.B)
      issueD(opcode=4)
      sevenBlockedCycles(port=0,counter=0)
      until(innerResponses==1,"read response after resource release")

      initialize();putBeat();d.reserveSliceZero.poke(true.B)
      issueD(opcode=0,put=d.putList.peek().litValue)
      until(d.arrayValid(1).peek().litToBoolean,"demand write reaching SRAM")
      d.writeIsDemand.expect(true.B)
      sevenBlockedCycles(port=1,counter=1)
      until(innerResponses==1,"write acknowledgement")

      initialize();d.reserveSliceZero.poke(true.B)
      refillBeat()
      sevenBlockedCycles(port=2,counter=2)
      (0 until 3).foreach(_=>refillBeat())
      until(arrayFires(2)==4,"all refill data written")

      initialize();d.reserveSliceZero.poke(true.B)
      d.cRequest.bits.opcode.poke(7.U);d.cRequest.bits.param.poke(1.U)
      d.cRequest.bits.dirty.poke(true.B);d.cRequest.valid.poke(true.B)
      until(d.cRequest.ready.peek().litToBoolean,"eviction request acceptance");step()
      d.cRequest.valid.poke(false.B)
      sevenBlockedCycles(port=3,counter=3)
      until(outerReleases==4,"four dirty eviction beats")
      assert(arrayFires(3)==4)

      initialize();(0 until 4).foreach(_=>releaseBeat());(0 until 3).foreach(_=>step())
      d.reserveSliceZero.poke(true.B)
      issueD(opcode=7,coherence=true,size=7,put=d.releaseList.peek().litValue)
      until(d.arrayValid(1).peek().litToBoolean,"coherence write reaching SRAM")
      d.writeIsDemand.expect(false.B)
      sevenBlockedCycles(port=1,counter=4)
      until(arrayFires(1)==4 && innerResponses==1,"coherence write retirement and ReleaseAck")

      // A reserved different SRAM slice must not manufacture a stall event.
      initialize();d.reserveSliceZero.poke(true.B)
      issueD(opcode=4,offset=32)
      until(innerResponses==1,"nonoverlapping read while slice zero is reserved")
      assert(arrayFires(0)==1)
      assert(eventTotals.forall(_==0),s"Nonoverlapping request incorrectly reported array pressure: ${eventTotals.toSeq}")
      eventVector(-1)

      // Grant without data reserves ordering at the SRAM interface, but is a noop.
      initialize();d.reserveSliceZero.poke(true.B)
      refillBeat(data=false)
      sevenBlockedCycles(port=2,counter=2,noop=true)
      assert(arrayFires(2)==0,"a data-less Grant must not write SRAM")
      assert(eventTotals.forall(_==0),"A blocked noop must not increment real-access stall counters")
    }
  }
}
