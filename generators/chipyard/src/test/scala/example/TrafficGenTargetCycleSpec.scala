package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.{IssuedAccess, RTLL2Access, TrafficGenAccessBatch}
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenTargetCycleSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGenRTLEngine banked replay"

  private val allLanes = (BigInt(1) << TrafficGenAccessBatch.lanes) - 1

  private def pokeAccess(access: RTLL2Access, id: BigInt = 0, cycle: BigInt = 0,
                         bundleId: BigInt = 0, bundleCount: Int = 1,
                         wake: Boolean = false, blocked: Boolean = false): Unit = {
    access.id.poke(id.U)
    access.address.poke(0.U)
    access.cycleCount.poke(cycle.U)
    access.mBundleId.poke(bundleId.U)
    access.mWakeRelevantBundle.poke(wake.B)
    access.mIsWrite.poke(false.B)
    access.mWarpBlocked.poke(blocked.B)
    access.bundleIssueCount.poke(bundleCount.U)
  }

  private def pokePackedAccess(access: UInt, id: BigInt, cycle: BigInt = 0,
                               bundleId: BigInt = 0, bundleCount: Int = 1,
                               wake: Boolean = false, blocked: Boolean = false): Unit = {
    val packed =
      id |
      (cycle << 128) |
      (bundleId << 192) |
      ((if (wake) BigInt(1) else BigInt(0)) << 256) |
      ((if (blocked) BigInt(1) else BigInt(0)) << 258) |
      (BigInt(bundleCount) << 259)
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
      dut.io.issue(3).ready.poke(true.B)
      dut.io.accessReadConsumeMask.expect((BigInt(1) << 3).U)
      dut.clock.step()
      dut.io.accessReadDataValid(3).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
    }
  }

  it should "freeze target time and suppress issue throughout prefetch quiescence" in {
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
      dut.io.currentCycleAfterIssue.expect(pausedCycle.U)
      dut.io.issue(0).valid.expect(false.B)
      dut.io.accessReadConsumeMask.expect(0.U)

      dut.io.accessReadPrefetchPauseReq.poke(false.B)
      waitFor(dut.io.issue(0).valid.peek().litToBoolean, dut)
      dut.clock.step()
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

      // The second member issues in the same cycle that the first returns.
      dut.io.issue(1).ready.poke(true.B)
      pokeAccess(dut.io.completion(0).bits, id = 1, bundleId = 77, bundleCount = 2)
      dut.io.completion(0).valid.poke(true.B)
      waitFor(dut.io.completion(0).ready.peek().litToBoolean, dut)
      dut.io.accessReadConsumeMask.expect(2.U)
      dut.clock.step()
      dut.io.completion(0).valid.poke(false.B)
      dut.io.accessReadDataValid(1).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      dut.io.completedBundleIdWriteEn.expect(false.B)

      pokeAccess(dut.io.completion(1).bits, id = 2, bundleId = 77, bundleCount = 2)
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

      // A different request identity releases the suppression without an
      // extra bubble and can be consumed independently.
      pokePackedAccess(dut.io.accessReadData(0), id = 91, bundleId = 91)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.accessReadConsumeMask.expect(1.U)
      dut.clock.step()
    }
  }

  it should "extend an aliased bundle with one new annotated tranche in a later round" in {
    test(new TrafficGenRTLEngine(TrafficGenParams(numGenerators = 2, memOutstanding = 2))) { dut =>
      initialize(dut, 2)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 200 + lane,
          bundleId = 55, bundleCount = 2)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 3, dut)
      dut.clock.step()
      for (lane <- 0 until 2) dut.io.accessReadDataValid(lane).poke(false.B)
      dut.io.accessReadLaneDoneMask.poke(allLanes.U)
      waitFor(dut.io.roundComplete.peek().litToBoolean, dut)
      dut.clock.step()

      // The old tranche is fully issued but its two returns are still
      // outstanding.  A later scheduler round may add more accesses to the
      // same intentionally aliased bundle ID.
      dut.io.accessReadLaneDoneMask.poke((allLanes ^ 3).U)
      for (lane <- 0 until 2) {
        dut.io.accessReadDataValid(lane).poke(true.B)
        pokePackedAccess(dut.io.accessReadData(lane), id = 300 + lane,
          bundleId = 55, bundleCount = 2)
      }
      startRound(dut)
      waitFor(dut.io.accessReadConsumeMask.peek().litValue == 3, dut)
      dut.clock.step()
      dut.io.issuedAccessBatch.valid.expect(true.B)
      dut.io.issuedAccessBatch.bits.validMask.expect(3.U)
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
