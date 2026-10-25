package firechip.chip

import chisel3._
import chisel3.util.experimental.BoringUtils
import org.chipsalliance.cde.config.Config
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, InModuleBody}
import freechips.rocketchip.subsystem.{BaseSubsystem, TilesLocated, InSubsystem, SystemBusKey, MemoryBusKey, ExtMem}
import freechips.rocketchip.tile.{RocketTile, RocketTileParams}
import freechips.rocketchip.util.ElaborationArtefacts
import chipyard.iobinders.{ComposeLazyIOBinder, Port}
import chipyard.harness.HarnessBinder
import firechip.bridgeinterfaces.{L2PerfBridgeKey, L2PerfCounterDesc}
import firechip.bridgestubs.L2PerfBridge
import sifive.blocks.inclusivecache.{InclusiveCache, L2PerfEnabled, L2PerfEvents, L2PerfPort}

class ClockedL2PerfIO(banks: Int) extends Bundle {
  val clock = Output(Clock())
  val reset = Output(Bool())
  val perf = Output(new L2PerfPort(banks))
}
case class L2PerfPortBinding(getIO: () => ClockedL2PerfIO, key: L2PerfBridgeKey)
    extends Port[ClockedL2PerfIO]

/** Evidence from resolved parameters and already-instantiated tile/RoCC objects.
  * No accelerator constructors are called while producing this manifest.
  */
object L2PerfTargetManifest {
  private def quote(x: String): String = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def descendants(m: LazyModule): Seq[LazyModule] =
    Seq(m) ++ m.getChildren.flatMap(descendants)

  def emit(system: BaseSubsystem, key: L2PerfBridgeKey): Unit = {
    val params = system.p
    val attached = params(TilesLocated(InSubsystem)).map(_.tileParams).sortBy(_.tileId)
    val actual = descendants(system).collect { case tile: RocketTile => tile }
    val byId = actual.map(tile => tile.tileParams.tileId -> tile).toMap
    require(byId.size == actual.size, "L2Perf manifest found duplicate Rocket tile IDs")
    val harts = attached.map {
      case tp: RocketTileParams =>
        val tile = byId.getOrElse(tp.tileId,
          throw new IllegalArgumentException(s"L2Perf manifest cannot find instantiated Rocket ${tp.tileId}"))
        val roccs = tile.roccs.map { rocc =>
          val opcodes = rocc.opcodes.opcodes.map(_.litValue).mkString(",")
          val details = rocc match {
            case gemmini: illixr_fp32_gemmini.Gemmini[_, _, _] =>
              val c = gemmini.config
              val scratchpadBytes = BigInt(c.sp_banks) * c.sp_bank_entries * c.sp_width / 8
              val accumulatorBytes = BigInt(c.acc_banks) * c.acc_bank_entries * c.meshColumns * c.tileColumns * c.accType.getWidth / 8
              s""", "gemmini":{"rows":${c.meshRows * c.tileRows},"columns":${c.meshColumns * c.tileColumns},"input_type":${quote(c.inputType.getClass.getName)},"input_bits":${c.inputType.getWidth},"accumulator_bits":${c.accType.getWidth},"scratchpad_bytes":$scratchpadBytes,"accumulator_bytes":$accumulatorBytes,"header":${quote(c.headerFileName)}}"""
            case _ => ""
          }
          s"""{"class":${quote(rocc.getClass.getName)},"opcodes":[$opcodes]$details}"""
        }.mkString(",")
        val vector = tile.vector_unit.map(v => quote(v.getClass.getName)).getOrElse("null")
        s"""{"hart_id":${tp.tileId},"num_hpm_counters":${tp.core.nPerfCounters},"vlen":${tp.core.vector.map(_.vLen).getOrElse(0)},"vector_class":$vector,"roccs":[$roccs]}"""
      case other => throw new IllegalArgumentException(s"L2Perf target contains unexpected tile ${other.getClass.getName}")
    }.mkString(",")
    val trafficgen = params(chipyard.example.TrafficGenKey).getOrElse(
      throw new IllegalArgumentException("L2Perf target has no TrafficGen"))
    val backend = if (trafficgen.backend == chipyard.example.TrafficGenRTLBackend) "rtl" else "dpi"
    val memory = params(ExtMem).getOrElse(
      throw new IllegalArgumentException("L2Perf target has no external memory"))
    val sbus = params(SystemBusKey)
    val mbus = params(MemoryBusKey)
    val source = s"""{"schema_version":1,"harts":[$harts],"trafficgen":{"num_lanes":${trafficgen.numGenerators},"mem_outstanding":${trafficgen.memOutstanding},"serialize_same_line":${trafficgen.serializeSameLine},"backend":${quote(backend)},"memory_backend":${quote(trafficgen.memBackend.getClass.getName)},"replay_entries":${trafficgen.maxL2AccessEntries},"remap_trace_addresses":${trafficgen.remapTraceAddresses},"remap_base":${trafficgen.remapBase},"remap_size":${trafficgen.remapSize},"base":${trafficgen.base},"size":${trafficgen.size},"region_stride":${trafficgen.regionStride}},"bus":{"system_beat_bytes":${sbus.beatBytes},"memory_beat_bytes":${mbus.beatBytes},"system_frequency_hz":${sbus.dtsFrequency.map(_.toString).getOrElse("null")},"memory_frequency_hz":${mbus.dtsFrequency.map(_.toString).getOrElse("null")}},"memory_channels":${memory.nMemoryChannels},"memory_bytes":${memory.master.size},"l2_banks":${key.banks},"events_per_bank":${key.events.size}}"""
    ElaborationArtefacts.add("l2-perf-target.json", source)
  }
}

