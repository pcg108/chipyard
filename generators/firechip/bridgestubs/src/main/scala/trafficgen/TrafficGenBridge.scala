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
  )

  val io = IO(new TrafficGenBridgeTargetIO)
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
    ep.io.trafficgen.hasPendingWork := trafficGenIO.hasPendingWork
    ep.io.trafficgen.startTrafficGen := trafficGenIO.startTrafficGen
    ep.io.trafficgen.roundStarted := trafficGenIO.roundStarted
    ep.io.trafficgen.roundComplete := trafficGenIO.roundComplete
    ep.io.trafficgen.roundExitReason := trafficGenIO.roundExitReason
    ep.io.trafficgen.currentCycleAfterIssue := trafficGenIO.currentCycleAfterIssue
    ep.io.trafficgen.dpiState := trafficGenIO.dpiState
    ep.io.trafficgen.issuedAccessWriteback <> trafficGenIO.issuedAccessWriteback
    ep.io.trafficgen.completedBundleIdWriteEn := trafficGenIO.completedBundleIdWriteEn
    ep.io.trafficgen.completedBundleIdWriteIdx := trafficGenIO.completedBundleIdWriteIdx
    ep.io.trafficgen.completedBundleIdWriteData := trafficGenIO.completedBundleIdWriteData
    ep.io.trafficgen.completedBundleCountWriteEn := trafficGenIO.completedBundleCountWriteEn
    ep.io.trafficgen.completedBundleCountWriteData := trafficGenIO.completedBundleCountWriteData
    trafficGenIO.startRound := ep.io.trafficgen.startRound
    trafficGenIO.trafficGenDone := ep.io.trafficgen.trafficGenDone
    trafficGenIO.minIssueCycle := ep.io.trafficgen.minIssueCycle
    trafficGenIO.blockedWarpBitmapReady := ep.io.trafficgen.blockedWarpBitmapReady
    ep.io.trafficgen.blockedWarpQueryIdx := trafficGenIO.blockedWarpQueryIdx
    ep.io.trafficgen.blockedWarpQueryEn := trafficGenIO.blockedWarpQueryEn
    ep.io.trafficgen.blockedWarpQueryRespStored := trafficGenIO.blockedWarpQueryRespStored
    trafficGenIO.blockedWarpQueryResp := ep.io.trafficgen.blockedWarpQueryResp
    trafficGenIO.blockedWarpQueryRespValid := ep.io.trafficgen.blockedWarpQueryRespValid
    trafficGenIO.blockedWarpQueryReady := ep.io.trafficgen.blockedWarpQueryReady
    ep.io.trafficgen.accessReadCycle := trafficGenIO.accessReadCycle
    ep.io.trafficgen.accessReadEn := trafficGenIO.accessReadEn
    ep.io.trafficgen.accessReadDataReady := trafficGenIO.accessReadDataReady
    ep.io.trafficgen.accessReadBucketDoneReady := trafficGenIO.accessReadBucketDoneReady
    trafficGenIO.accessReadData := ep.io.trafficgen.accessReadData
    trafficGenIO.accessReadDataValid := ep.io.trafficgen.accessReadDataValid
    trafficGenIO.accessReadBucketDone := ep.io.trafficgen.accessReadBucketDone
    trafficGenIO.accessReadReady := ep.io.trafficgen.accessReadReady
    trafficGenIO.accessStoreCount := ep.io.trafficgen.accessStoreCount
    trafficGenIO.accessStoreMaxCycle := ep.io.trafficgen.accessStoreMaxCycle
    trafficGenIO.accessStoreHasEntries := ep.io.trafficgen.accessStoreHasEntries
    ep.io.trafficgen.reservationWindowAdvanceCycle := trafficGenIO.reservationWindowAdvanceCycle
    ep.io.trafficgen.reservationWindowAdvanceEn := trafficGenIO.reservationWindowAdvanceEn
    trafficGenIO.uploadDone := ep.io.trafficgen.uploadDone
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  }
}
