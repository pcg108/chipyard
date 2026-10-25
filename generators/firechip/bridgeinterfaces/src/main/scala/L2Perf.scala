// See LICENSE for license details.
package firechip.bridgeinterfaces

import chisel3._

/** Descriptor order is identical in each bank; flattened storage is bank-major. */
case class L2PerfCounterDesc(name: String, unit: String, requester: String = "all", gauge: Boolean = false)
case class L2PerfBridgeKey(banks: Int, events: Seq[L2PerfCounterDesc]) {
  require(banks > 0 && events.nonEmpty)
  require(events.map(x => (x.name, x.requester)).distinct.size == events.size)
  val count: Int = banks * events.size
}

/** A ground UInt keeps Golden Gate channel lowering independent of aggregate shape. */
class L2PerfBridgeTargetIO(key: L2PerfBridgeKey) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val events = Input(UInt((key.count * 32).W))
  val markerValid = Input(Bool())
  val markerKind = Input(UInt(3.W))
  val windowId = Input(UInt(64.W))
  val markerSequence = Input(UInt(32.W))
  val markerErrors = Input(UInt(32.W))
}
