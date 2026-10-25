// See LICENSE for license details.
package firechip.goldengateimplementations

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import midas.widgets._
import firesim.lib.bridgeutils._
import firechip.bridgeinterfaces._

class L2PerfBridgeModule(key: L2PerfBridgeKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[L2PerfBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new L2PerfBridgeTargetIO(key)))
    val core = Module(new L2PerfAccumulator(key))
    val initDone = RegInit(false.B)
    val target = hPort.hBits
    core.io.token.valid := hPort.toHost.hValid && hPort.fromHost.hReady && initDone
    hPort.toHost.hReady := core.io.token.ready && hPort.fromHost.hReady && initDone
    hPort.fromHost.hValid := core.io.token.ready && hPort.toHost.hValid && initDone
    core.io.token.bits.targetReset := target.reset
    for (i <- 0 until key.count) {
      core.io.token.bits.events(i) := target.events(32 * i + 31, 32 * i)
    }
    core.io.token.bits.markerValid := target.markerValid
    core.io.token.bits.markerKind := target.markerKind
    core.io.token.bits.windowId := target.windowId
    core.io.token.bits.markerSequence := target.markerSequence
    core.io.token.bits.markerErrors := target.markerErrors

    val index = RegInit(0.U(32.W))
    val selected = Mux(index < key.count.U, core.io.snapshot.bits.values(index), 0.U)
    // All reads address the frozen queue head. Only explicit acknowledgement
    // advances it, so indexed and split-word reads cannot tear.
    genWOReg(initDone, "init_done")
    genROReg(1.U(32.W), "schema_version")
    genROReg(key.count.U(32.W), "counter_count")
    genROReg(core.io.snapshot.valid, "record_valid")
    genROReg(core.io.snapshot.bits.kind, "record_kind")
    genROReg(core.io.snapshot.bits.sequence(31, 0), "sequence_low")
    genROReg(core.io.snapshot.bits.sequence(63, 32), "sequence_high")
    genROReg(core.io.snapshot.bits.cycle(31, 0), "cycle_low")
    genROReg(core.io.snapshot.bits.cycle(63, 32), "cycle_high")
    genROReg(core.io.snapshot.bits.windowId(31, 0), "window_low")
    genROReg(core.io.snapshot.bits.windowId(63, 32), "window_high")
    genROReg(core.io.snapshot.bits.markerSequence, "marker_sequence")
    genROReg(core.io.snapshot.bits.errors, "record_errors")
    genROReg(core.io.errors, "sticky_errors")
    genWOReg(index, "counter_index")
    genROReg(selected(31, 0), "counter_low")
    genROReg(selected(63, 32), "counter_high")
    Pulsify(genWORegInit(core.io.snapshot.ready, "record_ack", false.B), 1)
    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      val descriptors = for (bank <- 0 until key.banks; event <- key.events) yield
        CppStruct("l2perf_t::Counter", Seq(
          "bank" -> UInt32(bank), "name" -> CStrLit(event.name),
          "unit" -> CStrLit(event.unit), "requester" -> CStrLit(event.requester),
          "gauge" -> Verbatim(if (event.gauge) "true" else "false")))
      genConstructor(base, sb, "l2perf_t", "l2perf",
        Seq(StdVector("l2perf_t::Counter", descriptors)))
    }
  }
}
