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
  val names = Seq("Core 0 DCache", "Core 0 Vector Load", "stream-reader", "trafficgenmem0", "serial_tl_test")
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
    innerAFirst := inner.a.fire && ei.first(inner.a) && inner.a.bits.opcode =/= TLMessages.Hint
    innerDLast := inner.d.fire && ei.last(inner.d) && inner.d.bits.opcode =/= TLMessages.ReleaseAck && inner.d.bits.opcode =/= TLMessages.HintAck
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
      var independentProbes=0L
      val requesterNames=Seq("rocket","saturn","gemmini_int8","trafficgen","other")
      val acceptedByClient=Array.fill(d.client.size)(BigInt(0))
      val completedByClient=Array.fill(d.client.size)(BigInt(0))
      val expectedOpcode=Array.fill(8)(BigInt(0))
      val aRemaining=Array.fill(d.client.size)(0)
      val dRemaining=Array.fill(d.client.size)(0)
      var expectedRequestedBytes=BigInt(0)
      var expectedHintAccepted=BigInt(0); var expectedHintCompleted=BigInt(0)
      var expectedOutstandingIntegral=BigInt(0)
      var simultaneousAcceptComplete=0
      val activeSources=mutable.Set.empty[(Int,Int)]
      val cpuAcquires=mutable.Map.empty[Int,BigInt]
      val cpuPermissions=mutable.Map.empty[BigInt,Int] // Grant param: toT=0, toB=1
      var holdProbeResponses=false
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
      var ctrlResponses=0
      var ctrlData=BigInt(0)
      var markerRecords=Vector.empty[(Int,BigInt,BigInt,BigInt)]
      def step(): Unit = {
        val outstandingBefore=acceptedByClient.sum-completedByClient.sum
        expectedOutstandingIntegral+=outstandingBefore
        val cpu=d.client(0)
        cpu.e.valid.poke(grantAck.nonEmpty.B); cpu.e.bits.sink.poke(grantAck.getOrElse(BigInt(0)).U)
        if (!holdProbeResponses && probeActive.isEmpty && probePending.nonEmpty) { probeActive=Some(probePending.dequeue()); cBeat=0 }
        cpu.c.valid.poke(probeActive.nonEmpty.B)
        probeActive.foreach { case (param,addr) =>
          val dirty=coherentDirty && addr==coherentLine
          cpu.c.bits.opcode.poke((if(dirty) 5 else 4).U)
          val permission=cpuPermissions.getOrElse(addr,2)
          val report=if(permission==0) { if(param==2)1 else if(param==1)0 else 3 }
            else if(permission==1) { if(param==2)2 else 4 } else 5
          cpu.c.bits.param.poke(report.U)
          cpu.c.bits.address.poke(addr.U); cpu.c.bits.size.poke(7.U)
          cpu.c.bits.data.poke(dirtyData.U)
        }
        if(cpu.e.valid.peek().litToBoolean && cpu.e.ready.peek().litToBoolean) grantAck=None
        if(cpu.b.valid.peek().litToBoolean && cpu.b.ready.peek().litToBoolean) {
          independentProbes+=1
          probePending.enqueue((cpu.b.bits.param.peek().litValue.toInt,cpu.b.bits.address.peek().litValue))
        }
        if(cpu.c.valid.peek().litToBoolean && cpu.c.ready.peek().litToBoolean) {
          val (param,addr)=probeActive.get
          val dirty=coherentDirty && addr==coherentLine
          cBeat+=1
          if(!dirty || cBeat==4) {
            if(param==2)cpuPermissions.remove(addr)
            else if(param==1 && cpuPermissions.contains(addr))cpuPermissions(addr)=1
            if(dirty)coherentDirty=false
            probeActive=None
          }
        }
        // Software packet tracking uses only handshakes at the named client ports.
        // It is independent of Scheduler/MSHR event increment expressions.
        d.client.zipWithIndex.foreach { case(b,i) =>
          if(b.a.valid.peek().litToBoolean && b.a.ready.peek().litToBoolean) {
            val op=b.a.bits.opcode.peek().litValue.toInt
            val size=b.a.bits.size.peek().litValue.toInt
            if(aRemaining(i)==0) {
              val source=b.a.bits.source.peek().litValue.toInt
              assert(!activeSources((i,source)),s"Client $i reused source $source before completion")
              activeSources.add((i,source))
              val bytes=BigInt(1)<<size
              if(op==5)expectedHintAccepted+=1
              else { acceptedByClient(i)+=1;expectedOpcode(op)+=1;expectedRequestedBytes+=bytes }
              aRemaining(i)=if(op<=3)math.max(1,(1<<size)/32)-1 else 0
              if(i==0 && (op==6 || op==7))cpuAcquires(b.a.bits.source.peek().litValue.toInt)=b.a.bits.address.peek().litValue
              transcript += ((cycle,-i-1,BigInt(op),b.a.bits.address.peek().litValue))
            } else aRemaining(i)-=1
          }
        }
        if(d.innerAFirst.peek().litToBoolean) independentAccepted+=1
        if(d.innerDLast.peek().litToBoolean) independentCompleted+=1
        if(d.outerAFirst.peek().litToBoolean) independentRefills+=1
        if(d.outerDData.peek().litToBoolean) independentRefillBeats+=1
        if(d.outerCFirst.peek().litToBoolean) independentWritebacks+=1
        if(d.outerCData.peek().litToBoolean) independentWritebackBeats+=1
        if(d.innerAFirst.peek().litToBoolean && d.innerDLast.peek().litToBoolean)simultaneousAcceptComplete+=1
        if(enabled) {
          d.perf.banks(0).events(L2PerfEvents.index("outstanding_requests")).expect(outstandingBefore.U)
          d.perf.banks(0).events(L2PerfEvents.index("outstanding_request_entry_cycles")).expect(outstandingBefore.U)
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
            val metadata=BigInt(opcode)|(BigInt(src)<<8)|(b.d.bits.param.peek().litValue<<16)|(b.d.bits.size.peek().litValue<<24)|(b.d.bits.sink.peek().litValue<<32)
            responses += ((i,src,opcode,data)); transcript += ((cycle,i,metadata,data))
            assert(!b.d.bits.denied.peek().litToBoolean && !b.d.bits.corrupt.peek().litToBoolean)
            val responseBeats=if(opcode==1 || opcode==5)math.max(1,(1<<b.d.bits.size.peek().litValue.toInt)/32) else 1
            val last=if(dRemaining(i)==0) { dRemaining(i)=responseBeats-1;responseBeats==1 }
              else { dRemaining(i)-=1;dRemaining(i)==0 }
            if(last && opcode!=6)assert(activeSources.remove((i,src)),s"Unexpected response for client $i source $src")
            if(last && opcode==2)expectedHintCompleted+=1
            if(last && opcode!=2 && opcode!=6)completedByClient(i)+=1
            if((opcode==4 || opcode==5) && last) {
              grantAck=Some(b.d.bits.sink.peek().litValue)
              cpuPermissions(cpuAcquires.remove(src).get)=b.d.bits.param.peek().litValue.toInt
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
      def total(name:String):BigInt=counts("all:"+name)
      // After the backing response/probe is deliberately held, these occupancies
      // follow from the independent outstanding requests in this directed phase.
      def steady(cycles:Int,outstanding:Int,mshrs:Int,queued:Int,refills:Int,probes:Int=0):Unit={
        val increments=Seq("outstanding_request_entry_cycles"->outstanding,
          "normal_mshr_entry_cycles"->mshrs,"secondary_queue_entry_cycles"->queued,
          "refill_wait_entry_cycles"->refills,"probe_wait_entry_cycles"->probes)
        val before=increments.map {case(n,_)=>n->total(n)}.toMap
        for(_ <- 0 until cycles) {
          if(enabled)Seq("outstanding_requests"->outstanding,"normal_mshr_occupancy"->mshrs,
            "secondary_queue_occupancy"->queued).foreach {case(n,v)=>
              d.perf.banks(0).events(L2PerfEvents.index(n)).expect(v.U)
            }
          step()
        }
        if(enabled)increments.foreach {case(n,v)=>assert(total(n)-before(n)==BigInt(cycles)*v,s"Incorrect settled $n integral")}
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
      assert(txn(4,4,0x400).head._4==merged)
      assert(independentRefills==before)
      if(enabled) {
        assert(counts("all:tag_misses")==1,"A cold Put must be classified as a miss")
        assert(counts("all:tag_hits")==4,"Partial write and repeated Gets must hit the allocated line")
        requesterNames.foreach { r =>
          val expectedMiss=if(r=="trafficgen")1 else 0
          val expectedHit=if(r=="rocket")0 else 1
          assert(counts(s"$r:${r}_tag_misses")==expectedMiss,s"Wrong first-phase miss attribution to $r")
          assert(counts(s"$r:${r}_tag_hits")==expectedHit,s"Wrong first-phase hit attribution to $r")
        }
      }
      // Keep a warm-line request stream active across responses, reusing each
      // source only after its previous response. This exercises simultaneous
      // request/completion updates and validates source reuse independently.
      val burstBase=responses.size
      for(i <- 0 until 24) {
        val source=i%8
        until(!activeSources((3,source)))
        send(3,4,0x400,source=source)
      }
      until(responses.size>=burstBase+24);idle(16)
      assert(responses.drop(burstBase).forall(_._4==merged))
      // Four-beat write counts as one demand, not four.
      val writeBase=responses.size
      (0 until 4).foreach(i=>send(2,0,0x800,size=7,data=BigInt(i+1)))
      until(responses.size>writeBase);idle(20)
      (0 until 4).foreach(i=>assert(txn(1,4,0x800+i*32).head._4==BigInt(i+1)))
      // Logical atomic swap uses the existing allocated line.
      txn(2,3,0x800,size=3,param=3,data=42,mask=255)
      assert((txn(3,4,0x800).head._4 & ((BigInt(1)<<64)-1))==42)
      val old=txn(2,2,0x800,size=3,param=4,data=5,mask=255).head._4 // Arithmetic ADD
      assert((old & ((BigInt(1)<<64)-1))==42)
      assert((txn(3,4,0x800).head._4 & ((BigInt(1)<<64)-1))==47)
      assert(txn(4,5,0x800,size=7).head._3==2) // HintAck is not demand completion
      // AcquirePerm returns a one-beat Grant and a subsequent non-owner Get must
      // wait for an actual child probe. Hold that response for a known interval.
      val cpuHitsBefore=counts("rocket:rocket_tag_hits")
      assert(txn(0,7,0x800,size=7,param=1).head._3==4)
      if(enabled)assert(counts("rocket:rocket_tag_hits")==cpuHitsBefore+1)
      holdProbeResponses=true
      val probeBase=responses.size;val probeHitsBefore=total("hits_requiring_child_probes")
      send(3,4,0x800)
      until(probePending.nonEmpty);idle(4)
      steady(13,outstanding=1,mshrs=1,queued=0,refills=0,probes=1)
      holdProbeResponses=false;until(responses.size>probeBase);idle(20)
      assert((responses(probeBase)._4 & ((BigInt(1)<<64)-1))==47)
      if(enabled)assert(total("hits_requiring_child_probes")==probeHitsBefore+1)
      // Hold backing D in an initially unused set (no dirty victim acknowledgement
      // can block the first outer acquire) while equal/colliding requests accumulate.
      d.blockMemoryD.poke(true.B)
      val queuedBase=responses.size;val refillBase=independentRefills
      val sameBefore=total("same_line_secondary_accepted");val otherBefore=total("same_set_other_line_secondary_accepted")
      send(3,4,0x1080,source=0);until(independentRefills>refillBase);idle(8)
      steady(9,outstanding=1,mshrs=1,queued=0,refills=1)
      send(1,4,0x1080,source=1);idle(8)
      if(enabled) {
        assert(total("same_line_secondary_accepted")==sameBefore+1)
        assert(total("same_set_other_line_secondary_accepted")==otherBefore)
      }
      steady(11,outstanding=2,mshrs=1,queued=1,refills=1)
      send(2,4,0x1280,source=2);idle(8)
      if(enabled) {
        assert(total("same_line_secondary_accepted")==sameBefore+1)
        assert(total("same_set_other_line_secondary_accepted")==otherBefore+1)
      }
      steady(7,outstanding=3,mshrs=1,queued=2,refills=1)
      d.blockMemoryD.poke(false.B)
      until(responses.size>=queuedBase+3);idle(32)
      // Fill the queue twice: the held sixth request is first a different line,
      // then the active line. This independently distinguishes both blocked labels.
      for(sameBlocked <- Seq(false,true)) {
        d.blockMemoryD.poke(true.B)
        val pressureBase=responses.size
        val base=if(sameBlocked)0x7000 else 0x4000
        val sameBlockedBefore=total("same_line_secondary_blocked_cycles")
        val otherBlockedBefore=total("same_set_other_line_secondary_blocked_cycles")
        val pressure=d.client(3)
        def address(source:Int)=if(sameBlocked && source==5)base else base+source*512
        var nextSource=0
        for(tick <- 0 until 160) {
          pressure.a.valid.poke((nextSource<8).B)
          pressure.a.bits.opcode.poke(4.U);pressure.a.bits.size.poke(5.U)
          pressure.a.bits.source.poke((nextSource%8).U)
          pressure.a.bits.address.poke(address(nextSource).U)
          val accepted=nextSource<8 && pressure.a.ready.peek().litToBoolean
          step();if(accepted)nextSource+=1
        }
        if(enabled) {
          val sameDelta=total("same_line_secondary_blocked_cycles")-sameBlockedBefore
          val otherDelta=total("same_set_other_line_secondary_blocked_cycles")-otherBlockedBefore
          assert(if(sameBlocked)sameDelta>0 && otherDelta==0 else otherDelta>0 && sameDelta==0,
            s"Incorrect blocked conflict class: same=$sameDelta other=$otherDelta")
        }
        pressure.a.valid.poke(false.B);d.blockMemoryD.poke(false.B)
        while(nextSource<8){send(3,4,address(nextSource),source=nextSource);nextSource+=1}
        until(responses.size>=pressureBase+8);idle(32)
      }
      // Independent clients issue concurrently, then stop accepting D to back up the cache.
      d.client.foreach(_.d.ready.poke(false.B)); d.blockMemoryA.poke(true.B)
      d.client.zipWithIndex.foreach {case(b,i)=>
        b.a.bits.opcode.poke(4.U);b.a.bits.size.poke(5.U);b.a.bits.source.poke(0.U)
        b.a.bits.address.poke((0x2000+i*128).U);b.a.bits.mask.poke("hffffffff".U);b.a.valid.poke(true.B)
      }
      var waiting=d.client.indices.toSet;val parallelBase=responses.size
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
      until(responses.size>=parallelBase+d.client.size);idle(32)
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
        assert(count("instrumentation_errors")==0)
        assert(Seq("demand_get","demand_put","demand_acquire_block","demand_acquire_perm","demand_atomic","demand_other").map(count(_)).sum==independentAccepted)
        assert(acceptedByClient.sum==independentAccepted && completedByClient.sum==independentCompleted)
        assert(count("demand_get")==expectedOpcode(4))
        assert(count("demand_put")==expectedOpcode(0)+expectedOpcode(1))
        assert(count("demand_atomic")==expectedOpcode(2)+expectedOpcode(3))
        assert(count("demand_acquire_block")==expectedOpcode(6))
        assert(count("demand_acquire_perm")==expectedOpcode(7))
        assert(count("demand_other")==0)
        assert(count("demand_requested_bytes")==expectedRequestedBytes)
        assert(count("hint_accepted")==expectedHintAccepted && expectedHintAccepted==1)
        assert(count("hint_completed")==expectedHintCompleted && expectedHintCompleted==1)
        assert(count("demand_completed")==independentCompleted)
        assert(count("outstanding_request_entry_cycles")==expectedOutstandingIntegral)
        assert(simultaneousAcceptComplete>0,"The scenario must exercise simultaneous accepted request and completion")
        assert(count("tag_hits")+count("tag_misses")==independentAccepted)
        assert(count("refill_transactions")==independentRefills)
        assert(count("refill_data_bytes")==independentRefillBeats*32)
        assert(count("dirty_writeback_transactions")==independentWritebacks)
        assert(count("dirty_writeback_data_bytes")==independentWritebackBeats*32)
        assert(gauge("outstanding_requests")==0)
        assert(count("same_line_secondary_accepted")>0)
        assert(count("same_set_other_line_secondary_accepted")>0)
        assert(count("dirty_replacements")>0)
        assert(count("probes_sent")==independentProbes && independentProbes>0)
        assert(count("flush_invalidations")==1)
        assert(count("normal_mshr_full_cycles")>0)
        assert(count("normal_mshr_allocation_stall_cycles")>0)
        assert(count("secondary_queue_full_stall_cycles")>0)
        assert(count("outer_request_backpressure_cycles")>0)
        assert(count("inner_response_backpressure_cycles")>0)
        val classes=Seq("rocket","saturn","gemmini_int8","trafficgen","other")
        assert(classes.map(c=>count(s"${c}_accepted",c)).sum==independentAccepted)
        assert(classes.map(c=>count(s"${c}_completed",c)).sum==independentCompleted)
        classes.zipWithIndex.foreach {case(c,i)=>
          assert(count(s"${c}_accepted",c)==acceptedByClient(i),s"Wrong accepted attribution to $c")
          assert(count(s"${c}_completed",c)==completedByClient(i),s"Wrong completion attribution to $c")
          assert(count(s"${c}_tag_hits",c)+count(s"${c}_tag_misses",c)==acceptedByClient(i),s"Wrong outcome attribution to $c")
          assert(acceptedByClient(i)>0,s"Missing requester $c")
        }
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
