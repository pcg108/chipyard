package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.{
  IssuedAccess,
  RTLL2Access,
  TrafficGenAccessBatch,
  TrafficGenRoundExitReason,
}
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenTargetCycleSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGenRTLEngine banked replay"

  private val allLanes = (BigInt(1) << TrafficGenAccessBatch.lanes) - 1

  private def pokeAccess(access: RTLL2Access, id: BigInt = 0, cycle: BigInt = 0,
                         bundleId: BigInt = 0, bundleCount: Int = 1,
                         wake: Boolean = false, blocked: Boolean = false,
                         generation: BigInt = 0): Unit = {
    access.id.poke(id.U)
    access.address.poke(0.U)
    access.cycleCount.poke(cycle.U)
    access.mBundleId.poke(bundleId.U)
    access.mWakeRelevantBundle.poke(wake.B)
    access.mIsWrite.poke(false.B)
    access.mWarpBlocked.poke(blocked.B)
    access.bundleIssueCount.poke(bundleCount.U)
    access.bundleGeneration.poke(generation.U)
  }

  private def pokePackedAccess(access: UInt, id: BigInt, cycle: BigInt = 0,
                               bundleId: BigInt = 0, bundleCount: Int = 1,
                               wake: Boolean = false, blocked: Boolean = false,
                               generation: BigInt = 0): Unit = {
    val packed =
      id |
      (cycle << 128) |
      (bundleId << 192) |
      ((if (wake) BigInt(1) else BigInt(0)) << 256) |
      ((if (blocked) BigInt(1) else BigInt(0)) << 258) |
      (BigInt(bundleCount) << 259) |
      (generation << 275)
    access.poke(packed.U)
  }

  private def pokeIssued(access: IssuedAccess, id: BigInt = 0, cycle: BigInt = 0,
                         address: BigInt = 0, isWrite: Boolean = false): Unit = {
    access.requestUid.poke(id.U)
    access.cycleIssued.poke(cycle.U)
    access.address.poke(address.U)
    access.isWrite.poke(isWrite.B)
  }

  private def packedLane(value: UInt, lane: Int): BigInt =
    (value.peek().litValue >> (lane * IssuedAccess.streamWidthBits)) &
      ((BigInt(1) << IssuedAccess.streamWidthBits) - 1)

  private def packedId(value: BigInt): BigInt = value & ((BigInt(1) << 64) - 1)
  private def packedCycle(value: BigInt): BigInt =
    (value >> 64) & ((BigInt(1) << 64) - 1)
  private def packedAddress(value: BigInt): BigInt =
    (value >> 128) & ((BigInt(1) << 64) - 1)
  private def packedIsWrite(value: BigInt): Boolean =
    ((value >> 192) & 1) != 0

  private def initialize(dut: TrafficGenRTLEngine, numGenerators: Int): Unit = {
    dut.io.uploadReady.poke(true.B)
    dut.io.minIssueCycle.poke(1000.U)
    dut.io.startRound.poke(false.B)
    dut.io.trafficGenDone.poke(false.B)
    dut.io.issuedAccessBatch.ready.poke(true.B)
    dut.io.accessReadRespValid.poke(true.B)
    dut.io.accessReadRespId.poke(0.U)
    dut.io.accessReadBucketDone.poke(false.B)
    dut.io.accessReadReady.poke(true.B)
    dut.io.accessReadPrefetchPauseReq.poke(false.B)
    dut.io.accessReadLaneDoneMask.poke((allLanes ^ ((BigInt(1) << numGenerators) - 1)).U)
    dut.io.accessStoreCount.poke(numGenerators.U)
    dut.io.accessStoreMaxCycle.poke(1000.U)
    dut.io.accessStoreHasEntries.poke(true.B)
    dut.io.accessStoreHasMore.poke(false.B)
    dut.io.memActive.poke(false.B)
    for (lane <- 0 until numGenerators) {
      dut.io.memInflightAccesses(lane).poke(0.U)
      dut.io.issue(lane).ready.poke(true.B)
      dut.io.completion(lane).valid.poke(false.B)
      pokeAccess(dut.io.completion(lane).bits)
    }
    for (lane <- 0 until TrafficGenAccessBatch.lanes) {
      dut.io.accessReadDataValid(lane).poke(false.B)
      pokePackedAccess(dut.io.accessReadData(lane), id = 0)
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

  it should "let fifteen ready lanes fire while one lane retains its head" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 16, memOutstanding = 1))) { dut =>
      initialize(dut, 16)
      dut.io.minIssueCycle.poke(20.U)
      for (lane <- 0 until 16) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 100 + lane, bundleId = 1000 + lane)
      }
      dut.io.issue(3).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)

      dut.io.accessReadConsumeMask.expect((allLanes ^ (BigInt(1) << 3)).U)
      dut.io.issue(3).valid.expect(true.B)
      dut.clock.step()
      dut.io.issuedAccessBatch.valid.expect(true.B)
      dut.io.issuedAccessBatch.bits.validMask.expect((allLanes ^ (BigInt(1) << 3)).U)

      for (lane <- 0 until 16 if lane != 3) {
        dut.io.accessReadDataValid(lane).poke(false.B)
      }
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ (BigInt(1) << 3)).U)
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()
      dut.io.issue(3).ready.poke(true.B)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == (BigInt(1) << 3), dut)
      dut.clock.step()
      dut.io.accessReadDataValid(3).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "keep target time running while prefetch quiescence suppresses issue" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 1, bundleId = 1)
      startRound(dut)
      waitFor(dut.io.accessReadEn.peek().litToBoolean, dut)
      dut.io.accessReadPrefetchPauseReq.poke(true.B)
      dut.io.accessReadPrefetchPauseAck.expect(true.B)
      val pausedCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(10)
      dut.io.currentCycleAfterIssue.expect((pausedCycle + 10).U)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)

      dut.io.accessReadPrefetchPauseReq.poke(false.B)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
    }
  }

  it should "run target time in idle and stop issue at the minimum cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      val idleCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect((idleCycle + 3).U)

      val minCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.accessReadDataValid(0).poke(true.B)
      dut.io.accessReadDataValid(1).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(0),
        id = 10,
        cycle = minCycle - 1,
        bundleId = 10,
      )
      pokePackedAccess(
        dut.io.accessReadData(1),
        id = 11,
        cycle = minCycle,
        bundleId = 11,
      )

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.io.issue(0).bits.cycleCount.expect((minCycle - 1).U)
      dut.clock.step()

      // A target edge occurred after the issue at min-1. At min itself the
      // second lane is due, but the scheduling window is already closed.
      dut.io.currentCycleAfterIssue.expect(minCycle.U)
      dut.io.issue(1).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      dut.io.accessReadEn.expect(false.B)
      dut.io.accessReadBatchReady.expect(false.B)

      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ 2).U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.hasPendingWork.expect(true.B)
      dut.clock.step()

      // The unconsumed boundary-cycle head remains available to the next
      // scheduler round and receives its actual later target issue cycle.
      val nextMinCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(nextMinCycle.U)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 2, dut)
      assert(dut.io.issue(1).bits.cycleCount.peek().litValue < nextMinCycle)
      dut.clock.step()
    }
  }

  it should "carry an overdue backpressured head across the boundary" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      val minCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 12, cycle = 0, bundleId = 12)
      dut.io.issue(0).ready.poke(false.B)

      startRound(dut)
      waitFor(dut.io.currentCycleAfterIssue.peek().litValue >= minCycle, dut)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.hasPendingWork.expect(true.B)
      dut.clock.step()

      dut.io.issue(0).ready.poke(true.B)
      val nextMinCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(nextMinCycle.U)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      assert(dut.io.issue(0).bits.cycleCount.peek().litValue < nextMinCycle)
      dut.clock.step()
    }
  }

  it should "record a completion visible at the boundary before exiting" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      val minCycle = dut.io.currentCycleAfterIssue.peek().litValue + 10
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 13, bundleId = 13)

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val trackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(dut.io.currentCycleAfterIssue.peek().litValue >= minCycle, dut)

      pokeAccess(dut.io.completion(0).bits, id = 13, bundleId = trackingToken)
      dut.io.completion(0).valid.poke(true.B)
      dut.io.completion(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)

      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(13.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "keep time running while boundary outputs are backpressured" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      val minCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.issuedAccessBatch.ready.poke(false.B)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 14, bundleId = 14)

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(
        dut.io.currentCycleAfterIssue.peek().litValue >= minCycle &&
          dut.io.issuedAccessBatch.valid.peek().litToBoolean,
        dut,
      )

      val stalledCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect((stalledCycle + 3).U)
      dut.io.roundComplete.expect(false.B)

      dut.io.issuedAccessBatch.ready.poke(true.B)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "apply issues before returns and retain a bundle until every member issued" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = lane + 1, bundleId = 77, bundleCount = 2)
      }
      dut.io.issue(1).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ (BigInt(1) << 1)).U)
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()

      // The second member issues in the same cycle that the first returns.
      dut.io.issue(1).ready.poke(true.B)
      // The cache returns the engine-private bundle-table token carried on
      // io.issue, not the scheduler-owned bundle ID.
      pokeAccess(dut.io.completion(0).bits, id = 1, bundleId = 0, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.io.accessReadConsumeMask.expect(2.U)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.accessReadDataValid(1).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.completedBundleIdWriteEn.expect(false.B)

      pokeAccess(dut.io.completion(1).bits, id = 2, bundleId = 0, bundleCount = 2)
      dut.io.completion(1).valid.poke(true.B)
      waitFor(dut.io.completion(1).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(1).valid.poke(false.B)
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(77.U)
    }
  }

  it should "stamp the actual independent lane-acceptance cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 20 + lane, cycle = 3, bundleId = 20 + lane)
      }
      dut.io.issue(1).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val firstCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ 2).U)
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()
      dut.clock.step(3)
      dut.io.issue(1).ready.poke(true.B)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 2, dut)
      val secondCycle = dut.io.currentCycleAfterIssue.peek().litValue
      assert(secondCycle > firstCycle)
    }
  }

  it should "not reissue a consumed head while its HostPort snapshot is stale" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 90, bundleId = 90)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()

      // Model the bridge-to-target HostPort latency: valid and payload remain
      // unchanged after the consume mask has transferred.
      for (_ <- 0 until 3) {
        dut.io.issue(0).valid.expect(false.B)
        dut.io.accessReadConsumeMask.expect(0.U)
        dut.clock.step()
      }

      // A transient replacement head is not an acknowledgement: HostPort can
      // replay the old snapshot before the bridge observes the consume mask.
      pokePackedAccess(dut.io.accessReadData(0), id = 91, bundleId = 91)
      dut.io.issue(0).valid.expect(false.B)
      dut.clock.step()
      pokePackedAccess(dut.io.accessReadData(0), id = 90, bundleId = 90)
      dut.io.issue(0).valid.expect(false.B)
      dut.clock.step()

      // The bridge response generation advances only after the consume mask
      // crosses HostPort.  The replacement head is eligible after that ack.
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()
      pokePackedAccess(dut.io.accessReadData(0), id = 91, bundleId = 91)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
    }
  }

  it should "give a later-round alias a distinct cache tracking token" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      dut.io.minIssueCycle.poke(20.U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 200 + lane,
          bundleId = 55, bundleCount = 2, generation = 1)
      }
      startRound(dut)
      withClue("first round did not issue its aliased tranche: ") {
        waitFor(dut.io.accessReadConsumeMask.peek().litValue == 3, dut)
      }
      val firstTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      for (lane <- 0 until 2) dut.io.accessReadDataValid(lane).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      withClue("first round did not reach its min-cycle boundary: ") {
        waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      }
      dut.clock.step()

      // The old tranche is fully issued but its two returns are still
      // outstanding.  A later scheduler round may add more accesses to the
      // same intentionally aliased bundle ID.
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ 3).U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 300 + lane,
          bundleId = 55, bundleCount = 2, generation = 2)
      }
      dut.io.minIssueCycle.poke(
        (dut.io.currentCycleAfterIssue.peek().litValue + 20).U)
      startRound(dut)
      withClue("second round did not issue its aliased tranche: ") {
        waitFor(dut.io.accessReadConsumeMask.peek().litValue == 3, dut)
      }
      val secondTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      assert(secondTrackingToken != firstTrackingToken)
      dut.clock.step()
      dut.io.issuedAccessBatch.valid.expect(true.B)
      dut.io.issuedAccessBatch.bits.validMask.expect(3.U)
    }
  }

  it should "retain one bundle generation across a capacity refill" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(((BigInt(1) << 64) - 1).U)
      dut.io.accessStoreHasMore.poke(true.B)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 350, bundleId = 66,
        bundleCount = 2, generation = 7)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val firstTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.capacity)
      dut.clock.step()

      dut.io.accessStoreHasMore.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ 1).U)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 351, bundleId = 66,
        bundleCount = 1, generation = 7)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val secondTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      assert(secondTrackingToken == firstTrackingToken)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.accessReadRespId.poke(2.U)

      for (id <- Seq(350, 351)) {
        pokeAccess(dut.io.completion(0).bits, id = id, bundleId = firstTrackingToken)
        dut.io.completion(0).valid.poke(true.B)
        waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
        dut.clock.step()
        dut.io.completion(0).valid.poke(false.B)
      }
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(66.U)
    }
  }

  it should "keep draining the real cache after the upload empties and exit on a blocked wake" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(0),
        id = 400,
        bundleId = 400,
        wake = true,
        blocked = true,
      )
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)

      val cycleAfterIssue = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.roundComplete.expect(false.B)
      assert(dut.io.currentCycleAfterIssue.peek().litValue > cycleAfterIssue)

      pokeAccess(dut.io.completion(0).bits, id = 400, bundleId = 0)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
    }
  }

  it should "ignore a non-wake completion and continue to the minimum issue cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      dut.io.minIssueCycle.poke(12.U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(
          dut.io.accessReadData(lane),
          id = 500 + lane,
          bundleId = 500 + lane,
        )
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 3, dut)
      dut.clock.step()
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(false.B)
      }
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)

      pokeAccess(dut.io.completion(0).bits, id = 500, bundleId = 0)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.roundComplete.expect(false.B)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      assert(dut.io.currentCycleAfterIssue.peek().litValue >= 12)
      dut.io.hasPendingWork.expect(true.B)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
    }
  }

  it should "request another upload only at an explicit capacity boundary" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessStoreHasMore.poke(true.B)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 600, bundleId = 600)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.capacity)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "finish normally when the upload is empty and no work remains" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.accessStoreCount.poke(0.U)
      dut.io.accessStoreMaxCycle.poke(0.U)
      dut.io.accessStoreHasEntries.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      startRound(dut)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.hasPendingWork.expect(false.B)
    }
  }

  it should "exit at no-next-cycle while an incomplete bundle waits for a later round" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(((BigInt(1) << 64) - 1).U)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(0),
        id = 710,
        bundleId = 71,
        bundleCount = 2,
      )
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(0).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)

      // The only issued member returns, leaving a valid bundle-table entry
      // with no actual request in the RTL cache.  Its second member will be
      // supplied by a later scheduler round.
      pokeAccess(dut.io.completion(0).bits, id = 710, bundleId = 0, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut, limit = 20)
      assert(dut.io.currentCycleAfterIssue.peek().litValue < 20)
      // An incomplete bookkeeping entry without a resident access is not
      // pending engine work, matching the DPI engine's HasPendingWork.
      dut.io.hasPendingWork.expect(false.B)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
    }
  }

  it should "prefer the minimum issue cycle over a later capacity refill" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(0.U)
      dut.io.accessStoreMaxCycle.poke(10.U)
      dut.io.accessStoreHasMore.poke(true.B)
      dut.io.accessReadDataValid(0).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(0), id = 700, cycle = 10, bundleId = 700)
      startRound(dut)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "adapt legacy DPI writebacks to consecutive round-robin banks" in {
    test(new LegacyIssuedAccessBatchAdapter) { dut =>
      dut.io.legacy.valid.poke(false.B)
      pokeIssued(dut.io.legacy.bits)
      dut.io.batch.ready.poke(true.B)

      for (lane <- 0 until 3) {
        pokeIssued(
          dut.io.legacy.bits,
          id = 70 + lane,
          cycle = 120 + lane,
          address = 0x1000 + lane,
          isWrite = lane == 1,
        )
        dut.io.legacy.valid.poke(true.B)
        dut.clock.step()
        dut.io.legacy.valid.poke(false.B)
        dut.io.batch.valid.expect(true.B)
        dut.io.batch.bits.batchId.expect(lane.U)
        dut.io.batch.bits.validMask.expect((BigInt(1) << lane).U)
        val packed = packedLane(dut.io.batch.bits.accesses, lane)
        assert(packedId(packed) == 70 + lane)
        assert(packedCycle(packed) == 120 + lane)
        assert(packedAddress(packed) == 0x1000 + lane)
        assert(packedIsWrite(packed) == (lane == 1))
        dut.clock.step()
      }
      dut.io.legacy.valid.poke(false.B)
    }
  }
}
