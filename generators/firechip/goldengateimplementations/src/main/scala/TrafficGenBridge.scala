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
    val hPort = IO(HostPort(new TrafficGenBridgeTargetIO(key.useRTL)))
    val target = hPort.hBits.trafficgen

    /*
     * Bridge-driver control and target clock gating.
     */
    val pauseTarget = RegInit(false.B)
    val targetPaused = RegInit(false.B)
    val accessReadTargetPaused = RegInit(false.B)
    val trafficGenDone = RegInit(false.B)
    val trafficGenDonePulse = Wire(Bool())
    trafficGenDonePulse := false.B
    when(pauseTarget.asBool) {
      targetPaused := ~targetPaused
    }

    // Latch the target's start doorbell until the host acknowledges it by
    // pausing the target clock. This keeps the MMIO-visible start bit high long
    // enough for the bridge driver to observe it even though the target write is
    // only a one-cycle pulse.
    val startTrafficGenLatched = RegInit(false.B)
    when(target.startTrafficGen) {
      startTrafficGenLatched := true.B
      trafficGenDone := false.B
    }
    when(pauseTarget.asBool) {
      startTrafficGenLatched := false.B
    }
    when(trafficGenDonePulse) {
      trafficGenDone := true.B
    }
    target.trafficGenDone := trafficGenDone

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !targetPaused &&
      !accessReadTargetPaused

    // completedBundleIds will be streamed back from traffic generator to bridge driver.
    // Each 512-bit beat carries eight 64-bit bundle IDs.
    val completedBundleIdBeats = CompletedBundleIds.beats
    val completedBundleBeatIdxWidth = log2Ceil(completedBundleIdBeats)

    private val replayLanes = TrafficGenAccessBatch.lanes
    require(key.maxL2AccessEntries % replayLanes == 0,
      "TrafficGen banked replay requires maxL2AccessEntries to divide evenly across lanes")
    private val laneBankDepth = key.maxL2AccessEntries / replayLanes
    private val laneBankIdxWidth = log2Ceil(laneBankDepth max 2)
    private val laneBankCountWidth = log2Ceil(laneBankDepth + 1)

    /*
      Target-issued accesses are buffered into issuedAccessWritebackStore for logging in the GPU model
    */

    // Each target issue lane owns one writeback bank.  A masked issued batch
    // can therefore commit every firing lane in one bridge cycle.
    val issuedAccessWritebackStores = Seq.fill(replayLanes) {
      val mem = SyncReadMem(laneBankDepth, UInt(issuedStreamWidth.W))
      RAMStyleHint(mem, RAMStyles.BLOCK)
      mem
    }
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackLaneCounts = RegInit(VecInit(Seq.fill(replayLanes)(0.U(32.W))))
    val issuedBatchExpectedId = RegInit(0.U(32.W))
    val issuedBatchPackedLanes =
      target.issuedAccessBatch.bits.accesses.asTypeOf(Vec(replayLanes, UInt(issuedStreamWidth.W)))
    val issuedBatchIncomingCount = PopCount(target.issuedAccessBatch.bits.validMask)
    val issuedBatchFits = VecInit((0 until replayLanes).map { lane =>
      !target.issuedAccessBatch.bits.validMask(lane) ||
        issuedAccessWritebackLaneCounts(lane) < laneBankDepth.U
    }).asUInt.andR

    target.issuedAccessBatch.ready := fire && issuedBatchFits
    val issuedBatchCapture = target.issuedAccessBatch.valid && target.issuedAccessBatch.ready
    when(fire && target.issuedAccessBatch.valid) {
      assert(issuedBatchFits,
        "TrafficGenBridge issued-access batch exceeded the writeback store capacity")
    }
    when(issuedBatchCapture) {
      assert(target.issuedAccessBatch.bits.validMask.orR,
        "TrafficGenBridge captured an empty issued-access batch")
      assert(target.issuedAccessBatch.bits.batchId === issuedBatchExpectedId,
        "TrafficGenBridge captured an out-of-order issued-access batch")
      issuedBatchExpectedId := issuedBatchExpectedId + 1.U
      issuedAccessWritebackCount := issuedAccessWritebackCount + issuedBatchIncomingCount
      for (lane <- 0 until replayLanes) {
        when(target.issuedAccessBatch.bits.validMask(lane)) {
          issuedAccessWritebackStores(lane).write(
            issuedAccessWritebackLaneCounts(lane)(laneBankIdxWidth - 1, 0),
            issuedBatchPackedLanes(lane))
          issuedAccessWritebackLaneCounts(lane) := issuedAccessWritebackLaneCounts(lane) + 1.U
        }
      }
    }

    // SyncReadMem writes commit on the bridge edge after capture.  Keep an
    // explicit one-cycle fence for round-completion visibility.
    val issuedWriteCommitPending = RegNext(issuedBatchCapture, false.B)

    val axiRawIssuedReadLane = WireDefault(0.U(log2Ceil(replayLanes).W))
    val axiRawIssuedReadIdx = WireDefault(0.U(laneBankIdxWidth.W))
    val axiRawIssuedReadEn = WireDefault(false.B)
    val axiRawIssuedReadLaneReg = RegInit(0.U(log2Ceil(replayLanes).W))
    val issuedAccessReadBits = VecInit((0 until replayLanes).map { lane =>
      issuedAccessWritebackStores(lane).read(
        axiRawIssuedReadIdx,
        axiRawIssuedReadEn && axiRawIssuedReadLane === lane.U)
    })

    /*
      * completed bundle IDs are written by the target during/after traffic generator, and used for scheduling next batch of accesses
    */

    // Store completed bundle IDs completed by target, to be streamed back to the bridge driver.
    val completedBundleIds = SyncReadMem(completedBundleIdBeats, Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
    // This Vec memory lowers to one physical memory per lane. A single RAMStyleHint
    // on the aggregate fans out during FIRRTL renaming, which Golden Gate rejects.
    val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))
    val completedBundleIdsWriteEn = WireDefault(false.B)
    val completedBundleIdsWriteAddr = WireDefault(0.U(completedBundleBeatIdxWidth.W))
    val completedBundleIdsWriteData = Wire(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
    completedBundleIdsWriteData.foreach(_ := 0.U)
    val completedBundleIdsWriteMask = Wire(Vec(CompletedBundleIds.idsPerBeat, Bool()))
    completedBundleIdsWriteMask.foreach(_ := false.B)

    // target writes the completedBundleCount to help bridge driver read
    when(fire && target.completedBundleCountWriteEn) {
      completedBundleCount := target.completedBundleCountWriteData
    }
    // write index and data to write into completedBundleIds
    when(fire && target.completedBundleIdWriteEn) {
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
     * Raw XDMA-uploaded L2 accesses from the bridge driver.
     */

    // Store uploaded accesses lane-major.  Every bank has its own read port and
    // a two-entry target-facing prefetch FIFO.
    val accessStores = Seq.fill(replayLanes) {
      val mem = SyncReadMem(laneBankDepth, UInt(accessStreamWidth.W))
      RAMStyleHint(mem, RAMStyles.BLOCK)
      mem
    }
    val accessStoreCount = RegInit(0.U(32.W))
    val accessStoreMaxCycle = RegInit(0.U(64.W))
    val accessStoreHasEntries = RegInit(false.B)
    val accessStoreHasMore = RegInit(false.B)
    val uploadLaneCounts = RegInit(VecInit(Seq.fill(replayLanes)(0.U(32.W))))

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
    val accessReadPrefillPending = RegInit(false.B)
    val accessReplayActive = RegInit(false.B)

    // reset the target-side access-read response state when a fresh upload begins
    val accessReadReset = WireDefault(false.B)

    val accessReadQueues = Seq.fill(replayLanes) {
      withReset(reset.asBool || accessReadReset) {
        Module(new Queue(UInt(accessStreamWidth.W), 2, pipe = true, flow = false))
      }
    }
    val accessReadCursors = RegInit(VecInit(Seq.fill(replayLanes)(0.U(laneBankCountWidth.W))))
    val accessReadFetchPending = RegInit(VecInit(Seq.fill(replayLanes)(false.B)))
    val accessReadBankReadEn = Wire(Vec(replayLanes, Bool()))
    val accessReadBankReadAddr = Wire(Vec(replayLanes, UInt(laneBankIdxWidth.W)))
    val accessReadBankReadBits = Wire(Vec(replayLanes, UInt(accessStreamWidth.W)))
    val accessReadConsume = Wire(Vec(replayLanes, Bool()))

    for (lane <- 0 until replayLanes) {
      val queue = accessReadQueues(lane)
      val canFetch = accessReadCursors(lane) < uploadLaneCounts(lane) &&
        queue.io.enq.ready && !accessReadReset &&
        (accessReadPrefillPending || accessReplayActive)
      accessReadBankReadEn(lane) := canFetch
      accessReadBankReadAddr(lane) := accessReadCursors(lane)(laneBankIdxWidth - 1, 0)
      accessReadBankReadBits(lane) := accessStores(lane).read(
        accessReadBankReadAddr(lane), accessReadBankReadEn(lane))

      queue.io.enq.valid := accessReadFetchPending(lane)
      queue.io.enq.bits := accessReadBankReadBits(lane)
      when(canFetch) {
        accessReadCursors(lane) := accessReadCursors(lane) + 1.U
      }
      // SyncReadMem is a one-cycle pipeline, not a request/response transaction.
      // Accept the previous response and launch the next bank read on the same
      // bridge edge whenever the FIFO can take it.  Serializing those actions
      // created a refill bubble after two consecutive target consumes, so the
      // target could observe and re-present a consume mask after the FIFO had
      // already become empty.
      accessReadFetchPending(lane) :=
        (accessReadFetchPending(lane) && !queue.io.enq.fire) || canFetch

      accessReadConsume(lane) := fire && target.accessReadConsumeMask(lane)
      queue.io.deq.ready := accessReadConsume(lane)
      when(accessReadConsume(lane)) {
        assert(queue.io.deq.valid,
          "TrafficGenBridge consumed an invalid prefetched lane head")
        assert(queue.io.deq.bits(191, 128) <= target.accessReadCycle,
          "TrafficGenBridge consumed a lane head before its scheduled cycle")
      }
    }

    val accessReadLaneDone = VecInit((0 until replayLanes).map { lane =>
      accessReadCursors(lane) >= uploadLaneCounts(lane) &&
        !accessReadFetchPending(lane) && !accessReadQueues(lane).io.deq.valid
    })
    val accessReadHeadMissing = VecInit((0 until replayLanes).map { lane =>
      !accessReadLaneDone(lane) && !accessReadQueues(lane).io.deq.valid
    })
    val accessReadNeedsPause =
      (accessReadPrefillPending || accessReplayActive) && accessReadHeadMissing.asUInt.orR
    val issuedBatchSafeToPause = !target.issuedAccessBatch.valid || issuedBatchCapture

    target.accessReadPrefetchPauseReq := accessReadNeedsPause
    target.accessReadLaneDoneMask := accessReadLaneDone.asUInt
    target.accessReadData := VecInit(accessReadQueues.map(_.io.deq.bits))
    target.accessReadDataValid := VecInit(accessReadQueues.map(_.io.deq.valid))
    target.accessReadRespValid := target.accessReadDataValid.asUInt.orR || accessReadLaneDone.asUInt.andR
    val accessReadRespId = RegInit(0.U(32.W))
    when(accessReadConsume.asUInt.orR) {
      accessReadRespId := accessReadRespId + 1.U
    }
    target.accessReadRespId := accessReadRespId
    target.accessReadBucketDone := accessReadLaneDone.asUInt.andR
    target.accessReadReady := true.B

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

    val accessReadPrefillComplete = VecInit((0 until replayLanes).map { lane =>
      uploadLaneCounts(lane) === 0.U ||
        accessReadQueues(lane).io.count === 2.U ||
        (accessReadCursors(lane) >= uploadLaneCounts(lane) &&
          !accessReadFetchPending(lane) && accessReadQueues(lane).io.deq.valid)
    }).asUInt.andR

    // Once driver indicates the uploads to XDMA are ready,
    // bridge module can indicate to target that data is ready
    when(commitUpload) {
      assert(!target.issuedAccessBatch.valid && !issuedWriteCommitPending,
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
      accessReadCursors.foreach(_ := 0.U)
      accessReadFetchPending.foreach(_ := false.B)
      accessReadRespId := 0.U
    }

    when(accessReadPrefillPending && accessReadPrefillComplete && !accessReadReset) {
      uploadReady := true.B
      accessReadPrefillPending := false.B
      accessReadTargetPaused := false.B
    }

    target.uploadReady := uploadReady

    when(target.roundStarted) {
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
    target.minIssueCycle := minIssueCycle
    target.accessStoreCount := accessStoreCount
    target.accessStoreMaxCycle := accessStoreMaxCycle
    target.accessStoreHasEntries := accessStoreHasEntries
    target.accessStoreHasMore := accessStoreHasMore

    /*
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

    bramAxi.w.ready := axiWriteActive && !axiWriteRespValid

    // each W beat writes directly into the SyncReadMem
    val axiWriteLastBeat = axiWriteBeatsRemaining === 1.U
    when(bramAxi.w.fire) {
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
      Nil
    ) = Enum(4)

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
    val axiReadIssue = axiReadActive && !axiReadPending && !axiReadRespValid
    val axiReadLastIssue = axiReadBeatsRemaining === 1.U

    // generate the appropriate index and enable for the XDMA read into issued access and completed bundle IDs
    val axiIssuedFlatIdx = beatIndex(
      axiReadAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset)
    val laneDepthForRead = laneBankDepth.U(axiIssuedFlatIdx.getWidth.W)
    axiRawIssuedReadLane := (axiIssuedFlatIdx / laneDepthForRead)(log2Ceil(replayLanes) - 1, 0)
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
    val startRoundPending = RegInit(false.B)
    val currentRound = RegInit(0.U(64.W))
    dontTouch(currentRound)
    val startRoundPulsePrev = RegNext(startRoundPulse, false.B)
    val startRoundRisingEdge = startRoundPulse && !startRoundPulsePrev
    startRoundPulse := false.B
    when(startRoundRisingEdge) {
      startRoundPending := true.B
      currentRound := currentRound + 1.U
    }
    when(target.roundStarted) {
      startRoundPending := false.B
    }
    target.startRound := startRoundPending

    // Expose completion only after the final target batch has crossed HostPort
    // and all lane-bank writes from that batch have committed.
    val roundCompleteLatched = RegInit(false.B)
    val roundCompletePending = RegInit(false.B)
    when(target.roundStarted) {
      roundCompleteLatched := false.B
      roundCompletePending := false.B
    }.elsewhen(roundCompletePending &&
               !target.issuedAccessBatch.valid &&
               !issuedWriteCommitPending) {
      roundCompleteLatched := true.B
      roundCompletePending := false.B
    }.elsewhen(fire && target.roundComplete) {
      roundCompletePending := true.B
    }



    val targetReset = fire && hPort.hBits.reset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
      accessReadReset := true.B
      accessReadTargetPaused := false.B
      accessReadCursors.foreach(_ := 0.U)
      accessReadFetchPending.foreach(_ := false.B)
      accessReadRespId := 0.U
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
      trafficGenDone := false.B
      currentRound := 0.U
    }

    /////////// MMIO registers for bridge driver interaction ///////////

    // for target program to trigger traffic generation
    genROReg(startTrafficGenLatched, "start_trafficgen")

    // for bridge driver to read to determine if target is still running the previous traffic pattern
    genROReg(target.targetBusy, "target_busy")
    genROReg(target.hasPendingWork, "has_pending_work")
    Pulsify(genWORegInit(trafficGenDonePulse, "trafficgen_done", false.B), pulseLength = 1)

    // bridge driver toggles to pause/resume target while generating traffic patterns
    Pulsify(genWORegInit(pauseTarget, "pause_target", false.B), pulseLength = 1)

    // bridge driver pulses this when a freshly uploaded scheduling round is ready to issue
    Pulsify(genWORegInit(startRoundPulse, "start_round", false.B), pulseLength = 1)
    genROReg(currentRound(31, 0), "current_round_low")
    genROReg(currentRound(63, 32), "current_round_high")

    // bridge driver commits the uploaded L2 accesses
    genWORegInit(uploadCount, "upload_count", 0.U)
    for (lane <- 0 until replayLanes) {
      genWORegInit(uploadLaneCounts(lane), s"upload_lane_count_$lane", 0.U)
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

    genROReg(target.currentCycleAfterIssue(31, 0), "current_cycle_after_issue_low")
    genROReg(target.currentCycleAfterIssue(63, 32), "current_cycle_after_issue_high")
    genROReg(target.roundExitReason, "round_exit_reason")
    genROReg(target.dpiState, "dpi_state")
    genROReg(issuedAccessWritebackCount, "issued_access_writeback_count")
    for (lane <- 0 until replayLanes) {
      genROReg(issuedAccessWritebackLaneCounts(lane), s"issued_lane_count_$lane")
    }


    genROReg(completedBundleCount, "completed_bundle_count")




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
        ),
      )
    }
  }
}
