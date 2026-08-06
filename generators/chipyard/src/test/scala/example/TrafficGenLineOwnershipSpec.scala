package chipyard.example

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class TrafficGenLineOwnershipHarness(
  lanes: Int = 2,
  slots: Int = 2,
  remapTraceAddresses: Boolean = true,
) extends Module {
  private val slotBits = log2Ceil(slots max 2)
  private val blockOffsetBits = 7

  val io = IO(new Bundle {
    val traceAddress = Input(Vec(lanes, UInt(64.W)))
    val coreOffset = Input(UInt(64.W))
    val allocate = Input(Vec(lanes, Bool()))
    val releaseAck = Input(Vec(lanes, Bool()))
    val probeComplete = Input(Vec(lanes, Bool()))
    val completionSlot = Input(Vec(lanes, UInt(slotBits.W)))
    val allowed = Output(Vec(lanes, Bool()))
    val ownerCount = Output(Vec(lanes, UInt(log2Ceil(slots + 1).W)))
  })

  val valid = RegInit(VecInit(Seq.fill(lanes)(VecInit(Seq.fill(slots)(false.B)))))
  val lines = Reg(Vec(lanes, Vec(slots, UInt(64.W))))

  for (lane <- 0 until lanes) {
    val address = TrafficGenLineOwnership.selectedAddress(
      io.traceAddress(lane), io.coreOffset, remapTraceAddresses, 0x100000000L)
    val line = TrafficGenLineOwnership.lineAddress(address, blockOffsetBits)
    val conflict = TrafficGenLineOwnership.conflict(
      line, valid(lane).toSeq, lines(lane).toSeq)
    val freeOH = VecInit(valid(lane).map(v => !v)).asUInt
    val hasFree = freeOH.orR
    val freeSlot = PriorityEncoder(freeOH)(slotBits - 1, 0)

    io.allowed(lane) := hasFree && !conflict
    io.ownerCount(lane) := PopCount(valid(lane))

    val completion = io.releaseAck(lane) || io.probeComplete(lane)
    when(completion) {
      assert(!(io.releaseAck(lane) && io.probeComplete(lane)))
      assert(io.completionSlot(lane) < slots.U)
      valid(lane)(io.completionSlot(lane)) := false.B
    }
    when(io.allocate(lane) && io.allowed(lane)) {
      valid(lane)(freeSlot) := true.B
      lines(lane)(freeSlot) := line
    }
  }
}

class TrafficGenLineOwnershipSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TrafficGen physical cache-line admission"

  private def initialize(dut: TrafficGenLineOwnershipHarness): Unit = {
    dut.io.coreOffset.poke(0.U)
    for (lane <- dut.io.traceAddress.indices) {
      dut.io.traceAddress(lane).poke(0.U)
      dut.io.allocate(lane).poke(false.B)
      dut.io.releaseAck(lane).poke(false.B)
      dut.io.probeComplete(lane).poke(false.B)
      dut.io.completionSlot(lane).poke(0.U)
    }
  }

  private def allocate(
    dut: TrafficGenLineOwnershipHarness,
    lane: Int,
    address: BigInt,
  ): Unit = {
    dut.io.traceAddress(lane).poke(address.U)
    dut.io.allowed(lane).expect(true.B)
    dut.io.allocate(lane).poke(true.B)
    dut.clock.step()
    dut.io.allocate(lane).poke(false.B)
  }

  it should "block a same-line request until its ReleaseAck completes" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = 0x80)

      dut.io.traceAddress(0).poke(0xc0.U)
      dut.io.allowed(0).expect(false.B)
      dut.io.releaseAck(0).poke(true.B)
      dut.io.completionSlot(0).poke(0.U)
      dut.clock.step()
      dut.io.releaseAck(0).poke(false.B)
      dut.io.allowed(0).expect(true.B)
    }
  }

  it should "block a same-line request until its probe response completes" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = 0x80)

      dut.io.traceAddress(0).poke(0xc0.U)
      dut.io.allowed(0).expect(false.B)
      dut.io.probeComplete(0).poke(true.B)
      dut.io.completionSlot(0).poke(0.U)
      dut.clock.step()
      dut.io.probeComplete(0).poke(false.B)
      dut.io.allowed(0).expect(true.B)
    }
  }

  it should "use another source slot for a different physical line" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = 0x80)
      allocate(dut, lane = 0, address = 0x100)
      dut.io.ownerCount(0).expect(2.U)
    }
  }

  it should "compare the remapped physical address rather than trace upper bits" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = BigInt("100000080", 16))
      dut.io.traceAddress(0).poke(BigInt("2000000c0", 16).U)
      dut.io.allowed(0).expect(false.B)
    }
  }

  it should "keep identical physical lines independent across lanes" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = 0x80)
      dut.io.traceAddress(1).poke(0xc0.U)
      dut.io.allowed(1).expect(true.B)
      allocate(dut, lane = 1, address = 0xc0)
      dut.io.ownerCount(0).expect(1.U)
      dut.io.ownerCount(1).expect(1.U)
    }
  }

  it should "clear all physical-line ownership on reset" in {
    test(new TrafficGenLineOwnershipHarness()) { dut =>
      initialize(dut)
      allocate(dut, lane = 0, address = 0x80)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.ownerCount(0).expect(0.U)
      dut.io.traceAddress(0).poke(0xc0.U)
      dut.io.allowed(0).expect(true.B)
    }
  }
}
