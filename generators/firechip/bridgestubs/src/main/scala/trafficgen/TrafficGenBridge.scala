// See LICENSE for license details

package firechip.bridgestubs

import chisel3._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

class TrafficGenBridge()(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[TrafficGenBridgeTargetIO]] {
  val moduleName = "firechip.goldengateimplementations.TrafficGenBridgeModule"
  private val bridgeKey = TrafficGenBridgeKey(
    p(chipyard.example.TrafficGenKey).get.maxL2AccessEntries,
    p(chipyard.example.TrafficGenKey).get.numGenerators,
  )

  val io = IO(new TrafficGenBridgeTargetIO(bridgeKey))
  val bridgeIO = HostPort(io)
  val constructorArg = Some(bridgeKey)

  generateAnnotations()
}

object TrafficGenBridge {
  def apply(
    clock: Clock,
    trafficGenIO: chipyard.iobinders.TrafficGenPortPeripheralIO,
    reset: Bool
  )(implicit p: Parameters): TrafficGenBridge = {
    val ep = Module(new TrafficGenBridge())

    ep.io.trafficgen.targetBusy := trafficGenIO.targetBusy
    ep.io.trafficgen.startTrafficGen := trafficGenIO.startTrafficGen
    ep.io.trafficgen.currentCycleAfterIssue := trafficGenIO.currentCycleAfterIssue
    ep.io.trafficgen.completedBundleIdWriteEn := trafficGenIO.completedBundleIdWriteEn
    ep.io.trafficgen.completedBundleIdWriteIdx := trafficGenIO.completedBundleIdWriteIdx
    ep.io.trafficgen.completedBundleIdWriteData := trafficGenIO.completedBundleIdWriteData
    ep.io.trafficgen.completedBundleCountWriteEn := trafficGenIO.completedBundleCountWriteEn
    ep.io.trafficgen.completedBundleCountWriteData := trafficGenIO.completedBundleCountWriteData
    trafficGenIO.trafficComplete := ep.io.trafficgen.trafficComplete
    trafficGenIO.minIssueCycle := ep.io.trafficgen.minIssueCycle
    trafficGenIO.blockedWarpBitmapReady := ep.io.trafficgen.blockedWarpBitmapReady
    ep.io.trafficgen.blockedWarpQueryIdx := trafficGenIO.blockedWarpQueryIdx
    ep.io.trafficgen.blockedWarpQueryEn := trafficGenIO.blockedWarpQueryEn
    trafficGenIO.blockedWarpQueryResp := ep.io.trafficgen.blockedWarpQueryResp
    trafficGenIO.blockedWarpQueryRespValid := ep.io.trafficgen.blockedWarpQueryRespValid
    ep.io.trafficgen.accessReadAddr := trafficGenIO.accessReadAddr
    ep.io.trafficgen.accessReadEn := trafficGenIO.accessReadEn
    trafficGenIO.accessReadData := ep.io.trafficgen.accessReadData
    trafficGenIO.accessReadDataValid := ep.io.trafficgen.accessReadDataValid
    trafficGenIO.accessStoredCount := ep.io.trafficgen.accessStoredCount
    trafficGenIO.uploadDone := ep.io.trafficgen.uploadDone
    trafficGenIO.uploadOverflow := ep.io.trafficgen.uploadOverflow
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  }
}
