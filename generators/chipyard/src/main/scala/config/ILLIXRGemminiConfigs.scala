package chipyard

import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.{BuildRoCC, TileKey}
import illixr_fp32_gemmini._

/** One FP32 accelerator on hart zero. Saturn uses the independent vector port. */
class WithILLIXRFP32Gemmini extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC, site) ++ (if (site(TileKey).tileId == 0) Seq(
    (p: Parameters) => {
      implicit val q: Parameters = p
      LazyModule(new Gemmini(GemminiFPConfigs.FP32DefaultConfig.copy(
        sp_capacity = CapacityInKilobytes(32),
        acc_capacity = CapacityInKilobytes(8),
        headerFileName = "gemmini_params_illixr.h")))
    }) else Nil)
})

class ILLIXRSingleRocketGemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRFP32Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

class ILLIXRQuadRocketGemminiSaturnConfig extends Config(
  new chipyard.config.WithNPerfCounters(13) ++
  new WithILLIXRFP32Gemmini ++
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(4) ++
  new chipyard.config.AbstractConfig)
