// See LICENSE for license details.
package firechip.bridgestubs

import chisel3._
import chisel3.util._
import firesim.lib.bridgeutils._
import firechip.bridgeinterfaces._

class L2PerfBridge(key: L2PerfBridgeKey) extends BlackBox
    with Bridge[HostPortIO[L2PerfBridgeTargetIO]] {
  val moduleName = "firechip.goldengateimplementations.L2PerfBridgeModule"
  val io = IO(new L2PerfBridgeTargetIO(key))
  val bridgeIO = HostPort(io)
  val constructorArg = Some(key)
  generateAnnotations()
}

object L2PerfBridge {
  def apply(clock: Clock, reset: Bool, events: Seq[UInt], markerValid: Bool,
            markerKind: UInt, windowId: UInt, markerSequence: UInt,
            markerErrors: UInt, key: L2PerfBridgeKey): L2PerfBridge = {
    require(events.size == key.count)
    val bridge = Module(new L2PerfBridge(key))
    bridge.io.clock := clock
    bridge.io.reset := reset
    bridge.io.events := Cat(events.reverse)
    bridge.io.markerValid := markerValid
    bridge.io.markerKind := markerKind
    bridge.io.windowId := windowId
    bridge.io.markerSequence := markerSequence
    bridge.io.markerErrors := markerErrors
    bridge
  }
}
