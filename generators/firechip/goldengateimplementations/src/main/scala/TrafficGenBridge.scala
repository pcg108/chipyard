// See LICENSE for license details

package firechip.goldengateimplementations

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import midas.core.CPUManagedAXI4Key
import midas.widgets._
import midas.targetutils.xdc.{RAMStyleHint, RAMStyles}
import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

// Create fixed address map for Bridge module UsesCPUManagedBRAM
object TrafficGenBRAMAddressMap {
  val base: BigInt   = BigInt("40000000", 16)
  val stride: BigInt = BigInt("08000000", 16)
  val size: BigInt   = stride

  val rawAccessStoreOffset: BigInt              = BigInt("02000000", 16)
  val rawIssuedAccessWritebackStoreOffset: BigInt = BigInt("03000000", 16)
  val rawCompletedBundleIdsOffset: BigInt       = BigInt("04480000", 16)
  val lrcMetadataOffset: BigInt = BigInt("05000000", 16)
  val lrcReportOffset: BigInt = BigInt("05800000", 16)
  // Logical tables retain their capacities; metadata is installed in paused chunks.
  val lrcMetadataEntries = 4096
  val lrcReportPayloadBits = 136 // kind, 64-bit identity, 64-bit cycle
}

class TrafficGenBridgeModule(key: TrafficGenBridgeKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TrafficGenBridgeTargetIO]]()(p)
    with UsesCPUManagedBRAM {

  private val cpuManagedAXI4Params = p(CPUManagedAXI4Key).get
  private val bramBeatBytes        = cpuManagedAXI4Params.dataBits / 8
  private val accessStreamWidth =
    if (key.useRTL) RTLL2Access.streamWidthBits else L2Access.streamWidthBits
  private val issuedStreamWidth = IssuedAccess.streamWidthBits
  require(cpuManagedAXI4Params.dataBits == 512, "TrafficGen direct BRAM path expects 512-bit CPU-managed XDMA")

  // create bramSlaveNode- this is connected in FPGATop to a xbar along with regular streaming engines to FPGA XDMA via io_pcis
  val bramBase: BigInt = TrafficGenBRAMAddressMap.base + BigInt(getWId) * TrafficGenBRAMAddressMap.stride
  val bramSize: BigInt = TrafficGenBRAMAddressMap.size
  val bramAddress: Seq[AddressSet] = Seq(AddressSet(bramBase, bramSize - 1))
  val bramSlaveNode: AXI4SlaveNode = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        slaves = Seq(
          AXI4SlaveParameters(
            address       = bramAddress,
            resources     = (new MemoryDevice).reg,
            regionType    = RegionType.UNCACHED,
            executable    = false,
            supportsWrite = TransferSizes(bramBeatBytes, 4096),
            supportsRead  = TransferSizes(bramBeatBytes, 4096),
            interleavedId = Some(0),
          )
        ),
        beatBytes = bramBeatBytes,
      )
    )
  )

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new TrafficGenBridgeTargetIO(key.numLanes, key.useRTL)))
    val target = hPort.hBits.trafficgen

    // Active launch submissions stop at the engine's normal round-completion
    // fence. Only an idle engine may pause directly on a new control request.
    val resumeTarget = RegInit(false.B)
    val boundaryPaused = RegInit(false.B)
    val startRoundPending = RegInit(false.B)
    val accessReadTargetPaused = RegInit(false.B)
    val trafficGenDone = RegInit(false.B)
    val trafficGenDonePulse = Wire(Bool())
    trafficGenDonePulse := false.B
    val lrcTokenPermit = WireDefault(true.B)
    val tokenEffects = Wire(Bool())

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !boundaryPaused &&
      !accessReadTargetPaused && lrcTokenPermit
    tokenEffects := fire

    // Metadata commits and native completion accounting run exclusively in
    // host cycles. HostPort's current target token remains held until every
    // same-cycle return and timer has committed.
    val lrcEnable = RegInit(false.B)
    val lrcMonitoring = RegInit(false.B)
    val lrcDrainOnly = RegInit(false.B)
    val lrcRefillOnly = RegInit(false.B)
    val lrcArrivalLow = RegInit(0.U(32.W))
    val lrcArrivalHigh = RegInit(0.U(32.W))
    val lrcArrivalHorizon = Cat(lrcArrivalHigh, lrcArrivalLow)
    val lrcBeginRound = WireDefault(false.B)
    val lrcRoundActive = RegInit(false.B)
    val lrcParkedToken = RegInit(false.B)
    // Legacy SimpleTrafficGen returns one cycle after its last issue without
    // retiring that next cycle. Park the next raw token before either monitor
    // sees it; resume processes it under the replacement public wake policy.
    val legacyIssueBoundaryPending = RegInit(false.B)
    val legacyUnaccountedBoundary = RegInit(false.B)
    val lrcOrdinaryParked = RegInit(false.B)
    val lrcOrdinaryResume = lrcOrdinaryParked && !lrcEnable
    val lrcCheckpointPending = RegInit(false.B)
    val lrcCheckpointActive = RegInit(false.B)
    val lrcCheckpointCycle = Reg(UInt(64.W))
    val lrcRecheck = WireDefault(false.B)
    val lrcReplayEpoch = RegInit(0.U(32.W))
    val lrcUnissued = RegInit(0.U(32.W))
    val lrcBoundaryFlags = RegInit(0.U(32.W))
    val lrcReset = WireDefault(false.B)
    val lrcMonitor = withReset(reset.asBool || lrcReset) { Module(new LrcCompletionMonitor(key.numLanes)) }
    val tmaMonitor = withReset(reset.asBool || lrcReset) { Module(new TmaWakeMonitor(key.numLanes)) }
    RAMStyleHint(tmaMonitor.heads, RAMStyles.BLOCK)
    RAMStyleHint(tmaMonitor.edges, RAMStyles.BLOCK)
    RAMStyleHint(tmaMonitor.groups, RAMStyles.BLOCK)
    RAMStyleHint(tmaMonitor.heap.mem, RAMStyles.BLOCK)
    val accountingReady = lrcMonitor.io.tokenReady && tmaMonitor.io.tokenReady
    val accountingIdle = lrcMonitor.io.metadataIdle && tmaMonitor.io.metadataIdle
    val accountingPending = lrcMonitor.io.pending || tmaMonitor.io.pending
    val accountingWake = lrcMonitor.io.wake || tmaMonitor.io.wake
    val honorDeadline = !lrcDrainOnly || tmaMonitor.io.honorDeadline
    RAMStyleHint(lrcMonitor.services, RAMStyles.BLOCK)
    RAMStyleHint(lrcMonitor.bundles, RAMStyles.BLOCK)
    RAMStyleHint(lrcMonitor.heap.mem, RAMStyles.BLOCK)
    val lrcMetadataIndexBits = log2Ceil(TrafficGenBRAMAddressMap.lrcMetadataEntries)
    val lrcMetadata = SyncReadMem(TrafficGenBRAMAddressMap.lrcMetadataEntries, UInt(512.W))
    val lrcReports = SyncReadMem(131072, UInt(TrafficGenBRAMAddressMap.lrcReportPayloadBits.W))
    RAMStyleHint(lrcMetadata, RAMStyles.BLOCK)
    RAMStyleHint(lrcReports, RAMStyles.BLOCK)
    val lrcMetadataCount = RegInit(0.U(32.W))
    val lrcCommitMetadata = WireDefault(false.B)
    val lrcMetadataBusy = RegInit(false.B)
    val lrcMetadataCursor = RegInit(0.U((lrcMetadataIndexBits + 1).W))
    val lrcMetaReadPending = RegInit(false.B)
    val lrcMetaValid = RegInit(false.B)
    val lrcMetaBits = Reg(UInt(512.W))
    val lrcMetaRead = lrcMetadataBusy && lrcMetadataCursor < lrcMetadataCount &&
      !lrcMetaReadPending && !lrcMetaValid
    val lrcMetaReadBits = lrcMetadata.read(lrcMetadataCursor(lrcMetadataIndexBits - 1, 0), lrcMetaRead)
    lrcMetaReadPending := lrcMetaRead
    when(lrcMetaReadPending) { lrcMetaBits := lrcMetaReadBits; lrcMetaValid := true.B }
    val tmaMetadata = lrcMetaBits(7,0) >= 4.U
    lrcMonitor.io.metadata.valid := lrcMetaValid && !tmaMetadata
    tmaMonitor.io.metadata.valid := lrcMetaValid && tmaMetadata
    tmaMonitor.io.metadata.bits := lrcMetaBits
    lrcMonitor.io.metadata.bits := lrcMetaBits
    when(lrcMonitor.io.metadata.fire || tmaMonitor.io.metadata.fire) {
      lrcMetaValid := false.B
      lrcMetadataCursor := lrcMetadataCursor + 1.U
    }
    when(lrcCommitMetadata) {
      assert(boundaryPaused && !lrcMetadataBusy, "LRC metadata commit requires a paused boundary")
      assert(lrcMetadataCount <= TrafficGenBRAMAddressMap.lrcMetadataEntries.U, "LRC metadata staging capacity exceeded")
      lrcMetadataBusy := true.B; lrcMetadataCursor := 0.U
    }
    when(lrcMetadataBusy && lrcMetadataCursor === lrcMetadataCount &&
      !lrcMetaReadPending && !lrcMetaValid && accountingIdle) {
      lrcMetadataBusy := false.B; lrcMonitoring := true.B
    }
    val lrcMetadataReady = !lrcMetadataBusy && accountingIdle
    val lrcReportCount = RegInit(0.U(32.W))
    val lrcServiceHighWater = RegInit(0.U(32.W))
    val lrcBundleHighWater = RegInit(0.U(32.W))
    val lrcTimerHighWater = RegInit(0.U(32.W))
    val lrcReportHighWater = RegInit(0.U(32.W))
    lrcServiceHighWater := Mux(lrcMonitor.io.serviceCount > lrcServiceHighWater,
      lrcMonitor.io.serviceCount, lrcServiceHighWater)
    lrcBundleHighWater := Mux(lrcMonitor.io.bundleCount > lrcBundleHighWater,
      lrcMonitor.io.bundleCount, lrcBundleHighWater)
    lrcTimerHighWater := Mux(lrcMonitor.io.timerCount > lrcTimerHighWater,
      lrcMonitor.io.timerCount, lrcTimerHighWater)
    lrcReportHighWater := Mux(lrcReportCount > lrcReportHighWater,
      lrcReportCount, lrcReportHighWater)
    // These new event channels have zero target latency; the legacy status
    // channels retain their existing one-cycle latency.
    val lrcCurrentCycle = target.physicalCompletionCycles(63, 0)
    // Ordinary HostPort status is delayed one target token. Keep physical
    // pretracking on that same public cycle until a native batch takes over.
    val lrcOrdinaryReturnMask = RegInit(0.U(key.numLanes.W))
    val lrcOrdinaryReturnTokens = Reg(UInt((key.numLanes * 64).W))
    val lrcOrdinaryReturnCycles = Reg(UInt((key.numLanes * 64).W))
    when(fire && !lrcEnable) {
      lrcOrdinaryReturnMask := target.physicalCompletionValidMask
      lrcOrdinaryReturnTokens := target.physicalCompletionTokens
      lrcOrdinaryReturnCycles := target.physicalCompletionCycles
    }
    // Reserve enough output space for every live bundle timer plus all raw
    // returns on the token before starting its indivisible accounting pass.
    val lrcReportCapacity = Module(new LrcReportCapacityGate(key.numLanes))
    lrcReportCapacity.io.enabled := lrcMonitoring && !boundaryPaused && !lrcParkedToken
    lrcReportCapacity.io.tokenIdle := lrcMonitor.io.tokenIdle && tmaMonitor.io.tokenIdle
    lrcReportCapacity.io.count := lrcReportCount + tmaMonitor.io.groupCount
    lrcReportCapacity.io.bundles := lrcMonitor.io.bundleCount
    lrcReportCapacity.io.returns := Mux(lrcEnable, target.physicalCompletionValidMask, lrcOrdinaryReturnMask)
    val lrcReportDrainRequired = lrcReportCapacity.io.drainRequired
    val lrcClearReports = WireDefault(false.B)
    val completionReports = Module(new Arbiter(UInt(512.W), 2))
    completionReports.io.in(0) <> lrcMonitor.io.report
    completionReports.io.in(1) <> tmaMonitor.io.report
    completionReports.io.out.ready := lrcReportCount < 131072.U
    when(completionReports.io.out.fire) {
      assert(completionReports.io.out.bits(511, TrafficGenBRAMAddressMap.lrcReportPayloadBits) === 0.U,
        "LRC/TMA report exceeds packed payload width")
      lrcReports.write(lrcReportCount(16, 0), completionReports.io.out.bits(TrafficGenBRAMAddressMap.lrcReportPayloadBits - 1, 0))
      lrcReportCount := lrcReportCount + 1.U
    }
    when(lrcClearReports) {
      assert(boundaryPaused || lrcReportDrainRequired, "LRC report clear requires a paused boundary")
      lrcReportCount := 0.U
    }
    val lrcReportReadEnable = WireDefault(false.B)
    val lrcReportReadIndex = WireDefault(0.U(17.W))
    // Preserve the existing one-report-per-512-bit DMA beat ABI.
    val lrcReportReadBits = lrcReports.read(lrcReportReadIndex, lrcReportReadEnable).pad(512)
    val lrcMonitorCanRun = lrcMonitoring && !boundaryPaused && !accessReadTargetPaused &&
      !lrcMetadataBusy && !lrcReportDrainRequired && hPort.toHost.hValid && hPort.fromHost.hReady
    lrcMonitor.io.tokenValid := lrcMonitorCanRun &&
      (lrcCheckpointPending || (!lrcParkedToken && !lrcCheckpointActive && !legacyIssueBoundaryPending))
    lrcMonitor.io.recheck := lrcRecheck
    lrcMonitor.io.cycle := Mux(lrcCheckpointPending || lrcCheckpointActive, lrcCheckpointCycle,
      Mux(lrcEnable, lrcCurrentCycle, target.currentCycleAfterIssue))
    lrcMonitor.io.returns := Mux(lrcCheckpointPending || lrcCheckpointActive, 0.U,
      Mux(lrcEnable, target.physicalCompletionValidMask, lrcOrdinaryReturnMask))
    lrcMonitor.io.tokens := Mux(lrcEnable, target.physicalCompletionTokens,
      lrcOrdinaryReturnTokens).asTypeOf(Vec(key.numLanes, UInt(64.W)))
    lrcMonitor.io.returnCycles := Mux(lrcEnable, target.physicalCompletionCycles,
      lrcOrdinaryReturnCycles).asTypeOf(Vec(key.numLanes, UInt(64.W)))
    lrcMonitor.io.tokenConsumed := fire && lrcMonitoring
    lrcTokenPermit := !lrcMetadataBusy && (!lrcMonitoring || accountingReady)
    tmaMonitor.io.tokenValid := lrcMonitor.io.tokenValid
    tmaMonitor.io.recheck := lrcMonitor.io.recheck
    tmaMonitor.io.cycle := lrcMonitor.io.cycle
    tmaMonitor.io.returns := lrcMonitor.io.returns
    tmaMonitor.io.tokens := lrcMonitor.io.tokens
    tmaMonitor.io.returnCycles := lrcMonitor.io.returnCycles
    tmaMonitor.io.tokenConsumed := lrcMonitor.io.tokenConsumed
    target.batchMode := lrcEnable
    target.replayEpoch := lrcReplayEpoch

    private val launchSlots = 4
    val launchRegistryIds = RegInit(VecInit(Seq.fill(launchSlots)(0.U(64.W))))
    val launchIds = RegInit(VecInit(Seq.fill(launchSlots)(0.U(64.W))))
    val launchPendingMask = RegInit(0.U(launchSlots.W))
    val closeSubmissions = RegInit(false.B)

    // stages the update registers so that all launch and session status updates are applied atomically
    val stagedLaunchStatusIdLow = RegInit(VecInit(Seq.fill(launchSlots)(0.U(32.W))))
    val stagedLaunchStatusIdHigh = RegInit(VecInit(Seq.fill(launchSlots)(0.U(32.W))))
    val stagedLaunchStatuses = RegInit(VecInit(Seq.fill(launchSlots)(0.U(8.W))))
    val stagedSessionStatus = RegInit(0.U(32.W))
    val launchStatusIds = RegInit(VecInit(Seq.fill(launchSlots)(0.U(64.W))))
    val launchStatuses = RegInit(VecInit(Seq.fill(launchSlots)(0.U(8.W))))
    val sessionStatus = RegInit(0.U(32.W))
    val commitLaunchStatus = WireDefault(false.B)

    val priorLaunchStatusIds = RegEnable(launchStatusIds.asUInt, 0.U, fire && !lrcEnable)
    val priorLaunchStatuses = RegEnable(launchStatuses.asUInt, 0.U, fire && !lrcEnable)
    val priorSessionStatus = RegEnable(sessionStatus, 0.U, fire && !lrcEnable)
    target.launchStatusIds := Mux(lrcOrdinaryResume, priorLaunchStatusIds, launchStatusIds.asUInt)
    target.launchStatuses := Mux(lrcOrdinaryResume, priorLaunchStatuses, launchStatuses.asUInt)
    target.sessionStatus := Mux(lrcOrdinaryResume, priorSessionStatus, sessionStatus)
    when(commitLaunchStatus) {
      assert(boundaryPaused, "TrafficGenBridge published launch status while target was running")
      for (slot <- 0 until launchSlots) {
        launchStatusIds(slot) := Cat(stagedLaunchStatusIdHigh(slot), stagedLaunchStatusIdLow(slot))
      }
      launchStatuses := stagedLaunchStatuses
      sessionStatus := stagedSessionStatus
    }

    // The first token after resume may still contain the old pending mask.
    // Compare the IDs with the committed host acknowledgement as well, so that
    // token cannot stop the target a second time for the same submission.
    val unacknowledgedLaunches = VecInit((0 until launchSlots).map { slot =>
      val id = target.launchIds(64 * slot + 63, 64 * slot)
      target.launchPendingMask(slot) && id =/= 0.U && id =/= launchStatusIds(slot)
    }).asUInt
    val unacknowledgedClose = target.closeSubmissions && !sessionStatus(1)
    val sessionTerminal = sessionStatus(5, 3).orR
    // An idle-looking token may be the first response after the host has
    // started a round. A launch first visible on that token must reach the
    // engine's control-exit fence, otherwise pausing here strands its start
    // handshake while the driver is already waiting for round completion.
    val idleControlBoundary = fire && !target.targetBusy && !startRoundPending && !sessionTerminal &&
      (unacknowledgedLaunches.orR || unacknowledgedClose)
    val legacyStartBoundary = fire && target.startTrafficGen

    when(tokenEffects) {
      for (slot <- 0 until launchSlots) {
        launchRegistryIds(slot) := target.launchRegistryIds(64 * slot + 63, 64 * slot)
        launchIds(slot) := target.launchIds(64 * slot + 63, 64 * slot)
      }
      launchPendingMask := unacknowledgedLaunches
      closeSubmissions := target.closeSubmissions
    }

    // Retain the existing driver doorbell name for idle control boundaries.
    val startTrafficGenLatched = RegInit(false.B)
    when(resumeTarget) {
      startTrafficGenLatched := false.B
      boundaryPaused := false.B
    }
    when(idleControlBoundary || legacyStartBoundary) {
      startTrafficGenLatched := true.B
      trafficGenDone := false.B
      boundaryPaused := true.B
    }
    when(trafficGenDonePulse) {
      trafficGenDone := true.B
    }
    val priorTrafficGenDone = RegEnable(trafficGenDone, false.B, fire && !lrcEnable)
    target.trafficGenDone := Mux(lrcOrdinaryResume, priorTrafficGenDone, trafficGenDone)


    // Bridge protocol width and number of independent access-store banks.
    /*
      Lane: independent request-issue path
      Slot: holds prefetched request that lane can issue next

      The accessStore drives N independent issue lanes in the RTL engine, corresponding to the number of generators specified in the config.
      To avoid issuing the same request multiple times, we have the following handshake: 
      - engine issues request 0 from slot 0 and signals that it did
      - bridge receives that signal, removes request 0, and updates ackonwledgement to slot 0
      - engine sees acknowledgement from slot 0 before using that slot again
      Thus there is a 3-cycle latency before the engine can consume from the next slot. 
      Therefore we have 3 slots per lane, each with a 2-entry FIFO to prefetch the next entry for the slot
    */
    private val replayLanes = key.numLanes
    private val replaySlotsPerLane = TrafficGenReplaySlots.slotsPerLane(key.useRTL) //3, basically
    private val replaySlots = TrafficGenReplaySlots.totalSlots(replayLanes, key.useRTL) // N * 3 basically
    private val replayLaneIdxWidth = log2Ceil(replayLanes max 2)
    private val replaySlotSelectWidth = log2Ceil(replaySlotsPerLane max 2)
    private val replaySlotIdxWidth = log2Ceil(replaySlots)
    require(key.maxL2AccessEntries % replayLanes == 0,
      "TrafficGen banked replay requires maxL2AccessEntries to divide evenly across lanes")
    
    // how many entries per lane bank
    private val laneBankDepth = key.maxL2AccessEntries / replayLanes
    private val laneBankIdxWidth = log2Ceil(laneBankDepth max 2)
    private val laneBankCountWidth = log2Ceil(laneBankDepth + 1)

    /*

      ISSUED ACCESS WRITEBACK

      Target-issued accesses are buffered into issuedAccessWritebackStore for logging in the GPU model
    
      There are N separate issued-access writeback stores, one per replay lane.
      issuedAccessWritebackLaneCounts 
    
    */

    // One SyncReadMem bank per lane to store issued-access batches from target
    val issuedAccessWritebackStores = Seq.fill(replayLanes) {
      val mem = SyncReadMem(laneBankDepth, UInt(issuedStreamWidth.W))
      RAMStyleHint(mem, RAMStyles.BLOCK)
      mem
    }
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackLaneCounts = RegInit(VecInit(Seq.fill(replayLanes)(0.U(32.W))))

    // set a monotonically increasing expected batch ID that tracks the batchID set in the target
    // to ensure that we don't drop issued-access batches
    val issuedBatchExpectedId = RegInit(0.U(32.W))

    // Convert the packed target batch into one issued-access record per lane.
    val issuedCaptureMask = Mux(lrcEnable, target.physicalIssueValidMask, target.issuedAccessBatch.bits.validMask)
    val issuedBatchPackedLanes = Mux(lrcEnable, target.physicalIssueAccesses,
      target.issuedAccessBatch.bits.accesses).asTypeOf(Vec(replayLanes, UInt(issuedStreamWidth.W)))
    val issuedBatchIncomingCount = PopCount(issuedCaptureMask)

    // check that every valid lane in incoming issued batch has space in corresponding writeback lane bank
    // e.g. batch doesn't contain that lane, or lane write-back bank not full
    val issuedBatchFits = VecInit((0 until replayLanes).map { lane =>
      !issuedCaptureMask(lane) ||
        issuedAccessWritebackLaneCounts(lane) < laneBankDepth.U
    }).asUInt.andR

    // ensure that bridge has space to capture issued-batch 
    target.issuedAccessBatch.ready := fire && issuedBatchFits
    // fire signal for issued batch
    val issuedBatchCapture = Mux(lrcEnable, tokenEffects && target.physicalIssueValidMask.orR,
      target.issuedAccessBatch.valid && target.issuedAccessBatch.ready)
    when(tokenEffects && Mux(lrcEnable, target.physicalIssueValidMask.orR, target.issuedAccessBatch.valid)) {
      assert(issuedBatchFits,
        "TrafficGenBridge issued-access batch exceeded the writeback store capacity")
    }

    when(issuedBatchCapture) {
      assert(issuedCaptureMask.orR, "TrafficGenBridge captured an empty issued-access batch")
      when(!lrcEnable) {
        assert(target.issuedAccessBatch.bits.batchId === issuedBatchExpectedId, "TrafficGenBridge captured an out-of-order issued-access batch")
        issuedBatchExpectedId := issuedBatchExpectedId + 1.U
      }
      
      // write into writeback store and increment lane counts
      issuedAccessWritebackCount := issuedAccessWritebackCount + issuedBatchIncomingCount
      for (lane <- 0 until replayLanes) {
        when(issuedCaptureMask(lane)) {
          issuedAccessWritebackStores(lane).write(
            issuedAccessWritebackLaneCounts(lane)(laneBankIdxWidth - 1, 0),
            issuedBatchPackedLanes(lane))
          issuedAccessWritebackLaneCounts(lane) := issuedAccessWritebackLaneCounts(lane) + 1.U
        }
      }
    }

    // read path for bridge driver to read issuedAccessWritebackStores through XDMA/AXI interface

    // SyncReadMem writes commit on the bridge edge after capture.  Keep an
    // explicit one-cycle fence for round-completion visibility.
    val issuedWriteCommitPending = RegNext(issuedBatchCapture, false.B)

    val axiRawIssuedReadLane = WireDefault(0.U(replayLaneIdxWidth.W))
    val axiRawIssuedReadIdx = WireDefault(0.U(laneBankIdxWidth.W))
    val axiRawIssuedReadEn = WireDefault(false.B)
    val axiRawIssuedReadLaneReg = RegInit(0.U(replayLaneIdxWidth.W))
    val issuedAccessReadBits = VecInit((0 until replayLanes).map { lane =>
      issuedAccessWritebackStores(lane).read(
        axiRawIssuedReadIdx,
        axiRawIssuedReadEn && axiRawIssuedReadLane === lane.U)
    })

    /*

      COMPLETED BUNDLE IDs

      Completed bundle IDs are written by the target during/after traffic generator, and used for scheduling next batch of accesses

      - as bundles complete, IDs enter per-lane completion queues in target 
      - target arbiter drains those (one ID per cycle) into completedBundleIds SyncReadMem
      - at round exit, target drains IDs and then writes completedBundleCount with roundComplete

    */

    // completedBundleIds will be streamed back from traffic generator to bridge driver.
    // Each 512-bit beat carries eight 64-bit bundle IDs.
    val completedBundleIdBeats = CompletedBundleIds.beats
    val completedBundleBeatIdxWidth = log2Ceil(completedBundleIdBeats)

    // Store completed bundle IDs completed by target, to be streamed back to the bridge driver.
    val completedBundleIds = SyncReadMem(completedBundleIdBeats, Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))

    val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))
    val completedBundleIdsWriteEn = WireDefault(false.B)
    val completedBundleIdsWriteAddr = WireDefault(0.U(completedBundleBeatIdxWidth.W))
    val completedBundleIdsWriteData = Wire(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
    completedBundleIdsWriteData.foreach(_ := 0.U)
    val completedBundleIdsWriteMask = Wire(Vec(CompletedBundleIds.idsPerBeat, Bool()))
    completedBundleIdsWriteMask.foreach(_ := false.B)

    // target writes the completedBundleCount to help bridge driver read
    when(tokenEffects && target.completedBundleCountWriteEn && !lrcEnable) {
      completedBundleCount := target.completedBundleCountWriteData
    }
    // write index and data to write into completedBundleIds
    when(tokenEffects && target.completedBundleIdWriteEn && !lrcEnable) {
      val beatIdx = target.completedBundleIdWriteIdx >> log2Ceil(CompletedBundleIds.idsPerBeat)
      val laneIdx = target.completedBundleIdWriteIdx(log2Ceil(CompletedBundleIds.idsPerBeat) - 1, 0)
      val writeData = Wire(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
      writeData.foreach(_ := target.completedBundleIdWriteData)
      completedBundleIdsWriteEn := true.B
      completedBundleIdsWriteAddr := beatIdx(completedBundleBeatIdxWidth - 1, 0)
      completedBundleIdsWriteData := writeData
      completedBundleIdsWriteMask.zip(UIntToOH(laneIdx, CompletedBundleIds.idsPerBeat).asBools).foreach { case (dst, src) =>
        dst := src
      }
    }

    // AXI enable and data signals to read from completedBundleIds and send over XDMA
    val axiRawCompletedReadIdx = WireDefault(0.U(completedBundleBeatIdxWidth.W))
    val axiRawCompletedReadEn = WireDefault(false.B)
    val completedBundleReadBits = completedBundleIds.read(axiRawCompletedReadIdx, axiRawCompletedReadEn)

    /*

      L2 ACCESS UPLOAD AND READ

      - SyncReadMem stores raw XDMA-uploaded L2 accesses from the bridge driver
      - N independent read lanes from bridge module to target, each with 3 2-entry prefetch FIFOs
      - if any slot FIFO is empty while that slot still has requests, the target is paused, to avoid wasting target cycles
    */

    // Store uploaded accesses lane-major
    // Every bank has its own read port and a two-entry target-facing prefetch FIFO
    val accessStores = Seq.fill(replayLanes) {
      val mem = SyncReadMem(laneBankDepth, UInt(accessStreamWidth.W))
      RAMStyleHint(mem, RAMStyles.BLOCK)
      mem
    }
    // target facing metadata
    val accessStoreCount = RegInit(0.U(32.W))
    val accessStoreMaxCycle = RegInit(0.U(64.W))
    val accessStoreHasEntries = RegInit(false.B)
    val accessStoreHasMore = RegInit(false.B) // driven by bridge driver indicating there is more to this round
    val uploadLaneCounts = RegInit(VecInit(Seq.fill(replayLanes)(0.U(32.W))))

    // enable, address, and data for each lane's SyncReadMem write from XDMA/AXI
    val accessStoreWriteEn = Wire(Vec(replayLanes, Bool()))
    val accessStoreWriteAddr = Wire(Vec(replayLanes, UInt(laneBankIdxWidth.W)))
    val accessStoreWriteData = Wire(Vec(replayLanes, UInt(accessStreamWidth.W)))
    accessStoreWriteEn.foreach(_ := false.B)
    accessStoreWriteAddr.foreach(_ := 0.U)
    accessStoreWriteData.foreach(_ := 0.U)

    // Bridge driver pulses this after XDMA writes have populated the uploaded round data.
    val commitUpload = Wire(Bool())
    // written by bridge driver to indicate how many L2 accesses we are uploading
    val uploadCount = RegInit(0.U(32.W))
    val uploadMaxCycleLow = RegInit(0.U(32.W))
    val uploadMaxCycleHigh = RegInit(0.U(32.W))
    val uploadMaxCycle = Cat(uploadMaxCycleHigh, uploadMaxCycleLow)
    val uploadHasMore = RegInit(false.B) 

    val uploadReady = RegInit(false.B)
    // prefetch FIFOs have not been sufficiently filled for target to start replay
    val accessReadPrefillPending = RegInit(false.B)
    val accessReplayActive = RegInit(false.B)

    // reset the target-side access-read response state when a fresh upload begins
    val accessReadReset = WireDefault(false.B)

    // Three independently consumable two-entry prefetch FIFOs per RTL replay lane
    // The DPI backend retains one FIFO per lane.
    val accessReadQueues = Seq.fill(replaySlots) {
      withReset(reset.asBool || accessReadReset) {
        Module(new Queue(UInt(accessStreamWidth.W), 2, pipe = true, flow = false))
      }
    }

    /*
      Slot 0 cursor = 0 → 3 → 6 → …
      Slot 1 cursor = 1 → 4 → 7 → …
      Slot 2 cursor = 2 → 5 → 8 → … 
    */
    // next BRAM entry to fetch for this slot (3 slots per lane)
    // index = lane * 3 + slot 
    val accessReadCursors = RegInit(VecInit(Seq.tabulate(replaySlots) { flat =>
      (flat % replaySlotsPerLane).U(laneBankCountWidth.W)
    }))
    // whether BRAM read has been launched for slot FIFO, and which slot should recieve the pending BRAM read 
    val accessReadFetchPending = RegInit(VecInit(Seq.fill(replayLanes)(false.B)))
    val accessReadFetchPendingSlot = RegInit(VecInit(Seq.fill(replayLanes)(0.U(replaySlotSelectWidth.W))))
    
    // which slot gets the next BRAM read for this lane
    val accessReadFetchRoundRobin = RegInit(VecInit(Seq.fill(replayLanes)(0.U(replaySlotSelectWidth.W))))
    // which slot target must consume next
    val accessReadExpectedConsumeSlot = RegInit(VecInit(Seq.fill(replayLanes)(0.U(replaySlotSelectWidth.W))))

    // read enable, address, bits for each lane's bank read
    val accessReadBankReadEn = Wire(Vec(replayLanes, Bool()))
    val accessReadBankReadAddr = Wire(Vec(replayLanes, UInt(laneBankIdxWidth.W)))
    val accessReadBankReadBits = Wire(Vec(replayLanes, UInt(accessStreamWidth.W)))

    // per-slot pulse that removes the head from a slot's FIFO
    val accessReadConsume = Wire(Vec(replaySlots, Bool()))
    val accessReadSlotDone = Wire(Vec(replaySlots, Bool()))

    // handle filling 3*N prefetch FIFOs across N lanes
    for (lane <- 0 until replayLanes) { 
      
      val laneBase = lane * replaySlotsPerLane
      
      // for each of the N lanes, write the accessStore read into one of the 3 slot FIFOs
      val pendingResponseAccepted = WireDefault(false.B)
      for (slot <- 0 until replaySlotsPerLane) {
        val flat = laneBase + slot
        val queue = accessReadQueues(flat)
        queue.io.enq.valid := accessReadFetchPending(lane) && accessReadFetchPendingSlot(lane) === slot.U
        queue.io.enq.bits := accessReadBankReadBits(lane)
        when(queue.io.enq.fire) {
          pendingResponseAccepted := true.B
        }
      }

      // eligibility- bridge can launch BRAM read to refill slot FIFO this cycle
      val pipelineCanLaunch = !accessReadFetchPending(lane) || pendingResponseAccepted
      val slotCanFetch = Wire(Vec(replaySlotsPerLane, Bool()))
      for (slot <- 0 until replaySlotsPerLane) {
        val flat = laneBase + slot
        slotCanFetch(slot) :=
          pipelineCanLaunch &&                                // no response pending or previous resp entered FIFO this cycle
          accessReadCursors(flat) < uploadLaneCounts(lane) && // there are more entries to fetch in this lane
          accessReadQueues(flat).io.enq.ready &&              // FIFO can accept
          !accessReadReset &&
          (accessReadPrefillPending || accessReplayActive)    // bridge is filling FIFOs or actively replaying
      }

      // round-robin arbiter that chooses which slot gets lane's next BRAM read 
      val selectedFetchValid = WireDefault(false.B)
      val selectedFetchSlot = WireDefault(0.U(replaySlotSelectWidth.W))
      if (replaySlotsPerLane == 1) {
        selectedFetchValid := slotCanFetch(0)
      } else {
        switch(accessReadFetchRoundRobin(lane)) {
          is(0.U) {
            when(slotCanFetch(0)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 0.U
            }.elsewhen(slotCanFetch(1)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 1.U
            }.elsewhen(slotCanFetch(2)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 2.U
            }
          }
          is(1.U) {
            when(slotCanFetch(1)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 1.U
            }.elsewhen(slotCanFetch(2)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 2.U
            }.elsewhen(slotCanFetch(0)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 0.U
            }
          }
          is(2.U) {
            when(slotCanFetch(2)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 2.U
            }.elsewhen(slotCanFetch(0)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 0.U
            }.elsewhen(slotCanFetch(1)) {
              selectedFetchValid := true.B
              selectedFetchSlot := 1.U
            }
          }
        }
      }

      // perform the actual read from lane's accessStore and enqueue into the selected slot FIFO
      val selectedFetchFlat = Wire(UInt(replaySlotIdxWidth.W))
      selectedFetchFlat := laneBase.U(replaySlotIdxWidth.W) + selectedFetchSlot
      accessReadBankReadEn(lane) := selectedFetchValid
      accessReadBankReadAddr(lane) := accessReadCursors(selectedFetchFlat)(laneBankIdxWidth - 1, 0)
      accessReadBankReadBits(lane) := accessStores(lane).read(accessReadBankReadAddr(lane), accessReadBankReadEn(lane))

      // increment cursor for selected slot and update the round-robin pointer for next fetch
      when(selectedFetchValid) {
        accessReadCursors(selectedFetchFlat) := accessReadCursors(selectedFetchFlat) + replaySlotsPerLane.U
        accessReadFetchPendingSlot(lane) := selectedFetchSlot
        accessReadFetchRoundRobin(lane) := Mux(selectedFetchSlot === (replaySlotsPerLane - 1).U,
                                                0.U, selectedFetchSlot + 1.U)
      }

      accessReadFetchPending(lane) := (accessReadFetchPending(lane) && !pendingResponseAccepted) || selectedFetchValid

      // select the 3 consume bits belonging to current lane from full 3N consume mask
      val consumeMask = Mux(lrcEnable, target.physicalConsumeMask, target.accessReadConsumeMask)
      val laneConsumeMask = consumeMask(laneBase + replaySlotsPerLane - 1, laneBase)
      when(tokenEffects) {
        assert(PopCount(laneConsumeMask) <= 1.U,
          "TrafficGenBridge consumed more than one slot from a replay lane")
      }
      // check that target consumed expected slot in the lane
      when(tokenEffects && laneConsumeMask.orR) {
        assert(laneConsumeMask ===
          UIntToOH(accessReadExpectedConsumeSlot(lane), replaySlotsPerLane),
          "TrafficGenBridge consumed replay slots out of lane order")
        accessReadExpectedConsumeSlot(lane) :=
          Mux(accessReadExpectedConsumeSlot(lane) === (replaySlotsPerLane - 1).U,
            0.U, accessReadExpectedConsumeSlot(lane) + 1.U)
      }
      for (slot <- 0 until replaySlotsPerLane) {
        val flat = laneBase + slot
        val queue = accessReadQueues(flat)
        // remove the head from the slot that target consumed 
        accessReadConsume(flat) := tokenEffects && consumeMask(flat)
        queue.io.deq.ready := accessReadConsume(flat)
        when(accessReadConsume(flat)) {
          assert(queue.io.deq.valid, "TrafficGenBridge consumed an invalid prefetched replay slot")
          assert(queue.io.deq.bits(191, 128) <= Mux(lrcEnable, lrcCurrentCycle, target.accessReadCycle), "TrafficGenBridge consumed a replay slot before its scheduled cycle")
        }
        // check if the slot is exhausted 
        accessReadSlotDone(flat) :=
            accessReadCursors(flat) >= uploadLaneCounts(lane) && // no more uploaded BRAM entries to fetch for slot 
            !(accessReadFetchPending(lane) &&                    // no fetched response still waiting to enter slot FIFO
            accessReadFetchPendingSlot(lane) === slot.U) &&
            !queue.io.deq.valid                                  // FIFO is empty
      }
    }

    // A physical lane is complete only after all three striped slot streams have drained.
    val accessReadLaneDone = VecInit((0 until replayLanes).map { lane =>
      val laneBase = lane * replaySlotsPerLane
      accessReadSlotDone.slice(laneBase, laneBase + replaySlotsPerLane).reduce(_ && _)
    })
    // check if any of the FIFOs are empty
    val accessReadHeadMissing = VecInit((0 until replaySlots).map { flat =>
      !accessReadSlotDone(flat) && !accessReadQueues(flat).io.deq.valid
    })

    // active lane is temporarily empty, but lane is not finished, so pause the target until the lane can be refilled
    // pause if any unfinished slot has an empty FIFO
    val accessReadNeedsPause =
      (accessReadPrefillPending || accessReplayActive) && accessReadHeadMissing.asUInt.orR
    // Native replay does not spend a target cycle acknowledging a host BRAM
    // refill. Completion accounting may finish while this gate remains shut.
    val issuedBatchSafeToPause = !target.issuedAccessBatch.valid || issuedBatchCapture

    // present current replay state to target
    target.accessReadPrefetchPauseReq := accessReadNeedsPause
    target.accessReadLaneDoneMask := accessReadLaneDone.asUInt
    target.accessReadData := VecInit(accessReadQueues.map(_.io.deq.bits))
    target.accessReadDataValid := VecInit(accessReadQueues.map(_.io.deq.valid))
    target.accessReadRespValid := target.accessReadDataValid.asUInt.orR || accessReadLaneDone.asUInt.andR
    
    // Every replay slot has an independent two-bit consume generation.
    // when a target consumes a slot, the generation is incremented.  The target can use this to detect if it has consumed a slot more than once.
    val accessReadRespGenerations =
      RegInit(VecInit(Seq.fill(replaySlots)(0.U(2.W))))
    for (slot <- 0 until replaySlots) {
      when(accessReadConsume(slot)) {
        accessReadRespGenerations(slot) := accessReadRespGenerations(slot) + 1.U
      }
    }
    target.accessReadRespId := accessReadRespGenerations.asUInt

    val accessReadGenerationsPrev = RegNext(accessReadRespGenerations.asUInt)
    val accessReadConsumePrev = RegNext(accessReadConsume.asUInt, 0.U)
    val accessReadGenerationCheckValid = RegNext(
      !(accessReadReset || trafficGenDonePulse), false.B)
    when(accessReadGenerationCheckValid) {
      for (slot <- 0 until replaySlots) {
        assert(
          (accessReadRespGenerations(slot) =/=
            accessReadGenerationsPrev(2 * slot + 1, 2 * slot)) ===
            accessReadConsumePrev(slot),
          "TrafficGenBridge slot generation change did not match its consume")
      }
    }
    target.accessReadBucketDone := accessReadLaneDone.asUInt.andR
    target.accessReadReady := true.B

    // pause the target
    when(accessReadNeedsPause && fire && target.accessReadPrefetchPauseAck && issuedBatchSafeToPause) {
      assert(!target.issuedAccessBatch.valid || issuedBatchCapture,
        "TrafficGenBridge paused before capturing a pending issued-access batch")
      accessReadTargetPaused := true.B
    }
    when(accessReadTargetPaused && !accessReadNeedsPause) {
      accessReadTargetPaused := false.B
    }
    val accessReadTargetPausedPrev = RegNext(accessReadTargetPaused, false.B)
    val accessReadPausedTargetCycle = RegEnable(
      target.currentCycleAfterIssue, accessReadTargetPaused && !accessReadTargetPausedPrev)
    when(accessReadTargetPaused && accessReadTargetPausedPrev) {
      assert(target.currentCycleAfterIssue === accessReadPausedTargetCycle,
        "TrafficGenBridge target cycle advanced during prefetch quiescence")
    }

    // check that the prefill FIFOs have been filled for all lanes before allowing the target to start replaying
    val accessReadPrefillComplete = VecInit((0 until replaySlots).map { flat =>
      val lane = flat / replaySlotsPerLane
      val slot = flat % replaySlotsPerLane
      uploadLaneCounts(lane) <= slot.U ||
        accessReadQueues(flat).io.count === 2.U ||
        (accessReadCursors(flat) >= uploadLaneCounts(lane) &&
          !(accessReadFetchPending(lane) &&
            accessReadFetchPendingSlot(lane) === slot.U) &&
          accessReadQueues(flat).io.deq.valid)
    }).asUInt.andR

    // Once driver indicates the uploads to XDMA are ready, bridge module can indicate to target that data is ready
    when(commitUpload) {
      assert(!issuedWriteCommitPending,
        "TrafficGenBridge started a new round before issued batches committed")
      assert(uploadLaneCounts.reduce(_ +& _) === uploadCount,
        "TrafficGenBridge upload lane counts did not equal total upload count")
      for (lane <- 0 until replayLanes) {
        assert(uploadLaneCounts(lane) <= laneBankDepth.U,
          "TrafficGenBridge access-bank upload overflowed")
      }
      uploadReady := false.B
      accessReadPrefillPending := true.B
      accessReadTargetPaused := true.B
      accessReadReset := true.B
      issuedAccessWritebackCount := 0.U
      issuedAccessWritebackLaneCounts.foreach(_ := 0.U)
      // Batch IDs describe the target-reset lifetime, not a physical retry
      // upload.  The RTL engine deliberately preserves its sequence across
      // retries, so the bridge's expected ID must do the same.
      accessStoreCount := uploadCount
      accessStoreMaxCycle := uploadMaxCycle
      accessStoreHasEntries := uploadCount =/= 0.U
      accessStoreHasMore := uploadHasMore
      lrcUnissued := uploadCount
      lrcReplayEpoch := lrcReplayEpoch + 1.U
      for (flat <- 0 until replaySlots) {
        accessReadCursors(flat) := (flat % replaySlotsPerLane).U
      }
      accessReadFetchPending.foreach(_ := false.B)
      accessReadFetchPendingSlot.foreach(_ := 0.U)
      accessReadFetchRoundRobin.foreach(_ := 0.U)
      accessReadExpectedConsumeSlot.foreach(_ := 0.U)
      accessReadRespGenerations.foreach(_ := 0.U)
    }

    when(accessReadPrefillPending && accessReadPrefillComplete && !accessReadReset) {
      uploadReady := true.B
      accessReadPrefillPending := false.B
      accessReadTargetPaused := false.B
    }

    target.uploadReady := uploadReady && !(lrcOrdinaryParked && !lrcEnable)

    when(tokenEffects && target.roundStarted && !lrcEnable) {
      uploadReady := false.B
      accessReplayActive := true.B
    }
    when(fire && target.roundComplete) {
      accessReplayActive := false.B
    }

    // MMIO min_issue_cycle registers and report to target
    val minIssueCycleLow = RegInit(0.U(32.W))
    val minIssueCycleHigh = RegInit(0.U(32.W))
    val minIssueCycle = Cat(minIssueCycleHigh, minIssueCycleLow)
    target.minIssueCycle := Mux(lrcEnable && lrcDrainOnly, ~0.U(64.W), minIssueCycle)
    target.accessStoreCount := accessStoreCount
    target.accessStoreMaxCycle := accessStoreMaxCycle
    target.accessStoreHasEntries := accessStoreHasEntries
    target.accessStoreHasMore := accessStoreHasMore

    /*

      XDMA/AXI READ/WRITE PATH FROM BRIDGE DRIVER

     * Direct CPU-managed XDMA access to TrafficGen BRAM-backed stores.
     * Upload writes directly access the physical SyncReadMems.
    */

    // receive AXI from XDMA
    val bramAxi = bramSlaveNode.in.head._1
    val bramAddrBits = cpuManagedAXI4Params.addrBits
    val bramBeatOffsetBits = log2Ceil(bramBeatBytes)
    val bramBaseU = bramBase.U(bramAddrBits.W)

    // subtract bramBase to determine which offset window access falls into
    def localAddr(addr: UInt): UInt = addr - bramBaseU
    def inBramRange(addr: UInt, offset: BigInt, bytes: BigInt): Bool = {
      val a = localAddr(addr)
      a >= offset.U && a < (offset + bytes).U
    }
    def beatIndex(addr: UInt, offset: BigInt): UInt =
      ((localAddr(addr) - offset.U) >> bramBeatOffsetBits).asUInt

    val accessWindowBytes = BigInt(key.maxL2AccessEntries) * bramBeatBytes
    val completedWindowBytes = BigInt(completedBundleIdBeats) * bramBeatBytes
    require(TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset + completedWindowBytes <=
            TrafficGenBRAMAddressMap.size,
            "TrafficGen completed-bundle ID window exceeds BRAM window")

    val axiWriteActive = RegInit(false.B)
    val axiWriteAddr = Reg(UInt(bramAddrBits.W))
    val axiWriteBeatsRemaining = Reg(UInt(9.W))
    val axiWriteRespValid = RegInit(false.B)
    val axiWriteId = Reg(chiselTypeOf(bramAxi.aw.bits.id))
    val axiWriteUser = Reg(chiselTypeOf(bramAxi.aw.bits.user))

    bramAxi.aw.ready := !axiWriteActive && !axiWriteRespValid
    bramAxi.b.valid := axiWriteRespValid
    bramAxi.b.bits.resp := 0.U
    bramAxi.b.bits.id := axiWriteId
    bramAxi.b.bits.user := axiWriteUser

    // latch the address from AW (write address channel) and determine how many beats for write
    when(bramAxi.aw.fire) {
      axiWriteActive := true.B
      axiWriteAddr := bramAxi.aw.bits.addr
      axiWriteBeatsRemaining := bramAxi.aw.bits.len +& 1.U
      axiWriteId := bramAxi.aw.bits.id
      axiWriteUser := bramAxi.aw.bits.user
    }

    // write response on B channel is true after all beats received
    when(bramAxi.b.fire) {
      axiWriteRespValid := false.B
    }

    // determine which SyncReadMem the write should go to based on the address offset
    val writeRawAccess = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset, accessWindowBytes)
    val writeRawIssued = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset, accessWindowBytes)
    val writeRawCompleted = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)
    val writeLrcMetadata = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.lrcMetadataOffset,
      BigInt(TrafficGenBRAMAddressMap.lrcMetadataEntries) * 64)

    bramAxi.w.ready := axiWriteActive && !axiWriteRespValid

    // each W beat writes directly into the SyncReadMem
    val axiWriteLastBeat = axiWriteBeatsRemaining === 1.U
    when(bramAxi.w.fire) {
      when(writeLrcMetadata) {
        assert(boundaryPaused && !lrcMetadataBusy, "LRC staging write raced metadata processing")
        assert(bramAxi.w.bits.strb.andR, "LRC metadata requires complete 512-bit descriptor writes")
        lrcMetadata.write(beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.lrcMetadataOffset)(lrcMetadataIndexBits - 1, 0), bramAxi.w.bits.data)
      }
      when(writeRawAccess) {
        val flatIdx = beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset)
        // Match the divisor to the XDMA-derived index width.  Besides making
        // the arithmetic intent explicit, this avoids Verilator's fatal
        // WIDTHEXPAND warning for the lane-major address calculation.
        val laneDepthForWrite = laneBankDepth.U(flatIdx.getWidth.W)
        val bank = flatIdx / laneDepthForWrite
        val bankIdx = flatIdx % laneDepthForWrite
        assert(bank < replayLanes.U, "TrafficGenBridge raw access-bank write out of range")
        for (lane <- 0 until replayLanes) {
          when(bank === lane.U) {
            accessStoreWriteEn(lane) := true.B
            accessStoreWriteAddr(lane) := bankIdx(laneBankIdxWidth - 1, 0)
            accessStoreWriteData(lane) := bramAxi.w.bits.data(accessStreamWidth - 1, 0)
          }
        }
      }
      ////
      //  Unused paths since XDMA does not write into issuedAccessWritebackStore or completedBundleIds
      assert(!writeRawIssued, "TrafficGenBridge does not support XDMA writes to issued-access banks")
      when(writeRawCompleted) {
        completedBundleIdsWriteEn := true.B
        completedBundleIdsWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset)(completedBundleBeatIdxWidth - 1, 0)
        completedBundleIdsWriteData := bramAxi.w.bits.data.asTypeOf(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
        completedBundleIdsWriteMask.foreach(_ := true.B)
      }
      ////

      axiWriteAddr := axiWriteAddr + bramBeatBytes.U
      axiWriteBeatsRemaining := axiWriteBeatsRemaining - 1.U
      when(axiWriteLastBeat) {
        axiWriteActive := false.B
        axiWriteRespValid := true.B
      }
    }

    val (
      axiReadRouteNone ::
      axiReadRouteAccess ::
      axiReadRouteIssued ::
      axiReadRouteCompleted ::
      axiReadRouteLrcReport ::
      Nil
    ) = Enum(5)

    val axiReadActive = RegInit(false.B)
    val axiReadAddr = Reg(UInt(bramAddrBits.W))
    val axiReadBeatsRemaining = Reg(UInt(9.W))
    val axiReadPending = RegInit(false.B)
    val axiReadRespValid = RegInit(false.B)
    val axiReadLastReg = RegInit(false.B)
    val axiReadPendingLast = RegInit(false.B)
    val axiReadRouteReg = RegInit(axiReadRouteNone)
    val axiReadDataReg = Reg(UInt(cpuManagedAXI4Params.dataBits.W))
    val axiReadId = Reg(chiselTypeOf(bramAxi.ar.bits.id))
    val axiReadUser = Reg(chiselTypeOf(bramAxi.ar.bits.user))

    bramAxi.ar.ready := !axiReadActive && !axiReadPending && !axiReadRespValid
    bramAxi.r.valid := axiReadRespValid
    bramAxi.r.bits.data := axiReadDataReg
    bramAxi.r.bits.resp := 0.U
    bramAxi.r.bits.last := axiReadLastReg
    bramAxi.r.bits.id := axiReadId
    bramAxi.r.bits.user := axiReadUser

    // latch the address from AR (read address channel) and determine how many beats for read
    when(bramAxi.ar.fire) {
      axiReadActive := true.B
      axiReadAddr := bramAxi.ar.bits.addr
      axiReadBeatsRemaining := bramAxi.ar.bits.len +& 1.U
      axiReadId := bramAxi.ar.bits.id
      axiReadUser := bramAxi.ar.bits.user
    }
    when(bramAxi.r.fire) {
      axiReadRespValid := false.B
    }

    // determine which SyncReadMem the read should go to based on address offset
    val readRawAccess = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset, accessWindowBytes)
    val readRawIssued = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset, accessWindowBytes)
    val readRawCompleted = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)
    val readLrcReport = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.lrcReportOffset, BigInt(131072) * 64)
    val axiReadIssue = axiReadActive && !axiReadPending && !axiReadRespValid
    val axiReadLastIssue = axiReadBeatsRemaining === 1.U
    lrcReportReadEnable := axiReadIssue && readLrcReport
    lrcReportReadIndex := beatIndex(axiReadAddr, TrafficGenBRAMAddressMap.lrcReportOffset)(16, 0)

    // generate the appropriate index and enable for the XDMA read into issued access and completed bundle IDs
    val axiIssuedFlatIdx = beatIndex(
      axiReadAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset)
    val laneDepthForRead = laneBankDepth.U(axiIssuedFlatIdx.getWidth.W)
    axiRawIssuedReadLane := (axiIssuedFlatIdx / laneDepthForRead)(replayLaneIdxWidth - 1, 0)
    axiRawIssuedReadIdx := (axiIssuedFlatIdx % laneDepthForRead)(laneBankIdxWidth - 1, 0)
    axiRawIssuedReadEn := axiReadIssue && readRawIssued
    when(axiRawIssuedReadEn) {
      axiRawIssuedReadLaneReg := axiRawIssuedReadLane
    }
    axiRawCompletedReadIdx := beatIndex(
                                axiReadAddr,
                                TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset,
                              )(completedBundleBeatIdxWidth - 1, 0)
    axiRawCompletedReadEn := axiReadIssue && readRawCompleted
    val axiAccessReadBits = 0.U(cpuManagedAXI4Params.dataBits.W)
    val axiIssuedReadBits = Mux1H(
      UIntToOH(axiRawIssuedReadLaneReg, replayLanes), issuedAccessReadBits).pad(cpuManagedAXI4Params.dataBits)
    val axiCompletedReadBits = Cat(completedBundleReadBits.reverse)

    // drive the axiReadDataReg from the appropriate source based on the address
    // and track the beat count to know when the read is done
    when(axiReadIssue) {
      axiReadPending := true.B
      axiReadPendingLast := axiReadLastIssue
      axiReadRouteReg := MuxCase(
        axiReadRouteNone,
        Seq(
          readRawAccess -> axiReadRouteAccess,
          readRawIssued -> axiReadRouteIssued,
          readRawCompleted -> axiReadRouteCompleted,
          readLrcReport -> axiReadRouteLrcReport,
        ),
      )
      axiReadAddr := axiReadAddr + bramBeatBytes.U
      axiReadBeatsRemaining := axiReadBeatsRemaining - 1.U
      when(axiReadLastIssue) {
        axiReadActive := false.B
      }
    }

    when(axiReadPending) {
      axiReadDataReg := MuxLookup(
        axiReadRouteReg,
        0.U(cpuManagedAXI4Params.dataBits.W),
        Seq(
          axiReadRouteAccess -> axiAccessReadBits,
          axiReadRouteIssued -> axiIssuedReadBits,
          axiReadRouteCompleted -> axiCompletedReadBits.asUInt,
          axiReadRouteLrcReport -> lrcReportReadBits,
        ),
      )
      axiReadLastReg := axiReadPendingLast
      axiReadRespValid := true.B
      axiReadPending := false.B
    }

    /*
      perform the actual writes into the SyncReadMems
    */
    // Access banks are written from XDMA, host -> bridge module.
    for (lane <- 0 until replayLanes) {
      when(accessStoreWriteEn(lane)) {
        accessStores(lane).write(accessStoreWriteAddr(lane), accessStoreWriteData(lane))
      }
    }
    when(completedBundleIdsWriteEn) {
      completedBundleIds.write(completedBundleIdsWriteAddr, completedBundleIdsWriteData, completedBundleIdsWriteMask)
    }

    // Hold the round start request high until the target DPI acknowledges it.
    val startRoundPulse = Wire(Bool())
    val currentRound = RegInit(0.U(64.W))
    dontTouch(currentRound)
    val startRoundPulsePrev = RegNext(startRoundPulse, false.B)
    val startRoundRisingEdge = startRoundPulse && !startRoundPulsePrev
    startRoundPulse := false.B
    when(startRoundRisingEdge) {
      startRoundPending := true.B
      currentRound := currentRound + 1.U
      when(lrcEnable) { lrcRoundActive := true.B; accessReplayActive := true.B }
    }
    when(tokenEffects && target.roundStarted && !lrcEnable) {
      startRoundPending := false.B
    }
    // A native start is acknowledged by the first fully accounted snapshot or
    // release of the preceding parked snapshot. Its delayed legacy roundStarted
    // signal is intentionally not used to reset native boundary state.
    when(lrcEnable && startRoundPending && (tokenEffects || fire)) {
      startRoundPending := false.B
    }
    target.startRound := startRoundPending && !(lrcOrdinaryParked && !lrcEnable)

    // Expose completion only after the final target batch has crossed HostPort
    // and all lane-bank writes from that batch have committed.
    val roundCompleteLatched = RegInit(false.B)
    val roundCompletePending = RegInit(false.B)
    // A cycle snapshot belongs to a boundary, not to the last memory round:
    // an open session may resume target software and become idle for any time.
    val boundaryCycle = RegInit(0.U(64.W))
    val boundaryCycleValid = RegInit(false.B)
    val roundExitReason = RegInit(TrafficGenRoundExitReason.scheduling)
    // indicates that we exited because one of the lanes had a full bundle table, and we need to do bundle table recovery
    val bundleTableFullLaneMask = RegInit(0.U(replayLanes.W))
    when(resumeTarget) {
      boundaryCycleValid := false.B
    }
    when(fire && target.roundStarted && !lrcEnable) {
      roundCompleteLatched := false.B
      roundCompletePending := false.B
      boundaryCycleValid := false.B
      roundExitReason := TrafficGenRoundExitReason.scheduling
      bundleTableFullLaneMask := 0.U
    }.elsewhen(roundCompletePending && !issuedWriteCommitPending) {
      roundCompleteLatched := true.B
      roundCompletePending := false.B
    }.elsewhen(tokenEffects && target.roundComplete && !lrcEnable) {
      roundCompletePending := true.B
      boundaryCycle := target.currentCycleAfterIssue
      boundaryCycleValid := true.B
      roundExitReason := target.roundExitReason
      bundleTableFullLaneMask := (if (key.useRTL) {
        Mux(target.roundExitReason === TrafficGenRoundExitReason.bundleTableFull,
          target.bundleTableFullLaneMask, 0.U)
      } else { 0.U })
      boundaryPaused := true.B
    }.elsewhen(idleControlBoundary || legacyStartBoundary) {
      boundaryCycle := target.currentCycleAfterIssue
      boundaryCycleValid := true.B
      roundExitReason := TrafficGenRoundExitReason.control
      bundleTableFullLaneMask := 0.U
    }

    val lrcIssuedThisCycle = PopCount(target.physicalIssueValidMask)
    val lrcUnissuedAfter = lrcUnissued - lrcIssuedThisCycle
    val lrcIssueBoundary = lrcUnissuedAfter === 0.U && !accessStoreHasMore &&
      lrcCurrentCycle >= lrcArrivalHorizon
    val lrcPrivateRefill = lrcUnissuedAfter === 0.U && accessStoreHasMore
    val lrcSchedulingFence = accountingWake ||
      (honorDeadline && lrcCurrentCycle >= minIssueCycle) ||
      Mux(lrcDrainOnly, !accountingPending, lrcIssueBoundary && tmaMonitor.io.batchSemantics)
    val lrcAccountingReady = accountingReady && !lrcMetadataBusy &&
      !lrcReportDrainRequired && !accessReadNeedsPause && !accessReadTargetPaused &&
      !boundaryPaused && hPort.toHost.hValid && hPort.fromHost.hReady
    val lrcOrdinaryFence = key.useRTL.B && lrcMonitoring && !lrcEnable &&
      lrcAccountingReady && target.roundComplete && !lrcOrdinaryParked
    val lrcFenceDecision = Module(new LrcNativeFence)
    lrcFenceDecision.io.enabled := lrcEnable
    lrcFenceDecision.io.accounted := lrcAccountingReady
    lrcFenceDecision.io.parked := lrcParkedToken
    lrcFenceDecision.io.checkpoint := lrcCheckpointPending || lrcCheckpointActive
    lrcFenceDecision.io.active := lrcRoundActive
    lrcFenceDecision.io.controlPending := unacknowledgedLaunches.orR || unacknowledgedClose
    lrcFenceDecision.io.scheduling := lrcSchedulingFence
    lrcFenceDecision.io.refill := lrcPrivateRefill
    val lrcFence = lrcFenceDecision.io.fence
    lrcTokenPermit := !lrcMetadataBusy && !lrcReportDrainRequired &&
      (!lrcMonitoring || accountingReady) && (!lrcEnable || !accessReadNeedsPause) &&
      !lrcFence && !lrcOrdinaryFence && !lrcCheckpointPending && !lrcCheckpointActive
    tokenEffects := Mux(lrcEnable, lrcAccountingReady && !lrcParkedToken &&
      !lrcCheckpointPending && !lrcCheckpointActive,
      lrcOrdinaryFence || (fire && !lrcOrdinaryParked))
    when(tokenEffects && lrcEnable && lrcRoundActive) {
      legacyUnaccountedBoundary := false.B
      assert(lrcIssuedThisCycle <= lrcUnissued, "LRC physical issue exceeded uploaded descriptors")
      lrcUnissued := lrcUnissuedAfter
    }
    when(fire && lrcEnable && lrcRoundActive && !tmaMonitor.io.batchSemantics &&
      !lrcDrainOnly && lrcIssueBoundary && !accountingWake && lrcCurrentCycle < minIssueCycle) {
      legacyIssueBoundaryPending := true.B
    }
    when(lrcEnable && legacyIssueBoundaryPending && !boundaryPaused &&
      hPort.toHost.hValid && hPort.fromHost.hReady && !lrcMetadataBusy) {
      assert(!fire, "Legacy TMA issue boundary consumed an unaccounted token")
      legacyUnaccountedBoundary := true.B
      boundaryCycle := lrcCurrentCycle
      boundaryCycleValid := true.B
      boundaryPaused := true.B
      roundCompletePending := true.B
      roundExitReason := TrafficGenRoundExitReason.scheduling
      bundleTableFullLaneMask := 0.U
      lrcBoundaryFlags := Mux(!accountingPending, 8.U,
        Mux(lrcCurrentCycle >= minIssueCycle, 2.U, 4.U))
      lrcRoundActive := false.B
      accessReplayActive := false.B
    }
    when(lrcEnable && (lrcBeginRound || startRoundRisingEdge)) {
      legacyIssueBoundaryPending := false.B
      assert(boundaryPaused, "LRC start requires the previous boundary fence")
      roundCompleteLatched := false.B
      roundCompletePending := false.B
      lrcCheckpointCycle := boundaryCycle
      // A legacy issue boundary parked C+1 before accounting any of it. Feed
      // that complete raw token to both monitors under the new policy, rather
      // than allowing a floor-only checkpoint to wake ahead of C+1 returns.
      lrcCheckpointPending := !legacyUnaccountedBoundary
      lrcCheckpointActive := false.B
    }
    when(lrcCheckpointPending && lrcMonitorCanRun && accountingIdle) {
      // stateDone retains the already-accounted target snapshot; stateIdle is
      // possible when entering native mode at the initial control boundary.
      lrcRecheck := lrcMonitor.io.tokenReady
      lrcCheckpointPending := false.B
      lrcCheckpointActive := true.B
    }
    val lrcCheckpointFence = accountingWake ||
      (honorDeadline && lrcCheckpointCycle >= minIssueCycle) ||
      Mux(lrcDrainOnly, !accountingPending,
        lrcUnissued === 0.U && !accessStoreHasMore && lrcCheckpointCycle >= lrcArrivalHorizon)
    when(lrcCheckpointActive && lrcAccountingReady) {
      lrcCheckpointActive := false.B
      when(lrcCheckpointFence) {
        boundaryCycle := lrcCheckpointCycle
        boundaryCycleValid := true.B
        boundaryPaused := true.B
        roundCompletePending := true.B
        roundExitReason := TrafficGenRoundExitReason.scheduling
        bundleTableFullLaneMask := 0.U
        lrcBoundaryFlags := Cat(0.U(25.W), tmaMonitor.io.transferWake, tmaMonitor.io.phaseWake, false.B, lrcDrainOnly && !accountingPending,
          !lrcDrainOnly && lrcUnissued === 0.U && !accessStoreHasMore && lrcCheckpointCycle >= lrcArrivalHorizon,
          honorDeadline && lrcCheckpointCycle >= minIssueCycle, lrcMonitor.io.wake)
        lrcRoundActive := false.B
      }.elsewhen(!lrcParkedToken) {
        // The ordinary boundary's raw C+1 snapshot has not been accounted.
        // Start it on the next host edge, under the newly committed metadata.
        lrcRecheck := true.B
        lrcMonitor.io.cycle := lrcCurrentCycle
        lrcMonitor.io.returns := target.physicalCompletionValidMask
      }
    }
    when(lrcOrdinaryFence) {
      // Publish ordinary cycle C, leaving raw C+1 held for either the legacy
      // resume delay or native completion accounting with the next metadata.
      lrcOrdinaryParked := true.B
    }
    when(fire && lrcOrdinaryParked) {
      lrcOrdinaryParked := false.B
    }
    when(lrcFence) {
      // Account C once, but keep its token parked. On resume the new epoch's
      // fromHost inputs accompany consumption of C, so the latency-one wire
      // channel cannot produce an old-epoch C+1 issue after a hard wake.
      boundaryCycle := lrcCurrentCycle
      boundaryCycleValid := true.B
      boundaryPaused := true.B
      roundCompletePending := true.B
      roundExitReason := Mux(lrcFenceDecision.io.control, TrafficGenRoundExitReason.control,
        Mux(lrcSchedulingFence, TrafficGenRoundExitReason.scheduling, TrafficGenRoundExitReason.capacity))
      bundleTableFullLaneMask := 0.U
      lrcBoundaryFlags := Cat(0.U(25.W), tmaMonitor.io.transferWake, tmaMonitor.io.phaseWake, lrcPrivateRefill,
        lrcDrainOnly && !accountingPending,
        !lrcDrainOnly && lrcIssueBoundary,
        honorDeadline && lrcCurrentCycle >= minIssueCycle,
        lrcMonitor.io.wake)
      lrcRoundActive := false.B
      accessReplayActive := false.B
      lrcParkedToken := true.B
      when(lrcFenceDecision.io.idleControl) {
        startTrafficGenLatched := true.B
        trafficGenDone := false.B
      }
    }
    when(fire && lrcParkedToken) { lrcParkedToken := false.B }

    val boundaryPausedPrev = RegNext(boundaryPaused, false.B)
    val boundaryCycleValidPrev = RegNext(boundaryCycleValid, false.B)
    val boundaryCyclePrev = RegNext(boundaryCycle, 0.U)
    val roundExitReasonPrev = RegNext(roundExitReason, TrafficGenRoundExitReason.scheduling)
    val bundleTableFullLaneMaskPrev = RegNext(bundleTableFullLaneMask, 0.U)
    when(boundaryPaused) {
      assert(!fire, "TrafficGenBridge target token fired while boundary-paused")
    }
    when(boundaryPaused && boundaryPausedPrev &&
         boundaryCycleValid && boundaryCycleValidPrev && !resumeTarget) {
      assert(boundaryCycle === boundaryCyclePrev,
        "TrafficGenBridge cycle snapshot changed while boundary-paused")
      assert(roundExitReason === roundExitReasonPrev &&
        bundleTableFullLaneMask === bundleTableFullLaneMaskPrev,
        "TrafficGenBridge recovery metadata changed while boundary-paused")
    }



    val targetReset = fire && hPort.hBits.reset
    lrcReset := targetReset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
      lrcEnable := false.B
      legacyIssueBoundaryPending := false.B
      legacyUnaccountedBoundary := false.B
      lrcMonitoring := false.B
      lrcRoundActive := false.B
      lrcParkedToken := false.B
      lrcOrdinaryParked := false.B
      lrcCheckpointPending := false.B
      lrcCheckpointActive := false.B
      lrcOrdinaryReturnMask := 0.U
      lrcMetadataBusy := false.B
      lrcMetaReadPending := false.B
      lrcMetaValid := false.B
      lrcReportCount := 0.U
      lrcServiceHighWater := 0.U
      lrcBundleHighWater := 0.U
      lrcTimerHighWater := 0.U
      lrcReportHighWater := 0.U
      lrcReplayEpoch := 0.U
      lrcUnissued := 0.U
      lrcBoundaryFlags := 0.U
      accessReadReset := true.B
      accessReadTargetPaused := false.B
      for (flat <- 0 until replaySlots) {
        accessReadCursors(flat) := (flat % replaySlotsPerLane).U
      }
      accessReadFetchPending.foreach(_ := false.B)
      accessReadFetchPendingSlot.foreach(_ := 0.U)
      accessReadFetchRoundRobin.foreach(_ := 0.U)
      accessReadExpectedConsumeSlot.foreach(_ := 0.U)
      accessReadRespGenerations.foreach(_ := 0.U)
      issuedAccessWritebackCount := 0.U
      issuedAccessWritebackLaneCounts.foreach(_ := 0.U)
      issuedBatchExpectedId := 0.U
      accessStoreCount := 0.U
      accessStoreMaxCycle := 0.U
      accessStoreHasEntries := false.B
      accessStoreHasMore := false.B
      accessReadPrefillPending := false.B
      accessReplayActive := false.B
      uploadReady := false.B
      uploadMaxCycleLow := 0.U
      uploadMaxCycleHigh := 0.U
      uploadHasMore := false.B
      completedBundleCount := 0.U
      startRoundPending := false.B
      startTrafficGenLatched := false.B
      roundCompleteLatched := false.B
      roundCompletePending := false.B
      boundaryCycle := 0.U
      boundaryCycleValid := false.B
      roundExitReason := TrafficGenRoundExitReason.scheduling
      bundleTableFullLaneMask := 0.U
      boundaryPaused := false.B
      trafficGenDone := false.B
      currentRound := 0.U
      launchRegistryIds.foreach(_ := 0.U)
      launchIds.foreach(_ := 0.U)
      launchPendingMask := 0.U
      closeSubmissions := false.B
      stagedLaunchStatusIdLow.foreach(_ := 0.U)
      stagedLaunchStatusIdHigh.foreach(_ := 0.U)
      stagedLaunchStatuses.foreach(_ := 0.U)
      stagedSessionStatus := 0.U
      launchStatusIds.foreach(_ := 0.U)
      launchStatuses.foreach(_ := 0.U)
      sessionStatus := 0.U
    }

    when(trafficGenDonePulse) {
      accessReadReset := true.B
      accessReadTargetPaused := false.B
      accessReplayActive := false.B
      accessReadPrefillPending := false.B
      uploadReady := false.B
      for (flat <- 0 until replaySlots) {
        accessReadCursors(flat) := (flat % replaySlotsPerLane).U
      }
      accessReadFetchPending.foreach(_ := false.B)
      accessReadFetchPendingSlot.foreach(_ := 0.U)
      accessReadFetchRoundRobin.foreach(_ := 0.U)
      accessReadExpectedConsumeSlot.foreach(_ := 0.U)
      accessReadRespGenerations.foreach(_ := 0.U)
    }

    /////////// MMIO registers for bridge driver interaction ///////////

    // for target program to trigger traffic generation
    genROReg(startTrafficGenLatched, "start_trafficgen")

    // for bridge driver to read to determine if target is still running the previous traffic pattern
    genROReg(target.targetBusy, "target_busy")
    genROReg(Mux(lrcEnable, accountingPending || lrcUnissued =/= 0.U ||
      (boundaryCycle < lrcArrivalHorizon), target.hasPendingWork), "has_pending_work")
    Pulsify(genWORegInit(trafficGenDonePulse, "trafficgen_done", false.B), pulseLength = 1)

    // Boundary pauses are entered synchronously from target tokens and exited
    // only by this idempotent host command.
    Pulsify(genWORegInit(resumeTarget, "resume_target", false.B), pulseLength = 1)
    genROReg(boundaryPaused, "target_paused")

    // bridge driver pulses this when a freshly uploaded scheduling round is ready to issue
    Pulsify(genWORegInit(startRoundPulse, "start_round", false.B), pulseLength = 1)
    genROReg(currentRound(31, 0), "current_round_low")
    genROReg(currentRound(63, 32), "current_round_high")

    // bridge driver commits the uploaded L2 accesses
    genWORegInit(uploadCount, "upload_count", 0.U)
    val uploadLaneCountIndex = Wire(UInt(replayLaneIdxWidth.W))
    val uploadLaneCountValue = Wire(UInt(32.W))
    val uploadLaneCountWrite = Wire(Bool())
    genWORegInit(uploadLaneCountIndex, "upload_lane_count_index", 0.U)
    genWORegInit(uploadLaneCountValue, "upload_lane_count_value", 0.U)
    Pulsify(genWORegInit(uploadLaneCountWrite, "upload_lane_count_write", false.B), pulseLength = 1)
    when(uploadLaneCountWrite) {
      assert(uploadLaneCountIndex < replayLanes.U,
        "TrafficGenBridge upload lane-count index out of range")
      when(uploadLaneCountIndex < replayLanes.U) {
        uploadLaneCounts(uploadLaneCountIndex) := uploadLaneCountValue
      }
    }
    genWORegInit(uploadMaxCycleLow, "access_store_max_cycle_low", 0.U)
    genWORegInit(uploadMaxCycleHigh, "access_store_max_cycle_high", 0.U)
    genWORegInit(uploadHasMore, "access_store_has_more", false.B)
    Pulsify(genWORegInit(commitUpload, "commit_upload", false.B), pulseLength = 1)

    genROReg(roundCompleteLatched, "round_complete")
    genROReg(uploadReady, "upload_ready")

    // bridge driver writes the min_issue_cycle for the traffic generator to stop at
    genWORegInit(minIssueCycleLow, "min_issue_cycle_low", 0.U)
    genWORegInit(minIssueCycleHigh, "min_issue_cycle_high", 0.U)

    val reportedCycleAfterIssue = Mux(
      boundaryCycleValid, boundaryCycle, Mux(lrcEnable, lrcCurrentCycle, target.currentCycleAfterIssue))
    genROReg(reportedCycleAfterIssue(31, 0), "current_cycle_after_issue_low")
    genROReg(reportedCycleAfterIssue(63, 32), "current_cycle_after_issue_high")
    genROReg(roundExitReason, "round_exit_reason")
    genROReg(target.dpiState, "dpi_state")
    genROReg(issuedAccessWritebackCount, "issued_access_writeback_count")
    val issuedLaneCountIndex = Wire(UInt(replayLaneIdxWidth.W))
    genWORegInit(issuedLaneCountIndex, "issued_lane_count_index", 0.U)
    val issuedLaneCountValue = Mux(
      issuedLaneCountIndex < replayLanes.U,
      issuedAccessWritebackLaneCounts(issuedLaneCountIndex),
      0.U)
    genROReg(issuedLaneCountValue, "issued_lane_count_value")


    genROReg(completedBundleCount, "completed_bundle_count")
    // Append the new register so existing MMIO offsets retain their positions.
    require(replayLanes <= 32, "TrafficGen recovery lane mask must fit one MMIO word")
    genROReg(bundleTableFullLaneMask, "bundle_table_full_lane_mask")

    // Append protocol-v2 registers so the existing replay MMIO offsets remain
    // stable. Slot zero occupies the least significant slice of target IO.
    val launchSlotIndex = Wire(UInt(log2Ceil(launchSlots).W))
    genWORegInit(launchSlotIndex, "launch_slot_index", 0.U)
    genROReg(launchRegistryIds(launchSlotIndex)(31, 0), "launch_registry_id_low")
    genROReg(launchRegistryIds(launchSlotIndex)(63, 32), "launch_registry_id_high")
    genROReg(launchIds(launchSlotIndex)(31, 0), "launch_id_low")
    genROReg(launchIds(launchSlotIndex)(63, 32), "launch_id_high")
    genROReg(launchPendingMask, "launch_pending_mask")
    genROReg(closeSubmissions, "close_submissions")

    // Use write handshakes rather than level-sensitive register mirrors: an
    // index change alone must never write the preceding slot's staged value.
    def launchStatusWrite(name: String)(update: UInt => Unit): Unit = {
      val write = Wire(Decoupled(UInt(32.W)))
      write.ready := true.B
      attachDecoupledSink(write, name)
      when(write.fire && !targetReset) { update(write.bits) }
    }
    launchStatusWrite("slot_status_id_low") { value =>
      stagedLaunchStatusIdLow(launchSlotIndex) := value
    }
    launchStatusWrite("slot_status_id_high") { value =>
      stagedLaunchStatusIdHigh(launchSlotIndex) := value
    }
    launchStatusWrite("slot_status") { value =>
      stagedLaunchStatuses(launchSlotIndex) := value(7, 0)
    }
    launchStatusWrite("session_status") { value =>
      stagedSessionStatus := value
    }
    Pulsify(genWORegInit(commitLaunchStatus, "commit_launch_status", false.B), pulseLength = 1)

    // Native v6 descriptor/report ABI. Keep this append-only order synchronized
    // with trafficgen.h. Staging records and reports are documented in
    // LrcCompletionMonitor; their windows use complete 512-bit XDMA beats.
    genWORegInit(lrcEnable, "lrc_enable", false.B)
    genWORegInit(lrcMetadataCount, "lrc_metadata_count", 0.U)
    Pulsify(genWORegInit(lrcCommitMetadata, "lrc_commit_metadata", false.B), pulseLength = 1)
    genROReg(lrcMetadataReady, "lrc_metadata_ready")
    Pulsify(genWORegInit(lrcBeginRound, "lrc_begin_round", false.B), pulseLength = 1)
    genWORegInit(lrcDrainOnly, "lrc_drain_only", false.B)
    genWORegInit(lrcArrivalLow, "lrc_arrival_horizon_low", 0.U)
    genWORegInit(lrcArrivalHigh, "lrc_arrival_horizon_high", 0.U)
    genROReg(lrcReportCount, "lrc_report_count")
    Pulsify(genWORegInit(lrcClearReports, "lrc_clear_reports", false.B), pulseLength = 1)
    genROReg(lrcMonitor.io.error | (tmaMonitor.io.error << 16)(31,0), "lrc_error")
    genROReg(65536.U(32.W), "lrc_service_capacity")
    genROReg(65536.U(32.W), "lrc_bundle_capacity")
    genROReg(65536.U(32.W), "lrc_timer_capacity")
    genWORegInit(lrcRefillOnly, "lrc_refill_only", false.B)
    genROReg(lrcReportDrainRequired, "lrc_report_drain_required")
    genROReg(lrcMonitor.io.serviceCount.pad(32), "lrc_service_count")
    genROReg(lrcMonitor.io.bundleCount.pad(32), "lrc_bundle_count")
    genROReg(lrcMonitor.io.timerCount.pad(32), "lrc_timer_count")
    genROReg(lrcServiceHighWater, "lrc_service_high_water")
    genROReg(lrcBundleHighWater, "lrc_bundle_high_water")
    genROReg(lrcTimerHighWater, "lrc_timer_high_water")
    genROReg(lrcReportHighWater, "lrc_report_high_water")
    genROReg(lrcBoundaryFlags, "lrc_boundary_flags")
    val tmaGroupHighWater = RegInit(0.U(32.W))
    val tmaEdgeHighWater = RegInit(0.U(32.W))
    val tmaTimerHighWater = RegInit(0.U(32.W))
    tmaGroupHighWater := Mux(tmaMonitor.io.groupCount > tmaGroupHighWater, tmaMonitor.io.groupCount, tmaGroupHighWater)
    tmaEdgeHighWater := Mux(tmaMonitor.io.edgeCount > tmaEdgeHighWater, tmaMonitor.io.edgeCount, tmaEdgeHighWater)
    tmaTimerHighWater := Mux(tmaMonitor.io.timerCount > tmaTimerHighWater, tmaMonitor.io.timerCount, tmaTimerHighWater)
    when(targetReset) { tmaGroupHighWater:=0.U; tmaEdgeHighWater:=0.U; tmaTimerHighWater:=0.U }
    genROReg(tmaMonitor.io.groupCount.pad(32), "tma_group_count")
    genROReg(tmaMonitor.io.edgeCount.pad(32), "tma_edge_count")
    genROReg(tmaMonitor.io.timerCount.pad(32), "tma_timer_count")
    genROReg(tmaGroupHighWater, "tma_group_high_water")
    genROReg(tmaEdgeHighWater, "tma_edge_high_water")
    genROReg(tmaTimerHighWater, "tma_timer_high_water")




    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "trafficgen_t",
        "trafficgen",
        Seq(
          UInt64(bramBase),
          UInt64(TrafficGenBRAMAddressMap.rawAccessStoreOffset),
          UInt64(TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset),
          UInt64(TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset),
          UInt64(accessWindowBytes),
          UInt64(completedWindowBytes),
          UInt64(if (key.useRTL) 1 else 0),
          UInt64(key.numLanes),
        ),
      )
    }
  }
}
