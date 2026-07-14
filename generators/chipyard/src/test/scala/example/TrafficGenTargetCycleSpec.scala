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

  private def initialize(dut: TrafficGenRTLEngine, maxCycle: BigInt, minCycle: BigInt): Unit = {
    dut.io.uploadReady.poke(true.B)
    dut.io.minIssueCycle.poke(minCycle.U)
    dut.io.startRound.poke(false.B)
    dut.io.trafficGenDone.poke(false.B)
    dut.io.issuedAccessWriteback.ready.poke(true.B)
    dut.io.accessReadRespValid.poke(false.B)
    dut.io.accessReadRespId.poke(0.U)
    dut.io.accessReadBucketDone.poke(false.B)
    dut.io.accessReadReady.poke(true.B)
    dut.io.accessStoreCount.poke(1.U)
    dut.io.accessStoreMaxCycle.poke(maxCycle.U)
    dut.io.accessStoreHasEntries.poke(true.B)
    dut.io.memActive.poke(false.B)
    dut.io.memInflightAccesses(0).poke(0.U)
    dut.io.issue(0).ready.poke(true.B)
    dut.io.completion(0).valid.poke(false.B)
    pokeAccess(dut.io.completion(0).bits)
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

  it should "advance query cycles through handshake delay and issue a final query past the horizon" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, maxCycle = 5, minCycle = 5)
      startRound(dut)

      val firstCycle = acceptRequest(dut)
      dut.clock.step(10)
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
}
