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

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !targetPaused

    // completedBundleIds will be streamed back from traffic generator to bridge driver.
    // Each 512-bit beat carries eight 64-bit bundle IDs.
    val completedBundleIdBeats = CompletedBundleIds.beats
    val completedBundleBeatIdxWidth = log2Ceil(completedBundleIdBeats)

    // traffic generator indicates that it has advanced its cycle, so the cycle window can slide
    val reservationWindowAdvanceCycle = target.reservationWindowAdvanceCycle
    val reservationWindowAdvanceEn = target.reservationWindowAdvanceEn

    val accessIdxWidth = log2Ceil(key.maxL2AccessEntries + 1)


    /*
      * target issued accesses are buffered into issuedAccssWritebackStore for logging in the GPU model
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

    target.issuedAccessWriteback.ready := fire &&
      issuedAccessWritebackIdx < key.maxL2AccessEntries.U
    val doIssuedAccessWriteback = target.issuedAccessWriteback.valid &&
      target.issuedAccessWriteback.ready
    when(doIssuedAccessWriteback) {
      issuedAccessWritebackStoreWriteEn := true.B
      issuedAccessWritebackStoreWriteAddr := issuedAccessWritebackIdx(accessIdxWidth - 1, 0)
      issuedAccessWritebackStoreWriteData := L2Access.pack(target.issuedAccessWriteback.bits)
      issuedAccessWritebackIdx := issuedAccessWritebackIdx + 1.U
      issuedAccessWritebackCount := issuedAccessWritebackCount + 1.U
    }

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

    when(target.completedBundleCountWriteEn) {
      completedBundleCount := target.completedBundleCountWriteData
    }
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

    // Store the blocked warp bitmap in memory to avoid a large register array.
    // The bridge driver uploads every beat before blockedWarpUploadDone is set.
    val blockedWarpBitmap = SyncReadMem(BlockedWarpBitmap.streamBeatCount, UInt(L2Access.streamWidthBits.W))
    RAMStyleHint(blockedWarpBitmap, RAMStyles.BLOCK)
    val blockedWarpBitmapWriteEn = WireDefault(false.B)
    val blockedWarpBitmapWriteAddr = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val blockedWarpBitmapWriteData = WireDefault(0.U(L2Access.streamWidthBits.W))
    
    // signal to start upload process from bridge driver, which includes both L2 accesses and blocked warp bitmap
    val uploadStart = Wire(Bool())
    // written by bridge driver to indicate how many L2 accesses we are uploading
    val uploadCount = RegInit(0.U(32.W))
    val uploadMaxCycleLow = RegInit(0.U(32.W))
    val uploadMaxCycleHigh = RegInit(0.U(32.W))
    val uploadMaxCycle = Cat(uploadMaxCycleHigh, uploadMaxCycleLow)

    // upload completion signals
    val uploadDone = RegInit(true.B)
    val blockedWarpUploadDone = RegInit(false.B)

    // reset the target-side query/read response state when a fresh upload begins
    val accessReadReset = WireDefault(false.B)

    // Raw XDMA writes have already populated accessStore and blockedWarpBitmap
    // before the driver pulses uploadStart; publish the new round metadata here.
    when(uploadStart) {
      uploadDone := true.B
      blockedWarpUploadDone := true.B
      accessReadReset := true.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      accessStoreCount := uploadCount
      accessStoreMaxCycle := uploadMaxCycle
      accessStoreHasEntries := uploadCount =/= 0.U
    }

    target.blockedWarpBitmapReady := blockedWarpUploadDone
    target.uploadDone := uploadDone

    // MMIO min_issue_cycle registers and report to target 
    val minIssueCycleLow = RegInit(0.U(32.W))
    val minIssueCycleHigh = RegInit(0.U(32.W))
    val minIssueCycle = Cat(minIssueCycleHigh, minIssueCycleLow)
    target.minIssueCycle := minIssueCycle
    target.accessStoreCount := accessStoreCount
    target.accessStoreMaxCycle := accessStoreMaxCycle
    target.accessStoreHasEntries := accessStoreHasEntries

    /*
     * Respond to traffic-generator queries for accesses and blocked warps, then retire completed cycle buckets.
     */

    // target requests one logical cycle bucket at a time. The bridge scans a
    // flat accessStore sorted by cycleCount and returns each matching entry,
    // followed by an explicit bucket-done. A one-entry peek buffer preserves
    // the first future-cycle access for the next request.
    val accessReadDataReg = Reg(new L2Access)
    val accessReadDataValidReg = RegInit(false.B)
    val accessReadBucketDoneReg = RegInit(false.B)
    val accessReadReadyReg = RegInit(true.B)
    val accessReadServingReg = RegInit(false.B)
    val accessReadReqCycleReg = Reg(UInt(64.W))
    val accessReadCursor = RegInit(0.U(accessIdxWidth.W))
    val accessReadPeekBitsReg = Reg(UInt(L2Access.streamWidthBits.W))
    val accessReadPeekValidReg = RegInit(false.B)
    val accessReadFetchPending = RegInit(false.B)
    val accessReadWaitForEnLow = RegInit(false.B)
    val accessReadEntryIdx = WireDefault(0.U(accessIdxWidth.W))
    val accessReadEntryEn = WireDefault(false.B)
    val axiRawAccessReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val axiRawAccessReadEn = WireDefault(false.B)
    val accessReadDataBits = accessStore.read(
      Mux(axiRawAccessReadEn, axiRawAccessReadIdx, accessReadEntryIdx),
      accessReadEntryEn || axiRawAccessReadEn,
    )
    val accessReadPeekData = L2Access.unpack(accessReadPeekBitsReg)
    val accessReadCanAccept = accessReadReadyReg &&
      !accessReadServingReg &&
      !accessReadFetchPending &&
      !accessReadDataValidReg &&
      !accessReadBucketDoneReg &&
      !accessReadWaitForEnLow
    val accessReadReq = fire && target.accessReadEn && accessReadCanAccept
    val accessReadDataFire = fire && accessReadDataValidReg && target.accessReadDataReady
    val accessReadBucketDoneFire = fire && accessReadBucketDoneReg && target.accessReadBucketDoneReady

    when(accessReadReq) {
      accessReadReadyReg := false.B
      accessReadServingReg := true.B
      accessReadReqCycleReg := target.accessReadCycle
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
      accessReadWaitForEnLow := true.B
    }

    when(fire && !target.accessReadEn) {
      accessReadWaitForEnLow := false.B
    }

    when(accessReadServingReg &&
      !accessReadFetchPending &&
      !accessReadDataValidReg &&
      !accessReadBucketDoneReg) {
      when(accessReadPeekValidReg) {
        assert(accessReadPeekData.cycleCount >= accessReadReqCycleReg,
          "TrafficGen flat accessStore returned stale/unsorted access")
        when(accessReadPeekData.cycleCount === accessReadReqCycleReg) {
          accessReadDataReg := accessReadPeekData
          accessReadDataValidReg := true.B
          accessReadPeekValidReg := false.B
          accessReadCursor := accessReadCursor + 1.U
        }.otherwise {
          accessReadBucketDoneReg := true.B
        }
      }.elsewhen(accessReadCursor < accessStoreCount) {
        accessReadEntryIdx := accessReadCursor
        accessReadEntryEn := true.B
        accessReadFetchPending := true.B
      }.otherwise {
        accessReadBucketDoneReg := true.B
      }
    }

    when(accessReadFetchPending) {
      accessReadPeekBitsReg := accessReadDataBits
      accessReadPeekValidReg := true.B
      accessReadFetchPending := false.B
    }

    when(accessReadDataFire) {
      accessReadDataValidReg := false.B
    }

    when(accessReadBucketDoneFire) {
      accessReadBucketDoneReg := false.B
      accessReadServingReg := false.B
      accessReadReadyReg := true.B
    }

    // reset signals 
    when(accessReadReset) {
      accessReadReadyReg := true.B
      accessReadServingReg := false.B
      accessReadCursor := 0.U
      accessReadPeekValidReg := false.B
      accessReadFetchPending := false.B
      accessReadWaitForEnLow := false.B
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
    }

    // Hold read responses until the target/DPI acknowledges them. Otherwise a
    // paused target clock can miss a one-cycle pulse.
    target.accessReadData := accessReadDataReg
    target.accessReadDataValid := accessReadDataValidReg
    target.accessReadBucketDone := accessReadBucketDoneReg
    target.accessReadReady := accessReadCanAccept
    

    // respond to target query as to whether warp is blocked by accessing blocked warp bitmap
    val blockedWarpQueryWordIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.indexBits - 1, BlockedWarpBitmap.streamBeatOffsetBits)
    val blockedWarpQueryBitIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.streamBeatOffsetBits - 1, 0)
    val blockedWarpQueryReadIdx = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val blockedWarpQueryReadEn = WireDefault(false.B)
    val axiRawBlockedReadIdx = WireDefault(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val axiRawBlockedReadEn = WireDefault(false.B)
    val blockedWarpQueryReadBits = blockedWarpBitmap.read(
      Mux(axiRawBlockedReadEn, axiRawBlockedReadIdx, blockedWarpQueryReadIdx),
      blockedWarpQueryReadEn || axiRawBlockedReadEn,
    )
    val blockedWarpQueryBitIdxReg = Reg(UInt(BlockedWarpBitmap.streamBeatOffsetBits.W))
    val blockedWarpQueryRespReg = RegInit(false.B)
    val blockedWarpQueryRespValidReg = RegInit(false.B)
    val blockedWarpQueryReadyReg = RegInit(true.B)
    val blockedWarpQueryPending = RegInit(false.B)
    val blockedWarpQueryReadPending = RegInit(false.B)
    val blockedWarpQueryReq = fire && target.blockedWarpQueryEn && blockedWarpQueryReadyReg

    // respond to blocked warp query by looking up the corresponding bit in the blocked warp bitmap, 
    // but only after the upload is done and the bitmap is valid; hold the response until the target acknowledges it
    when(blockedWarpQueryReq) {
      blockedWarpQueryReadyReg := false.B
      blockedWarpQueryPending := true.B
      blockedWarpQueryReadPending := true.B
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryBitIdxReg := blockedWarpQueryBitIdx
      blockedWarpQueryReadIdx := blockedWarpQueryWordIdx
      blockedWarpQueryReadEn := true.B
    }
    // The bitmap is a SyncReadMem, so the accepted query returns one cycle after the read is issued.
    when(blockedWarpQueryReadPending) {
      blockedWarpQueryRespReg := blockedWarpUploadDone && blockedWarpQueryReadBits(blockedWarpQueryBitIdxReg)
      blockedWarpQueryPending := false.B
      blockedWarpQueryReadPending := false.B
      blockedWarpQueryRespValidReg := true.B
    }
    // once the target stores the blocked warp query, we can clear the valid and mark ready for the next query
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
     * Production upload writes directly access the physical SyncReadMems.
     */
    val bramAxi = bramSlaveNode.in.head._1
    val bramAddrBits = cpuManagedAXI4Params.addrBits
    val bramBeatOffsetBits = log2Ceil(bramBeatBytes)
    val bramBaseU = bramBase.U(bramAddrBits.W)

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

    when(bramAxi.aw.fire) {
      axiWriteActive := true.B
      axiWriteAddr := bramAxi.aw.bits.addr
      axiWriteBeatsRemaining := bramAxi.aw.bits.len +& 1.U
      axiWriteId := bramAxi.aw.bits.id
      axiWriteUser := bramAxi.aw.bits.user
    }
    when(bramAxi.b.fire) {
      axiWriteRespValid := false.B
    }

    val writeRawAccess = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset, accessWindowBytes)
    val writeRawIssued = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset, accessWindowBytes)
    val writeRawBlocked = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset, blockedWindowBytes)
    val writeRawCompleted = inBramRange(axiWriteAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)

    bramAxi.w.ready := axiWriteActive && !axiWriteRespValid

    val axiWriteLastBeat = axiWriteBeatsRemaining === 1.U
    when(bramAxi.w.fire) {
      when(writeRawAccess) {
        accessStoreWriteEn := true.B
        accessStoreWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset)(accessIdxWidth - 1, 0)
        accessStoreWriteData := bramAxi.w.bits.data
      }
      when(writeRawIssued) {
        issuedAccessWritebackStoreWriteEn := true.B
        issuedAccessWritebackStoreWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset)(accessIdxWidth - 1, 0)
        issuedAccessWritebackStoreWriteData := bramAxi.w.bits.data
      }
      when(writeRawBlocked) {
        blockedWarpBitmapWriteEn := true.B
        blockedWarpBitmapWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset)(BlockedWarpBitmap.streamBeatIdxBits - 1, 0)
        blockedWarpBitmapWriteData := bramAxi.w.bits.data
      }
      when(writeRawCompleted) {
        completedBundleIdsWriteEn := true.B
        completedBundleIdsWriteAddr := beatIndex(axiWriteAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset)(completedBundleBeatIdxWidth - 1, 0)
        completedBundleIdsWriteData := bramAxi.w.bits.data.asTypeOf(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
        completedBundleIdsWriteMask.foreach(_ := true.B)
      }

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

    val readRawAccess = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawAccessStoreOffset, accessWindowBytes)
    val readRawIssued = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset, accessWindowBytes)
    val readRawBlocked = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset, blockedWindowBytes)
    val readRawCompleted = inBramRange(axiReadAddr, TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset, completedWindowBytes)
    val axiReadIssue = axiReadActive && !axiReadPending && !axiReadRespValid
    val axiReadLastIssue = axiReadBeatsRemaining === 1.U

    axiRawAccessReadIdx := beatIndex(
      axiReadAddr,
      TrafficGenBRAMAddressMap.rawAccessStoreOffset,
    )(accessIdxWidth - 1, 0)
    axiRawAccessReadEn := axiReadIssue && readRawAccess
    axiRawIssuedReadIdx := beatIndex(
      axiReadAddr,
      TrafficGenBRAMAddressMap.rawIssuedAccessWritebackStoreOffset,
    )(accessIdxWidth - 1, 0)
    axiRawIssuedReadEn := axiReadIssue && readRawIssued
    axiRawBlockedReadIdx := beatIndex(
      axiReadAddr,
      TrafficGenBRAMAddressMap.rawBlockedWarpBitmapOffset,
    )(BlockedWarpBitmap.streamBeatIdxBits - 1, 0)
    axiRawBlockedReadEn := axiReadIssue && readRawBlocked
    axiRawCompletedReadIdx := beatIndex(
      axiReadAddr,
      TrafficGenBRAMAddressMap.rawCompletedBundleIdsOffset,
    )(completedBundleBeatIdxWidth - 1, 0)
    axiRawCompletedReadEn := axiReadIssue && readRawCompleted
    val axiAccessReadBits = accessReadDataBits
    val axiIssuedReadBits = issuedAccessReadBits
    val axiBlockedReadBits = blockedWarpQueryReadBits
    val axiCompletedReadBits = Cat(completedBundleReadBits.reverse)

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

    // When the target advances the access window, it reports the last cycle
    // processed. The host rebuilds and uploads the complete flat access store
    // for pending accesses, so the bridge only drops the store when all uploaded
    // accesses are known to be behind the target.
    when(reservationWindowAdvanceEn) {
      when(accessStoreHasEntries && accessStoreMaxCycle <= reservationWindowAdvanceCycle) {
        accessStoreCount := 0.U
        accessStoreHasEntries := false.B
        accessStoreMaxCycle := 0.U
      }
    }

    when(accessStoreWriteEn) {
      accessStore.write(accessStoreWriteAddr, accessStoreWriteData)
    }
    when(issuedAccessWritebackStoreWriteEn) {
      issuedAccessWritebackStore.write(issuedAccessWritebackStoreWriteAddr, issuedAccessWritebackStoreWriteData)
    }
    when(blockedWarpBitmapWriteEn) {
      blockedWarpBitmap.write(blockedWarpBitmapWriteAddr, blockedWarpBitmapWriteData)
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
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
      blockedWarpQueryReadyReg := true.B
      blockedWarpQueryPending := false.B
      blockedWarpQueryReadPending := false.B
      blockedWarpQueryRespValidReg := false.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      accessStoreCount := 0.U
      accessStoreMaxCycle := 0.U
      accessStoreHasEntries := false.B
      uploadMaxCycleLow := 0.U
      uploadMaxCycleHigh := 0.U
      completedBundleCount := 0.U
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

    // bridge driver triggers to start sending L2 Accesses and blocked warp bitmap to the bridge module
    genWORegInit(uploadCount, "upload_count", 0.U)
    genWORegInit(uploadMaxCycleLow, "access_store_max_cycle_low", 0.U)
    genWORegInit(uploadMaxCycleHigh, "access_store_max_cycle_high", 0.U)
    Pulsify(genWORegInit(uploadStart, "upload_start", false.B), pulseLength = 1)

    genROReg(roundCompleteLatched, "round_complete")
    genROReg(uploadDone, "upload_done")
    genROReg(blockedWarpUploadDone, "blocked_warp_upload_done")

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
