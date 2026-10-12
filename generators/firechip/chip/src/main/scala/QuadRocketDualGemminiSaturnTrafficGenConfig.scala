package firechip.chip

import org.chipsalliance.cde.config.Config

/** XRSight processors and accelerators sharing the TrafficGen memory system.
  * Host FPGA frequency is selected separately in the U250 build recipe.
  */
class FireSimQuadRocketDualGemminiSaturnTrafficGenConfig extends Config(
  new chipyard.config.WithNoTraceIO ++
  new chipyard.example.WithTrafficGenMemL2PutGet ++
  // Overrides the XRSight base's 128-bit sbus and 256 MiB memory below.
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
  new chipyard.ILLIXRQuadRocketDualGemminiSaturnConfig)
