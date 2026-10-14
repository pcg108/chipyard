package chipyard.example
import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.TmaWakeMonitor
import org.scalatest.flatspec.AnyFlatSpec

class TmaWakeMonitorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TMA wake groups"
  private def begin(epoch: Int, groups: Int, edges: Int): BigInt =
    BigInt(4) | (BigInt(epoch)<<8) | (BigInt(groups)<<40) | (BigInt(edges)<<53) | (BigInt(1)<<72)
  private def group(slot: Int, remaining: Int, floor: Int, conservative: Boolean=false): BigInt =
    BigInt(5) | (BigInt(slot)<<8) | (BigInt(remaining)<<20) | (BigInt(floor)<<52) | (BigInt(if(conservative) 1 else 0)<<116)
  private def edge(index: Int, slot: Int, next: Int=262144): BigInt =
    BigInt(6) | (BigInt(index)<<8) | (BigInt(slot)<<26) | (BigInt(next)<<38)
  private def head(slot: Int, epoch: Int, first: Int, generation: Int=1): BigInt =
    BigInt(7) | (BigInt(generation*65536+slot)<<8) | (BigInt(epoch)<<72) | (BigInt(first)<<104)
  private def init(d: TmaWakeMonitor): Unit = {
    d.clock.setTimeout(10000); d.io.metadata.valid.poke(false.B); d.io.metadata.bits.poke(0.U)
    d.io.tokenValid.poke(false.B); d.io.recheck.poke(false.B); d.io.tokenConsumed.poke(false.B)
    d.io.cycle.poke(0.U); d.io.returns.poke(0.U); d.io.tokens.foreach(_.poke(0.U)); d.io.returnCycles.foreach(_.poke(0.U))
    d.io.report.ready.poke(true.B); d.clock.step(40); d.io.metadataIdle.expect(true.B)
  }
  private def meta(d: TmaWakeMonitor,b: BigInt): Unit = {
    d.io.metadata.bits.poke(b.U); d.io.metadata.valid.poke(true.B)
    var n=0
    while(!d.io.metadata.ready.peek().litToBoolean && n<1000) { d.clock.step(); n+=1 }
    assert(n<1000); d.clock.step(); d.io.metadata.valid.poke(false.B)
    n=0
    while(!d.io.metadataIdle.peek().litToBoolean && n<1000) { d.clock.step(); n+=1 }
    assert(n<1000); d.io.error.expect(0.U)
  }
  private def commit(d: TmaWakeMonitor,epoch: Int): Unit = meta(d,BigInt(8)|(BigInt(epoch)<<8))
  private def token(d: TmaWakeMonitor,at: Int,returns: Seq[Int]=Nil,stallReports: Int=0): Seq[(Int,BigInt,BigInt)] = {
    d.io.cycle.poke(at.U); d.io.returns.poke(((1<<returns.size)-1).U)
    returns.zipWithIndex.foreach { case(t,l)=>d.io.tokens(l).poke(t.U); d.io.returnCycles(l).poke(at.U) }
    d.io.tokenValid.poke(true.B)
    val result=scala.collection.mutable.ArrayBuffer.empty[(Int,BigInt,BigInt)]
    var n=0
    while(!d.io.tokenReady.peek().litToBoolean && n<3000) {
      d.io.report.ready.poke((n>=stallReports).B)
      if(n>=stallReports && d.io.report.valid.peek().litToBoolean) {
        val b=d.io.report.bits.peek().litValue
        result+=(((b&255).toInt,(b>>8)&((BigInt(1)<<64)-1),b>>72))
      }
      d.clock.step(); n+=1
    }
    assert(n<3000); d.io.error.expect(0.U)
    d.clock.step(4); d.io.report.valid.expect(false.B)
    d.io.tokenConsumed.poke(true.B); d.clock.step(); d.io.tokenConsumed.poke(false.B); d.io.tokenValid.poke(false.B)
    result.toSeq
  }
  it should "wait for all five services and their admission floor, retaining independent multicast groups" in {
    test(new TmaWakeMonitor(4,32,16,64)) { d =>
      init(d); meta(d,begin(1,2,10)); meta(d,group(0,5,50)); meta(d,group(1,5,60,true))
      for(i<-0 until 5) { meta(d,edge(2*i,0)); meta(d,edge(2*i+1,1,2*i)) }
      for(i<-0 until 5) meta(d,head(i,1,2*i+1)); commit(d,1)
      assert(token(d,10,Seq(65536,65537,65538,65539)).isEmpty)
      assert(token(d,20,Seq(65540)).isEmpty); d.io.timerCount.expect(2.U)
      assert(token(d,49).isEmpty)
      assert(token(d,50,stallReports=100)==Seq((3,BigInt(1)<<32,BigInt(50))))
      assert(token(d,60)==Seq((4,(BigInt(1)<<32)+1,BigInt(60))))
      d.io.pending.expect(false.B); d.io.timerCount.expect(0.U)
    }
  }
  it should "replace floor-only policies and reject stale service generations without waking" in {
    test(new TmaWakeMonitor(2,32,16,64)) { d =>
      init(d); meta(d,begin(1,1,0)); meta(d,group(0,0,100)); commit(d,1)
      assert(token(d,10).isEmpty)
      meta(d,begin(2,0,0)); commit(d,2); assert(token(d,100).isEmpty); d.io.pending.expect(false.B)
      meta(d,begin(3,1,1)); meta(d,group(0,1,0)); meta(d,edge(0,0)); meta(d,head(0,3,0,2)); commit(d,3)
      assert(token(d,101,Seq(65536)).isEmpty)
      assert(token(d,102,Seq(131072))==Seq((3,BigInt(3)<<32,BigInt(102))))
    }
  }
  it should "allow more than four groups per service and heads published by a private refill" in {
    test(new TmaWakeMonitor(2,32,16,64)) { d =>
      init(d); meta(d,begin(1,8,8))
      for(i<-0 until 8) meta(d,group(i,1,0,i%2==1))
      for(i<-0 until 8) meta(d,edge(i,i,if(i==0) 262144 else i-1))
      commit(d,1); assert(token(d,10).isEmpty)
      meta(d,head(0,1,7))
      val r=token(d,20,Seq(65536)); assert(r.size==8 && r.map(_._2).distinct.size==8 && r.forall(_._3==20))
      d.io.pending.expect(false.B)
    }
  }
  it should "evaluate a newly installed ready policy at the held cycle without retrospective timestamps" in {
    test(new TmaWakeMonitor(2,32,16,64)) { d =>
      init(d); assert(token(d,100).isEmpty)
      meta(d,begin(1,1,0)); meta(d,group(0,0,20)); commit(d,1)
      assert(token(d,100)==Seq((3,BigInt(1)<<32,BigInt(100))))
    }
  }
}
