package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.{
  IssuedAccess,
  RTLL2Access,
  TrafficGenRoundExitReason,
}
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenTargetCycleSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGenRTLEngine banked replay"

  private val slotsPerLane = 3
  private def totalSlots(numGenerators: Int): Int = numGenerators * slotsPerLane
  private def allLanes(dut: TrafficGenRTLEngine): BigInt =
    (BigInt(1) << dut.io.issue.length) - 1
  private def replaySlot(lane: Int, slot: Int = 0): Int =
    lane * slotsPerLane + slot
  private def replaySlotMask(entries: (Int, Int)*): BigInt =
    entries.foldLeft(BigInt(0)) {
      case (packed, (lane, slot)) => packed | (BigInt(1) << replaySlot(lane, slot))
    }
  private def allLaneSlotZeroMask(dut: TrafficGenRTLEngine): BigInt =
    (0 until dut.io.issue.length).foldLeft(BigInt(0)) {
      case (packed, lane) => packed | (BigInt(1) << replaySlot(lane))
    }
  private def allLaneSlotZeroGenerationOne(dut: TrafficGenRTLEngine): BigInt =
    (0 until dut.io.issue.length).foldLeft(BigInt(0)) {
      case (packed, lane) => packed | (BigInt(1) << (2 * replaySlot(lane)))
    }

  private def laneGenerations(entries: (Int, Int)*): BigInt =
    entries.foldLeft(BigInt(0)) {
      case (packed, (lane, generation)) =>
        packed | (BigInt(generation & 3) << (2 * replaySlot(lane)))
    }

  private def slotGenerations(entries: (Int, Int, Int)*): BigInt =
    entries.foldLeft(BigInt(0)) {
      case (packed, (lane, slot, generation)) =>
        packed | (BigInt(generation & 3) << (2 * replaySlot(lane, slot)))
    }

  private def pokeAccess(access: RTLL2Access, id: BigInt = 0, cycle: BigInt = 0,
                         bundleId: BigInt = 0, bundleCount: Int = 1,
                         wake: Boolean = false, blocked: Boolean = false,
                         generation: BigInt = 0, launchId: BigInt = 0,
                         isWrite: Boolean = false): Unit = {
    access.launchId.poke(launchId.U)
    access.id.poke(id.U)
    access.address.poke(0.U)
    access.cycleCount.poke(cycle.U)
    access.mBundleId.poke(bundleId.U)
    access.mWakeRelevantBundle.poke(wake.B)
    access.mIsWrite.poke(isWrite.B)
    access.mWarpBlocked.poke(blocked.B)
    access.bundleIssueCount.poke(bundleCount.U)
    access.bundleGeneration.poke(generation.U)
  }

  private def pokePackedAccess(access: UInt, id: BigInt, cycle: BigInt = 0,
                               bundleId: BigInt = 0, bundleCount: Int = 1,
                               wake: Boolean = false, blocked: Boolean = false,
                               generation: BigInt = 0, launchId: BigInt = 0,
                               isWrite: Boolean = false): Unit = {
    val packed =
      id |
      (cycle << 128) |
      (bundleId << 192) |
      ((if (wake) BigInt(1) else BigInt(0)) << 256) |
      ((if (blocked) BigInt(1) else BigInt(0)) << 258) |
      (BigInt(bundleCount) << 259) |
      (generation << 275) |
      (launchId << 307) |
      ((if (isWrite) BigInt(1) else BigInt(0)) << 257)
    access.poke(packed.U)
  }

  private def pokeIssued(access: IssuedAccess, id: BigInt = 0, cycle: BigInt = 0,
                         address: BigInt = 0, isWrite: Boolean = false): Unit = {
    access.launchId.poke(0.U)
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
    dut.io.controlPending.poke(false.B)
    dut.io.launchStatusIds.poke(0.U)
    dut.io.launchStatuses.poke(0.U)
    dut.io.sessionStatus.poke(0.U)
    dut.io.issuedAccessBatch.ready.poke(true.B)
    dut.io.accessReadRespValid.poke(true.B)
    dut.io.accessReadRespId.poke(0.U)
    dut.io.accessReadBucketDone.poke(false.B)
    dut.io.accessReadReady.poke(true.B)
    dut.io.accessReadPrefetchPauseReq.poke(false.B)
    dut.io.accessReadLaneDoneMask.poke(0.U)
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
    for (slot <- 0 until totalSlots(numGenerators)) {
      dut.io.accessReadDataValid(slot).poke(false.B)
      pokePackedAccess(dut.io.accessReadData(slot), id = 0)
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

  it should "let a ready lane fire while a backpressured lane retains its head" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 1))) { dut =>
      initialize(dut, 2)
      dut.io.minIssueCycle.poke(20.U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = 100 + lane, bundleId = 1000 + lane)
      }
      dut.io.issue(1).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)

      dut.io.accessReadConsumeMask.expect(replaySlotMask((0, 0)).U)
      dut.io.issue(1).valid.expect(true.B)
      dut.clock.step()
      dut.io.issuedAccessBatch.valid.expect(true.B)
      dut.io.issuedAccessBatch.bits.validMask.expect(1.U)

      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ (BigInt(1) << 1)).U)
      dut.io.issue(1).ready.poke(true.B)
      // Lane 1 never consumed the first snapshot, so it remains independently
      // eligible without an acknowledgement for lane 0.
      dut.io.accessReadConsumeMask.expect(replaySlotMask((1, 0)).U)
      dut.clock.step()
      dut.io.issuedAccessBatch.bits.validMask.expect(2.U)
    }
  }

  for (numGenerators <- Seq(8, 16)) {
    it should s"pipeline three slots across $numGenerators lanes and issue slot-zero replacements at N plus three" in {
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = numGenerators, memOutstanding = 2)))
        .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      initialize(dut, numGenerators)
      dut.io.minIssueCycle.poke(100.U)
      for (lane <- 0 until numGenerators; slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(lane, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane, slot)),
          id = 1000 * slot + lane, bundleId = 1000 * slot + lane)
      }

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == allLaneSlotZeroMask(dut), dut)
      val firstIssueCycle = dut.io.issue(0).bits.cycleCount.peek().litValue
      dut.io.issue(0).bits.id.expect(0.U)
      dut.clock.step()

      dut.io.issue.foreach(_.valid.expect(true.B))
      dut.io.issue(0).bits.id.expect(1000.U)
      dut.io.issue(0).bits.cycleCount.expect((firstIssueCycle + 1).U)
      dut.io.accessReadConsumeMask.expect(
        (allLaneSlotZeroMask(dut) << 1).U)
      dut.clock.step()

      dut.io.issue.foreach(_.valid.expect(true.B))
      dut.io.issue(0).bits.id.expect(2000.U)
      dut.io.issue(0).bits.cycleCount.expect((firstIssueCycle + 2).U)
      dut.io.accessReadConsumeMask.expect(
        (allLaneSlotZeroMask(dut) << 2).U)
      dut.clock.step()

      // The lane pointers are back at slot zero, whose stale snapshot remains
      // blocked until the matching generations and replacement heads arrive.
      dut.io.issue.foreach(_.valid.expect(false.B))
      dut.io.accessReadConsumeMask.expect(0.U)
      for (lane <- 0 until numGenerators) {
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)),
          id = 3000 + lane, bundleId = 3000 + lane)
      }
      dut.io.accessReadRespId.poke(allLaneSlotZeroGenerationOne(dut).U)
      dut.io.issue.foreach(_.valid.expect(true.B))
      dut.io.issue(0).bits.id.expect(3000.U)
      dut.io.issue(0).bits.cycleCount.expect((firstIssueCycle + 3).U)
      dut.io.accessReadConsumeMask.expect(allLaneSlotZeroMask(dut).U)
      }
    }
  }

  it should "acknowledge only the matching pending slot" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
      initialize(dut, 1)
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = 10 + slot, bundleId = 10 + slot)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(2.U)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(4.U)
      dut.clock.step()

      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 20, bundleId = 20)
      // Slot one may acknowledge, but it cannot unlock the selected slot zero.
      dut.io.accessReadRespId.poke(slotGenerations((0, 1, 1)).U)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      dut.clock.step()

      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (0, 1, 1)).U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(20.U)
      dut.io.accessReadConsumeMask.expect(1.U)
    }
  }

  it should "recognize a two-bit slot generation wrapping from three to zero" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadRespId.poke(laneGenerations(0 -> 3).U)
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = 40 + slot, bundleId = 40 + slot)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step(3)

      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 41, bundleId = 41)
      dut.io.accessReadRespId.poke(0.U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(41.U)
      dut.io.accessReadConsumeMask.expect(1.U)
    }
  }

  it should "acknowledge safely while issued-batch output is full" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
      initialize(dut, 1)
      dut.io.issuedAccessBatch.ready.poke(false.B)
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = 30 + slot, bundleId = 30 + slot)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(2.U)
      dut.clock.step()

      // Two batches fill the output queue. Slot two remains selected but cannot
      // issue until output capacity is available.
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      dut.clock.step()
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)

      dut.io.issuedAccessBatch.ready.poke(true.B)
      dut.clock.step()
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 4, dut)
      dut.io.issue(0).bits.id.expect(32.U)
    }
  }

  it should "keep target time running while prefetch quiescence suppresses issue" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 1, bundleId = 1)
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

  it should "start target time with round one and stop issue at the minimum cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect(0.U)

      // A start request cannot establish the workload epoch until its upload
      // has been accepted.
      dut.io.uploadReady.poke(false.B)
      startRound(dut)
      dut.io.currentCycleAfterIssue.expect(0.U)
      dut.io.targetBusy.expect(false.B)
      dut.io.uploadReady.poke(true.B)

      val minCycle = BigInt(8)
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      dut.io.accessReadDataValid(replaySlot(1)).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(0)),
        id = 10,
        cycle = minCycle - 1,
        bundleId = 10,
      )
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(1)),
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

      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ 2).U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.hasPendingWork.expect(true.B)
      dut.clock.step()
      val idleCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect((idleCycle + 3).U)

      // The unconsumed boundary-cycle head remains available to the next
      // scheduler round and receives its actual later target issue cycle.
      val nextMinCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(nextMinCycle.U)
      val beforeLaterRound = dut.io.currentCycleAfterIssue.peek().litValue
      startRound(dut)
      dut.io.currentCycleAfterIssue.expect((beforeLaterRound + 1).U)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == replaySlotMask((1, 0)), dut)
      assert(dut.io.issue(1).bits.cycleCount.peek().litValue < nextMinCycle)
      dut.clock.step()
    }
  }

  it should "preserve session time through idle and freeze its final cycle at completion" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessStoreHasEntries.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.minIssueCycle.poke(8.U)
      startRound(dut)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      assert(dut.io.currentCycleAfterIssue.peek().litValue >= 8)
      dut.clock.step()
      val idleCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect((idleCycle + 3).U)
      dut.io.trafficGenDone.poke(true.B)
      dut.io.sessionStatus.poke(8.U)
      val finalCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(5)
      dut.io.currentCycleAfterIssue.expect(finalCycle.U)
      dut.io.targetBusy.expect(false.B)
      dut.io.hasPendingWork.expect(false.B)
    }
  }

  it should "carry an overdue backpressured head across the boundary" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      val minCycle = dut.io.currentCycleAfterIssue.peek().litValue + 8
      dut.io.minIssueCycle.poke(minCycle.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 12, cycle = 0, bundleId = 12)
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
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 13, bundleId = 13)

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val trackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
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
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 14, bundleId = 14)

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
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

  it should "complete aliased scheduler members as one replay group after every member issues and returns" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      for (slot <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = slot + 1, bundleId = 77, bundleCount = 2, launchId = 43,
          wake = slot == 0, blocked = slot == 0)
      }
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1)).U)

      // The second member issues in the same cycle that the first returns.
      // The cache returns the engine-private bundle-table token carried on
      // io.issue, not the scheduler-owned bundle ID.
      pokeAccess(dut.io.completion(0).bits, id = 1, bundleId = 0, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.io.accessReadConsumeMask.expect(replaySlotMask((0, 1)).U)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.accessReadDataValid(replaySlot(0, 1)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (0, 1, 1)).U)
      dut.io.completedBundleIdWriteEn.expect(false.B)
      dut.clock.step(3)
      dut.io.completedBundleIdWriteEn.expect(false.B)
      dut.io.roundComplete.expect(false.B)

      pokeAccess(dut.io.completion(0).bits, id = 2, bundleId = 0, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(77.U)
    }
  }

  it should "stamp the actual independent lane-acceptance cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = 20 + lane, cycle = 3, bundleId = 20 + lane)
      }
      dut.io.issue(1).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val firstCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ 2).U)
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()
      dut.clock.step(3)
      dut.io.issue(1).ready.poke(true.B)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == replaySlotMask((1, 0)), dut)
      val secondCycle = dut.io.currentCycleAfterIssue.peek().litValue
      assert(secondCycle > firstCycle)
    }
  }

  it should "not reissue a consumed head while its HostPort snapshot is stale" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
      initialize(dut, 1)
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = 90 + slot, bundleId = 90 + slot)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()

      // Slots one and two remain usable while slot zero's HostPort snapshot is
      // stale. Once the pointer returns to slot zero, stale data is blocked.
      dut.io.accessReadConsumeMask.expect(2.U)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(4.U)
      dut.clock.step()
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)

      // Neither an invalid changed generation nor a valid unchanged
      // generation acknowledges the previous consume.
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 100, bundleId = 100)
      dut.io.accessReadRespValid.poke(false.B)
      dut.io.accessReadRespId.poke(1.U)
      dut.io.issue(0).valid.expect(false.B)
      dut.clock.step()
      dut.io.accessReadRespValid.poke(true.B)
      dut.io.accessReadRespId.poke(0.U)
      dut.io.issue(0).valid.expect(false.B)
      dut.clock.step()

      // The bridge response generation advances only after the consume mask
      // crosses HostPort. The replacement head issues on the acknowledgement
      // edge instead of waiting for the pending register to clear first.
      dut.io.accessReadRespId.poke(1.U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(100.U)
      dut.io.accessReadConsumeMask.expect(1.U)
      dut.clock.step()
    }
  }

  it should "acknowledge at the minimum boundary without issuing" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(20.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 300, bundleId = 300)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      waitFor(dut.io.currentCycleAfterIssue.peek().litValue >= 20, dut)

      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 301, bundleId = 301)
      dut.io.accessReadRespId.poke(1.U)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      dut.clock.step()

      // The same-edge acknowledgement clears bookkeeping and makes the
      // boundary exit eligible immediately, without consuming the replacement.
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "give a later-round alias a distinct cache tracking token" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      dut.io.minIssueCycle.poke(20.U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = 200 + lane,
          bundleId = 55, bundleCount = 2, generation = 1)
      }
      startRound(dut)
      withClue("first round did not issue its aliased tranche: ") {
        waitFor(dut.io.accessReadConsumeMask.peek().litValue ==
          replaySlotMask((0, 0), (1, 0)), dut)
      }
      val firstTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      for (lane <- 0 until 2) dut.io.accessReadDataValid(replaySlot(lane)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(laneGenerations(0 -> 1, 1 -> 1).U)
      withClue("first round did not reach its min-cycle boundary: ") {
        waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      }
      dut.clock.step()

      // The old tranche is fully issued but its two returns are still
      // outstanding.  A later scheduler round may add more accesses to the
      // same intentionally aliased bundle ID.
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ 3).U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = 300 + lane,
          bundleId = 55, bundleCount = 2, generation = 2)
      }
      dut.io.minIssueCycle.poke(
        (dut.io.currentCycleAfterIssue.peek().litValue + 20).U)
      startRound(dut)
      withClue("second round did not issue its aliased tranche: ") {
        waitFor(dut.io.accessReadConsumeMask.peek().litValue ==
          replaySlotMask((0, 0), (1, 0)), dut)
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
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 350, bundleId = 66,
        bundleCount = 2, generation = 7)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val firstTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(1.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.capacity)
      dut.clock.step()

      dut.io.accessStoreHasMore.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ 1).U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 351, bundleId = 66,
        bundleCount = 1, generation = 7)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val secondTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      assert(secondTrackingToken == firstTrackingToken)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
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

  it should "stall only the lane whose local bundle table is full" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 1))) { dut =>
      initialize(dut, 2)
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(
          dut.io.accessReadData(replaySlot(0, slot)),
          id = 100 + slot,
          bundleId = 100 + slot,
        )
      }

      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == replaySlotMask((0, 0)), dut)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(replaySlotMask((0, 1)).U)
      dut.clock.step()
      dut.io.accessReadConsumeMask.expect(replaySlotMask((0, 2)).U)
      dut.clock.step()

      // Lane zero now occupies all three of its private entries.  A new head
      // on lane one must still allocate its own local entry zero.
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 103, bundleId = 103)
      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1)).U)
      dut.io.accessReadDataValid(replaySlot(1)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(1)), id = 200, bundleId = 200)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.issue(1).valid.expect(true.B)
      dut.io.issue(1).bits.mBundleId.expect(0.U)
      dut.io.accessReadConsumeMask.expect(replaySlotMask((1, 0)).U)

      // Retiring lane zero's local entry zero frees it after this edge; the
      // blocked lane-zero access then allocates that index on the next cycle.
      pokeAccess(dut.io.completion(0).bits, id = 100, bundleId = 0)
      dut.io.completion(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(103.U)
      dut.io.issue(0).bits.mBundleId.expect(0.U)
    }
  }

  it should "drain full partial-bundle tables, report future heads, and resume a promoted bundle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 1))) { dut =>
      initialize(dut, 2)
      val noHorizon = (BigInt(1) << 64) - 1
      dut.io.minIssueCycle.poke(noHorizon.U)
      for (lane <- 0 until 2; slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(replaySlot(lane, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane, slot)),
          id = 100 * (lane + 1) + slot, bundleId = 100 * (lane + 1) + slot,
          bundleCount = 2, generation = 7)
      }
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      for (slot <- 0 until slotsPerLane) {
        for (lane <- 0 until 2) {
          dut.io.issue(lane).valid.expect(true.B)
          dut.io.issue(lane).bits.mBundleId.expect(slot.U)
        }
        dut.clock.step()
      }
      // The selected slot zero is still stale. None of these fully partial
      // tables may trigger until its new head is acknowledged.
      dut.io.issuedAccessBatch.ready.poke(false.B)
      dut.io.memActive.poke(true.B)
      for (lane <- 0 until 2) {
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)),
          id = 900 + lane, cycle = if (lane == 0) 0 else 300,
          bundleId = 900 + lane, generation = 7)
      }
      dut.clock.step(2)
      dut.io.roundComplete.expect(false.B)
      dut.io.bundleTableFullLaneMask.expect(0.U)
      // Acknowledge only the selected slots first. Lane zero's due head
      // triggers recovery; lane one's future head must appear in its mask.
      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (1, 0, 1)).U)
      dut.io.issue.foreach(_.valid.expect(false.B))
      dut.clock.step()
      for (entry <- 0 until slotsPerLane) {
        for (lane <- 0 until 2) {
          dut.io.completion(lane).valid.poke(true.B)
          pokeAccess(dut.io.completion(lane).bits,
            id = 100 * (lane + 1) + entry, bundleId = entry)
          dut.io.completion(lane).ready.expect(true.B)
        }
        dut.clock.step()
      }
      dut.io.completion.foreach(_.valid.poke(false.B))
      // Adapter activity, then unacknowledged slot consumes, independently
      // prevent a drained recovery boundary.
      dut.clock.step(2)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.memActive.poke(false.B)
      dut.clock.step(2)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.accessReadRespId.poke(slotGenerations(
        (0, 0, 1), (0, 1, 1), (0, 2, 1), (1, 0, 1), (1, 1, 1), (1, 2, 1)).U)
      dut.clock.step()
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.bundleTableFull)
      dut.io.bundleTableFullLaneMask.expect(3.U)
      dut.io.hasPendingWork.expect(true.B)
      dut.clock.step(3)
      dut.io.roundComplete.expect(false.B) // issued outputs still backpressured
      dut.io.issuedAccessBatch.ready.poke(true.B)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.clock.step(2)
      dut.io.bundleTableFullLaneMask.expect(3.U) // held while paused

      // Host promotes one resident's final member. Its later scheduled cycle
      // precedes the old, earlier-dated head in the new upload.
      dut.io.accessReadDataValid.foreach(_.poke(false.B))
      dut.io.accessReadLaneDoneMask.poke(2.U)
      val promotedCycle = dut.io.currentCycleAfterIssue.peek().litValue + 10
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 400,
        cycle = promotedCycle, bundleId = 100, bundleCount = 2, generation = 7)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0, 1)), id = 900,
        cycle = 0, bundleId = 900, generation = 7)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      dut.io.accessReadDataValid(replaySlot(0, 1)).poke(true.B)
      startRound(dut)
      dut.io.bundleTableFullLaneMask.expect(0.U)
      while (dut.io.currentCycleAfterIssue.peek().litValue < promotedCycle) {
        dut.io.issue(0).valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(400.U)
      dut.io.issue(0).bits.mBundleId.expect(0.U) // original entry survived
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      // Entry zero is now fully issued but not yet completed. That transient
      // fullness must wait for its return, not start another recovery.
      dut.clock.step(4)
      dut.io.roundComplete.expect(false.B)
      dut.io.bundleTableFullLaneMask.expect(0.U)
      dut.io.issue(0).valid.expect(false.B)
      pokeAccess(dut.io.completion(0).bits, id = 400, bundleId = 0)
      dut.io.completion(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.completedBundleIdWriteEn.expect(true.B)
      dut.io.completedBundleIdWriteData.expect(100.U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(900.U)
      dut.io.issue(0).bits.mBundleId.expect(0.U) // newly freed entry reused
      assert(dut.io.issue(0).bits.cycleCount.peek().litValue > promotedCycle)
    }
  }

  for (preemption <- Seq("horizon", "wake")) {
    it should s"prefer a $preemption exit while draining for bundle-table recovery" in {
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 1))) { dut =>
        initialize(dut, 2)
        for (slot <- 0 until slotsPerLane) {
          dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
          pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
            id = 100 + slot, bundleId = 100 + slot, bundleCount = 2)
        }
        dut.io.accessReadDataValid(replaySlot(1)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(1)), id = 200,
          bundleId = 200, wake = true, blocked = true)
        startRound(dut)
        waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
        dut.clock.step(slotsPerLane)
        dut.io.accessReadDataValid(replaySlot(1)).poke(false.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 103, bundleId = 103)
        dut.io.accessReadRespId.poke(slotGenerations(
          (0, 0, 1), (0, 1, 1), (0, 2, 1), (1, 0, 1)).U)
        dut.io.memActive.poke(true.B)
        dut.clock.step() // latch recovery with memory still outstanding
        if (preemption == "horizon") {
          dut.io.minIssueCycle.poke(dut.io.currentCycleAfterIssue.peek().litValue.U)
        } else {
          pokeAccess(dut.io.completion(1).bits, id = 200, bundleId = 0)
          dut.io.completion(1).valid.poke(true.B)
          dut.clock.step()
          dut.io.completion(1).valid.poke(false.B)
        }
        waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
        dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
        dut.io.bundleTableFullLaneMask.expect(0.U)
        dut.io.hasPendingWork.expect(true.B)
      }
    }
  }

  it should "handle independent lane-local completions in one cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 4, memOutstanding = 2))) { dut =>
      initialize(dut, 4)
      val bundleIds = Seq(90, 91, 92, 93)
      val bundleCounts = Seq(1, 1, 1, 1)
      for (lane <- 0 until 4) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(
          dut.io.accessReadData(replaySlot(lane)),
          id = 900 + lane,
          bundleId = bundleIds(lane),
          bundleCount = bundleCounts(lane),
        )
      }

      startRound(dut)
      waitFor(
        dut.io.accessReadConsumeMask.peek().litValue ==
          replaySlotMask((0, 0), (1, 0), (2, 0), (3, 0)),
        dut,
      )
      val tokens = (0 until 4).map(lane =>
        dut.io.issue(lane).bits.mBundleId.peek().litValue)
      assert(tokens.distinct == Seq(BigInt(0)))
      dut.clock.step()

      for (lane <- 0 until 4) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(false.B)
      }
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(laneGenerations(
        0 -> 1, 1 -> 1, 2 -> 1, 3 -> 1).U)

      // Identical local indices refer to independent tables, so all three
      // lanes can retire their entry zero on the same target edge.
      for (lane <- 0 until 3) {
        pokeAccess(
          dut.io.completion(lane).bits,
          id = 900 + lane,
          bundleId = tokens(lane),
        )
        dut.io.completion(lane).valid.poke(true.B)
      }
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      for (lane <- 0 until 3) {
        dut.io.completion(lane).valid.poke(false.B)
      }

      val completed = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var cycles = 0
      while (completed.size < 3 && cycles < 20) {
        if (dut.io.completedBundleIdWriteEn.peek().litToBoolean) {
          completed += dut.io.completedBundleIdWriteData.peek().litValue
        }
        dut.clock.step()
        cycles += 1
      }
      assert(completed.toSeq.sorted == Seq(BigInt(90), BigInt(91), BigInt(92)))

      pokeAccess(dut.io.completion(3).bits, id = 903, bundleId = tokens(3))
      dut.io.completion(3).valid.poke(true.B)
      waitFor(dut.io.completion(3).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(3).valid.poke(false.B)
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(93.U)
    }
  }

  it should "assert on an out-of-range completion table index" in {
    assertThrows[Exception] {
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
        initialize(dut, 1)
        dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 950, bundleId = 95)
        startRound(dut)
        waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
        dut.clock.step()

        // One lane and one outstanding request create three table entries, so
        // index three is the first invalid private token.
        pokeAccess(dut.io.completion(0).bits, id = 950, bundleId = 3)
        dut.io.completion(0).valid.poke(true.B)
        waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
        dut.clock.step()
      }
    }
  }

  it should "assert when a completion underflows an incomplete bundle" in {
    assertThrows[Exception] {
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
        initialize(dut, 1)
        dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
        pokePackedAccess(
          dut.io.accessReadData(replaySlot(0)),
          id = 960,
          bundleId = 96,
          bundleCount = 2,
        )
        startRound(dut)
        waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
        val token = dut.io.issue(0).bits.mBundleId.peek().litValue
        dut.clock.step()

        // The first return leaves a valid entry with zero outstanding accesses
        // and one member still expected in a future scheduler round.
        pokeAccess(dut.io.completion(0).bits, id = 960, bundleId = token)
        dut.io.completion(0).valid.poke(true.B)
        waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
        dut.clock.step()
        dut.io.completion(0).valid.poke(false.B)
        dut.clock.step()

        pokeAccess(dut.io.completion(0).bits, id = 961, bundleId = token)
        dut.io.completion(0).valid.poke(true.B)
        waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
        dut.clock.step()
      }
    }
  }

  it should "keep draining the real cache after the upload empties and exit on a blocked wake" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(0)),
        id = 400,
        bundleId = 400,
        wake = true,
        blocked = true,
      )
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(1.U)

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

  it should "batch a pending wake with later traffic and preserve baseline exit timing and output drain" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(((BigInt(1) << 64) - 1).U)
      val issued = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val completed = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      def tick(): Unit = {
        if (dut.io.issuedAccessBatch.valid.peek().litToBoolean &&
            dut.io.issuedAccessBatch.ready.peek().litToBoolean) {
          issued += packedId(packedLane(dut.io.issuedAccessBatch.bits.accesses, 0))
        }
        if (dut.io.completedBundleIdWriteEn.peek().litToBoolean) {
          completed += dut.io.completedBundleIdWriteData.peek().litValue
        }
        dut.clock.step()
      }
      def until(condition: => Boolean): Unit = {
        var remaining = 30
        while (!condition && remaining > 0) { tick(); remaining -= 1 }
        assert(condition)
      }
      for (slot <- 0 until slotsPerLane) {
        dut.io.accessReadDataValid(slot).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(slot), id = 410 + slot,
          bundleId = 1410 + slot, wake = slot == 0, blocked = slot == 0)
      }
      startRound(dut)
      until(dut.io.issue(0).valid.peek().litToBoolean)
      val wakeToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      tick()
      dut.io.issue(0).bits.id.expect(411.U)
      val otherToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      pokeAccess(dut.io.completion(0).bits, id = 410, bundleId = wakeToken)
      dut.io.completion(0).valid.poke(true.B)
      dut.io.completion(0).ready.expect(true.B)
      tick() // B issues on the same edge as A's wake.

      // The pending wake keeps the baseline's cooperative semantics: C can
      // issue and B can return on the following edge, in this same round.
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).bits.id.expect(412.U)
      val finalToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      pokeAccess(dut.io.completion(0).bits, id = 411, bundleId = otherToken)
      dut.io.completion(0).ready.expect(true.B)
      tick()
      dut.io.issuedAccessBatch.ready.poke(false.B)
      dut.io.accessReadDataValid.foreach(_.poke(false.B))
      dut.io.accessReadLaneDoneMask.poke(1.U)
      pokeAccess(dut.io.completion(0).bits, id = 412, bundleId = finalToken)
      dut.io.completion(0).ready.expect(true.B)
      tick()
      dut.io.completion(0).valid.poke(false.B)
      // Baseline wake handling enters output drain on the first quiet edge,
      // even before consume acknowledgments return to the target. The bridge
      // receives consume tokens before the later round-completion token.
      val lastCompletionCycle = dut.io.currentCycleAfterIssue.peek().litValue
      tick()
      dut.io.dpiState.expect(3.U)
      dut.io.currentCycleAfterIssue.expect((lastCompletionCycle + 1).U)
      for (_ <- 0 until 3) {
        dut.io.roundComplete.expect(false.B)
        dut.io.dpiState.expect(3.U) // C's writeback is still held.
        tick()
      }
      dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (0, 1, 1), (0, 2, 1)).U)
      dut.io.issuedAccessBatch.ready.poke(true.B)
      until(dut.io.roundComplete.peek().litToBoolean)
      dut.io.completedBundleCountWriteData.expect(3.U)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.hasPendingWork.expect(false.B)
      assert(issued.toSeq == Seq(BigInt(410), BigInt(411), BigInt(412)))
      assert(completed.toSeq == Seq(BigInt(1410), BigInt(1411), BigInt(1412)))
    }
  }

  for (wakeInDrain <- Seq(false, true)) {
    it should s"batch a finite completion sequence after a wake in ${if (wakeInDrain) "initial completion drain" else "issue"}" in {
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4))) { dut =>
        initialize(dut, 1)
        dut.io.minIssueCycle.poke(12.U)
        for (slot <- 0 until 2) {
          dut.io.accessReadDataValid(slot).poke(true.B)
          pokePackedAccess(dut.io.accessReadData(slot), id = 420 + slot,
            bundleId = 1420 + slot, wake = slot == 0, blocked = slot == 0)
        }
        startRound(dut)
        waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
        val wakeToken = dut.io.issue(0).bits.mBundleId.peek().litValue
        dut.clock.step()
        val otherToken = dut.io.issue(0).bits.mBundleId.peek().litValue
        dut.clock.step()
        dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (0, 1, 1)).U)
        for (slot <- 0 until 2) dut.io.accessReadDataValid(slot).poke(false.B)
        if (wakeInDrain) {
          waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
          dut.clock.step()
        }
        dut.io.minIssueCycle.poke(((BigInt(1) << 64) - 1).U)
        val nextSlot = if (wakeInDrain) 0 else 2
        dut.io.accessReadDataValid(nextSlot).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(nextSlot), id = 422, bundleId = 1422)
        pokeAccess(dut.io.completion(0).bits, id = 420, bundleId = wakeToken)
        dut.io.completion(0).valid.poke(true.B)
        if (wakeInDrain) startRound(dut)
        dut.io.completion(0).ready.expect(true.B)
        dut.io.issue(0).valid.expect((!wakeInDrain).B)
        dut.clock.step()
        pokeAccess(dut.io.completion(0).bits, id = 421, bundleId = otherToken)
        if (!wakeInDrain) {
          dut.io.accessReadRespId.poke(slotGenerations((0, 0, 1), (0, 1, 1), (0, 2, 1)).U)
          dut.io.accessReadDataValid(nextSlot).poke(false.B)
        }
        dut.io.completion(0).ready.expect(true.B)
        dut.io.roundComplete.expect(false.B)
        dut.clock.step()
        dut.io.completion(0).valid.poke(false.B)
        waitFor(dut.io.roundComplete.peek().litToBoolean, dut, limit = 8)
        dut.io.completedBundleCountWriteData.expect(2.U)
        dut.io.hasPendingWork.expect(true.B)
        dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      }
    }
  }

  it should "ignore a non-wake completion and continue to the minimum issue cycle" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      dut.io.minIssueCycle.poke(12.U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(
          dut.io.accessReadData(replaySlot(lane)),
          id = 500 + lane,
          bundleId = 500 + lane,
        )
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue ==
        replaySlotMask((0, 0), (1, 0)), dut)
      dut.clock.step()
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(false.B)
      }
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(laneGenerations(0 -> 1, 1 -> 1).U)

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
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 600, bundleId = 600)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(1.U)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.capacity)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "finish normally when the upload is empty and no work remains" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.accessStoreCount.poke(0.U)
      dut.io.minIssueCycle.poke(20.U)
      dut.io.accessStoreMaxCycle.poke(0.U)
      dut.io.accessStoreHasEntries.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
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
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(0)),
        id = 710,
        bundleId = 71,
        bundleCount = 2,
      )
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(1.U)

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
      // Protocol v2 requires all unfinished bundles to keep the session pending.
      dut.io.hasPendingWork.expect(true.B)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
    }
  }

  it should "prefer the minimum issue cycle over a later capacity refill" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(0.U)
      dut.io.accessStoreMaxCycle.poke(10.U)
      dut.io.accessStoreHasMore.poke(true.B)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 700, cycle = 10, bundleId = 700)
      startRound(dut)

      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.io.hasPendingWork.expect(true.B)
    }
  }

  it should "adapt legacy DPI writebacks to parameterized round-robin banks" in {
    test(new LegacyIssuedAccessBatchAdapter(8)) { dut =>
      dut.io.legacy.valid.poke(false.B)
      pokeIssued(dut.io.legacy.bits)
      dut.io.batch.ready.poke(true.B)

      for (record <- 0 until 9) {
        val lane = record % 8
        pokeIssued(
          dut.io.legacy.bits,
          id = 70 + record,
          cycle = 120 + record,
          address = 0x1000 + record,
          isWrite = record == 1,
        )
        dut.io.legacy.valid.poke(true.B)
        dut.clock.step()
        dut.io.legacy.valid.poke(false.B)
        dut.io.batch.valid.expect(true.B)
        dut.io.batch.bits.batchId.expect(record.U)
        dut.io.batch.bits.validMask.expect((BigInt(1) << lane).U)
        val packed = packedLane(dut.io.batch.bits.accesses, lane)
        assert(packedId(packed) == 70 + record)
        assert(packedCycle(packed) == 120 + record)
        assert(packedAddress(packed) == 0x1000 + record)
        assert(packedIsWrite(packed) == (record == 1))
        dut.clock.step()
      }
      dut.io.legacy.valid.poke(false.B)
    }
  }

  it should "retain launch identity when recorded request UIDs repeat" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 1))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = 7,
          bundleId = 100 + lane, launchId = 41 + lane)
      }
      startRound(dut)
      waitFor(dut.io.issuedAccessBatch.valid.peek().litToBoolean, dut)
      for (lane <- 0 until 2) {
        val packed = packedLane(dut.io.issuedAccessBatch.bits.accesses, lane)
        assert(packedId(packed) == 7)
        assert((packed >> 193) == 41 + lane)
      }
    }
  }

  it should "fence a control interruption and preserve outstanding memory across launches" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 9, bundleId = 90, launchId = 1)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val token = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.memActive.poke(true.B)
      dut.io.controlPending.poke(true.B)
      // A new launch fences even a continuously offered memory response.
      // The outstanding request must survive for the next round.
      pokeAccess(dut.io.completion(0).bits, id = 9, bundleId = token, launchId = 1)
      dut.io.completion(0).valid.poke(true.B)
      dut.io.completion(0).ready.expect(false.B)
      dut.clock.step(4)
      dut.io.roundComplete.expect(false.B) // the consume is still unacknowledged
      dut.io.issue(0).valid.expect(false.B)
      dut.io.completion(0).ready.expect(false.B)
      dut.io.accessReadRespId.poke(1.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.control)
      dut.io.hasPendingWork.expect(true.B)
      val boundary = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step()
      dut.io.controlPending.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(0.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 9, bundleId = 91, launchId = 2)
      startRound(dut)
      assert(dut.io.currentCycleAfterIssue.peek().litValue > boundary)
      pokeAccess(dut.io.completion(0).bits, id = 9, bundleId = token, launchId = 1)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(90.U)
    }
  }

  it should "report a store bundle once without forcing a blocked-warp wake" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 1))) { dut =>
      initialize(dut, 1)
      dut.io.minIssueCycle.poke(30.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(dut.io.accessReadData(replaySlot(0)), id = 44, bundleId = 400,
        launchId = 3, blocked = true, isWrite = true)
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val token = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadRespId.poke(1.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      // Keep an unissued lane head outstanding to separate wake from natural exhaustion.
      pokeAccess(dut.io.completion(0).bits, id = 44, bundleId = token, launchId = 3, isWrite = true)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      var reports = 0
      while (!dut.io.roundComplete.peek().litToBoolean) {
        if (dut.io.completedBundleIdWriteEn.peek().litToBoolean) {
          dut.io.completedBundleIdWriteData.expect(400.U)
          reports += 1
        }
        dut.clock.step()
      }
      assert(reports == 1)
      assert(dut.io.currentCycleAfterIssue.peek().litValue >= 30)
    }
  }

  it should "flush a full completion report while preserving requests for the next round" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 4),
      completionReportLimit = 2)) { dut =>
      initialize(dut, 1)
      val tokens = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      for (slot <- 0 until 3) {
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(0, slot)),
          id = 50 + slot, bundleId = 500 + slot, launchId = 4, isWrite = true)
      }
      startRound(dut)
      for (slot <- 0 until 3) {
        waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
        tokens += dut.io.issue(0).bits.mBundleId.peek().litValue
        dut.clock.step()
        dut.io.accessReadDataValid(replaySlot(0, slot)).poke(false.B)
        val generations = (0 to slot).map(i => BigInt(1) << (2 * i)).reduce(_ | _)
        dut.io.accessReadRespId.poke(generations.U)
      }
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.memActive.poke(true.B)
      for (member <- 0 until 2) {
        pokeAccess(dut.io.completion(0).bits, id = 50 + member,
          bundleId = tokens(member), launchId = 4, isWrite = true)
        dut.io.completion(0).valid.poke(true.B)
        waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
        dut.clock.step()
      }
      // A third response is already available. The completion report must
      // backpressure it and exit without waiting for the memory to drain.
      pokeAccess(dut.io.completion(0).bits, id = 52, bundleId = tokens(2), launchId = 4, isWrite = true)
      dut.io.completion(0).ready.expect(false.B)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.completedBundleCountWriteData.expect(2.U)
      dut.io.hasPendingWork.expect(true.B)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
      dut.clock.step()
      dut.io.accessStoreHasEntries.poke(false.B)
      dut.io.minIssueCycle.poke(((BigInt(1) << 64) - 1).U)
      startRound(dut)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.memActive.poke(false.B)
      waitFor(dut.io.completedBundleIdWriteEn.peek().litToBoolean, dut)
      dut.io.completedBundleIdWriteData.expect(502.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.completedBundleCountWriteData.expect(1.U)
      dut.io.hasPendingWork.expect(false.B)
    }
  }

  it should "reserve a full completion report for simultaneous lane returns" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2),
      completionReportLimit = 2)) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2; slot <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane, slot)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane, slot)),
          id = 60 + lane * 2 + slot, bundleId = 600 + lane * 2 + slot,
          launchId = 5, isWrite = true)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == allLaneSlotZeroMask(dut), dut)
      val tokens = (0 until 2).map(lane => dut.io.issue(lane).bits.mBundleId.peek().litValue)
      dut.clock.step()
      for (lane <- 0 until 2) {
        dut.io.issue(lane).ready.poke(false.B)
        dut.io.accessReadDataValid(replaySlot(lane)).poke(false.B)
        pokeAccess(dut.io.completion(lane).bits, id = 60 + lane * 2,
          bundleId = tokens(lane), launchId = 5, isWrite = true)
        dut.io.completion(lane).valid.poke(true.B)
      }
      dut.io.accessReadRespId.poke(allLaneSlotZeroGenerationOne(dut).U)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.io.completion(1).ready.expect(true.B)
      dut.clock.step()
      for (lane <- 0 until 2) {
        dut.io.completion(lane).valid.poke(false.B)
        dut.io.issue(lane).ready.poke(true.B)
        dut.io.issue(lane).valid.expect(false.B)
      }
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.io.completedBundleCountWriteData.expect(2.U)
      dut.io.hasPendingWork.expect(true.B)
      dut.io.roundExitReason.expect(TrafficGenRoundExitReason.scheduling)
    }
  }

  it should "reject zero generators and generator counts that do not divide the access store" in {
    intercept[IllegalArgumentException] {
      TrafficGenParams(numGenerators = 0)
    }
    intercept[IllegalArgumentException] {
      TrafficGenParams(numGenerators = 3, maxL2AccessEntries = 32768)
    }
  }
}
