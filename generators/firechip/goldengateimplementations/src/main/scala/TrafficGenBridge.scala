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
  val rawBlockedWarpBitmapOffset: BigInt        = BigInt("04080000", 16)
  val rawCompletedBundleIdsOffset: BigInt       = BigInt("04090000", 16)
}

class TrafficGenBridgeModule(key: TrafficGenBridgeKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TrafficGenBridgeTargetIO]]()(p)
    with UsesCPUManagedBRAM {

  private val cpuManagedAXI4Params = p(CPUManagedAXI4Key).get
  private val bramBeatBytes        = cpuManagedAXI4Params.dataBits / 8
  require(cpuManagedAXI4Params.dataBits == L2Access.streamWidthBits, "TrafficGen direct BRAM path expects 512-bit CPU-managed XDMA")

  // create bramSlaveNode that is connected in FPGATop to a xbar with regular streaming engines
  // which connect to FPGA XDMA
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
    val hPort = IO(HostPort(new TrafficGenBridgeTargetIO))
    val target = hPort.hBits.trafficgen

    /*
     * Bridge-driver control and target clock gating.
     */
    val pauseTarget = RegInit(false.B)
    val targetPaused = RegInit(false.B)
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

    val accessReadBatchAssemblyPause = WireDefault(false.B)
    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !targetPaused &&
      !accessReadBatchAssemblyPause

    // completedBundleIds will be streamed back from traffic generator to bridge driver.
    // Each 512-bit beat carries eight 64-bit bundle IDs.
    val completedBundleIdBeats = CompletedBundleIds.beats
    val completedBundleBeatIdxWidth = log2Ceil(completedBundleIdBeats)

    val accessIdxWidth = log2Ceil(key.maxL2AccessEntries + 1)


    /*
      Target-issued accesses are buffered into issuedAccessWritebackStore for logging in the GPU model
    */

    // SyncReadMem to hold target-issued L2 accesses that are written back by target after issue.
    // Store the packed stream representation so synthesis sees one 512-bit memory
    // instead of one large memory per L2Access bundle field.
    val issuedAccessWritebackStore = SyncReadMem(key.maxL2AccessEntries, UInt(L2Access.streamWidthBits.W))
    RAMStyleHint(issuedAccessWritebackStore, RAMStyles.BLOCK)
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackIdx = RegInit(0.U(32.W))
    val issuedAccessWritebackStoreWriteEn = WireDefault(false.B)
    val issuedAccessWritebackStoreWriteAddr = WireDefault(0.U(accessIdxWidth.W))
    val issuedAccessWritebackStoreWriteData = WireDefault(0.U(L2Access.streamWidthBits.W))

    // Target -> issuedAccess store
    // write each subsequent issued access from target into issuedAccessWritebackStore
    target.issuedAccessWriteback.ready := fire &&
                                          issuedAccessWritebackIdx < key.maxL2AccessEntries.U
    val doIssuedAccessWriteback = target.issuedAccessWriteback.valid && target.issuedAccessWriteback.ready
    when(doIssuedAccessWriteback) {
      issuedAccessWritebackStoreWriteEn := true.B
      issuedAccessWritebackStoreWriteAddr := issuedAccessWritebackIdx(accessIdxWidth - 1, 0)
      issuedAccessWritebackStoreWriteData := L2Access.pack(target.issuedAccessWriteback.bits)
      issuedAccessWritebackIdx := issuedAccessWritebackIdx + 1.U
      issuedAccessWritebackCount := issuedAccessWritebackCount + 1.U
    }

    // AXI enable and data signals to read from issuedAccessWritebackStore and send over XDMA
    val axiRawIssuedReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val axiRawIssuedReadEn = WireDefault(false.B)
    val issuedAccessReadBits = issuedAccessWritebackStore.read(axiRawIssuedReadIdx, axiRawIssuedReadEn)

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
    when(target.completedBundleCountWriteEn) {
      completedBundleCount := target.completedBundleCountWriteData
    }
    // write index and data to write into completedBundleIds
    when(target.completedBundleIdWriteEn) {
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

    // Store packed 512-bit accesses so FIRRTL/Vivado do not split the L2Access
    // bundle into many independent large memories.
    val accessStore = SyncReadMem(key.maxL2AccessEntries, UInt(L2Access.streamWidthBits.W))
    RAMStyleHint(accessStore, RAMStyles.BLOCK)
    val accessStoreCount = RegInit(0.U(32.W))
    val accessStoreMaxCycle = RegInit(0.U(64.W))
    val accessStoreHasEntries = RegInit(false.B)

    val accessStoreWriteEn = WireDefault(false.B)
    val accessStoreWriteAddr = WireDefault(0.U(accessIdxWidth.W))
    val accessStoreWriteData = WireDefault(0.U(L2Access.streamWidthBits.W))

    /*
     * Setup for uploaded blocked warps.
    */

    val blockedWarpBitmap = SyncReadMem(BlockedWarpBitmap.streamBeatCount, UInt(L2Access.streamWidthBits.W))
    RAMStyleHint(blockedWarpBitmap, RAMStyles.BLOCK)
    val blockedWarpBitmapWriteEn = WireDefault(false.B)
    val blockedWarpBitmapWriteAddr = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val blockedWarpBitmapWriteData = WireDefault(0.U(L2Access.streamWidthBits.W))

    // Bridge driver pulses this after XDMA writes have populated the uploaded round data.
    val commitUpload = Wire(Bool())
    // written by bridge driver to indicate how many L2 accesses we are uploading
    val uploadCount = RegInit(0.U(32.W))
    val uploadMaxCycleLow = RegInit(0.U(32.W))
    val uploadMaxCycleHigh = RegInit(0.U(32.W))
    val uploadMaxCycle = Cat(uploadMaxCycleHigh, uploadMaxCycleLow)

    val uploadReady = RegInit(false.B)

    // reset the target-side query/read response state when a fresh upload begins
    val accessReadReset = WireDefault(false.B)

    // Once driver indicates the uploads to XDMA are ready,
    // bridge module can indicate to target that data is ready
    when(commitUpload) {
      uploadReady := true.B
      accessReadReset := true.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      accessStoreCount := uploadCount
      accessStoreMaxCycle := uploadMaxCycle
      accessStoreHasEntries := uploadCount =/= 0.U
    }

    target.uploadReady := uploadReady

    when(target.roundStarted) {
      uploadReady := false.B
    }

    // MMIO min_issue_cycle registers and report to target
    val minIssueCycleLow = RegInit(0.U(32.W))
    val minIssueCycleHigh = RegInit(0.U(32.W))
    val minIssueCycle = Cat(minIssueCycleHigh, minIssueCycleLow)
    target.minIssueCycle := minIssueCycle
    target.accessStoreCount := accessStoreCount
    target.accessStoreMaxCycle := accessStoreMaxCycle
    target.accessStoreHasEntries := accessStoreHasEntries

    /*
     * Respond to traffic-generator queries for accesses.

     * For cycle N, target requests all accesses with cycleCount==N.
     * Bridge walks accessStore with cursor and returns matching entries, followed by bucket done (cycle complete)

     */

    // target requests one logical cycle bucket at a time. The bridge scans a
    // flat accessStore sorted by cycleCount and returns each matching entry,
    // followed by an explicit bucket-done. A one-entry peek buffer preserves
    // the first future-cycle access for the next request.

    // expanded from 1 L2Access register and 1 valid bit, to 16 access lanes with batch valid bits, to support streaming parallel accesses to target
    // target sees:
    // - 1 response valid bit+ID
    // - N access lanes
    // - N lane-valid bits
    // - 1 bucket-done bit
    // - 1 batch-ready input from target
    val accessReadDataRegs = Reg(Vec(TrafficGenAccessBatch.lanes, new L2Access))
    val accessReadDataValidRegs = RegInit(VecInit(Seq.fill(TrafficGenAccessBatch.lanes)(false.B)))
    val accessReadRespValidReg = RegInit(false.B) // indicates that whole batch response is valid
    val accessReadRespIdReg = RegInit(0.U(32.W))
    val accessReadNextRespId = RegInit(0.U(32.W))
    val accessReadBucketDoneReg = RegInit(false.B)
    val accessReadReadyReg = RegInit(true.B)
    val accessReadServingReg = RegInit(false.B)
    val accessReadReqCycleReg = Reg(UInt(64.W))
    val accessReadCursor = RegInit(0.U(accessIdxWidth.W))
    val accessReadBatchCount = RegInit(0.U(log2Ceil(TrafficGenAccessBatch.lanes + 1).W))

    // one entry look-ahead. Stores next access read from memory while next access is processed by target.
    val accessReadPeekAccessReg = Reg(new L2Access)
    val accessReadPeekValidReg = RegInit(false.B)

    val accessReadFetchPending = RegInit(false.B)

    // prevent re-accepting same request if read en stays high after bridge finishes bucket
    val accessReadWaitForEnLow = RegInit(false.B)
    val accessReadEntryIdx = WireDefault(0.U(accessIdxWidth.W))
    val accessReadEntryEn = WireDefault(false.B)

    val blockedWarpQueryReadIdx = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val blockedWarpQueryReadEn = WireDefault(false.B)

    // AXI read path unused but present for completeness
    val axiRawBlockedReadIdx = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val axiRawBlockedReadEn = WireDefault(false.B)

    val blockedWarpQueryReadBits = blockedWarpBitmap.read(
      Mux(axiRawBlockedReadEn, axiRawBlockedReadIdx, blockedWarpQueryReadIdx),
      blockedWarpQueryReadEn || axiRawBlockedReadEn,
    )

    // AXI read path unused but present for completeness
    val axiRawAccessReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val axiRawAccessReadEn = WireDefault(false.B)

    val accessReadDataBits = accessStore.read(
      Mux(axiRawAccessReadEn, axiRawAccessReadIdx, accessReadEntryIdx),
      accessReadEntryEn || axiRawAccessReadEn,
    )
    val accessReadFetchedData = L2Access.unpack(accessReadDataBits)
    val accessReadPeekData = accessReadPeekAccessReg
    accessReadBatchAssemblyPause := accessReadServingReg &&
                                    target.accessReadBatchReady &&
                                    !accessReadRespValidReg
    // accept new target read request when not serving a request, not waiting on memory fetch, not holding unconsumed response packet
    val accessReadCanAccept = accessReadReadyReg &&
                              !accessReadServingReg &&
                              !accessReadFetchPending &&
                              !accessReadRespValidReg &&
                              !accessReadBucketDoneReg &&
                              !accessReadWaitForEnLow
    val accessReadReq = fire && target.accessReadEn && accessReadCanAccept
    val accessReadRespFire = fire && accessReadRespValidReg && target.accessReadBatchReady

    // includes a unique response ID to allow target to distinguish consecutive response packets
    def publishAccessReadResp(): Unit = {
      accessReadRespValidReg := true.B
      accessReadRespIdReg := accessReadNextRespId
      accessReadNextRespId := accessReadNextRespId + 1.U
    }

    // begin serving a target access read request
    when(accessReadReq) {
      accessReadReadyReg := false.B
      accessReadServingReg := true.B
      accessReadReqCycleReg := target.accessReadCycle
      accessReadDataValidRegs.foreach(_ := false.B)
      accessReadRespValidReg := false.B
      accessReadBucketDoneReg := false.B
      accessReadBatchCount := 0.U
      accessReadWaitForEnLow := true.B
    }

    when(fire && !target.accessReadEn) {
      accessReadWaitForEnLow := false.B
    }

    // clear valid lanes when target accepts response
    when(accessReadRespFire) {
      accessReadDataValidRegs.foreach(_ := false.B)
      accessReadRespValidReg := false.B
      accessReadBatchCount := 0.U
      when(accessReadBucketDoneReg) {
        accessReadBucketDoneReg := false.B
        accessReadServingReg := false.B
        accessReadReadyReg := true.B
      }
    }

    when(accessReadServingReg && !accessReadFetchPending && !accessReadRespValidReg && !accessReadBucketDoneReg) {
      // if there is a valid peek entry
      when(accessReadPeekValidReg) {
        // If a prior scheduling exit stopped mid-cycle, the next uploaded chunk
        // may contain retained accesses older than the target's current
        // modelCycle. Drain those with the current request instead of dropping
        // them or requiring the target to rewind modelCycle.
        when(accessReadPeekData.cycleCount <= accessReadReqCycleReg) {
          accessReadDataRegs(accessReadBatchCount) := accessReadPeekData
          accessReadDataValidRegs(accessReadBatchCount) := true.B
          accessReadPeekValidReg := false.B
          accessReadCursor := accessReadCursor + 1.U
          accessReadBatchCount := accessReadBatchCount + 1.U
          // if it fills last lane, publish batch immediately
          when(accessReadBatchCount === (TrafficGenAccessBatch.lanes - 1).U) {
            publishAccessReadResp()
          }
        }.otherwise {
          // if it is for future cycle, do not consume and mark cycle bucket done
          accessReadBucketDoneReg := true.B
          publishAccessReadResp()
        }
      }.elsewhen(accessReadCursor < accessStoreCount) {
        // if there is no peek entry but more uploaded accesses exist, issue memory read
        accessReadEntryIdx := accessReadCursor
        accessReadEntryEn := true.B
        accessReadFetchPending := true.B
      }.otherwise {
        // if no accesses at all, bucket is done
        accessReadBucketDoneReg := true.B
        publishAccessReadResp()
      }
    }

    // read issued in previous cycle returns here as peek entry
    when(accessReadFetchPending) {
      accessReadPeekAccessReg := accessReadFetchedData
      accessReadPeekValidReg := true.B
      accessReadFetchPending := false.B
    }

    // reset signals
    when(accessReadReset) {
      accessReadReadyReg := true.B
      accessReadServingReg := false.B
      accessReadCursor := 0.U
      accessReadPeekValidReg := false.B
      accessReadFetchPending := false.B
      accessReadWaitForEnLow := false.B
      accessReadDataValidRegs.foreach(_ := false.B)
      accessReadRespValidReg := false.B
      accessReadRespIdReg := 0.U
      accessReadNextRespId := 0.U
      accessReadBucketDoneReg := false.B
      accessReadBatchCount := 0.U
    }

    target.accessReadRespValid := accessReadRespValidReg
    target.accessReadRespId := accessReadRespIdReg
    target.accessReadData := accessReadDataRegs
    target.accessReadDataValid := accessReadDataValidRegs
    target.accessReadBucketDone := accessReadBucketDoneReg
    target.accessReadReady := accessReadCanAccept

    /*
     * Respond to traffic-generator queries for blocked warps.
    */

    val blockedWarpQueryWordIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.indexBits - 1, BlockedWarpBitmap.streamBeatOffsetBits)
    val blockedWarpQueryBitIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.streamBeatOffsetBits - 1, 0)
    val blockedWarpQueryBitIdxReg = Reg(UInt(BlockedWarpBitmap.streamBeatOffsetBits.W))
    val blockedWarpQueryRespReg = RegInit(false.B)
    val blockedWarpQueryRespValidReg = RegInit(false.B)
    val blockedWarpQueryReadyReg = RegInit(true.B)
    val blockedWarpQueryPending = RegInit(false.B)
    val blockedWarpQueryReadPending = RegInit(false.B)
    val blockedWarpQueryReq = fire && target.blockedWarpQueryEn && blockedWarpQueryReadyReg

    when(blockedWarpQueryReq) {
      blockedWarpQueryReadyReg := false.B
      blockedWarpQueryPending := true.B
      blockedWarpQueryReadPending := true.B
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryBitIdxReg := blockedWarpQueryBitIdx
      blockedWarpQueryReadIdx := blockedWarpQueryWordIdx
      blockedWarpQueryReadEn := true.B
    }

    when(blockedWarpQueryReadPending) {
      blockedWarpQueryRespReg := blockedWarpQueryReadBits(blockedWarpQueryBitIdxReg)
      blockedWarpQueryPending := false.B
      blockedWarpQueryReadPending := false.B
      blockedWarpQueryRespValidReg := true.B
    }

    when(target.blockedWarpQueryRespStored && blockedWarpQueryRespValidReg) {
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryReadyReg := true.B
    }

    when(accessReadReset) {
      blockedWarpQueryReadyReg := true.B
      blockedWarpQueryPending := false.B
      blockedWarpQueryReadPending := false.B
      blockedWarpQueryRespValidReg := false.B
    }

    target.blockedWarpQueryResp := blockedWarpQueryRespReg
    target.blockedWarpQueryRespValid := blockedWarpQueryRespValidReg
    target.blockedWarpQueryReady := blockedWarpQueryReadyReg && !blockedWarpQueryPending && !blockedWarpQueryReadPending

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
    val blockedWindowBytes = BigInt(BlockedWarpBitmap.streamBeatCount) * bramBeatBytes
    val completedWindowBytes = BigInt(completedBundleIdBeats) * bramBeatBytes

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
    val writeRawBlocked = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset, blockedWindowBytes)
    val writeRawCompleted = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)

    bramAxi.w.ready := axiWriteActive && !axiWriteRespValid

    // each W beat writes directly into the SyncReadMem
    val axiWriteLastBeat = axiWriteBeatsRemaining === 1.U
    when(bramAxi.w.fire) {
      when(writeRawAccess) {
        accessStoreWriteEn := true.B
        accessStoreWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset)(accessIdxWidth - 1, 0)
        accessStoreWriteData := bramAxi.w.bits.data
      }
      when(writeRawBlocked) {
        blockedWarpBitmapWriteEn := true.B
        blockedWarpBitmapWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset)(BlockedWarpBitmap.streamBeatIdxBits - 1, 0)
        blockedWarpBitmapWriteData := bramAxi.w.bits.data
      }
      ////
      //  Unused paths since XDMA does not write into issuedAccessWritebackStore or completedBundleIds
      when(writeRawIssued) {
        issuedAccessWritebackStoreWriteEn := true.B
        issuedAccessWritebackStoreWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset)(accessIdxWidth - 1, 0)
        issuedAccessWritebackStoreWriteData := bramAxi.w.bits.data
      }
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
      axiReadRouteBlocked ::
      axiReadRouteCompleted ::
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
    val axiReadDataReg = Reg(UInt(L2Access.streamWidthBits.W))
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
    val readRawBlocked = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset, blockedWindowBytes)
    val readRawCompleted = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)
    val axiReadIssue = axiReadActive && !axiReadPending && !axiReadRespValid
    val axiReadLastIssue = axiReadBeatsRemaining === 1.U

    // generate the appropriate index and enable for the XDMA read into issued access and completed bundle IDs
    axiRawIssuedReadIdx := beatIndex(
                            axiReadAddr,
                            TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset,
                          )(accessIdxWidth - 1, 0)
    axiRawIssuedReadEn := axiReadIssue && readRawIssued
    axiRawCompletedReadIdx := beatIndex(
                                axiReadAddr,
                                TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset,
                              )(completedBundleBeatIdxWidth - 1, 0)
    axiRawCompletedReadEn := axiReadIssue && readRawCompleted
    ////
    //  Unused paths since XDMA does not read from accessStore or blockedWarpBitmap
    axiRawAccessReadIdx := beatIndex(
                            axiReadAddr,
                            TrafficGenBRAMAddressMap.rawAccessStoreOffset,
                          )(accessIdxWidth - 1, 0)
    axiRawAccessReadEn := axiReadIssue && readRawAccess
    axiRawBlockedReadIdx := beatIndex(
                              axiReadAddr,
                              TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset,
                            )(BlockedWarpBitmap.streamBeatIdxBits - 1, 0)
    axiRawBlockedReadEn := axiReadIssue && readRawBlocked
    ////

    val axiAccessReadBits = accessReadDataBits
    val axiIssuedReadBits = issuedAccessReadBits
    val axiBlockedReadBits = blockedWarpQueryReadBits
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
          readRawBlocked -> axiReadRouteBlocked,
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
        0.U(L2Access.streamWidthBits.W),
        Seq(
          axiReadRouteAccess -> axiAccessReadBits,
          axiReadRouteIssued -> axiIssuedReadBits,
          axiReadRouteBlocked -> axiBlockedReadBits,
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
    // accessStore and blockedWarpBitmap written from XDMA, host->bridge module
    when(accessStoreWriteEn) {
      accessStore.write(accessStoreWriteAddr, accessStoreWriteData)
    }
    when(blockedWarpBitmapWriteEn) {
      blockedWarpBitmap.write(blockedWarpBitmapWriteAddr, blockedWarpBitmapWriteData)
    }
    // issuedAccess and completedBundleIds written from target -> bridge module
    when(issuedAccessWritebackStoreWriteEn) {
      issuedAccessWritebackStore.write(issuedAccessWritebackStoreWriteAddr, issuedAccessWritebackStoreWriteData)
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

    // Hold round completion for the bridge driver. The DPI can assert
    // roundComplete for only one target-visible cycle, while the C++ bridge
    // driver polls this MMIO register from the host side. Delay visibility by
    // one cycle so the final issuedAccessWriteback BRAM write has landed before
    // the driver reads the store through XDMA.
    val roundCompleteLatched = RegInit(false.B)
    val targetRoundCompleteDelayed = RegNext(target.roundComplete, false.B)
    when(target.roundStarted) {
      roundCompleteLatched := false.B
    }.elsewhen(targetRoundCompleteDelayed) {
      roundCompleteLatched := true.B
    }



    val targetReset = fire && hPort.hBits.reset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
      accessReadReadyReg := true.B
      accessReadServingReg := false.B
      accessReadCursor := 0.U
      accessReadPeekValidReg := false.B
      accessReadFetchPending := false.B
      accessReadDataValidRegs.foreach(_ := false.B)
      accessReadRespValidReg := false.B
      accessReadRespIdReg := 0.U
      accessReadNextRespId := 0.U
      accessReadBucketDoneReg := false.B
      accessReadBatchCount := 0.U
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      accessStoreCount := 0.U
      accessStoreMaxCycle := 0.U
      accessStoreHasEntries := false.B
      uploadReady := false.B
      uploadMaxCycleLow := 0.U
      uploadMaxCycleHigh := 0.U
      completedBundleCount := 0.U
      blockedWarpQueryReadyReg := true.B
      blockedWarpQueryPending := false.B
      blockedWarpQueryReadPending := false.B
      blockedWarpQueryRespValidReg := false.B
      startRoundPending := false.B
      startTrafficGenLatched := false.B
      roundCompleteLatched := false.B
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

    // bridge driver commits the uploaded L2 accesses and blocked warp bitmap
    genWORegInit(uploadCount, "upload_count", 0.U)
    genWORegInit(uploadMaxCycleLow, "access_store_max_cycle_low", 0.U)
    genWORegInit(uploadMaxCycleHigh, "access_store_max_cycle_high", 0.U)
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
          UInt64(TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset),
          UInt64(TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset),
          UInt64(accessWindowBytes),
          UInt64(blockedWindowBytes),
          UInt64(completedWindowBytes),
        ),
      )
    }
  }
}
