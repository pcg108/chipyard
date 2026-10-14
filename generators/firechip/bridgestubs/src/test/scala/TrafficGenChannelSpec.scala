package firechip.bridgestubs

import chisel3._
import chipyard.example.{TrafficGenKey, TrafficGenParams, TrafficGenRTLBackend, TrafficGenDPIBackend}
import circt.stage.ChiselStage
import firesim.lib.bridgeutils.PipeBridgeChannel
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class TrafficGenChannelSpec extends AnyFlatSpec {
  behavior of "TrafficGen bridge token channels"

  for (useRTL <- Seq(false, true)) {
    it should s"preserve legacy channel latency with RTL=$useRTL and fence native physical events at their cycle" in {
      implicit val p: Parameters = Parameters.empty.alterPartial {
        case TrafficGenKey => Some(TrafficGenParams(
          numGenerators = 2,
          backend = if (useRTL) TrafficGenRTLBackend else TrafficGenDPIBackend))
      }
      ChiselStage.emitCHIRRTL(new Module {
        val bridge = Module(new TrafficGenBridge)
        bridge.io := DontCare
        bridge.io.clock := clock
        bridge.io.reset := reset.asBool
        val pipes = bridge.bridgeIO.bridgeChannels().collect {
          case channel: PipeBridgeChannel => channel
        }
        val physical = pipes.filter(_.name.startsWith("trafficgen_physical"))
        require(physical.size == 6, "Every physical event field must have a token channel")
        require(physical.forall(_.latency == (if (useRTL) 0 else 1)),
          "Native physical events must not acquire a delayed reset token")
        require(pipes.filterNot(physical.contains).forall(_.latency == 1),
          "Existing replay and ordinary control channels must preserve their latency")
      })
    }
  }
}
