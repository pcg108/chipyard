package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces._
import org.scalatest.flatspec.AnyFlatSpec

class L2PerfAccumulatorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "L2Perf lossless target-boundary snapshots"
  private val key = L2PerfBridgeKey(1, Seq(
    L2PerfCounterDesc("requests", "transactions"),
    L2PerfCounterDesc("inflight", "entries", gauge = true)))

  private def initialize(d: L2PerfAccumulator): Unit = {
    d.io.token.valid.poke(false.B)
    d.io.token.bits.targetReset.poke(false.B)
    d.io.token.bits.events.foreach(_.poke(0.U))
    d.io.token.bits.markerValid.poke(false.B)
    d.io.token.bits.markerKind.poke(0.U)
    d.io.token.bits.windowId.poke(9.U)
    d.io.token.bits.markerSequence.poke(0.U)
    d.io.token.bits.markerErrors.poke(0.U)
    d.io.snapshot.ready.poke(false.B)
  }
  private def token(d: L2PerfAccumulator, amount: Int, gauge: Int, kind: Int = 0): Unit = {
    d.io.token.valid.poke(true.B)
    d.io.token.bits.events(0).poke(amount.U)
    d.io.token.bits.events(1).poke(gauge.U)
    d.io.token.bits.markerValid.poke((kind != 0).B)
    d.io.token.bits.markerKind.poke(kind.U)
    if (d.io.token.bits.targetReset.peek().litToBoolean) {
      d.io.token.bits.markerSequence.poke(0.U)
    } else if (kind != 0) {
      d.io.token.bits.markerSequence.poke((d.io.token.bits.markerSequence.peek().litValue + 1).U)
    }
    d.io.token.ready.expect(true.B)
    d.clock.step()
    d.io.token.valid.poke(false.B)
  }
  private def read(d: L2PerfAccumulator, seq: Int, cycle: Int, kind: Int,
                   count: Int, gauge: Int, errors: BigInt = 0): Unit = {
    d.io.snapshot.valid.expect(true.B)
    d.io.snapshot.bits.sequence.expect(seq.U)
    d.io.snapshot.bits.cycle.expect(cycle.U)
    d.io.snapshot.bits.kind.expect(kind.U)
    d.io.snapshot.bits.errors.expect(errors.U)
    d.io.snapshot.bits.values(0).expect(count.U)
    d.io.snapshot.bits.values(1).expect(gauge.U)
    d.io.snapshot.ready.poke(true.B); d.clock.step(); d.io.snapshot.ready.poke(false.B)
  }

  it should "include START-cycle events, exclude STOP-cycle events, and preserve intermediate snapshots" in {
    test(new L2PerfAccumulator(key)) { d =>
      initialize(d)
      token(d, 7, 1) // pre-window accepted work
      token(d, 2, 3, 1); read(d, 0, 1, 1, 7, 3)
      token(d, 5, 4, 2); read(d, 1, 2, 2, 9, 4)
      token(d, 3, 2, 2); read(d, 2, 3, 2, 14, 2)
      token(d, 11, 1, 3); read(d, 3, 4, 3, 17, 1) // window total=10, not21
      token(d, 0, 0, 4); read(d, 4, 5, 4, 28, 0) // drain=11
      d.io.errors.expect(0.U)
    }
  }

  it should "freeze a full queue without consuming target time or losing a boundary" in {
    test(new L2PerfAccumulator(key)) { d =>
      initialize(d)
      token(d, 1, 1, 1)
      token(d, 2, 2, 2) // both snapshot slots occupied
      d.io.token.valid.poke(true.B)
      d.io.token.bits.events(0).poke(3.U)
      d.io.token.bits.events(1).poke(3.U)
      d.io.token.bits.markerKind.poke(3.U)
      d.io.token.bits.markerSequence.poke(3.U)
      d.io.token.ready.expect(false.B)
      for (_ <- 0 until 10) {
        d.io.snapshot.bits.cycle.expect(0.U)
        d.io.snapshot.bits.values(0).expect(0.U)
        d.clock.step()
      }
      d.io.snapshot.ready.poke(true.B)
      d.io.token.ready.expect(true.B) // simultaneous dequeue/enqueue
      d.clock.step()
      d.io.snapshot.ready.poke(false.B); d.io.token.valid.poke(false.B)
      read(d, 1, 1, 2, 1, 2)
      read(d, 2, 2, 3, 3, 3) // not target cycle 12
      d.io.snapshot.valid.expect(false.B)
    }
  }

  it should "record invalid boundaries and reset interruption instead of silently dropping them" in {
    test(new L2PerfAccumulator(key)) { d =>
      initialize(d)
      token(d, 0, 0, 2)
      read(d, 0, 0, 2, 0, 0, (BigInt(1) << 16) | (BigInt(1) << 17))
      token(d, 4, 2, 1); read(d, 1, 1, 1, 0, 2)
      d.io.token.bits.targetReset.poke(true.B)
      token(d, 99, 0)
      read(d, 2, 2, 5, 4, 0, BigInt(1) << 18)
      d.io.token.bits.targetReset.poke(false.B)
      token(d, 1, 0, 1); read(d, 3, 3, 1, 4, 0)
      token(d, 0, 0, 3); read(d, 4, 4, 3, 5, 0)
    }
  }

  it should "preserve idle reset epochs and ignore marker errors outside marker tokens" in {
    test(new L2PerfAccumulator(key)) { d =>
      initialize(d)
      d.io.token.bits.markerErrors.poke(31.U)
      token(d, 5, 0)
      d.io.errors.expect(0.U)
      d.io.token.bits.markerErrors.poke(0.U)
      token(d, 1, 0, 1); read(d, 0, 1, 1, 5, 0)
      token(d, 0, 0, 3); read(d, 1, 2, 3, 6, 0)
      token(d, 0, 0, 4); read(d, 2, 3, 4, 6, 0)
      d.io.token.bits.targetReset.poke(true.B)
      token(d, 99, 0); read(d, 3, 4, 5, 6, 0)
      d.io.token.bits.targetReset.poke(false.B)
      token(d, 1, 0, 1); read(d, 4, 5, 1, 6, 0)
      d.io.errors.expect(0.U)
    }
  }

  it should "flag lost target marker sequence numbers" in {
    test(new L2PerfAccumulator(key)) { d =>
      initialize(d)
      d.io.token.bits.markerSequence.poke(8.U)
      token(d, 0, 0, 1)
      read(d, 0, 0, 1, 0, 0, BigInt(1) << 19)
      d.io.errors.expect((BigInt(1) << 19).U)
    }
  }


  it should "wrap the actual 64-bit accumulator while snapshots remain coherent" in {
    val initial = (BigInt(1) << 64) - 3
    test(new L2PerfAccumulator(key, initial)) { d =>
      initialize(d)
      token(d, 2, 0, 1)
      d.io.snapshot.bits.values(0).expect(initial.U)
      d.io.snapshot.ready.poke(true.B); d.clock.step(); d.io.snapshot.ready.poke(false.B)
      token(d, 3, 0, 2)
      d.io.snapshot.bits.values(0).expect(((BigInt(1) << 64) - 1).U)
      d.io.snapshot.ready.poke(true.B); d.clock.step(); d.io.snapshot.ready.poke(false.B)
      token(d, 0, 0, 3)
      read(d, 2, 2, 3, 2, 0) // modulo-2^64 window delta is exactly five
      d.io.errors.expect(0.U)
    }
  }

}
