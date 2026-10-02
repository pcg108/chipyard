package chipyard

import org.chipsalliance.cde.config.Config

/** Test-only configurations for reproducible RTL correctness regressions. */
class RTLFixRocketConfig extends Config(
  new saturn.rocket.WithRocketVectorUnit(256, 128, saturn.common.VectorParams.refParams,
    useL1DCache = false, mLen = Some(128)) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1DCacheNonblocking(4) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new freechips.rocketchip.rocket.WithNHugeCores(4) ++
  new chipyard.config.AbstractConfig)

class RTLFixShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 128,
    saturn.common.VectorParams.refParams, mLen = Some(128)) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.AbstractConfig)
