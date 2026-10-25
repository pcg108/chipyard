package chipyard.example

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import sifive.blocks.inclusivecache._
import scala.collection.mutable

/** Controllable backing-memory backpressure, independent of the counters. */
private class L2PerfMemoryGate(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode()
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val blockA = IO(Input(Bool()))
    val blockD = IO(Input(Bool()))
    val (in, _) = node.in.head
    val (out, _) = node.out.head
    out <> in
    out.a.valid := in.a.valid && !blockA
    in.a.ready := out.a.ready && !blockA
    in.d.valid := out.d.valid && !blockD
    out.d.ready := in.d.ready && !blockD
  }
}

private class L2PerfHarness(enabled: Boolean)(implicit p: Parameters) extends LazyModule {
  val names = Seq("Core 0 DCache", "Core 0 Vector Load", "stream-reader", "trafficgenmem0")
  val clients = names.zipWithIndex.map { case (name, i) =>
    TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters(
      name = name, sourceId = IdRange(0, 8),
      supportsProbe = if (i == 0) TransferSizes(128,128) else TransferSizes.none)))))
  }
  val ctrl = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters("test-control", IdRange(0,1))))))
  val xbar = LazyModule(new TLXbar)
  clients.foreach(xbar.node := _)
  val cache = LazyModule(new InclusiveCache(
    CacheParameters(level=2, ways=2, sets=4, blockBytes=128, beatBytes=32, hintsSkipProbe=false),
    InclusiveCacheMicroParameters(writeBytes=8, memCycles=8),
    Some(InclusiveCacheControlParameters(address=0x2010000, beatBytes=8, bankedControl=false))))
  val cacheInput = TLIdentityNode()
  val cacheOutput = TLIdentityNode()
  cache.node := cacheInput := xbar.node
  cache.ctrls.head.ctrlnode := ctrl
  val cork = LazyModule(new TLCacheCork)
  val gate = LazyModule(new L2PerfMemoryGate)
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xffff), beatBytes=32))
  cork.node := cacheOutput := cache.node
  gate.node := cork.node
  ram.node := TLFragmenter(32,128) := gate.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val client = clients.zipWithIndex.map { case (node, i) =>
      val port = IO(Flipped(chiselTypeOf(node.out.head._1))).suggestName(s"client$i")
      node.out.head._1 <> port
      port
    }
    val control = IO(Flipped(chiselTypeOf(ctrl.out.head._1)))
    ctrl.out.head._1 <> control
    val blockMemoryA = IO(Input(Bool()))
    val blockMemoryD = IO(Input(Bool()))
    gate.module.blockA := blockMemoryA
    gate.module.blockD := blockMemoryD
    val perf = IO(Output(new L2PerfPort(1)))
    cache.module.perf match {
      case Some(value) => perf := value
      case None => perf := 0.U.asTypeOf(perf)
    }
    val innerAFirst = IO(Output(Bool()))
    val innerDLast = IO(Output(Bool()))
    val outerAFirst = IO(Output(Bool()))
    val outerDData = IO(Output(Bool()))
    val outerCData = IO(Output(Bool()))
    val outerCFirst = IO(Output(Bool()))
    val (inner, ei) = cacheInput.in.head
    val (outer, eo) = cacheOutput.out.head
    innerAFirst := inner.a.fire && ei.first(inner.a)
    innerDLast := inner.d.fire && ei.last(inner.d) && inner.d.bits.opcode =/= TLMessages.ReleaseAck
    outerAFirst := outer.a.fire && eo.first(outer.a)
    outerDData := outer.d.fire && eo.hasData(outer.d.bits)
    outerCData := outer.c.fire && eo.hasData(outer.c.bits)
    outerCFirst := outer.c.fire && eo.first(outer.c) && outer.c.bits.opcode === TLMessages.ReleaseData
  }
}

