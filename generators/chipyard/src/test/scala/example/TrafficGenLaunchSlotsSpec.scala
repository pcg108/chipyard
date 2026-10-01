package chipyard.example

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenLaunchSlotsSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGen reusable launch slots"

  private def initialize(dut: TrafficGenLaunchSlots): Unit = {
    dut.io.submit.poke(0.U)
    dut.io.submitAndClose.poke(0.U)
    dut.io.close.poke(false.B)
    dut.io.clearSessionErrors.poke(0.U)
    dut.io.launchStatusIds.poke(0.U)
    dut.io.launchStatuses.poke(0.U)
    dut.io.hostSessionStatus.poke(0.U)
    for (slot <- 0 until 4) {
      dut.io.registryIds(slot).poke((100 + slot).U)
      dut.io.clearErrors(slot).poke(0.U)
    }
  }

  private def submit(dut: TrafficGenLaunchSlots, slot: Int): Unit = {
    dut.io.submit.poke((1 << slot).U)
    dut.clock.step()
    dut.io.submit.poke(0.U)
  }

  it should "allocate four unique IDs and reject a busy slot without changing its request" in {
    test(new TrafficGenLaunchSlots) { dut =>
      initialize(dut)
      for (slot <- 0 until 4) submit(dut, slot)
      val ids = (0 until 4).map(slot => BigInt(slot + 1) << (64 * slot)).reduce(_ | _)
      val registries = (0 until 4).map(slot => BigInt(100 + slot) << (64 * slot)).reduce(_ | _)
      dut.io.launchIds.expect(ids.U)
      dut.io.launchRegistryIds.expect(registries.U)
      dut.io.launchPendingMask.expect(15.U)
      dut.io.controlPending.expect(true.B)
      dut.io.registryIds(0).poke(999.U)
      submit(dut, 0)
      dut.io.errors(0).expect(1.U)
      dut.io.launchIds.expect(ids.U)
      dut.io.launchRegistryIds.expect(registries.U)
      dut.io.clearErrors(0).poke(1.U)
      dut.clock.step()
      dut.io.errors(0).expect(0.U)
      dut.io.launchStatusIds.poke(ids.U)
      dut.io.launchStatuses.poke(BigInt("02020202", 16).U)
      dut.io.launchPendingMask.expect(0.U)
      dut.io.controlPending.expect(false.B)
    }
  }

  it should "reuse a completed slot and ignore its old completion snapshot" in {
    test(new TrafficGenLaunchSlots) { dut =>
      initialize(dut)
      submit(dut, 0)
      dut.io.launchStatusIds.poke(1.U)
      dut.io.launchStatuses.poke(5.U)
      dut.io.statuses(0).expect(5.U)
      dut.io.registryIds(0).poke("h100000002".U)
      submit(dut, 0)
      dut.io.launchIds.expect(2.U)
      dut.io.launchRegistryIds.expect("h100000002".U)
      dut.io.statuses(0).expect(1.U)
      dut.io.launchPendingMask.expect(1.U)
      dut.clock.step(3)
      dut.io.statuses(0).expect(1.U)
      dut.io.launchStatusIds.poke(2.U)
      dut.io.launchStatuses.poke(4.U)
      dut.io.statuses(0).expect(4.U)
      dut.io.launchPendingMask.expect(0.U)
    }
  }

  it should "close submissions without discarding queued launches and retain sticky errors" in {
    test(new TrafficGenLaunchSlots) { dut =>
      initialize(dut)
      submit(dut, 0)
      dut.io.close.poke(true.B)
      dut.clock.step()
      dut.io.close.poke(false.B)
      dut.io.closeSubmissions.expect(true.B)
      dut.io.sessionStatus.expect(1.U)
      dut.io.launchPendingMask.expect(1.U)
      submit(dut, 1)
      dut.io.errors(1).expect(2.U)
      dut.io.launchIds.expect(1.U)
      dut.io.launchStatusIds.poke(1.U)
      dut.io.launchStatuses.poke(2.U)
      dut.io.controlPending.expect(true.B) // close has not been sent yet
      dut.io.hostSessionStatus.poke(2.U)
      dut.io.controlPending.expect(false.B)
      dut.io.sessionStatus.expect(3.U)
      dut.clock.step(2)
      dut.io.errors(1).expect(2.U)
      dut.io.clearErrors(1).poke(2.U)
      dut.clock.step()
      dut.io.errors(1).expect(0.U)
    }
  }

  it should "reject the multi-launch ABI explicitly for the legacy DPI backend" in {
    test(new TrafficGenLaunchSlots(useRTL = false)) { dut =>
      initialize(dut)
      submit(dut, 0)
      dut.io.launchIds.expect(0.U)
      dut.io.launchPendingMask.expect(0.U)
      dut.io.errors(0).expect(4.U)
      dut.io.sessionErrors.expect(2.U)
    }
  }

  it should "submit and close atomically with one acknowledged control boundary" in {
    test(new TrafficGenLaunchSlots) { dut =>
      initialize(dut)
      dut.io.registryIds(2).poke(1106.U)
      dut.io.submitAndClose.poke(4.U)
      dut.io.launchPendingMask.expect(0.U)
      dut.io.closeSubmissions.expect(false.B)
      dut.clock.step()
      dut.io.submitAndClose.poke(0.U)
      dut.io.launchIds.expect((BigInt(1) << 128).U)
      dut.io.launchPendingMask.expect(4.U)
      dut.io.closeSubmissions.expect(true.B)
      dut.io.controlPending.expect(true.B)
      assert(((dut.io.launchRegistryIds.peek().litValue >> 128) & ((BigInt(1) << 64) - 1)) == 1106)
      dut.io.launchStatusIds.poke((BigInt(1) << 128).U)
      dut.io.launchStatuses.poke((BigInt(2) << 16).U)
      dut.io.hostSessionStatus.poke(2.U)
      dut.io.launchPendingMask.expect(0.U)
      dut.io.controlPending.expect(false.B)
      submit(dut, 0)
      dut.io.errors(0).expect(2.U)
      dut.io.launchIds.expect((BigInt(1) << 128).U)
    }
  }

  it should "leave submission open when an atomic command names a busy slot" in {
    test(new TrafficGenLaunchSlots) { dut =>
      initialize(dut)
      submit(dut, 0)
      dut.io.registryIds(0).poke(999.U)
      dut.io.submitAndClose.poke(1.U)
      dut.clock.step()
      dut.io.submitAndClose.poke(0.U)
      dut.io.errors(0).expect(1.U)
      dut.io.closeSubmissions.expect(false.B)
      dut.io.launchIds.expect(1.U)
      assert((dut.io.launchRegistryIds.peek().litValue & ((BigInt(1) << 64) - 1)) == 100)
      dut.io.submitAndClose.poke(2.U)
      dut.clock.step()
      dut.io.submitAndClose.poke(0.U)
      dut.io.launchIds.expect(((BigInt(2) << 64) | 1).U)
      dut.io.launchPendingMask.expect(3.U)
      dut.io.closeSubmissions.expect(true.B)
    }
  }

  it should "reject atomic submissions for the legacy DPI backend without closing" in {
    test(new TrafficGenLaunchSlots(useRTL = false)) { dut =>
      initialize(dut)
      dut.io.submitAndClose.poke(1.U)
      dut.clock.step()
      dut.io.submitAndClose.poke(0.U)
      dut.io.launchIds.expect(0.U)
      dut.io.closeSubmissions.expect(false.B)
      dut.io.errors(0).expect(4.U)
      dut.io.sessionErrors.expect(2.U)
    }
  }
}
