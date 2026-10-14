package chipyard.example
import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.TmaWakeMonitor
import org.scalatest.flatspec.AnyFlatSpec

class TmaWakeMonitorFailureSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "atomic TMA metadata validation"
  private def begin(epoch: Int,groups: Int,edges: Int): BigInt =
    BigInt(4)|(BigInt(epoch)<<8)|(BigInt(groups)<<40)|(BigInt(edges)<<53)
  private def commit(epoch: Int): BigInt = BigInt(8)|(BigInt(epoch)<<8)
  private def group: BigInt = BigInt(5)|(BigInt(1)<<20)
  private def edge(next: Int=262144): BigInt = BigInt(6)|(BigInt(next)<<38)
  private def head(epoch: Int,token: Int=65536): BigInt = BigInt(7)|(BigInt(token)<<8)|(BigInt(epoch)<<72)
  private def reject(records: Seq[BigInt], errorCode: Int): Unit = {
      test(new TmaWakeMonitor(2,16,4,8)) { d =>
        d.clock.setTimeout(1000); d.io.metadata.valid.poke(false.B);d.io.metadata.bits.poke(0.U)
        d.io.tokenValid.poke(false.B);d.io.recheck.poke(false.B);d.io.tokenConsumed.poke(false.B)
        d.io.cycle.poke(0.U);d.io.returns.poke(0.U);d.io.tokens.foreach(_.poke(0.U));d.io.returnCycles.foreach(_.poke(0.U))
        d.io.report.ready.poke(true.B);d.clock.step(20)
        for((record,index)<-records.zipWithIndex) {
          d.io.metadata.ready.expect(true.B);d.io.metadata.bits.poke(record.U);d.io.metadata.valid.poke(true.B)
          d.clock.step();d.io.metadata.valid.poke(false.B);d.clock.step()
          d.io.error.expect((if(index==records.size-1) errorCode else 0).U)
        }
        // A bad descriptor latches a host-visible error and cannot publish work
        // or reports. Further metadata must remain blocked until reset.
        d.io.metadata.bits.poke(begin(2,0,0).U);d.io.metadata.valid.poke(true.B)
        for(_<-0 until 8) {
          d.io.error.expect(errorCode.U);d.io.metadata.ready.expect(false.B)
          d.io.metadataIdle.expect(false.B);d.io.tokenReady.expect(false.B)
          d.io.report.valid.expect(false.B);d.clock.step()
        }
        d.io.metadata.valid.poke(false.B)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.clock.step(20)
        d.io.error.expect(0.U);d.io.metadata.ready.expect(true.B)
        d.io.pending.expect(false.B);d.io.report.valid.expect(false.B)
      }
  }
  it should "reject incomplete group installation" in { reject(Seq(begin(1,1,0),commit(1)),5) }
  it should "reject incomplete edge installation" in { reject(Seq(begin(1,1,1),group,commit(1)),5) }
  it should "reject group capacity overflow before publication" in { reject(Seq(begin(1,5,0)),1) }
  it should "reject edge capacity overflow before publication" in { reject(Seq(begin(1,0,9)),1) }
  it should "reject cyclic or forward adjacency links" in { reject(Seq(begin(1,1,1),group,edge(0)),3) }
  it should "reject zero-generation physical tokens" in { reject(Seq(begin(1,1,1),group,edge(),commit(1),head(1,0)),4) }
  it should "reject stale service-head epochs" in { reject(Seq(begin(1,1,1),group,edge(),commit(1),head(2)),4) }
  it should "reject reused policy epochs" in { reject(Seq(begin(1,0,0),commit(1),begin(1,0,0)),1) }
}