class L2PerfSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Passive inclusive-cache performance instrumentation"

  private def runScenario(enabled: Boolean): Vector[(Long, Int, BigInt, BigInt)] = {
    implicit val p: Parameters = Parameters.empty.alterPartial { case L2PerfEnabled => enabled }
    val transcript = mutable.ArrayBuffer.empty[(Long,Int,BigInt,BigInt)]
    var functionalTranscript = Vector.empty[(Long,Int,BigInt,BigInt)]
    test(LazyModule(new L2PerfHarness(enabled)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(20000)
      var cycle = 0L
      val counts = mutable.Map.empty[String,BigInt].withDefaultValue(BigInt(0))
      val gauges = mutable.Map.empty[String,BigInt].withDefaultValue(BigInt(0))
      var independentAccepted=0L; var independentCompleted=0L
      var independentRefills=0L; var independentRefillBeats=0L
      var independentWritebacks=0L; var independentWritebackBeats=0L
      val responses = mutable.ArrayBuffer.empty[(Int,Int,Int,BigInt)]
      val probePending = mutable.Queue.empty[(Int,BigInt)]
      var coherentDirty = false
      var coherentLine = BigInt(0)
      var cBeat = 0
      var probeActive: Option[(Int,BigInt)] = None
      val dirtyData=BigInt("123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",16)
      def init(b: TLBundle): Unit = {
        b.a.valid.poke(false.B); b.a.bits.opcode.poke(4.U); b.a.bits.param.poke(0.U)
        b.a.bits.size.poke(3.U); b.a.bits.source.poke(0.U); b.a.bits.address.poke(0.U)
        b.a.bits.mask.poke(((BigInt(1)<<b.a.bits.mask.getWidth)-1).U); b.a.bits.data.poke(0.U); b.a.bits.corrupt.poke(false.B)
        b.d.ready.poke(true.B)
        if(b.params.hasBCE) {
        b.c.valid.poke(false.B); b.c.bits.opcode.poke(4.U); b.c.bits.param.poke(0.U)
        b.c.bits.size.poke(7.U); b.c.bits.source.poke(0.U); b.c.bits.address.poke(0.U)
        b.c.bits.data.poke(0.U); b.c.bits.corrupt.poke(false.B)
        b.b.ready.poke(true.B); b.d.ready.poke(true.B)
        b.e.valid.poke(false.B); b.e.bits.sink.poke(0.U)
        }
      }
      d.client.foreach(init); init(d.control)
      d.control.a.bits.mask.poke(255.U)
      d.blockMemoryA.poke(false.B); d.blockMemoryD.poke(false.B)
      var grantAck: Option[BigInt] = None
      var grantBeats=0
      var ctrlResponses=0
      var ctrlData=BigInt(0)
      var markerRecords=Vector.empty[(Int,BigInt,BigInt,BigInt)]
      def step(): Unit = {
        val cpu=d.client(0)
        cpu.e.valid.poke(grantAck.nonEmpty.B); cpu.e.bits.sink.poke(grantAck.getOrElse(BigInt(0)).U)
        if (probeActive.isEmpty && probePending.nonEmpty) { probeActive=Some(probePending.dequeue()); cBeat=0 }
        cpu.c.valid.poke(probeActive.nonEmpty.B)
        probeActive.foreach { case (param,addr) =>
          val dirty=coherentDirty && addr==coherentLine
          cpu.c.bits.opcode.poke((if(dirty) 5 else 4).U)
          cpu.c.bits.param.poke((if(coherentDirty) 1 else 2).U) // TtoN / BtoN
          cpu.c.bits.address.poke(addr.U); cpu.c.bits.size.poke(7.U)
          cpu.c.bits.data.poke(dirtyData.U)
        }
        if(cpu.e.valid.peek().litToBoolean && cpu.e.ready.peek().litToBoolean) grantAck=None
        if(cpu.b.valid.peek().litToBoolean) probePending.enqueue((cpu.b.bits.param.peek().litValue.toInt,cpu.b.bits.address.peek().litValue))
        if(cpu.c.valid.peek().litToBoolean && cpu.c.ready.peek().litToBoolean) {
          cBeat+=1
          if(!coherentDirty || cBeat==4) { probeActive=None;coherentDirty=false }
        }
        if(d.innerAFirst.peek().litToBoolean) independentAccepted+=1
        if(d.innerDLast.peek().litToBoolean) independentCompleted+=1
        if(d.outerAFirst.peek().litToBoolean) independentRefills+=1
        if(d.outerDData.peek().litToBoolean) independentRefillBeats+=1
        if(d.outerCFirst.peek().litToBoolean) independentWritebacks+=1
        if(d.outerCData.peek().litToBoolean) independentWritebackBeats+=1
        if(enabled) {
          L2PerfEvents.descriptors.zipWithIndex.foreach { case (desc,i) =>
            val value=d.perf.banks(0).events(i).peek().litValue
            val key=s"${desc.requester}:${desc.name}"
            if(desc.gauge) gauges(key)=value else counts(key)+=value
          }
          if(d.perf.marker.valid.peek().litToBoolean)
            markerRecords :+= ((d.perf.marker.kind.peek().litValue.toInt,d.perf.marker.windowId.peek().litValue,d.perf.marker.sequence.peek().litValue,d.perf.marker.errors.peek().litValue))
        }
        d.client.zipWithIndex.foreach { case (b,i) =>
          if(b.d.valid.peek().litToBoolean && b.d.ready.peek().litToBoolean) {
            val opcode=b.d.bits.opcode.peek().litValue.toInt
            val src=b.d.bits.source.peek().litValue.toInt
            val data=b.d.bits.data.peek().litValue
            responses += ((i,src,opcode,data)); transcript += ((cycle,i,BigInt(opcode),data))
            assert(!b.d.bits.denied.peek().litToBoolean && !b.d.bits.corrupt.peek().litToBoolean)
            if(opcode==4 || opcode==5) {
              grantBeats+=1
              if(opcode==4 || grantBeats==4) { grantAck=Some(b.d.bits.sink.peek().litValue);grantBeats=0 }
            }
          }
        }
        if(d.control.d.valid.peek().litToBoolean) { ctrlResponses+=1;ctrlData=d.control.d.bits.data.peek().litValue }
        d.clock.step();cycle+=1
      }
      def idle(n:Int):Unit=(0 until n).foreach(_=>step())
      def until(pred: =>Boolean):Unit={ var timeout=0;while(!pred && timeout<10000){step();timeout+=1};assert(pred,s"Timeout at $cycle") }
      def send(client:Int,opcode:Int,address:BigInt,source:Int=0,size:Int=5,param:Int=0,data:BigInt=BigInt(0),mask:BigInt=BigInt("ffffffff",16)):Unit={
        val b=d.client(client);b.a.bits.opcode.poke(opcode.U);b.a.bits.address.poke(address.U)
        b.a.bits.source.poke(source.U);b.a.bits.size.poke(size.U);b.a.bits.param.poke(param.U)
        b.a.bits.data.poke(data.U);b.a.bits.mask.poke(mask.U);b.a.valid.poke(true.B)
        until(b.a.ready.peek().litToBoolean);step();b.a.valid.poke(false.B)
      }
      def txn(client:Int,opcode:Int,address:BigInt,size:Int=5,param:Int=0,data:BigInt=BigInt(0),mask:BigInt=BigInt("ffffffff",16)):Vector[(Int,Int,Int,BigInt)]={
        val n=responses.size;send(client,opcode,address,size=size,param=param,data=data,mask=mask)
        val beats=if(opcode==6)4 else 1
        until(responses.size>=n+beats);idle(16);responses.drop(n).toVector
      }
      def ctrlWrite(offset:Int,value:BigInt):Unit={
        val b=d.control;val n=ctrlResponses
        b.a.valid.poke(true.B);b.a.bits.opcode.poke(0.U);b.a.bits.address.poke((BigInt(0x2010000)+offset).U)
        b.a.bits.size.poke(3.U);b.a.bits.data.poke(value.U)
        until(b.a.ready.peek().litToBoolean);step();b.a.valid.poke(false.B)
        until(ctrlResponses>n);idle(2)
      }
      def ctrlWrite32(offset:Int,value:BigInt):Unit={
        val b=d.control;val n=ctrlResponses;val shift=(offset%8)*8
        b.a.valid.poke(true.B);b.a.bits.opcode.poke(0.U);b.a.bits.address.poke((BigInt(0x2010000)+offset).U)
        b.a.bits.size.poke(2.U);b.a.bits.data.poke((value<<shift).U);b.a.bits.mask.poke((15<<(offset%8)).U)
        until(b.a.ready.peek().litToBoolean);step();b.a.valid.poke(false.B)
        until(ctrlResponses>n);b.a.bits.mask.poke(255.U);idle(2)
      }
      def ctrlRead(offset:Int):BigInt={
        val b=d.control;val n=ctrlResponses
        b.a.valid.poke(true.B);b.a.bits.opcode.poke(4.U);b.a.bits.address.poke((BigInt(0x2010000)+offset).U)
        b.a.bits.size.poke(3.U)
        until(b.a.ready.peek().litToBoolean);step();b.a.valid.poke(false.B)
        until(ctrlResponses>n);ctrlData
      }
      idle(32)
      // A cold Put, partial update, and repeated Gets have independently known outcomes.
      val initial=BigInt("77"*32,16)
      txn(3,0,0x400,data=initial)
      val before=independentRefills
      val partial=BigInt("aa"*32,16)
      txn(2,1,0x400,data=partial,mask=BigInt(0xffff))
      val merged=BigInt("77"*16+"aa"*16,16)
      assert(txn(1,4,0x400).head._4==merged)
      assert(txn(3,4,0x400).head._4==merged)
      assert(independentRefills==before)
      if(enabled) {
        assert(counts("all:tag_misses")==1,"A cold Put must be classified as a miss")
        assert(counts("all:tag_hits")==3,"Partial write and repeated Gets must hit the allocated line")
      }
      // Four-beat write counts as one demand, not four.
      val writeBase=responses.size
      (0 until 4).foreach(i=>send(2,0,0x800,size=7,data=BigInt(i+1)))
      until(responses.size>writeBase);idle(20)
      (0 until 4).foreach(i=>assert(txn(1,4,0x800+i*32).head._4==BigInt(i+1)))
      // Logical atomic swap uses the existing allocated line.
      txn(2,3,0x800,size=3,param=3,data=42,mask=255)
      assert((txn(3,4,0x800).head._4 & ((BigInt(1)<<64)-1))==42)
      // Hold backing D while requests for equal and colliding sets accumulate.
      d.blockMemoryD.poke(true.B)
      val queuedBase=responses.size
      send(3,4,0x1000,source=0); send(1,4,0x1000,source=1)
      send(2,4,0x1200,source=2); send(3,4,0x1400,source=3)
      idle(50);d.blockMemoryD.poke(false.B)
      until(responses.size>=queuedBase+4);idle(32)
      // Fill the shared queue behind a blocked refill. The source remains valid
      // when the cache stops accepting it; release backing memory after 160 cycles.
      d.blockMemoryD.poke(true.B)
      val pressureBase=responses.size
      val pressure=d.client(3)
      var nextSource=0
      for(tick <- 0 until 160) {
        pressure.a.valid.poke((nextSource<8).B)
        pressure.a.bits.opcode.poke(4.U);pressure.a.bits.size.poke(5.U)
        pressure.a.bits.source.poke((nextSource%8).U)
        pressure.a.bits.address.poke((0x4000+nextSource*512).U)
        val accepted=nextSource<8 && pressure.a.ready.peek().litToBoolean
        step();if(accepted)nextSource+=1
      }
      pressure.a.valid.poke(false.B);d.blockMemoryD.poke(false.B)
      while(nextSource<8){send(3,4,0x4000+nextSource*512,source=nextSource);nextSource+=1}
      until(responses.size>=pressureBase+8);idle(32)
      // Independent clients issue concurrently, then stop accepting D to back up the cache.
      d.client.foreach(_.d.ready.poke(false.B)); d.blockMemoryA.poke(true.B)
      d.client.zipWithIndex.foreach {case(b,i)=>
        b.a.bits.opcode.poke(4.U);b.a.bits.size.poke(5.U);b.a.bits.source.poke(0.U)
        b.a.bits.address.poke((0x2000+i*128).U);b.a.bits.mask.poke("hffffffff".U);b.a.valid.poke(true.B)
      }
      var waiting=Set(0,1,2,3);val parallelBase=responses.size
      var parallelCycles=0
      while(waiting.nonEmpty && parallelCycles<1000){
        if(parallelCycles==40)d.blockMemoryA.poke(false.B)
        if(parallelCycles==80)d.client.foreach(_.d.ready.poke(true.B))
        val fired=waiting.filter(i=>d.client(i).a.ready.peek().litToBoolean)
        step();fired.foreach(i=>d.client(i).a.valid.poke(false.B));waiting --= fired
        parallelCycles+=1
      }
      assert(waiting.isEmpty,"Parallel requests did not progress after releasing backpressure")
      d.blockMemoryA.poke(false.B);d.client.foreach(_.d.ready.poke(true.B))
      until(responses.size>=parallelBase+4);idle(32)
      // A child acquires write permission and returns dirty data only after a replacement probe.
      txn(0,6,0x3000,size=7,param=1);coherentDirty=true;coherentLine=0x3000
      val dirtyBeforeChild=counts("all:dirty_replacements")
      var conflict=1
      while(coherentDirty && conflict<100){txn(3,4,0x3000+conflict*512);conflict+=1}
      assert(!coherentDirty,"Conflict traffic must eventually evict/probe the acquired line")
      if(enabled)assert(counts("all:dirty_replacements")>dirtyBeforeChild,
        "Dirty data returned by a child must classify the replacement as dirty")
      idle(64)
      // The existing flush interface remains functional and separate from replacements.
      txn(3,0,0x6000,data=initial)
      ctrlWrite(0x200,0x6000)
      idle(64)
      functionalTranscript=transcript.toVector
      assert(independentAccepted==independentCompleted)
      if(enabled) {
        // Counter names are resolved by semantic descriptors, not internal increment expressions.
        def count(name:String,requester:String="all")=counts(s"$requester:$name")
        def gauge(name:String)=gauges(s"all:$name")
        // Additional family assertions below are filled once schema names are finalized.
        assert(count("instrumentation_errors")==0)
        assert(Seq("demand_get","demand_put","demand_acquire_block","demand_acquire_perm","demand_atomic","demand_other").map(count(_)).sum==independentAccepted)
        assert(count("demand_completed")==independentCompleted)
        assert(count("tag_hits")+count("tag_misses")==independentAccepted)
        assert(count("refill_transactions")==independentRefills)
        assert(count("refill_data_bytes")==independentRefillBeats*32)
        assert(count("dirty_writeback_transactions")==independentWritebacks)
        assert(count("dirty_writeback_data_bytes")==independentWritebackBeats*32)
        assert(gauge("outstanding_requests")==0)
        assert(count("same_line_secondary_accepted")>0)
        assert(count("same_set_other_line_secondary_accepted")>0)
        assert(count("dirty_replacements")>0)
        assert(count("probes_sent")>0)
        assert(count("flush_invalidations")==1)
        assert(count("normal_mshr_full_cycles")>0)
        assert(count("normal_mshr_allocation_stall_cycles")>0)
        assert(count("secondary_queue_full_stall_cycles")>0)
        assert(count("outer_request_backpressure_cycles")>0)
        assert(count("inner_response_backpressure_cycles")>0)
        val classes=Seq("rocket","saturn","gemmini_int8","trafficgen","other")
        assert(classes.map(c=>count(s"${c}_accepted",c)).sum==independentAccepted)
        assert(classes.map(c=>count(s"${c}_completed",c)).sum==independentCompleted)
        classes.take(4).foreach(c=>assert(count(s"${c}_accepted",c)>0,s"Missing requester $c"))
        ctrlWrite32(0x100,0x23456789L);ctrlWrite32(0x104,1)
        ctrlWrite(0x108,1);assert(ctrlRead(0x118)==1)
        ctrlWrite(0x108,2);ctrlWrite(0x108,2)
        ctrlWrite(0x108,3);assert(ctrlRead(0x118)==2)
        ctrlWrite(0x108,4);assert(ctrlRead(0x118)==0)
        assert(markerRecords.map(_._1)==Vector(1,2,2,3,4))
        assert(markerRecords.forall(_._2==BigInt(0x123456789L)))
        assert(markerRecords.map(_._3)==(1 to 5).map(BigInt(_)).toVector)
        assert(markerRecords.forall(_._4==0))
        assert(ctrlRead(0x110)==5)
        ctrlWrite(0x108,2);ctrlWrite(0x108,99)
        assert(markerRecords.takeRight(2).map(_._1)==Vector(0,0))
        assert(ctrlRead(0x120)==3)
        ctrlWrite(0x120,3);assert(ctrlRead(0x120)==0)
        ctrlWrite(0x100,9);ctrlWrite(0x108,1);ctrlWrite(0x108,3);ctrlWrite(0x108,4)
        assert(markerRecords.takeRight(3).map(_._1)==Vector(1,3,4))
        assert(markerRecords.takeRight(3).forall(_._2==9))
        assert(ctrlRead(0x110)==10)
      }
    }
    functionalTranscript
  }

  it should "match independent transactions, detect contention and preserve functional target cycles" in {
    val off=runScenario(false)
    val on=runScenario(true)
    assert(on==off,"Passive instrumentation must not change response data or response cycle")
  }
}
