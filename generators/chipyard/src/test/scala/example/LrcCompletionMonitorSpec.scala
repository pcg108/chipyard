package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.{LrcCompletionMonitor, LrcTimerHeap, LrcReportCapacityGate}
import org.scalatest.flatspec.AnyFlatSpec

class LrcCompletionMonitorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "the native LRC completion monitor"

  private def bundle(slot: Int, id: Int, pending: Int, floor: Int, wake: Boolean, generation: Int = 1): BigInt =
    BigInt(2) | (BigInt(slot) << 8) | (BigInt(id) << 24) |
      (BigInt(pending) << 88) | (BigInt(floor) << 105) | (BigInt(if (wake) 1 else 0) << 169) |
      (BigInt(generation * 65536 + slot) << 170)
  private def service(slot: Int, edges: Int*): BigInt = {
    val token = 65536 + slot
    edges.zipWithIndex.foldLeft(BigInt(1) | (BigInt(slot) << 8) | (BigInt(token) << 24) |
      (((BigInt(1) << edges.size) - 1) << 88)) { case (record, (edge, i)) =>
      record | (BigInt(65536 + edge) << (92 + 64 * i))
    }
  }
  private def initialize(dut: LrcCompletionMonitor): Unit = {
    dut.clock.setTimeout(5000)
    dut.io.metadata.valid.poke(false.B)
    dut.io.metadata.bits.poke(0.U)
    dut.io.tokenValid.poke(false.B)
    dut.io.recheck.poke(false.B)
    dut.io.tokenConsumed.poke(false.B)
    dut.io.cycle.poke(0.U)
    dut.io.returns.poke(0.U)
    dut.io.tokens.foreach(_.poke(0.U))
    dut.io.returnCycles.foreach(_.poke(0.U))
    dut.io.report.ready.poke(true.B)
    dut.clock.step(40)
    dut.io.metadataIdle.expect(true.B)
  }
  private def metadata(dut: LrcCompletionMonitor, record: BigInt): Unit = {
    dut.io.metadata.bits.poke(record.U)
    dut.io.metadata.valid.poke(true.B)
    var n = 0
    while (!dut.io.metadata.ready.peek().litToBoolean && n < 1000) { dut.clock.step(); n += 1 }
    assert(n < 1000)
    dut.clock.step()
    dut.io.metadata.valid.poke(false.B)
    n = 0
    while (!dut.io.metadataIdle.peek().litToBoolean && n < 1000) { dut.clock.step(); n += 1 }
    assert(n < 1000)
    dut.io.error.expect(0.U)
  }
  private def token(dut: LrcCompletionMonitor, at: Int, slots: Seq[Int], expectWake: Boolean,
                    hold: Int = 0): Seq[(Int, BigInt, BigInt)] = {
    dut.io.cycle.poke(at.U)
    dut.io.returns.poke(((1 << slots.size) - 1).U)
    slots.zipWithIndex.foreach { case (slot, lane) =>
      dut.io.tokens(lane).poke((65536 + slot).U)
      dut.io.returnCycles(lane).poke(at.U)
    }
    dut.io.tokenValid.poke(true.B)
    val reports = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
    var n = 0
    while (!dut.io.tokenReady.peek().litToBoolean && n < 2000) {
      dut.io.error.expect(0.U)
      if (dut.io.report.valid.peek().litToBoolean) {
        val bits = dut.io.report.bits.peek().litValue
        reports += (((bits & 255).toInt, (bits >> 8) & ((BigInt(1) << 64) - 1),
          (bits >> 72) & ((BigInt(1) << 64) - 1)))
      }
      dut.clock.step(); n += 1
    }
    assert(n < 2000)
    dut.io.error.expect(0.U)
    dut.io.wake.expect(expectWake.B)
    for (_ <- 0 until hold) {
      dut.io.report.valid.expect(false.B)
      dut.io.tokenReady.expect(true.B)
      dut.clock.step()
    }
    dut.io.tokenConsumed.poke(true.B)
    dut.clock.step()
    dut.io.tokenConsumed.poke(false.B)
    dut.io.tokenValid.poke(false.B)
    reports.toSeq
  }

  private def rejectedReturn(dut: LrcCompletionMonitor, physicalToken: BigInt,
                             expectedError: Int): Unit = {
    dut.io.cycle.poke(50.U)
    dut.io.returns.poke(1.U)
    dut.io.tokens(0).poke(physicalToken.U)
    dut.io.returnCycles(0).poke(50.U)
    dut.io.tokenValid.poke(true.B)
    var n = 0
    while (dut.io.error.peek().litValue == 0 && n < 100) {
      dut.clock.step(); n += 1
    }
    assert(n < 100, "The stale generation was not rejected")
    dut.io.error.expect(expectedError.U)
    for (_ <- 0 until 12) {
      dut.io.tokenReady.expect(false.B)
      dut.io.metadata.ready.expect(false.B)
      dut.io.report.valid.expect(false.B)
      dut.clock.step()
    }
  }

  it should "credit distinct services, delay future admissions, and consume each held token once" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, bundle(1, 101, 2, 140, wake = true))
      metadata(dut, bundle(2, 102, 1, 110, wake = true))
      metadata(dut, service(1, 1, 2))
      metadata(dut, service(2, 1))
      val first = token(dut, 120, Seq(1), expectWake = true, hold = 8)
      assert(first == Seq((1, BigInt(65537), BigInt(120)), (2, BigInt(102), BigInt(120))))
      dut.io.pending.expect(true.B)
      assert(token(dut, 130, Seq(2), expectWake = false) == Seq((1, BigInt(65538), BigInt(130))))
      dut.io.serviceCount.expect(0.U)
      dut.io.bundleCount.expect(1.U)
      assert(token(dut, 139, Nil, expectWake = false).isEmpty)
      assert(token(dut, 140, Nil, expectWake = true) == Seq((2, BigInt(101), BigInt(140))))
      dut.io.pending.expect(false.B)
    }
  }

  it should "complete all same-cycle returns before publishing a wake fence" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, bundle(0, 200, 2, 0, wake = true))
      metadata(dut, bundle(1, 201, 1, 0, wake = false))
      metadata(dut, service(3, 0))
      metadata(dut, service(4, 0, 1))
      val result = token(dut, 12, Seq(3, 4), expectWake = true)
      assert(result.count(_._1 == 1) == 2)
      assert(result.filter(_._1 == 2).map(_._2).toSet == Set(BigInt(200), BigInt(201)))
      assert(result.forall(_._3 == 12))
      dut.io.pending.expect(false.B)
    }
  }

  it should "preserve a timer across absolute metadata replay and update its wake interest" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, bundle(2, 300, 0, 80, wake = false))
      metadata(dut, bundle(2, 300, 0, 80, wake = true))
      dut.io.timerCount.expect(1.U)
      assert(token(dut, 79, Nil, expectWake = false).isEmpty)
      assert(token(dut, 80, Nil, expectWake = true) == Seq((2, BigInt(300), BigInt(80))))
      // A retired slot can be reused, guarded by a new opaque bundle token.
      metadata(dut, bundle(2, 301, 0, 90, wake = false, generation = 2))
      assert(token(dut, 90, Nil, expectWake = false) == Seq((2, BigInt(301), BigInt(90))))
      dut.io.serviceCount.expect(0.U)
      dut.io.bundleCount.expect(0.U)
      dut.io.timerCount.expect(0.U)
    }
  }

  it should "reset a held response, pending services, and future admission timers before reusing their slots" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, bundle(1, 401, 1, 0, wake = true))
      metadata(dut, bundle(2, 402, 0, 100, wake = true))
      metadata(dut, service(1, 1))
      dut.io.report.ready.poke(false.B)
      dut.io.cycle.poke(70.U)
      dut.io.returns.poke(1.U)
      dut.io.tokens(0).poke(65537.U)
      dut.io.returnCycles(0).poke(70.U)
      dut.io.tokenValid.poke(true.B)
      var n = 0
      while (!dut.io.report.valid.peek().litToBoolean && n < 100) {
        dut.clock.step(); n += 1
      }
      assert(n < 100)
      dut.io.tokenReady.expect(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      initialize(dut)
      dut.io.serviceCount.expect(0.U)
      dut.io.bundleCount.expect(0.U)
      dut.io.timerCount.expect(0.U)
      dut.io.report.valid.expect(false.B)
      assert(token(dut, 200, Nil, expectWake = false).isEmpty)
      metadata(dut, bundle(1, 403, 1, 0, wake = false))
      metadata(dut, service(1, 1))
      assert(token(dut, 201, Seq(1), expectWake = false) ==
        Seq((1, BigInt(65537), BigInt(201)), (2, BigInt(403), BigInt(201))))
      dut.io.pending.expect(false.B)
    }
  }

  it should "reject a stale physical return after its service slot is reused" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, service(1))
      assert(token(dut, 10, Seq(1), expectWake = false) == Seq((1, BigInt(65537), BigInt(10))))
      val generationTwoService = BigInt(1) | (BigInt(1) << 8) | (BigInt(131073) << 24)
      metadata(dut, generationTwoService)
      rejectedReturn(dut, 65537, expectedError = 7)
      dut.io.serviceCount.expect(1.U)
    }
  }

  it should "reject a stale bundle edge without completing the new generation in that slot" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      metadata(dut, bundle(1, 410, 0, 10, wake = false))
      assert(token(dut, 10, Nil, expectWake = false) == Seq((2, BigInt(410), BigInt(10))))
      metadata(dut, bundle(1, 411, 1, 0, wake = true, generation = 2))
      // The physical service is current, but its edge names the retired bundle.
      metadata(dut, service(2, 1))
      rejectedReturn(dut, 65538, expectedError = 8)
      dut.io.bundleCount.expect(1.U)
    }
  }

  it should "fill every timer slot and reuse a retired slot without losing the remaining admissions" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      for (slot <- 0 until 32) metadata(dut, bundle(slot, 500 + slot, 0, 100 + slot, wake = false))
      dut.io.bundleCount.expect(32.U)
      dut.io.timerCount.expect(32.U)
      metadata(dut, bundle(10, 510, 0, 110, wake = false))
      dut.io.timerCount.expect(32.U) // Absolute replay must not allocate twice.
      assert(token(dut, 100, Nil, expectWake = false) == Seq((2, BigInt(500), BigInt(100))))
      metadata(dut, bundle(0, 600, 0, 200, wake = true, generation = 2))
      dut.io.timerCount.expect(32.U)
      val remaining = token(dut, 131, Nil, expectWake = false)
      assert(remaining == (1 until 32).map(slot => (2, BigInt(500 + slot), BigInt(100 + slot))))
      dut.io.timerCount.expect(1.U)
      assert(token(dut, 200, Nil, expectWake = true) == Seq((2, BigInt(600), BigInt(200))))
      dut.io.pending.expect(false.B)
    }
  }

  it should "credit admissions added at a parked cycle without a second physical return or target step" in {
    test(new LrcCompletionMonitor(2, 32)) { dut =>
      initialize(dut)
      dut.io.cycle.poke(40.U)
      dut.io.tokenValid.poke(true.B)
      var n = 0
      while (!dut.io.tokenReady.peek().litToBoolean && n < 100) { dut.clock.step(); n += 1 }
      assert(n < 100)
      dut.io.tokenValid.poke(false.B) // Keep the target token parked, permit metadata.
      metadata(dut, bundle(1, 700, 0, 40, wake = true))
      dut.io.recheck.poke(true.B); dut.clock.step(); dut.io.recheck.poke(false.B)
      val reports = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      n = 0
      while (!dut.io.tokenReady.peek().litToBoolean && n < 100) {
        if (dut.io.report.valid.peek().litToBoolean) reports += dut.io.report.bits.peek().litValue
        dut.clock.step(); n += 1
      }
      assert(n < 100)
      assert(reports.toSeq == Seq(BigInt(2) | (BigInt(700) << 8) | (BigInt(40) << 72)))
      dut.io.wake.expect(true.B)
      dut.io.pending.expect(false.B)
      for (_ <- 0 until 8) {
        dut.io.report.valid.expect(false.B); dut.io.tokenReady.expect(true.B); dut.clock.step()
      }
      dut.io.tokenConsumed.poke(true.B); dut.clock.step(); dut.io.tokenConsumed.poke(false.B)
      dut.io.tokenIdle.expect(true.B)
    }
  }

  it should "reserve enough reports before accepting a token and retain the reservation until completion" in {
    test(new LrcReportCapacityGate(4, 128)) { dut =>
      dut.io.enabled.poke(true.B); dut.io.tokenIdle.poke(true.B)
      dut.io.count.poke(120.U); dut.io.bundles.poke(5.U); dut.io.returns.poke(15.U)
      dut.io.drainRequired.expect(true.B)
      // Host drains without consuming the held target token.
      dut.io.count.poke(0.U); dut.io.drainRequired.expect(false.B)
      // Exactly full is accepted; after processing starts, increasing report
      // occupancy must not revoke its reservation or deadlock the held token.
      dut.io.count.poke(119.U); dut.io.drainRequired.expect(false.B)
      dut.io.tokenIdle.poke(false.B); dut.io.count.poke(128.U)
      dut.io.drainRequired.expect(false.B)
      dut.io.tokenIdle.poke(true.B); dut.io.drainRequired.expect(true.B)
      dut.io.enabled.poke(false.B); dut.io.drainRequired.expect(false.B)
    }
  }

  it should "order a BRAM heap against an independent software priority queue" in {
    test(new LrcTimerHeap(32)) { dut =>
      dut.io.push.valid.poke(false.B); dut.io.pop.ready.poke(false.B)
      dut.io.push.bits.cycle.poke(0.U); dut.io.push.bits.token.poke(0.U); dut.io.push.bits.slot.poke(0.U)
      val oracle = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      val rng = new scala.util.Random(43)
      def awaitIdle(): Unit = {
        var n = 0
        while (!dut.io.idle.peek().litToBoolean && n < 1000) { dut.clock.step(); n += 1 }
        assert(n < 1000)
      }
      def pop(): Unit = {
        awaitIdle()
        val expected = oracle.min
        dut.io.pop.valid.expect(true.B)
        dut.io.pop.bits.cycle.expect(expected._1.U)
        dut.io.pop.bits.slot.expect(expected._2.U)
        dut.io.pop.ready.poke(true.B); dut.clock.step(); dut.io.pop.ready.poke(false.B)
        oracle.remove(oracle.indexOf(expected)); awaitIdle()
      }
      for (i <- 0 until 200) {
        if (oracle.size == 32 || (oracle.nonEmpty && rng.nextBoolean())) pop()
        else {
          awaitIdle()
          val at = rng.nextInt(100)
          val slot = i % 32
          dut.io.push.bits.cycle.poke(at.U); dut.io.push.bits.token.poke((i + 1).U)
          dut.io.push.bits.slot.poke(slot.U); dut.io.push.valid.poke(true.B)
          dut.io.push.ready.expect(true.B); dut.clock.step(); dut.io.push.valid.poke(false.B)
          oracle += ((at, slot)); awaitIdle()
        }
      }
      while (oracle.nonEmpty) pop()
      dut.io.count.expect(0.U)
    }
  }
}
