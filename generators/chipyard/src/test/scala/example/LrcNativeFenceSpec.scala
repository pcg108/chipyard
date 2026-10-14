package chipyard.example

import chisel3._
import chiseltest._
import firechip.bridgeinterfaces.LrcNativeFence
import org.scalatest.flatspec.AnyFlatSpec

class LrcNativeFenceSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "native bridge control fencing"

  it should "stop a later launch or close after draining an open session without an active scheduler round" in {
    test(new LrcNativeFence) { dut =>
      dut.io.enabled.poke(true.B); dut.io.active.poke(false.B)
      dut.io.accounted.poke(true.B); dut.io.parked.poke(false.B)
      dut.io.checkpoint.poke(false.B); dut.io.scheduling.poke(false.B)
      dut.io.refill.poke(false.B); dut.io.controlPending.poke(false.B)
      dut.io.fence.expect(false.B)
      // Either a queued launch or close produces the same unacknowledged
      // control condition. The driver is idle and needs the control doorbell.
      dut.io.controlPending.poke(true.B)
      dut.io.fence.expect(true.B); dut.io.idleControl.expect(true.B)
      dut.io.parked.poke(true.B)
      dut.io.fence.expect(false.B); dut.io.idleControl.expect(false.B)
      // Host acknowledgement prevents the held control from firing again.
      dut.io.controlPending.poke(false.B); dut.io.parked.poke(false.B)
      dut.io.fence.expect(false.B)
    }
  }

  it should "stop an active infinite-deadline round on control only after every same-cycle event commits" in {
    test(new LrcNativeFence) { dut =>
      dut.io.enabled.poke(true.B); dut.io.active.poke(true.B)
      dut.io.parked.poke(false.B); dut.io.checkpoint.poke(false.B)
      dut.io.scheduling.poke(false.B); dut.io.refill.poke(false.B)
      dut.io.controlPending.poke(true.B); dut.io.accounted.poke(false.B)
      dut.io.fence.expect(false.B)
      dut.io.accounted.poke(true.B)
      dut.io.fence.expect(true.B); dut.io.control.expect(true.B); dut.io.idleControl.expect(false.B)
      dut.io.checkpoint.poke(true.B)
      dut.io.fence.expect(false.B)
      // Ordinary replay retains its existing fence policy.
      dut.io.checkpoint.poke(false.B); dut.io.enabled.poke(false.B)
      dut.io.fence.expect(false.B)
    }
  }
}
