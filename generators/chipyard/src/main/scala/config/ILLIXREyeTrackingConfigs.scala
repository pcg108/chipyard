package chipyard

import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.{BuildRoCC, TileKey, OpcodeSet}
import illixr_fp32_gemmini._

/** Dedicated INT8 RoCC on hart zero; direct custom2 decoding, no ReRoCC. */
class WithILLIXRInt8Gemmini extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC, site) ++ (if (site(TileKey).tileId == 0) Seq(
    (p: Parameters) => {
      implicit val q: Parameters = p
      LazyModule(new Gemmini(GemminiConfigs.defaultConfig.copy(
        opcodes = OpcodeSet.custom2,
        sp_capacity = CapacityInKilobytes(256),
        acc_capacity = CapacityInKilobytes(64),
        headerFileName = "gemmini_params_illixr_int8.h")))
    }) else Nil)
})

class ILLIXRSingleRocketDualGemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRInt8Gemmini ++
  new WithILLIXRFP32Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)


class ILLIXRSingleRocketInt8GemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRInt8Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)


class ILLIXRQuadRocketDualGemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRInt8Gemmini ++
  new WithILLIXRFP32Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(4) ++
  new chipyard.config.AbstractConfig)


class ILLIXRQuadRocketInt8GemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRInt8Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(4) ++
  new chipyard.config.AbstractConfig)

