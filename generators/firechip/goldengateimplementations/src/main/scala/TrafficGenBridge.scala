// See LICENSE for license details

package firechip.goldengateimplementations

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import midas.widgets._
import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

class TrafficGenBridgeModule(key: TrafficGenBridgeKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TrafficGenBridgeTargetIO]]()(p)
    with StreamToHostCPU
    with StreamFromHostCPU {

  val toHostCPUQueueDepth = 1024
  val fromHostCPUQueueDepth = 1024

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new TrafficGenBridgeTargetIO(key)))
    val target = hPort.hBits.trafficgen

    /*
     * Bridge-driver control and target clock gating.
     */
    val pauseTarget = RegInit(false.B)
    val targetPaused = RegInit(false.B)
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
    }
    when(pauseTarget.asBool) {
      startTrafficGenLatched := false.B
    }

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !targetPaused

    /*
     * Stage 1: respond to read requests for reservedSubpartitionsByCycle from the bridge driver.
     *
     * This section owns the cycle-indexed L2 subpartition reservation table, the snapshot stream
     * state used by the bridge driver, and the reserved subpartition update/clear path
     * driven by uploaded accesses (to populate) and the traffic generator (to depopulate).
     */

    // assuming we have a 4096 cycle window, and up to 256 L2 subpartitions
    val reservedSubPartitionEntries = 4096
    val reservedSubPartitionEntryWidth = 256

    // Table that maps cycle to an occupancy mask. Use SyncReadMem so the larger
    // snapshot stores in RAM resources instead of registers.
    val reservedSubPartitionsByCycle = SyncReadMem(reservedSubPartitionEntries, UInt(reservedSubPartitionEntryWidth.W))
    // table managed as ring buffer sliding window, so maintain base index and cycle
    val reservedSubPartitionsBaseIdx = RegInit(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservedSubPartitionsBaseCycle = RegInit(0.U(64.W))
    val reservedSubPartitionIdxWidth = reservedSubPartitionsBaseIdx.getWidth

    // logic to clear bits in reservedSubPartition table based on reservation clear requests from target 
    val reservedSubPartitionSelectWidth = log2Ceil(reservedSubPartitionEntryWidth)

    // structure holding information to update a reserved L2 subpartition state for a given cycle
    // used for both setting bits when uploading accesses, and clearing bits when processing clear requests from traffic generator
    class ReservationUpdate extends Bundle {
      val idx = UInt(reservedSubPartitionIdxWidth.W)
      val mask = UInt(reservedSubPartitionEntryWidth.W)
      val setNotClear = Bool()
    }

    // structure to look up a status for a subpartition at a given cycle
    class ReservationLookup extends Bundle {
      val cycle = UInt(64.W)
      val subpartition = UInt(32.W)
    }

    // get one-hot encoding of sub-partition for updating the reservation table
    def reservationMaskForSubpartition(subpartition: UInt): UInt =
      UIntToOH(
        subpartition(reservedSubPartitionSelectWidth - 1, 0),
        reservedSubPartitionEntryWidth,
      ).asUInt

    // transform absolute cycle number into index and validity for reservedSubPartitionsByCycle based on base index and cycle
    def reservationWindowLookup(cycle: UInt): (Bool, UInt) = {
      val cycle64 = cycle.pad(64)
      val cycleDelta = cycle64 - reservedSubPartitionsBaseCycle
      val cycleBeforeBase = cycle64 < reservedSubPartitionsBaseCycle
      val cycleOutsideWindow = cycleBeforeBase || cycleDelta >= reservedSubPartitionEntries.U
      val wrappedOffset = cycleDelta(reservedSubPartitionIdxWidth - 1, 0)
      val reservationIdx = (reservedSubPartitionsBaseIdx + wrappedOffset)(reservedSubPartitionIdxWidth - 1, 0)
      (!cycleOutsideWindow, reservationIdx)
    }

    /*
     * Following is the stream interface for the bridge module to send data back to bridge driver. This includes:
     * 1. reservedSubpartitionsByCycle
     * 2. completedBundleIds (after traffic generation)
     * 3. issuedAccessWriteback (L2 accesses issued by traffic generator and written back by target after issue)
     */

    // trigger for bridge driver to read reservedSubPartitionsByCycle
    val readReservedSubPartitions = Wire(Bool())

    // completedBundleIds will be streamed back from traffic generator to bridge driver
    // need 4 beats to stream 32 64-bit bundle IDs (32x64 = 2048 bits = 4x512)
    val completedBundleIdBeats = 4

    // Send one 256-bit entry per 512-bit beat and leave the upper half zeroed so
    // the snapshot path only needs a single SyncReadMem read port.
    val reservedSubPartitionBeats = reservedSubPartitionEntries
    val streamBeatIdxWidth = log2Ceil(math.max(math.max(reservedSubPartitionBeats, key.maxL2AccessEntries), completedBundleIdBeats) + 1)
    val streamBeatIdx = RegInit(0.U(streamBeatIdxWidth.W))

    // indicates that we have issued a read to the SyncReadMem and are waiting for data to return
    val reservedStreamReadPending = RegInit(false.B)

    // index and enable signals to read from SyncReadMem for streaming out reserved sub-partition data
    val reservedStreamReadIdx = WireDefault(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservedStreamReadEn = WireDefault(false.B)
    val reservedStreamEntryBits = reservedSubPartitionsByCycle.read(
      reservedStreamReadIdx,
      reservedStreamReadEn,
    )

    // latch read data from SyncReadMem in a register and pad to 512 bits for streaming to bridge driver
    val reservedStreamEntryReg = Reg(UInt(reservedSubPartitionEntryWidth.W))
    // indicates that we can stream the data returned from SyncReadMem to the bridge driver
    val reservedStreamDataValid = RegInit(false.B)

    // latch to hold start stream pulse from bridge driver until we begin
    val reservedStreamStartPending = RegInit(false.B)
    val reservedStreamBits = Cat(0.U(reservedSubPartitionEntryWidth.W), reservedStreamEntryReg)

    // buffer reservation updates from both uploads and reservation clear requests,
    // and apply them to the reservedSubPartitionsByCycle table one at a time
    // since SyncReadMem doesn't support read-modify-write, this read is to do a RMW operation
    val reservationUpdateReadIdx = WireDefault(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservationUpdateReadEn = WireDefault(false.B)
    val reservationUpdateReadBits = reservedSubPartitionsByCycle.read(
      reservationUpdateReadIdx,
      reservationUpdateReadEn,
    )
    val reservationTableWriteIdx = WireDefault(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservationTableWriteBits = WireDefault(0.U(reservedSubPartitionEntryWidth.W))
    val reservationTableWriteEn = WireDefault(false.B)
    val reservationUpdateReadPending = RegInit(false.B)
    // staging register to hold the update information while waiting for SyncReadMem read to return
    val reservationUpdateReqReg = Reg(new ReservationUpdate)

    // traffic generator indicates that it has advance its cycle window, so old reservations can be cleared
    val reservationWindowAdvanceCycle = target.reservationWindowAdvanceCycle
    val reservationWindowAdvanceEn = target.reservationWindowAdvanceEn
    val reservationClearSweepCountWidth = reservedSubPartitionIdxWidth + 1
    val reservationClearSweepIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W))
    val reservationClearSweepRemaining = RegInit(reservedSubPartitionEntries.U(reservationClearSweepCountWidth.W))
    val reservationClearSweepActive = reservationClearSweepRemaining =/= 0.U


    // target drives reservation clear requests, which are enqueued and arbitrated before being applied to the reservation table
    val clearQueueDepth = 4
    val reservationClearQueues = Seq.fill(key.nGenerators) {
      Module(new Queue(new ReservationLookup, clearQueueDepth))
    }
    for ((queue, lane) <- reservationClearQueues.zipWithIndex) {
      queue.io.enq.valid := target.reservationClear(lane).valid && fire
      queue.io.enq.bits.cycle := target.reservationClear(lane).bits.cycle
      queue.io.enq.bits.subpartition := target.reservationClear(lane).bits.subpartition
      target.reservationClear(lane).ready := queue.io.enq.ready && fire
    }

    // we pick one clear request in this cycle
    val reservationClearArb = Module(new RRArbiter(new ReservationLookup, key.nGenerators))
    for ((queue, lane) <- reservationClearQueues.zipWithIndex) {
      reservationClearArb.io.in(lane) <> queue.io.deq
    }

    /*
     * Shared bridge-driver readback plumbing.
     *
     * These declarations sit early because the shared stream mux and reset logic
     * reference their registers. The actual bridge-driver readback boundary is
     * labeled below as Stage 5.
     */

    // Buffer target-issued L2 accesses before appending them into a per-round writeback store.
    val issuedAccessWritebackStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackIdx = RegInit(0.U(32.W))
    val issuedAccessWritebackQueueDepth = 4
    val issuedAccessWritebackQueues = Seq.fill(key.nGenerators) {
      Module(new Queue(new L2Access, issuedAccessWritebackQueueDepth))
    }
    for ((queue, lane) <- issuedAccessWritebackQueues.zipWithIndex) {
      queue.io.enq.valid := target.issuedAccessWriteback(lane).valid && fire
      queue.io.enq.bits := target.issuedAccessWriteback(lane).bits
      target.issuedAccessWriteback(lane).ready := queue.io.enq.ready && fire
    }
    val issuedAccessWritebackArb = Module(new RRArbiter(new L2Access, key.nGenerators))
    for ((queue, lane) <- issuedAccessWritebackQueues.zipWithIndex) {
      issuedAccessWritebackArb.io.in(lane) <> queue.io.deq
    }
    val doIssuedAccessWriteback = issuedAccessWritebackArb.io.out.valid &&
      issuedAccessWritebackIdx < key.maxL2AccessEntries.U
    issuedAccessWritebackArb.io.out.ready := doIssuedAccessWriteback
    when(doIssuedAccessWriteback) {
      issuedAccessWritebackStore.write(issuedAccessWritebackIdx, issuedAccessWritebackArb.io.out.bits)
      issuedAccessWritebackIdx := issuedAccessWritebackIdx + 1.U
      issuedAccessWritebackCount := issuedAccessWritebackCount + 1.U
    }
    // send the issued access back to the bridge driver when requested
    val readIssuedAccessWriteback = Wire(Bool())
    val issuedAccessStreamReadIdx = WireDefault(0.U(streamBeatIdxWidth.W))
    val issuedAccessStreamReadEn = WireDefault(false.B)
    val issuedAccessStreamEntryBits = issuedAccessWritebackStore.read(
      issuedAccessStreamReadIdx,
      issuedAccessStreamReadEn,
    )
    val issuedAccessStreamEntryReg = Reg(new L2Access)
    val issuedAccessStreamReadPending = RegInit(false.B)
    val issuedAccessStreamDataValid = RegInit(false.B)
    
    
    // vector of integers to store completed bundle IDs completed by target, to be read by bridge driver 
    val completedBundleIds = RegInit(VecInit(Seq.fill(32)(0.U(64.W))))
    val completedBundleCount = RegInit(0.U(6.W))
    val completedBundleIdsValid = RegInit(true.B)
    val completedBundleCountValid = RegInit(true.B)

    // target can write completed bundle IDs as they occur during traffic generation
    when(target.completedBundleIdWriteEn) {
      completedBundleIds(target.completedBundleIdWriteIdx) := target.completedBundleIdWriteData
      completedBundleIdsValid := true.B
    }
    when(target.completedBundleCountWriteEn) {
      completedBundleCount := target.completedBundleCountWriteData
      completedBundleCountValid := true.B
    }

    // trigger for bridge driver to read completedBundleIds 
    val readCompletedBundleIds = Wire(Bool())

    // completedBundleIdsPacked(0) contains completedBundleIds(15, 0) and completedBundleIdsPacked(1) contains completedBundleIds(31, 16)
    val completedBundleIdsPacked = Wire(Vec(completedBundleIdBeats, UInt(L2Access.streamWidthBits.W)))
    for (beat <- 0 until completedBundleIdBeats) {
      completedBundleIdsPacked(beat) := Cat((0 until 8).reverse.map(idx => completedBundleIds(beat * 8 + idx)))
    }

    // stream is used for reservedSubPartitionsByCycle, completedBundleIds, and issuedAccessWriteback
    val (
      streamSourceIdle ::
      streamSourceReservedSubPartitions ::
      streamSourceCompletedBundleIds ::
      streamSourceIssuedAccessWriteback ::
      Nil
    ) = Enum(4)
    val streamSource = RegInit(streamSourceIdle)

    val streamPayloadValid = MuxLookup(
      streamSource,
      reservedStreamDataValid,
      Seq(
        streamSourceCompletedBundleIds -> true.B,
        streamSourceIssuedAccessWriteback -> issuedAccessStreamDataValid,
      ),
    )

    // stream bits are either the packed reservedSubPartitionsByCycle or the packed completedBundleIds
    streamEnq.bits := MuxLookup(
      streamSource,
      reservedStreamBits,
      Seq(
        streamSourceCompletedBundleIds -> completedBundleIdsPacked(streamBeatIdx),
        streamSourceIssuedAccessWriteback -> L2Access.pack(issuedAccessStreamEntryReg),
      ),
    )
    val streamActive = streamSource =/= streamSourceIdle
    streamEnq.valid := streamActive && streamPayloadValid
    val doStreamEnq = streamEnq.valid && streamEnq.ready

    val streamLastBeat = MuxLookup(
      streamSource,
      streamBeatIdx === (reservedSubPartitionBeats - 1).U,
      Seq(
        streamSourceCompletedBundleIds -> (streamBeatIdx === (completedBundleIdBeats - 1).U),
        streamSourceIssuedAccessWriteback -> (streamBeatIdx === (issuedAccessWritebackCount - 1.U)),
      ),
    )

    // when the SyncReadMem returns, latch it in a register
    // this introduces extra cycle, because at steady state:
    // we issue a read to SyncReadMem while streaming previous data
    // then, on the next cycle the reservedStreamEntryBits is latched in reservedStreamEntryReg
    // and that is sent via stream on the following cycle
    when(reservedStreamReadPending) {
      reservedStreamEntryReg := reservedStreamEntryBits
      reservedStreamReadPending := false.B
      reservedStreamDataValid := true.B
    }
    when(issuedAccessStreamReadPending) {
      issuedAccessStreamEntryReg := issuedAccessStreamEntryBits
      issuedAccessStreamReadPending := false.B
      issuedAccessStreamDataValid := true.B
    }

    // write the updated entry back to the reservedSubPartitionsByCycle table when we get a response from the SyncReadMem for a reservation update read, and then clear the pending flag to allow next updates
    when(reservationUpdateReadPending) {
      val updatedEntryBits = Mux(
        reservationUpdateReqReg.setNotClear,
        reservationUpdateReadBits | reservationUpdateReqReg.mask,
        reservationUpdateReadBits & ~reservationUpdateReqReg.mask,
      )
      reservationTableWriteIdx := reservationUpdateReqReg.idx
      reservationTableWriteBits := updatedEntryBits
      reservationTableWriteEn := true.B
      reservationUpdateReadPending := false.B
    }

    // state machine to stream reservedSubPartitionsByCycle or completedBundleIds when triggered by bridge driver, and to keep track of stream beat index
   
    // bridge driver triggers to start streaming reservedSubPartitionsByCycle
    when(readReservedSubPartitions) {
      reservedStreamStartPending := true.B
    }

    when(reservedStreamStartPending && !streamActive && !reservationClearSweepActive) {
      streamSource := streamSourceReservedSubPartitions
      streamBeatIdx := 0.U

      // to read from SyncReadMem
      reservedStreamReadIdx := 0.U
      reservedStreamReadEn := true.B

      // set to latch the data from SyncReadMem into a register and indicate it is valid for streaming
      reservedStreamReadPending := true.B
      reservedStreamDataValid := false.B

      reservedStreamStartPending := false.B
    }.elsewhen(readCompletedBundleIds && completedBundleIdsValid && completedBundleCountValid && !streamActive) {
      streamSource := streamSourceCompletedBundleIds
      streamBeatIdx := 0.U
    }.elsewhen(readIssuedAccessWriteback && issuedAccessWritebackCount =/= 0.U && !streamActive) {
      streamSource := streamSourceIssuedAccessWriteback
      streamBeatIdx := 0.U
      issuedAccessStreamReadIdx := 0.U
      issuedAccessStreamReadEn := true.B
      issuedAccessStreamReadPending := true.B
      issuedAccessStreamDataValid := false.B
    }.elsewhen(doStreamEnq) {
      when(streamSource === streamSourceCompletedBundleIds) {
        when(streamLastBeat) {
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
        }.otherwise {
          streamBeatIdx := streamBeatIdx + 1.U
        }
      }.elsewhen(streamSource === streamSourceIssuedAccessWriteback) {
        when(streamLastBeat) {
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
          issuedAccessStreamDataValid := false.B
        }.otherwise {
          val nextBeatIdx = streamBeatIdx + 1.U
          streamBeatIdx := nextBeatIdx
          issuedAccessStreamReadIdx := nextBeatIdx
          issuedAccessStreamReadEn := true.B
          issuedAccessStreamReadPending := true.B
          issuedAccessStreamDataValid := false.B
        }
      }.otherwise {
        // when we are not on the last beat, issue next read to SyncReadMem
        when(streamLastBeat) {
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
          reservedStreamDataValid := false.B
        }.otherwise {
          val nextBeatIdx = streamBeatIdx + 1.U
          streamBeatIdx := nextBeatIdx

          // indicate next read from SyncReadMem is pending
          reservedStreamReadIdx := nextBeatIdx
          reservedStreamReadEn := true.B
          reservedStreamReadPending := true.B

          // only stream data once it is latched from SyncReadMem into a register
          reservedStreamDataValid := false.B
        }
      }
    }
    
    /*
     * Stage 2: upload L2 accesses from the bridge driver.
     *
     * Accesses are appended into cycle-indexed linked-list buckets. The same
     * stream phase later accepts the blocked-warp bitmap, but the access-store
     * allocation, append, and reservation update logic live here.
     */

    //// store L2Access vector from bridge driver to a backing store in the bridge module
    val accessIdxWidth = log2Ceil(key.maxL2AccessEntries + 1)
    val invalidAccessIdx = key.maxL2AccessEntries.U(accessIdxWidth.W)
    val accessStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val nextPtr = SyncReadMem(key.maxL2AccessEntries, UInt(accessIdxWidth.W))
    val cycleHead = SyncReadMem(reservedSubPartitionEntries, UInt(accessIdxWidth.W))
    val cycleTail = SyncReadMem(reservedSubPartitionEntries, UInt(accessIdxWidth.W))
    val cycleValid = RegInit(VecInit(Seq.fill(reservedSubPartitionEntries)(false.B)))
    val freeList = SyncReadMem(key.maxL2AccessEntries, UInt(accessIdxWidth.W))
    val freeListCount = RegInit(0.U(accessIdxWidth.W))
    val nextUnusedAccessIdx = RegInit(0.U(accessIdxWidth.W))
    val accessStoreCount = RegInit(0.U(32.W))
    val accessStoreMaxCycle = RegInit(0.U(64.W))
    val accessStoreHasEntries = RegInit(false.B)

    def markAccessStored(cycle: UInt): Unit = {
      when(!accessStoreHasEntries || cycle > accessStoreMaxCycle) {
        accessStoreMaxCycle := cycle
      }
      accessStoreHasEntries := true.B
    }

    def wrapCycleIdx(idx: UInt): UInt =
      Mux(idx === (reservedSubPartitionEntries - 1).U, 0.U, idx + 1.U)

    val (
      accessRetireIdle ::
      accessRetireReadHead ::
      accessRetireReadNext ::
      accessRetireFreeEntry ::
      accessRetireClearBucket ::
      Nil
    ) = Enum(5)
    val accessRetireState = RegInit(accessRetireIdle)
    val accessRetireCycleIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W))
    val accessRetireRemaining = RegInit(0.U((reservedSubPartitionIdxWidth + 1).W))
    val accessRetireEntryIdx = Reg(UInt(accessIdxWidth.W))
    val accessRetireActive = accessRetireState =/= accessRetireIdle || accessRetireRemaining =/= 0.U
    val accessRetireHeadReadIdx = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val accessRetireHeadReadEn = WireDefault(false.B)
    val accessRetireHeadReadBits = cycleHead.read(accessRetireHeadReadIdx, accessRetireHeadReadEn)
    val accessRetireNextReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val accessRetireNextReadEn = WireDefault(false.B)
    val accessRetireNextReadBits = nextPtr.read(accessRetireNextReadIdx, accessRetireNextReadEn)
    val pendingBaseCycle = RegInit(0.U(64.W))
    val pendingBaseIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W))
    val baseAdvancePending = RegInit(false.B)

    /*
     * Stage 3: upload blocked warps and min-issue-cycle controls.
     */

    // store the warps that are currently blocked in the scheduler, for the traffic generator to return on if it unblocks the scheduler
    // stored as a vec of beats to make it easier to stream
    val blockedWarpBitmap = RegInit(VecInit(Seq.fill(BlockedWarpBitmap.streamBeatCount)(0.U(L2Access.streamWidthBits.W))))
    
    // state machine to receive streamed L2Access and blocked warp bitmap
    val uploadStart = Wire(Bool())
    val uploadCount = RegInit(0.U(32.W))
    val uploadActive = RegInit(false.B)

    val uploadRecvCount = RegInit(0.U(32.W))
    val blockedWarpBeatCount = RegInit(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))

    val uploadDone = RegInit(true.B)
    val blockedWarpUploadDone = RegInit(false.B)
    val uploadOverflow = RegInit(false.B)

    val uploadPhaseIdle :: uploadPhaseL2Accesses :: uploadPhaseBlockedWarpBitmap :: Nil = Enum(3)
    val uploadPhase = RegInit(uploadPhaseIdle)
    val accessReadReset = WireDefault(false.B)

    when(uploadStart) {

      uploadActive := true.B
      uploadDone := uploadCount === 0.U
      uploadOverflow := false.B

      blockedWarpUploadDone := false.B
      uploadRecvCount := 0.U
      blockedWarpBeatCount := 0.U
      accessReadReset := true.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      uploadPhase := Mux(uploadCount === 0.U, uploadPhaseBlockedWarpBitmap, uploadPhaseL2Accesses)
      blockedWarpBitmap.foreach(_ := 0.U)
    }

    val uploadBits = L2Access.unpack(streamDeq.bits)

    // when uploading L2 accesses, also update the reserved sub-partition table based on the sub-partition of each access 
    // compute the index and the mask (one-hot encoding) for the reservation update 
    val (uploadReservationInWindow, uploadReservationIdx) = reservationWindowLookup(uploadBits.cycleCount)
    val uploadReservationMask = reservationMaskForSubpartition(uploadBits.mSubpartition)
    val uploadAppendTailReadIdx = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val uploadAppendTailReadEn = WireDefault(false.B)
    val uploadAppendTailReadBits = cycleTail.read(uploadAppendTailReadIdx, uploadAppendTailReadEn)
    val uploadAppendTailReadPending = RegInit(false.B)
    val uploadAppendCycleIdxReg = Reg(UInt(reservedSubPartitionIdxWidth.W))
    val uploadAppendNewIdxReg = Reg(UInt(accessIdxWidth.W))
    val uploadAppendCycleCountReg = Reg(UInt(64.W))
    val uploadAccessComplete = WireDefault(false.B)
    val freeListHasEntry = freeListCount =/= 0.U
    val canAllocateAccessIdx = freeListHasEntry || nextUnusedAccessIdx < key.maxL2AccessEntries.U
    val freeListReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val freeListReadEn = WireDefault(false.B)
    val freeListReadBits = freeList.read(freeListReadIdx, freeListReadEn)
    val uploadAllocatePending = RegInit(false.B)
    val uploadAllocateBitsReg = Reg(new L2Access)
    val uploadAllocateCycleIdxReg = Reg(UInt(reservedSubPartitionIdxWidth.W))

    // we are only ready to accept next L2 access if we are in the L2 access upload phase, and if L2 reservation updater is not busy with previous upload
    val reservationUpdaterBusy = reservationUpdateReadPending || reservationClearSweepActive || accessRetireActive || baseAdvancePending
    val canAcceptUploadReservation = !reservationUpdaterBusy
    streamDeq.ready := uploadActive && Mux(
      uploadPhase === uploadPhaseL2Accesses,
      canAcceptUploadReservation && !uploadAllocatePending && !uploadAppendTailReadPending && !accessRetireActive && !baseAdvancePending,
      true.B,
    )

    // get the reservation clear arbiter output to update the reservation table 
    val clearArbValid = reservationClearArb.io.out.valid
    val clearArbInWindow = Wire(Bool())
    val clearArbIdx = Wire(UInt(reservedSubPartitionIdxWidth.W))
    val clearArbMask = Wire(UInt(reservedSubPartitionEntryWidth.W))
    val (clearWindowOk, clearReservationIdx) = reservationWindowLookup(reservationClearArb.io.out.bits.cycle)
    clearArbInWindow := clearWindowOk
    clearArbIdx := clearReservationIdx
    clearArbMask := reservationMaskForSubpartition(reservationClearArb.io.out.bits.subpartition)

    // if we are updating reservation for upload, it is a set, otherwise it is a clear 
    val issueUploadReservation = streamDeq.fire && uploadPhase === uploadPhaseL2Accesses &&
      uploadReservationInWindow && canAllocateAccessIdx
    val issueClearReservation = !reservationUpdaterBusy && !issueUploadReservation && clearArbValid
    reservationClearArb.io.out.ready := issueClearReservation

    // if the upload reservation is in the window, we can latch the update to the reservation table 
    when(issueUploadReservation && uploadReservationInWindow) {
      reservationUpdateReadIdx := uploadReservationIdx
      reservationUpdateReadEn := true.B
      reservationUpdateReqReg.idx := uploadReservationIdx
      reservationUpdateReqReg.mask := uploadReservationMask
      reservationUpdateReqReg.setNotClear := true.B
      reservationUpdateReadPending := true.B
    }

    // if we are not doing upload reservation update and there is a clear request in the window, we latch the update to the reservation table
    when(issueClearReservation && clearArbInWindow) {
      reservationUpdateReadIdx := clearArbIdx
      reservationUpdateReadEn := true.B
      reservationUpdateReqReg.idx := clearArbIdx
      reservationUpdateReqReg.mask := clearArbMask
      reservationUpdateReqReg.setNotClear := false.B
      reservationUpdateReadPending := true.B
    }
    
    when(streamDeq.fire && !uploadStart) {
      switch(uploadPhase) {

        // first, stream L2 access data into backing store
        is(uploadPhaseL2Accesses) {

          // 1 L2Access is transferred per 512-bit beat; valid-window accesses
          // are appended into the linked list for their cycle bucket.
          when(uploadReservationInWindow && canAllocateAccessIdx) {
            when(freeListHasEntry) {
              uploadAllocateBitsReg := uploadBits
              uploadAllocateCycleIdxReg := uploadReservationIdx
              freeListReadIdx := freeListCount - 1.U
              freeListReadEn := true.B
              uploadAllocatePending := true.B
            }.otherwise {
              val newIdx = nextUnusedAccessIdx
              accessStore.write(newIdx, uploadBits)
              nextPtr.write(newIdx, invalidAccessIdx)
              nextUnusedAccessIdx := nextUnusedAccessIdx + 1.U

              when(!cycleValid(uploadReservationIdx)) {
                cycleHead.write(uploadReservationIdx, newIdx)
                cycleTail.write(uploadReservationIdx, newIdx)
                cycleValid(uploadReservationIdx) := true.B
                accessStoreCount := accessStoreCount + 1.U
                markAccessStored(uploadBits.cycleCount)
                uploadAccessComplete := true.B
              }.otherwise {
                uploadAppendCycleIdxReg := uploadReservationIdx
                uploadAppendNewIdxReg := newIdx
                uploadAppendCycleCountReg := uploadBits.cycleCount
                uploadAppendTailReadIdx := uploadReservationIdx
                uploadAppendTailReadEn := true.B
                uploadAppendTailReadPending := true.B
              }
            }
          }.otherwise {
            // track to indicate when we overflow and can't store all L2 accesses
            uploadOverflow := true.B
            uploadAccessComplete := true.B
          }

          when(!uploadReservationInWindow) {
            uploadOverflow := true.B
          }
        }

        // next, stream blocked warp bitmap data into registers in the bridge module
        is(uploadPhaseBlockedWarpBitmap) {

          blockedWarpBitmap(blockedWarpBeatCount) := streamDeq.bits

          // when completed, indicate to target and bridge driver that blocked warp ID upload done
          when(blockedWarpBeatCount === (BlockedWarpBitmap.streamBeatCount - 1).U) {
            uploadActive := false.B
            blockedWarpUploadDone := true.B
            uploadPhase := uploadPhaseIdle
          }.otherwise {
            blockedWarpBeatCount := blockedWarpBeatCount + 1.U
          }
        }
      }
    }

    when(uploadAllocatePending) {
      val newIdx = freeListReadBits
      accessStore.write(newIdx, uploadAllocateBitsReg)
      nextPtr.write(newIdx, invalidAccessIdx)
      freeListCount := freeListCount - 1.U

      when(!cycleValid(uploadAllocateCycleIdxReg)) {
        cycleHead.write(uploadAllocateCycleIdxReg, newIdx)
        cycleTail.write(uploadAllocateCycleIdxReg, newIdx)
        cycleValid(uploadAllocateCycleIdxReg) := true.B
        accessStoreCount := accessStoreCount + 1.U
        markAccessStored(uploadAllocateBitsReg.cycleCount)
        uploadAccessComplete := true.B
      }.otherwise {
        uploadAppendCycleIdxReg := uploadAllocateCycleIdxReg
        uploadAppendNewIdxReg := newIdx
        uploadAppendCycleCountReg := uploadAllocateBitsReg.cycleCount
        uploadAppendTailReadIdx := uploadAllocateCycleIdxReg
        uploadAppendTailReadEn := true.B
        uploadAppendTailReadPending := true.B
      }

      uploadAllocatePending := false.B
    }

    when(uploadAppendTailReadPending) {
      nextPtr.write(uploadAppendTailReadBits, uploadAppendNewIdxReg)
      cycleTail.write(uploadAppendCycleIdxReg, uploadAppendNewIdxReg)
      accessStoreCount := accessStoreCount + 1.U
      markAccessStored(uploadAppendCycleCountReg)
      uploadAppendTailReadPending := false.B
      uploadAccessComplete := true.B
    }

    when(uploadAccessComplete) {
      uploadRecvCount := uploadRecvCount + 1.U
      when(uploadRecvCount + 1.U === uploadCount) {
        uploadPhase := uploadPhaseBlockedWarpBitmap
        uploadDone := true.B
      }
    }

    target.blockedWarpBitmapReady := blockedWarpUploadDone
    target.uploadDone := uploadDone

    val minIssueCycleLow = RegInit(0.U(32.W))
    val minIssueCycleHigh = RegInit(0.U(32.W))
    val minIssueCycle = Cat(minIssueCycleHigh, minIssueCycleLow)
    target.minIssueCycle := minIssueCycle
    target.accessStoreCount := accessStoreCount
    target.accessStoreMaxCycle := accessStoreMaxCycle
    target.accessStoreHasEntries := accessStoreHasEntries

    /*
     * Stage 4: respond to traffic-generator queries for accesses and blocked
     * warps, then retire completed cycle buckets.
     */

    // target requests one logical cycle bucket at a time. The bridge walks the
    // linked list for that bucket and then returns an explicit bucket-done.
    val accessReadDataReg = Reg(new L2Access)
    val accessReadDataValidReg = RegInit(false.B)
    val accessReadBucketDoneReg = RegInit(false.B)
    val accessReadReadyReg = RegInit(true.B)
    val accessReadHeadPending = RegInit(false.B)
    val accessReadEntryPending = RegInit(false.B)
    val accessReadNextIdxReg = Reg(UInt(accessIdxWidth.W))
    val accessReadHeadIdx = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val accessReadHeadEn = WireDefault(false.B)
    val accessReadHeadBits = cycleHead.read(accessReadHeadIdx, accessReadHeadEn)
    val accessReadEntryIdx = WireDefault(0.U(accessIdxWidth.W))
    val accessReadEntryEn = WireDefault(false.B)
    val accessReadData = accessStore.read(accessReadEntryIdx, accessReadEntryEn)
    val accessReadNextPtr = nextPtr.read(accessReadEntryIdx, accessReadEntryEn)
    val (accessReadCycleInWindow, accessReadCycleIdx) = reservationWindowLookup(target.accessReadCycle)
    val accessReadReq = fire && target.accessReadEn && accessReadReadyReg
    val accessReadDataFire = fire && accessReadDataValidReg && target.accessReadDataReady
    val accessReadBucketDoneFire = fire && accessReadBucketDoneReg && target.accessReadBucketDoneReady

    when(accessReadReq) {
      accessReadReadyReg := false.B
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
      when(accessReadCycleInWindow && cycleValid(accessReadCycleIdx)) {
        accessReadHeadIdx := accessReadCycleIdx
        accessReadHeadEn := true.B
        accessReadHeadPending := true.B
      }.otherwise {
        accessReadBucketDoneReg := true.B
      }
    }
    when(accessReadHeadPending) {
      accessReadEntryIdx := accessReadHeadBits
      accessReadEntryEn := true.B
      accessReadNextIdxReg := accessReadHeadBits
      accessReadHeadPending := false.B
      accessReadEntryPending := true.B
    }
    when(accessReadEntryPending) {
      accessReadDataReg := accessReadData
      accessReadNextIdxReg := accessReadNextPtr
      accessReadDataValidReg := true.B
      accessReadEntryPending := false.B
    }
    when(accessReadDataFire) {
      accessReadDataValidReg := false.B
      when(accessReadNextIdxReg === invalidAccessIdx) {
        accessReadBucketDoneReg := true.B
      }.otherwise {
        accessReadEntryIdx := accessReadNextIdxReg
        accessReadEntryEn := true.B
        accessReadEntryPending := true.B
      }
    }
    when(accessReadBucketDoneFire) {
      accessReadBucketDoneReg := false.B
      accessReadReadyReg := true.B
    }
    when(accessReadReset) {
      accessReadReadyReg := true.B
      accessReadHeadPending := false.B
      accessReadEntryPending := false.B
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
    }

    // Hold read responses until the target/DPI acknowledges them. Otherwise a
    // paused target clock can miss a one-cycle pulse.
    target.accessReadData := accessReadDataReg
    target.accessReadDataValid := accessReadDataValidReg
    target.accessReadBucketDone := accessReadBucketDoneReg
    target.accessReadReady := accessReadReadyReg &&
      !accessReadHeadPending &&
      !accessReadEntryPending &&
      !accessReadDataValidReg &&
      !accessReadBucketDoneReg
    

    // respond to target query as to whether warp is blocked by accessing blocked warp bitmap
    val blockedWarpQueryWordIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.indexBits - 1, BlockedWarpBitmap.streamBeatOffsetBits)
    val blockedWarpQueryBitIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.streamBeatOffsetBits - 1, 0)
    val blockedWarpQueryRespReg = RegInit(false.B)
    val blockedWarpQueryRespValidReg = RegInit(false.B)
    val blockedWarpQueryReadyReg = RegInit(true.B)
    val blockedWarpQueryPending = RegInit(false.B)
    val blockedWarpQueryReq = fire && target.blockedWarpQueryEn && blockedWarpQueryReadyReg
    val blockedWarpQueryRespReturn = RegNext(blockedWarpQueryReq, false.B)

    when(blockedWarpQueryReq) {
      blockedWarpQueryReadyReg := false.B
      blockedWarpQueryPending := true.B
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryRespReg := blockedWarpUploadDone && blockedWarpBitmap(blockedWarpQueryWordIdx)(blockedWarpQueryBitIdx)
    }
    when(blockedWarpQueryRespReturn) {
      blockedWarpQueryPending := false.B
      blockedWarpQueryRespValidReg := true.B
    }
    when(target.blockedWarpQueryRespStored && blockedWarpQueryRespValidReg) {
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryReadyReg := true.B
    }
    when(accessReadReset) {
      blockedWarpQueryReadyReg := true.B
      blockedWarpQueryPending := false.B
      blockedWarpQueryRespValidReg := false.B
    }
    target.blockedWarpQueryResp := blockedWarpQueryRespReg
    target.blockedWarpQueryRespValid := blockedWarpQueryRespValidReg
    target.blockedWarpQueryReady := blockedWarpQueryReadyReg && !blockedWarpQueryPending

    // when the target advances the reservation window, it reports the last
    // cycle processed. Retire access buckets and reservation entries through
    // that cycle before sliding the physical cycle window forward.
    when(
      reservationWindowAdvanceEn &&
      reservationWindowAdvanceCycle >= reservedSubPartitionsBaseCycle &&
      !baseAdvancePending &&
      !accessRetireActive &&
      !reservationClearSweepActive
    ) {
      val nextReservationBaseCycle = reservationWindowAdvanceCycle + 1.U
      val cycleDelta = nextReservationBaseCycle - reservedSubPartitionsBaseCycle
      val clampedDelta = Mux(cycleDelta >= reservedSubPartitionEntries.U, reservedSubPartitionEntries.U, cycleDelta)
      val nextBaseIdxWide = reservedSubPartitionsBaseIdx +& clampedDelta(reservedSubPartitionIdxWidth - 1, 0)
      pendingBaseIdx := Mux(
        nextBaseIdxWide >= reservedSubPartitionEntries.U,
        nextBaseIdxWide - reservedSubPartitionEntries.U,
        nextBaseIdxWide,
      )(reservedSubPartitionIdxWidth - 1, 0)
      pendingBaseCycle := nextReservationBaseCycle
      baseAdvancePending := true.B
      accessRetireCycleIdx := reservedSubPartitionsBaseIdx
      accessRetireRemaining := clampedDelta(reservedSubPartitionIdxWidth, 0)
      accessRetireState := accessRetireReadHead
      reservationClearSweepIdx := reservedSubPartitionsBaseIdx
      reservationClearSweepRemaining := clampedDelta(reservationClearSweepCountWidth - 1, 0)
      when(accessStoreHasEntries && accessStoreMaxCycle <= reservationWindowAdvanceCycle) {
        accessStoreHasEntries := false.B
        accessStoreMaxCycle := 0.U
      }
    }.elsewhen(reservationClearSweepActive && !reservationUpdateReadPending) {
      reservationTableWriteIdx := reservationClearSweepIdx
      reservationTableWriteBits := 0.U
      reservationTableWriteEn := true.B
      reservationClearSweepIdx := wrapCycleIdx(reservationClearSweepIdx)
      reservationClearSweepRemaining := reservationClearSweepRemaining - 1.U
    }

    switch(accessRetireState) {
      is(accessRetireReadHead) {
        when(accessRetireRemaining =/= 0.U) {
          when(cycleValid(accessRetireCycleIdx)) {
            accessRetireHeadReadIdx := accessRetireCycleIdx
            accessRetireHeadReadEn := true.B
            accessRetireState := accessRetireReadNext
          }.otherwise {
            accessRetireState := accessRetireClearBucket
          }
        }.otherwise {
          accessRetireState := accessRetireIdle
        }
      }
      is(accessRetireReadNext) {
        accessRetireEntryIdx := accessRetireHeadReadBits
        accessRetireNextReadIdx := accessRetireHeadReadBits
        accessRetireNextReadEn := true.B
        accessRetireState := accessRetireFreeEntry
      }
      is(accessRetireFreeEntry) {
        when(freeListCount < key.maxL2AccessEntries.U) {
          freeList.write(freeListCount, accessRetireEntryIdx)
          freeListCount := freeListCount + 1.U
        }
        when(accessStoreCount =/= 0.U) {
          accessStoreCount := accessStoreCount - 1.U
        }
        when(accessRetireNextReadBits === invalidAccessIdx) {
          accessRetireState := accessRetireClearBucket
        }.otherwise {
          accessRetireEntryIdx := accessRetireNextReadBits
          accessRetireNextReadIdx := accessRetireNextReadBits
          accessRetireNextReadEn := true.B
          accessRetireState := accessRetireFreeEntry
        }
      }
      is(accessRetireClearBucket) {
        cycleHead.write(accessRetireCycleIdx, invalidAccessIdx)
        cycleTail.write(accessRetireCycleIdx, invalidAccessIdx)
        cycleValid(accessRetireCycleIdx) := false.B
        accessRetireCycleIdx := wrapCycleIdx(accessRetireCycleIdx)
        accessRetireRemaining := accessRetireRemaining - 1.U
        accessRetireState := Mux(accessRetireRemaining === 1.U, accessRetireIdle, accessRetireReadHead)
      }
    }

    when(baseAdvancePending && !accessRetireActive && !reservationClearSweepActive) {
      reservedSubPartitionsBaseIdx := pendingBaseIdx
      reservedSubPartitionsBaseCycle := pendingBaseCycle
      baseAdvancePending := false.B
    }

    when(reservationTableWriteEn) {
      reservedSubPartitionsByCycle.write(
        reservationTableWriteIdx,
        reservationTableWriteBits,
      )
    }

    // Hold the round start request high until the target DPI acknowledges it.
    val startRoundPulse = Wire(Bool())
    val startRoundPending = RegInit(false.B)
    startRoundPulse := false.B
    when(startRoundPulse) {
      startRoundPending := true.B
    }
    when(target.roundStarted) {
      startRoundPending := false.B
    }
    target.startRound := startRoundPending

    // Hold round completion for the bridge driver. The DPI can assert
    // roundComplete for only one target-visible cycle, while the C++ bridge
    // driver polls this MMIO register from the host side.
    val roundCompleteLatched = RegInit(false.B)
    when(startRoundPulse) {
      roundCompleteLatched := false.B
    }
    when(target.roundComplete) {
      roundCompleteLatched := true.B
    }

    

    val targetReset = fire && hPort.hBits.reset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
      streamSource := streamSourceIdle
      streamBeatIdx := 0.U
      reservedStreamReadPending := false.B
      reservedStreamDataValid := false.B
      reservedStreamStartPending := false.B
      reservationClearSweepIdx := 0.U
      reservationClearSweepRemaining := reservedSubPartitionEntries.U
      issuedAccessStreamReadPending := false.B
      issuedAccessStreamDataValid := false.B
      reservationUpdateReadPending := false.B
      accessReadReadyReg := true.B
      accessReadHeadPending := false.B
      accessReadEntryPending := false.B
      accessReadDataValidReg := false.B
      accessReadBucketDoneReg := false.B
      blockedWarpQueryReadyReg := true.B
      blockedWarpQueryPending := false.B
      blockedWarpQueryRespValidReg := false.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      cycleValid.foreach(_ := false.B)
      freeListCount := 0.U
      nextUnusedAccessIdx := 0.U
      uploadAllocatePending := false.B
      uploadAppendTailReadPending := false.B
      accessRetireState := accessRetireIdle
      accessRetireRemaining := 0.U
      baseAdvancePending := false.B
      accessStoreCount := 0.U
      accessStoreMaxCycle := 0.U
      accessStoreHasEntries := false.B
      completedBundleIds.foreach(_ := 0.U)
      completedBundleCount := 0.U
      completedBundleIdsValid := true.B
      completedBundleCountValid := true.B
      startRoundPending := false.B
      startTrafficGenLatched := false.B
      roundCompleteLatched := false.B
    }

    /////////// MMIO registers for bridge driver interaction ///////////

    // for target program to trigger traffic generation
    genROReg(startTrafficGenLatched, "start_trafficgen")

    // for bridge driver to read to determine if target is still running the previous traffic pattern
    genROReg(uploadActive || target.targetBusy || accessRetireActive || baseAdvancePending, "target_busy")
    genROReg(target.hasPendingWork, "has_pending_work")

    // bridge driver toggles to pause/resume target while generating traffic patterns 
    Pulsify(genWORegInit(pauseTarget, "pause_target", false.B), pulseLength = 1)

    // bridge driver pulses this when a freshly uploaded scheduling round is ready to issue
    Pulsify(genWORegInit(startRoundPulse, "start_round", false.B), pulseLength = 1)

    // bridge driver triggers to read reservedSubpartitionsByCycle from stream
    Pulsify(genWORegInit(readReservedSubPartitions, "read_reserved_subpartitions", false.B), pulseLength = 1)
    // index into ring buffer
    genROReg(reservedSubPartitionsBaseIdx, "reserved_subpartitions_base_idx")
    // base cycle corresponding to the index (split into 2 32-bit registers to use MMIO)
    genROReg(reservedSubPartitionsBaseCycle(31, 0), "reserved_subpartitions_base_cycle_low")
    genROReg(reservedSubPartitionsBaseCycle(63, 32), "reserved_subpartitions_base_cycle_high")

    // bridge driver triggers to start sending L2 Accesses and blocked warp bitmap to the bridge module
    genWORegInit(uploadCount, "upload_count", 0.U)
    Pulsify(genWORegInit(uploadStart, "upload_start", false.B), pulseLength = 1)

    genROReg(roundCompleteLatched, "round_complete")
    genROReg(uploadDone, "upload_done")
    genROReg(uploadOverflow, "upload_overflow")
    genROReg(blockedWarpUploadDone, "blocked_warp_upload_done")

    // bridge driver writes the min_issue_cycle for the traffic generator to stop at
    genWORegInit(minIssueCycleLow, "min_issue_cycle_low", 0.U)
    genWORegInit(minIssueCycleHigh, "min_issue_cycle_high", 0.U)

    /*
     * Stage 5: respond to the bridge driver with completed accesses, completed
     * bundle IDs, and current-cycle metadata.
     */

    // when completed bundle IDs or count are available, bridge driver can read them
    genROReg(completedBundleIdsValid, "completed_bundle_ids_valid")
    genROReg(completedBundleCountValid, "completed_bundle_count_valid")

    Pulsify(genWORegInit(readCompletedBundleIds, "read_completed_bundle_ids", false.B), pulseLength = 1)
    Pulsify(genWORegInit(readIssuedAccessWriteback, "read_issued_access_writeback", false.B), pulseLength = 1)
    
    genROReg(target.currentCycleAfterIssue(31, 0), "current_cycle_after_issue_low")
    genROReg(target.currentCycleAfterIssue(63, 32), "current_cycle_after_issue_high")
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
          UInt32(toHostStreamIdx),
          UInt32(toHostCPUQueueDepth),
          UInt32(fromHostStreamIdx),
          UInt32(fromHostCPUQueueDepth),
        ),
        hasStreams = true,
      )
    }
  }
}
