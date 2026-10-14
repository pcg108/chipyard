// See LICENSE for license details

package firechip.bridgestubs

import chisel3._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

class TrafficGenBridge()(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[TrafficGenBridgeTargetIO]] {
  val moduleName = "firechip.goldengateimplementations.TrafficGenBridgeModule"
  private val trafficGenParams = p(chipyard.example.TrafficGenKey).get
  private val useRTL =
    trafficGenParams.backend == chipyard.example.TrafficGenRTLBackend
  private val bridgeKey = TrafficGenBridgeKey(
    trafficGenParams.maxL2AccessEntries,
    trafficGenParams.numGenerators,
    useRTL,
  )

  val io = IO(new TrafficGenBridgeTargetIO(trafficGenParams.numGenerators, useRTL))
  // The ordinary channels keep HostPort's existing one-cycle latency. The
  // physical event snapshot must describe the current target cycle: a delayed
  // snapshot would let C+1 execute before the monitor can stop on a wake at C.
  // Keeping fromHost at latency one means releasing the held C snapshot supplies
  // the next replay epoch and descriptors for C+1, without advancing target time
  // while the host updates logical dependency metadata.
  val bridgeIO = new HostPortIO(io) {
    override def bridgeChannels(): Seq[BridgeChannel] =
      super.bridgeChannels().map {
        case channel: PipeBridgeChannel
            if useRTL && channel.name.startsWith("trafficgen_physical") =>
          channel.copy(latency = 0)
        case channel => channel
      }
  }
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
    ep.io.trafficgen.launchRegistryIds := trafficGenIO.launchRegistryIds
    ep.io.trafficgen.launchIds := trafficGenIO.launchIds
    ep.io.trafficgen.launchPendingMask := trafficGenIO.launchPendingMask
    ep.io.trafficgen.closeSubmissions := trafficGenIO.closeSubmissions
    trafficGenIO.launchStatusIds := ep.io.trafficgen.launchStatusIds
    trafficGenIO.launchStatuses := ep.io.trafficgen.launchStatuses
    trafficGenIO.sessionStatus := ep.io.trafficgen.sessionStatus
    ep.io.trafficgen.roundStarted := trafficGenIO.roundStarted
    ep.io.trafficgen.roundComplete := trafficGenIO.roundComplete
    ep.io.trafficgen.roundExitReason := trafficGenIO.roundExitReason
    ep.io.trafficgen.bundleTableFullLaneMask := trafficGenIO.bundleTableFullLaneMask
    ep.io.trafficgen.currentCycleAfterIssue := trafficGenIO.currentCycleAfterIssue
    ep.io.trafficgen.dpiState := trafficGenIO.dpiState
    ep.io.trafficgen.issuedAccessBatch <> trafficGenIO.issuedAccessBatch
    ep.io.trafficgen.physicalIssueValidMask := trafficGenIO.physicalIssueValidMask
    ep.io.trafficgen.physicalIssueAccesses := trafficGenIO.physicalIssueAccesses
    ep.io.trafficgen.physicalConsumeMask := trafficGenIO.physicalConsumeMask
    ep.io.trafficgen.physicalCompletionValidMask := trafficGenIO.physicalCompletionValidMask
    ep.io.trafficgen.physicalCompletionTokens := trafficGenIO.physicalCompletionTokens
    ep.io.trafficgen.physicalCompletionCycles := trafficGenIO.physicalCompletionCycles
    trafficGenIO.batchMode := ep.io.trafficgen.batchMode
    trafficGenIO.replayEpoch := ep.io.trafficgen.replayEpoch
    ep.io.trafficgen.completedBundleIdWriteEn := trafficGenIO.completedBundleIdWriteEn
    ep.io.trafficgen.completedBundleIdWriteIdx := trafficGenIO.completedBundleIdWriteIdx
    ep.io.trafficgen.completedBundleIdWriteData := trafficGenIO.completedBundleIdWriteData
    ep.io.trafficgen.completedBundleCountWriteEn := trafficGenIO.completedBundleCountWriteEn
    ep.io.trafficgen.completedBundleCountWriteData := trafficGenIO.completedBundleCountWriteData
    trafficGenIO.startRound := ep.io.trafficgen.startRound
    trafficGenIO.trafficGenDone := ep.io.trafficgen.trafficGenDone
    trafficGenIO.minIssueCycle := ep.io.trafficgen.minIssueCycle
    ep.io.trafficgen.accessReadCycle := trafficGenIO.accessReadCycle
    ep.io.trafficgen.accessReadEn := trafficGenIO.accessReadEn
    ep.io.trafficgen.accessReadBatchReady := trafficGenIO.accessReadBatchReady
    trafficGenIO.accessReadRespValid := ep.io.trafficgen.accessReadRespValid
    trafficGenIO.accessReadRespId := ep.io.trafficgen.accessReadRespId
    trafficGenIO.accessReadData := ep.io.trafficgen.accessReadData
    trafficGenIO.accessReadDataValid := ep.io.trafficgen.accessReadDataValid
    trafficGenIO.accessReadBucketDone := ep.io.trafficgen.accessReadBucketDone
    trafficGenIO.accessReadReady := ep.io.trafficgen.accessReadReady
    ep.io.trafficgen.accessReadConsumeMask := trafficGenIO.accessReadConsumeMask
    trafficGenIO.accessReadLaneDoneMask := ep.io.trafficgen.accessReadLaneDoneMask
    trafficGenIO.accessReadPrefetchPauseReq := ep.io.trafficgen.accessReadPrefetchPauseReq
    ep.io.trafficgen.accessReadPrefetchPauseAck := trafficGenIO.accessReadPrefetchPauseAck
    trafficGenIO.accessStoreCount := ep.io.trafficgen.accessStoreCount
    trafficGenIO.accessStoreMaxCycle := ep.io.trafficgen.accessStoreMaxCycle
    trafficGenIO.accessStoreHasEntries := ep.io.trafficgen.accessStoreHasEntries
    trafficGenIO.accessStoreHasMore := ep.io.trafficgen.accessStoreHasMore
    trafficGenIO.uploadReady := ep.io.trafficgen.uploadReady
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  }
}
