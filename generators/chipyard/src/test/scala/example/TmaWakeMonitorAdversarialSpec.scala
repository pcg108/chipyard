package chipyard.example
import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.TmaWakeMonitor
import org.scalatest.flatspec.AnyFlatSpec

class TmaWakeMonitorAdversarialSpec extends AnyFlatSpec with ChiselScalatestTester {
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
  it should "reduce simultaneous zero-latency returns before reporting overlapping groups" in {
    test(new TmaWakeMonitor(4,32,16,64)) { d =>
      init(d); meta(d,begin(1,3,4))
      meta(d,group(0,2,0)); meta(d,group(1,1,0,true)); meta(d,group(2,1,0))
      meta(d,edge(0,0)); meta(d,edge(1,1,0)); meta(d,edge(2,0)); meta(d,edge(3,2,2))
      meta(d,head(0,1,1)); meta(d,head(1,1,3)); commit(d,1)
      val r=token(d,0,Seq(65536,65537),100)
      assert(r.size==3 && r.map(_._2).toSet==Set(BigInt(1)<<32,(BigInt(1)<<32)+1,(BigInt(1)<<32)+2))
      assert(r.forall(_._3==0)); d.io.pending.expect(false.B)
    }
  }
  it should "keep a partially installed policy invisible and discard its heap on reset" in {
    test(new TmaWakeMonitor(2,32,16,64)) { d =>
      init(d); meta(d,begin(1,2,1)); meta(d,group(0,0,0))
      d.io.tokenReady.expect(false.B); d.io.report.valid.expect(false.B)
      d.clock.step(20); d.io.report.valid.expect(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(40)
      d.io.pending.expect(false.B); d.io.timerCount.expect(0.U); d.io.edgeCount.expect(0.U)
      meta(d,begin(1,1,0)); meta(d,group(0,0,0)); commit(d,1)
      assert(token(d,10)==Seq((3,BigInt(1)<<32,BigInt(10))))
    }
  }
  it should "recheck a parked token once under a replacement epoch" in {
    test(new TmaWakeMonitor(2,32,16,64)) { d =>
      init(d); meta(d,begin(1,0,0)); commit(d,1)
      d.io.cycle.poke(20.U); d.io.tokenValid.poke(true.B)
      while(!d.io.tokenReady.peek().litToBoolean) d.clock.step()
      d.io.tokenValid.poke(false.B)
      meta(d,begin(2,1,0)); meta(d,group(0,0,10)); commit(d,2)
      d.io.recheck.poke(true.B); d.clock.step(); d.io.recheck.poke(false.B)
      var reports=0; var n=0
      while(!d.io.tokenReady.peek().litToBoolean && n<1000) {
        if(d.io.report.valid.peek().litToBoolean) {
          val b=d.io.report.bits.peek().litValue
          assert((b>>72)==20 && ((b>>8)&((BigInt(1)<<64)-1))==(BigInt(2)<<32)); reports+=1
        }
        d.clock.step(); n+=1
      }
      assert(reports==1 && n<1000); d.clock.step(20); d.io.report.valid.expect(false.B)
      d.io.tokenConsumed.poke(true.B); d.clock.step()
    }
  }
}
