package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.{L2Access, TrafficGenAccessBatch}
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenTargetCycleSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGenRTLEngine target-cycle queries"

  private def pokeAccess(access: L2Access, bundleId: BigInt = 0, wake: Boolean = false,
                         blocked: Boolean = false): Unit = {
    access.id.poke(0.U)
    access.address.poke(0.U)
    access.cycleCount.poke(0.U)
    access.mSubpartition.poke(0.U)
    access.mSetIndex.poke(0.U)
    access.mTag.poke(0.U)
    access.mMask.poke(0.U)
    access.smId.poke(0.U)
    access.schedulerId.poke(0.U)
    access.warpId.poke(0.U)
    access.mBundleId.poke(bundleId.U)
    access.mWakeRelevantBundle.poke(wake.B)
    access.mIsWrite.poke(false.B)
    access.mWarpBlocked.poke(blocked.B)
  }

  private def packedLane(value: UInt, lane: Int): BigInt =
    (value.peek().litValue >> (lane * L2Access.streamWidthBits)) &
      ((BigInt(1) << L2Access.streamWidthBits) - 1)

  private def packedId(value: BigInt): BigInt = value & ((BigInt(1) << 64) - 1)
  private def packedCycle(value: BigInt): BigInt =
    (value >> 128) & ((BigInt(1) << 64) - 1)

  private def initialize(
    dut: TrafficGenRTLEngine,
    maxCycle: BigInt,
    minCycle: BigInt,
    numGenerators: Int = 1,
  ): Unit = {
    dut.io.uploadReady.poke(true.B)
    dut.io.minIssueCycle.poke(minCycle.U)
    dut.io.startRound.poke(false.B)
    dut.io.trafficGenDone.poke(false.B)
    dut.io.issuedAccessBatch.ready.poke(true.B)
    dut.io.accessReadRespValid.poke(false.B)
    dut.io.accessReadRespId.poke(0.U)
    dut.io.accessReadBucketDone.poke(false.B)
    dut.io.accessReadReady.poke(true.B)
    dut.io.accessStoreCount.poke(1.U)
    dut.io.accessStoreMaxCycle.poke(maxCycle.U)
    dut.io.accessStoreHasEntries.poke(true.B)
    dut.io.memActive.poke(false.B)
    for (lane <- 0 until numGenerators) {
      dut.io.memInflightAccesses(lane).poke(0.U)
      dut.io.issue(lane).ready.poke(true.B)
      dut.io.completion(lane).valid.poke(false.B)
      pokeAccess(dut.io.completion(lane).bits)
    }
    for (lane <- 0 until TrafficGenAccessBatch.lanes) {
      dut.io.accessReadDataValid(lane).poke(false.B)
      pokeAccess(dut.io.accessReadData(lane))
    }
  }

  private def startRound(dut: TrafficGenRTLEngine): Unit = {
    dut.io.startRound.poke(true.B)
    dut.clock.step()
    dut.io.startRound.poke(false.B)
  }

  private def waitFor(condition: => Boolean, dut: TrafficGenRTLEngine, limit: Int = 100): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(condition, s"condition was not reached within $limit cycles")
  }

  private def acceptRequest(dut: TrafficGenRTLEngine): BigInt = {
    waitFor(dut.io.accessReadEn.peek().litToBoolean, dut)
    val requestedCycle = dut.io.accessReadCycle.peek().litValue
    assert(requestedCycle == dut.io.currentCycleAfterIssue.peek().litValue)
    dut.clock.step()
    dut.io.accessReadReady.poke(false.B)
    dut.clock.step()
    requestedCycle
  }

  private def sendEmptyBucketDone(dut: TrafficGenRTLEngine, responseId: Int): Unit = {
    waitFor(dut.io.accessReadBatchReady.peek().litToBoolean, dut)
    dut.io.accessReadRespId.poke(responseId.U)
    dut.io.accessReadBucketDone.poke(true.B)
    dut.io.accessReadRespValid.poke(true.B)
    dut.clock.step()
    dut.io.accessReadRespValid.poke(false.B)
    dut.io.accessReadBucketDone.poke(false.B)
    dut.io.accessReadReady.poke(true.B)
  }

  it should "freeze target time while waiting for a batch and issue a final query past the horizon" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, maxCycle = 5, minCycle = 5)
      startRound(dut)

      val firstCycle = acceptRequest(dut)
      val waitCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(10)
      dut.io.currentCycleAfterIssue.expect(waitCycle.U)
      sendEmptyBucketDone(dut, responseId = 1)

      val finalCycle = acceptRequest(dut)
      assert(finalCycle > firstCycle)
      assert(finalCycle >= 5)
      sendEmptyBucketDone(dut, responseId = 2)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.hasPendingWork.expect(false.B)
    }
  }

  it should "stop after the initial query when a wake-relevant blocked bundle completes" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, maxCycle = 100, minCycle = 50)
      startRound(dut)
      acceptRequest(dut)

      waitFor(dut.io.accessReadBatchReady.peek().litToBoolean, dut)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokeAccess(dut.io.accessReadData(0), bundleId = 42, wake = true, blocked = true)
      dut.io.accessReadRespId.poke(1.U)
      dut.io.accessReadBucketDone.poke(true.B)
      dut.io.accessReadRespValid.poke(true.B)
      dut.clock.step()
      dut.io.accessReadRespValid.poke(false.B)
      dut.io.accessReadBucketDone.poke(false.B)
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadReady.poke(true.B)

      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
      pokeAccess(dut.io.completion(0).bits, bundleId = 42)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)

      var extraRequests = 0
      var cycles = 0
      while (!dut.io.roundComplete.peek().litToBoolean && cycles < 100) {
        if (dut.io.accessReadEn.peek().litToBoolean) extraRequests += 1
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.roundComplete.peek().litToBoolean)
      assert(extraRequests == 0)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "enqueue one 16-lane batch and flush it before round completion" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 16, memOutstanding = 1))) { dut =>
      initialize(dut, maxCycle = 0, minCycle = 0, numGenerators = 16)
      dut.io.issuedAccessBatch.ready.poke(false.B)
      startRound(dut)
      acceptRequest(dut)

      waitFor(dut.io.accessReadBatchReady.peek().litToBoolean, dut)
      for (lane <- 0 until TrafficGenAccessBatch.lanes) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokeAccess(dut.io.accessReadData(lane), bundleId = lane + 1)
        dut.io.accessReadData(lane).id.poke((100 + lane).U)
      }
      dut.io.accessReadRespId.poke(1.U)
      dut.io.accessReadBucketDone.poke(true.B)
      dut.io.accessReadRespValid.poke(true.B)
      dut.clock.step()
      dut.io.accessReadRespValid.poke(false.B)
      dut.io.accessReadBucketDone.poke(false.B)
      dut.io.accessReadDataValid.foreach(_.poke(false.B))
      dut.io.accessReadReady.poke(true.B)

      waitFor(dut.io.issuedAccessBatch.valid.peek().litToBoolean, dut)
      dut.io.issuedAccessBatch.bits.batchId.expect(0.U)
      dut.io.issuedAccessBatch.bits.validMask.expect("hffff".U)
      val issuedCycle = packedCycle(packedLane(dut.io.issuedAccessBatch.bits.accesses, 0))
      for (lane <- 0 until TrafficGenAccessBatch.lanes) {
        val packed = packedLane(dut.io.issuedAccessBatch.bits.accesses, lane)
        assert(packedId(packed) == 100 + lane)
        assert(packedCycle(packed) == issuedCycle)
      }

      // The queue must hold the complete batch stable, and completion must not
      // escape while the final issued batch is backpressured.
      dut.clock.step(5)
      dut.io.issuedAccessBatch.valid.expect(true.B)
      dut.io.issuedAccessBatch.bits.batchId.expect(0.U)
      dut.io.issuedAccessBatch.bits.validMask.expect("hffff".U)
      dut.io.roundComplete.expect(false.B)

      dut.io.issuedAccessBatch.ready.poke(true.B)
      dut.clock.step()
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "split a partial response into ordered batches and stall on FIFO backpressure" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 4, memOutstanding = 1))) { dut =>
      initialize(dut, maxCycle = 0, minCycle = 0, numGenerators = 4)
      dut.io.issuedAccessBatch.ready.poke(false.B)
      startRound(dut)
      acceptRequest(dut)

      waitFor(dut.io.accessReadBatchReady.peek().litToBoolean, dut)
      for (lane <- 0 until 10) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokeAccess(dut.io.accessReadData(lane), bundleId = lane + 1)
        dut.io.accessReadData(lane).id.poke((200 + lane).U)
      }
      dut.io.accessReadRespId.poke(1.U)
      dut.io.accessReadBucketDone.poke(true.B)
      dut.io.accessReadRespValid.poke(true.B)
      dut.clock.step()
      dut.io.accessReadRespValid.poke(false.B)
      dut.io.accessReadBucketDone.poke(false.B)
      dut.io.accessReadDataValid.foreach(_.poke(false.B))
      dut.io.accessReadReady.poke(true.B)

      waitFor(dut.io.issuedAccessBatch.valid.peek().litToBoolean, dut)
      dut.io.issuedAccessBatch.bits.batchId.expect(0.U)
      dut.io.issuedAccessBatch.bits.validMask.expect("hf".U)
      for (lane <- 0 until 4) {
        assert(packedId(packedLane(dut.io.issuedAccessBatch.bits.accesses, lane)) == 200 + lane)
      }

      // Two batches fit in the FIFO. With its output blocked, the third issue
      // chunk cannot fire and lane 0 remains backpressured.
      dut.clock.step(3)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.issuedAccessBatch.ready.poke(true.B)
      dut.clock.step()
      dut.io.issuedAccessBatch.bits.batchId.expect(1.U)
      dut.io.issuedAccessBatch.bits.validMask.expect("hf".U)
      for (lane <- 0 until 4) {
        assert(packedId(packedLane(dut.io.issuedAccessBatch.bits.accesses, lane)) == 204 + lane)
      }
      dut.clock.step()
      waitFor(dut.io.issuedAccessBatch.valid.peek().litToBoolean, dut)
      dut.io.issuedAccessBatch.bits.batchId.expect(2.U)
      dut.io.issuedAccessBatch.bits.validMask.expect(3.U)
      assert(packedId(packedLane(dut.io.issuedAccessBatch.bits.accesses, 0)) == 208)
      assert(packedId(packedLane(dut.io.issuedAccessBatch.bits.accesses, 1)) == 209)
      dut.clock.step()
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "adapt legacy DPI writebacks to consecutive lane-0-only batches" in {
    test(new LegacyIssuedAccessBatchAdapter) { dut =>
      dut.io.legacy.valid.poke(false.B)
      pokeAccess(dut.io.legacy.bits)
      dut.io.batch.ready.poke(false.B)

      dut.io.legacy.bits.id.poke(77.U)
      dut.io.legacy.bits.cycleCount.poke(123.U)
      dut.io.legacy.valid.poke(true.B)
      dut.clock.step()
      dut.io.legacy.valid.poke(false.B)

      dut.io.batch.valid.expect(true.B)
      dut.io.batch.bits.batchId.expect(0.U)
      dut.io.batch.bits.validMask.expect(1.U)
      assert(packedId(packedLane(dut.io.batch.bits.accesses, 0)) == 77)
      assert(packedCycle(packedLane(dut.io.batch.bits.accesses, 0)) == 123)
      assert(packedLane(dut.io.batch.bits.accesses, 1) == 0)
      dut.clock.step(3)
      dut.io.batch.bits.batchId.expect(0.U)
      assert(packedId(packedLane(dut.io.batch.bits.accesses, 0)) == 77)

      dut.io.batch.ready.poke(true.B)
      dut.clock.step()
      dut.io.legacy.bits.id.poke(88.U)
      dut.io.legacy.valid.poke(true.B)
      dut.clock.step()
      dut.io.legacy.valid.poke(false.B)
      dut.io.batch.valid.expect(true.B)
      dut.io.batch.bits.batchId.expect(1.U)
      dut.io.batch.bits.validMask.expect(1.U)
      assert(packedId(packedLane(dut.io.batch.bits.accesses, 0)) == 88)
    }
  }
}
