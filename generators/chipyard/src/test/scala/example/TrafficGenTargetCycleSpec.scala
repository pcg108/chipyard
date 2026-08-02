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
      test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = numGenerators, memOutstanding = 2))) { dut =>
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

  it should "reset and rearm workload time and bundle bookkeeping at global completion" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 1, memOutstanding = 2))) { dut =>
      initialize(dut, 1)
      val noBoundary = (BigInt(1) << 64) - 1
      dut.io.minIssueCycle.poke(noBoundary.U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(0)),
        id = 800,
        bundleId = 80,
        bundleCount = 2,
        generation = 1,
      )

      startRound(dut)
      dut.io.currentCycleAfterIssue.expect(1.U)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val firstTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(1.U)

      // Return the only resident member. The bundle-table entry remains valid
      // because one member is expected from a later round.
      pokeAccess(
        dut.io.completion(0).bits,
        id = 800,
        bundleId = firstTrackingToken,
        bundleCount = 2,
      )
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.clock.step()
      val idleCycle = dut.io.currentCycleAfterIssue.peek().litValue
      dut.clock.step(3)
      dut.io.currentCycleAfterIssue.expect((idleCycle + 3).U)

      dut.io.trafficGenDone.poke(true.B)
      dut.clock.step()
      dut.io.currentCycleAfterIssue.expect(0.U)
      dut.io.targetBusy.expect(false.B)
      dut.io.hasPendingWork.expect(false.B)
      dut.clock.step(5)
      dut.io.currentCycleAfterIssue.expect(0.U)

      // Reuse the same externally visible bundle ID and generation. If the
      // prior workload's incomplete table entry survived, this single member
      // would incorrectly finish that old bundle.
      dut.io.trafficGenDone.poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ 1).U)
      dut.io.accessReadDataValid(replaySlot(0)).poke(true.B)
      // Give the second round a finite scheduling boundary. The purpose of
      // this phase is to prove that the old bundle entry was cleared; it must
      // not rely on the no-next-access sentinel to select the round exit.
      dut.io.minIssueCycle.poke(8.U)
      pokePackedAccess(
        dut.io.accessReadData(replaySlot(0)),
        id = 801,
        bundleId = 80,
        bundleCount = 2,
        generation = 1,
      )
      startRound(dut)
      dut.io.currentCycleAfterIssue.expect(1.U)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 1, dut)
      val secondTrackingToken = dut.io.issue(0).bits.mBundleId.peek().litValue
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(2.U)

      pokeAccess(
        dut.io.completion(0).bits,
        id = 801,
        bundleId = secondTrackingToken,
        bundleCount = 2,
      )
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      for (_ <- 0 until 5) {
        dut.io.completedBundleIdWriteEn.expect(false.B)
        dut.clock.step()
      }
      dut.io.targetBusy.expect(false.B)
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

  it should "apply issues before returns and retain a bundle until every member issued" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(replaySlot(lane)).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(replaySlot(lane)), id = lane + 1, bundleId = 77, bundleCount = 2)
      }
      dut.io.issue(1).ready.poke(false.B)
      startRound(dut)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
      dut.io.accessReadDataValid(replaySlot(0)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke((allLanes(dut) ^ (BigInt(1) << 1)).U)
      dut.io.accessReadRespId.poke(1.U)
      dut.clock.step()

      // The second member issues in the same cycle that the first returns.
      dut.io.issue(1).ready.poke(true.B)
      // The cache returns the engine-private bundle-table token carried on
      // io.issue, not the scheduler-owned bundle ID.
      pokeAccess(dut.io.completion(0).bits, id = 1, bundleId = 0, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.io.accessReadConsumeMask.expect(replaySlotMask((1, 0)).U)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.accessReadDataValid(replaySlot(1)).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes(dut).U)
      dut.io.accessReadRespId.poke(laneGenerations(0 -> 1, 1 -> 1).U)
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

  it should "reject zero generators and generator counts that do not divide the access store" in {
    intercept[IllegalArgumentException] {
      TrafficGenParams(numGenerators = 0)
    }
    intercept[IllegalArgumentException] {
      TrafficGenParams(numGenerators = 3, maxL2AccessEntries = 32768)
    }
  }
}