/** Passive hierarchical taps; no target ready/valid path depends on collection. */
class WithL2PerfIOPunchthrough extends ComposeLazyIOBinder({
  (system: BaseSubsystem) => InModuleBody {
    def caches(m: LazyModule): Seq[InclusiveCache] =
      (m match { case c: InclusiveCache => Seq(c); case _ => Nil }) ++ m.getChildren.flatMap(caches)
    val found = caches(system)
    require(found.size == 1, s"L2Perf expects one inclusive cache, found ${found.size}")
    val cache = found.head
    require(cache.ctrls.size == 1, "L2Perf requires a single target coordinator/control peripheral")
    val key = L2PerfBridgeKey(cache.node.in.size, L2PerfEvents.descriptors.map { d =>
      L2PerfCounterDesc(d.name, d.unit, d.requester, d.gauge)
    })
    L2PerfTargetManifest.emit(system, key)
    val port = IO(new ClockedL2PerfIO(key.banks)).suggestName("l2perf")
    port.clock := BoringUtils.bore(cache.module.clock)
    port.reset := BoringUtils.bore(cache.module.reset).asBool
    port.perf := BoringUtils.bore(cache.module.perf.get)
    (Seq(L2PerfPortBinding(() => port, key)), Nil)
  }
})

class WithL2PerfBridge extends HarnessBinder({
  case (_: FireSim, port: L2PerfPortBinding, _: Int) =>
    val p = port.io.perf
    L2PerfBridge(port.io.clock, port.io.reset, p.banks.flatMap(_.events),
      p.marker.valid, p.marker.kind, p.marker.windowId, p.marker.sequence,
      p.marker.errors, port.key)
})

/** Ampere-only target. Physical FPGA clock is selected by the 30 MHz recipe. */
class FireSimQuadRocketInt8GemminiSaturnTrafficGenL2PerfConfig extends Config(
  new Config((site, here, up) => { case L2PerfEnabled => true }) ++
  new WithL2PerfIOPunchthrough ++
  new WithL2PerfBridge ++
  new chipyard.config.WithNoTraceIO ++
  new chipyard.example.WithTrafficGenMemL2PutGet ++
  new WithGPGPUSimLikeInclusiveL2 ++
  new chipyard.example.WithRTLTrafficGen(
    numGenerators = 16, memOutstanding = 8, serializeSameLine = true) ++
  new chipyard.iobinders.WithTrafficGenIOPunchthrough ++
  new WithTrafficGenBridge ++
  new freechips.rocketchip.subsystem.WithExtMemSize(8L << 30) ++
  new freechips.rocketchip.subsystem.WithTimebase(BigInt(500000)) ++
  new chipyard.config.WithSystemBusFrequency(500.0) ++
  new chipyard.config.WithControlBusFrequency(500.0) ++
  new chipyard.config.WithPeripheryBusFrequency(500.0) ++
  new chipyard.config.WithMemoryBusFrequency(500.0) ++
  new chipyard.config.WithFrontBusFrequency(500.0) ++
  new chipyard.config.WithOffchipBusFrequency(500.0) ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new WithDefaultFireSimBridgesWithoutTracerV ++
  new WithMinimalFireSimDesignTweaks ++
  new chipyard.ILLIXRQuadRocketInt8GemminiSaturnConfig)
