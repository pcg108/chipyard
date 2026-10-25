// See LICENSE for license details.
package firechip.bridgeinterfaces

import chisel3._
import chisel3.util._

class L2PerfToken(key: L2PerfBridgeKey) extends Bundle {
  val targetReset = Bool()
  val events = Vec(key.count, UInt(32.W))
  val markerValid = Bool()
  val markerKind = UInt(3.W)
  val windowId = UInt(64.W)
  val markerSequence = UInt(32.W)
  val markerErrors = UInt(32.W)
}
class L2PerfSnapshot(key: L2PerfBridgeKey) extends Bundle {
  val sequence = UInt(64.W)
  val cycle = UInt(64.W)
  val windowId = UInt(64.W)
  val markerSequence = UInt(32.W)
  val kind = UInt(3.W)
  val errors = UInt(32.W)
  val values = Vec(key.count, UInt(64.W))
}

/** Token-level implementation shared by the FPGA bridge and focused RTL tests.
  *
  * Snapshot cumulative values BEFORE consuming this token's increments. Thus
  * START includes its own cycle and STOP excludes its own cycle. Gauges capture
  * the instantaneous pre-edge state, not the previous cycle's state. Host stalls
  * can delay token acceptance but cannot change the observed target cycle count.
  * A target reset does not erase host evidence: it produces a RESET record if a
  * window is pending, invalidates that window, and pauses accumulation.
  */
class L2PerfAccumulator(key: L2PerfBridgeKey, initialCounterValue: BigInt = 0) extends Module {
  // Nonzero initialization is only used by focused tests to reach 64-bit wrap.
  // The production bridge instantiates this module with the zero default.
  require(initialCounterValue >= 0 && initialCounterValue < (BigInt(1) << 64))
  val io = IO(new Bundle {
    val token = Flipped(Decoupled(new L2PerfToken(key)))
    val snapshot = Decoupled(new L2PerfSnapshot(key))
    val errors = Output(UInt(32.W))
  })
  val input = io.token.bits
  val values = RegInit(VecInit(Seq.fill(key.count)(initialCounterValue.U(64.W))))
  val cycle = RegInit(0.U(64.W))
  val sequence = RegInit(0.U(64.W))
  val state = RegInit(0.U(2.W)) // idle, active, stopped awaiting drain end
  val windowId = RegInit(0.U(64.W))
  val markerSequence = RegInit(0.U(32.W))
  val wasReset = RegInit(true.B)
  val hasMarkers = RegInit(false.B)
  val errors = RegInit(0.U(32.W))
  val queue = Module(new Queue(new L2PerfSnapshot(key), 2, pipe = true))
  io.snapshot <> queue.io.deq
  io.errors := errors

  val resetRecord = input.targetReset && !wasReset && hasMarkers
  val interrupted = resetRecord && state =/= 0.U
  val marker = input.markerValid && !input.targetReset
  val capture = resetRecord || marker
  val kind = Mux(resetRecord, 5.U, input.markerKind)
  val allowed = MuxLookup(kind, false.B)(Seq(
    1.U -> (state === 0.U), 2.U -> (state === 1.U),
    3.U -> (state === 1.U), 4.U -> (state === 2.U), 5.U -> resetRecord))
  val matching = kind === 1.U || kind === 5.U || input.windowId === windowId
  val newErrors = Mux(marker, input.markerErrors, 0.U) |
    Mux(marker && !allowed, (1L << 16).U(32.W), 0.U) |
    Mux(marker && !matching, (1L << 17).U(32.W), 0.U) |
    Mux(interrupted, (1L << 18).U(32.W), 0.U) |
    Mux(marker && input.markerSequence =/= (markerSequence + 1.U), (1L << 19).U(32.W), 0.U)

  io.token.ready := !capture || queue.io.enq.ready
  queue.io.enq.valid := io.token.valid && capture
  queue.io.enq.bits.sequence := sequence
  queue.io.enq.bits.cycle := cycle
  queue.io.enq.bits.windowId := Mux(resetRecord, windowId, input.windowId)
  queue.io.enq.bits.markerSequence := Mux(resetRecord, markerSequence, input.markerSequence)
  queue.io.enq.bits.kind := kind
  queue.io.enq.bits.errors := newErrors
  for (i <- 0 until key.count) {
    if (key.events(i % key.events.size).gauge) {
      queue.io.enq.bits.values(i) := input.events(i)
    } else {
      queue.io.enq.bits.values(i) := values(i)
    }
  }
  when(io.token.fire) {
    cycle := cycle + 1.U
    wasReset := input.targetReset
    errors := errors | newErrors
    when(!input.targetReset) {
      for (i <- 0 until key.count if !key.events(i % key.events.size).gauge) {
        values(i) := values(i) + input.events(i)
      }
    }
    when(capture) { sequence := sequence + 1.U }
    when(input.targetReset) { markerSequence := 0.U; hasMarkers := false.B }
    when(resetRecord) { state := 0.U }
    when(marker) {
      hasMarkers := true.B
      markerSequence := input.markerSequence
      when(allowed && matching) {
        switch(kind) {
          is(1.U) { state := 1.U; windowId := input.windowId }
          is(3.U) { state := 2.U }
          is(4.U) { state := 0.U }
        }
      }
    }
  }
}
