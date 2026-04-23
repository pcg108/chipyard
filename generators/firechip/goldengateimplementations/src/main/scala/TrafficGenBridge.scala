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

    //// STEP 1: toggled by bridge driver to pause/resume target while generating traffic patterns
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

    //// STEP 2: get reserved sub-partitions by cycle

    val reservedSubPartitionEntries = 4096
    val reservedSubPartitionEntryWidth = 256

    // Table that maps cycle to an occupancy mask. Use SyncReadMem so the larger
    // snapshot stores in RAM resources instead of registers.
    val reservedSubPartitionsByCycle = SyncReadMem(reservedSubPartitionEntries, UInt(reservedSubPartitionEntryWidth.W))
    val reservedSubPartitionsBaseIdx = RegInit(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservedSubPartitionsBaseCycle = RegInit(0.U(64.W))
    val reservedSubPartitionIdxWidth = reservedSubPartitionsBaseIdx.getWidth

    // logic to clear bits in reservedSubPartition table based on reservation clear requests from target 
    val reservedSubPartitionSelectWidth = log2Ceil(reservedSubPartitionEntryWidth)

    class ReservationUpdate extends Bundle {
      val idx = UInt(reservedSubPartitionIdxWidth.W)
      val mask = UInt(reservedSubPartitionEntryWidth.W)
      val setNotClear = Bool()
    }

    class ReservationLookup extends Bundle {
      val cycle = UInt(32.W)
      val subpartition = UInt(32.W)
    }

    // get one-hot encoding of sub-partition for updating the reservation table
    def reservationMaskForSubpartition(subpartition: UInt): UInt =
      UIntToOH(
        subpartition(reservedSubPartitionSelectWidth - 1, 0),
        reservedSubPartitionEntryWidth,
      ).asUInt

    def reservationWindowLookup(cycle: UInt): (Bool, UInt) = {
      val cycle64 = cycle.pad(64)
      val cycleDelta = cycle64 - reservedSubPartitionsBaseCycle
      val cycleBeforeBase = cycle64 < reservedSubPartitionsBaseCycle
      val cycleOutsideWindow = cycleBeforeBase || cycleDelta >= reservedSubPartitionEntries.U
      val wrappedOffset = cycleDelta(reservedSubPartitionIdxWidth - 1, 0)
      val reservationIdx =
        (reservedSubPartitionsBaseIdx + wrappedOffset)(reservedSubPartitionIdxWidth - 1, 0)
      (!cycleOutsideWindow, reservationIdx)
    }

    // Temporary initialization preserves the old placeholder contents until the
    // target starts maintaining this table directly.
    val reservedSubPartitionsInitActive = RegInit(true.B)
    val reservedSubPartitionsInitIdx = RegInit(0.U(log2Ceil(reservedSubPartitionEntries).W))
    when(reservedSubPartitionsInitActive) {
      reservedSubPartitionsByCycle.write(
        reservedSubPartitionsInitIdx,
        reservedSubPartitionsInitIdx.asUInt.pad(reservedSubPartitionEntryWidth),
      )
      when(reservedSubPartitionsInitIdx === (reservedSubPartitionEntries - 1).U) {
        reservedSubPartitionsInitActive := false.B
      }.otherwise {
        reservedSubPartitionsInitIdx := reservedSubPartitionsInitIdx + 1.U
      }
    }

    // trigger for bridge driver to read reservedSubPartitionsByCycle
    val readReservedSubPartitions = Wire(Bool())

    // need 2 beats to stream 32 32-bit bundle IDs (32x32 = 1024 bits = 2x512)
    val completedBundleIdBeats = 2

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

    // latch read data from SyncReadMem in a register and pad to 512 bits for streaming
    val reservedStreamEntryReg = Reg(UInt(reservedSubPartitionEntryWidth.W))

    // indicates that we can stream the data returned from SyncReadMem to the bridge driver
    val reservedStreamDataValid = RegInit(false.B)

    // latch to hold start stream pulse from bridge driver until we begin
    val reservedStreamStartPending = RegInit(false.B)
    val reservedStreamBits = Cat(0.U(reservedSubPartitionEntryWidth.W), reservedStreamEntryReg)

    // buffer reservation updates from both uploads and reservation clear requests, and apply them to the reservedSubPartitionsByCycle table one at a time since SyncReadMem doesn't support read-modify-write
    val reservationUpdateReadIdx = WireDefault(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservationUpdateReadEn = WireDefault(false.B)
    val reservationUpdateReadBits = reservedSubPartitionsByCycle.read(
      reservationUpdateReadIdx,
      reservationUpdateReadEn,
    )
    val reservationUpdateReadPending = RegInit(false.B)
    val reservationUpdateReqReg = Reg(new ReservationUpdate)
    val reservationWindowAdvanceCycle = target.reservationWindowAdvanceCycle
    val reservationWindowAdvanceEn = target.reservationWindowAdvanceEn

    val clearQueueDepth = 4
    val reservationClearQueues = Seq.fill(key.nGenerators) {
      Module(new Queue(new ReservationLookup, clearQueueDepth))
    }

    // target drives reservation clear requests, which are enqueued and arbitrated before being applied to the reservation table
    for ((queue, lane) <- reservationClearQueues.zipWithIndex) {
      queue.io.enq.valid := target.reservationClear(lane).valid
      queue.io.enq.bits.cycle := target.reservationClear(lane).bits.cycle
      queue.io.enq.bits.subpartition := target.reservationClear(lane).bits.subpartition
      target.reservationClear(lane).ready := queue.io.enq.ready
    }

    // we pick one clear request in this cycle
    val reservationClearArb = Module(new RRArbiter(new ReservationLookup, key.nGenerators))
    for ((queue, lane) <- reservationClearQueues.zipWithIndex) {
      reservationClearArb.io.in(lane) <> queue.io.deq
    }

    // Buffer target-issued L2 accesses before appending them into a per-round writeback store.
    val issuedAccessWritebackStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackIdx = RegInit(0.U(32.W))
    val issuedAccessWritebackQueueDepth = 4
    val issuedAccessWritebackQueues = Seq.fill(key.nGenerators) {
      Module(new Queue(new L2Access, issuedAccessWritebackQueueDepth))
    }
    for ((queue, lane) <- issuedAccessWritebackQueues.zipWithIndex) {
      queue.io.enq <> target.issuedAccessWriteback(lane)
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
    
    
    //// STEP 5: get completed bundle IDs from TG

    // vector of integers to store completed bundle IDs completed by target, to be read by bridge driver 
    val completedBundleIds = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
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
      completedBundleIdsPacked(beat) := Cat((0 until 16).reverse.map(idx => completedBundleIds(beat * 16 + idx)))
    }

    //// streamEnq target->host for reservedSubPartitionsByCycle and completedBundleIds ////

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
        streamSourceCompletedBundleIds -> completedBundleIdsPacked(streamBeatIdx(0)), // only 2 beats, so only need the least significant bit of streamBeatIdx to index
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
      reservedSubPartitionsByCycle.write(
        reservationUpdateReqReg.idx,
        updatedEntryBits,
      )
      reservationUpdateReadPending := false.B
    }

    // state machine to stream reservedSubPartitionsByCycle or completedBundleIds when triggered by bridge driver, and to keep track of stream beat index
   
    // bridge driver triggers to start streaming reservedSubPartitionsByCycle
    when(readReservedSubPartitions) {
      reservedStreamStartPending := true.B
    }

    when(reservedStreamStartPending && !streamActive && !reservedSubPartitionsInitActive) {
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
    
    /// STEP 3: streamDeq for host->target streaming of L2Accesses and blocked warp bitmap from bridge driver to bridge module

    //// store L2Access vector from bridge driver to a backing store in the bridge module
    val accessStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    // accessStore is treated as a ring buffer with head and tail pointers, and a count of the number of entries stored
    val accessStoreHead = RegInit(0.U(32.W))
    val accessStoreTail = RegInit(0.U(32.W))
    val accessStoreCount = RegInit(0.U(32.W))

    // store the warps that are currently blocked in the scheduler, for the traffic generator to return on if it unblocks the scheduler
    // stored as a vec of beats to make it easier to stream
    val blockedWarpBitmap = RegInit(VecInit(Seq.fill(BlockedWarpBitmap.streamBeatCount)(0.U(L2Access.streamWidthBits.W))))
    
    // state machine to receive streamed L2Access and blocked warp bitmap
    val uploadStart = Wire(Bool())
    val uploadCount = RegInit(0.U(32.W))
    val uploadActive = RegInit(false.B)

    val uploadRecvCount = RegInit(0.U(32.W))
    val uploadStoredCount = RegInit(0.U(32.W))
    val blockedWarpBeatCount = RegInit(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))

    val uploadDone = RegInit(true.B)
    val blockedWarpUploadDone = RegInit(false.B)
    val uploadOverflow = RegInit(false.B)

    val uploadPhaseIdle :: uploadPhaseL2Accesses :: uploadPhaseBlockedWarpBitmap :: Nil = Enum(3)
    val uploadPhase = RegInit(uploadPhaseIdle)

    when(uploadStart) {

      uploadActive := true.B
      uploadDone := uploadCount === 0.U
      uploadOverflow := false.B

      blockedWarpUploadDone := false.B
      uploadRecvCount := 0.U
      uploadStoredCount := 0.U
      blockedWarpBeatCount := 0.U
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

    // we are only ready to accept next L2 access if we are in the L2 access upload phase, and if L2 reservation updater is not busy with previous upload
    val reservationUpdaterBusy = reservedSubPartitionsInitActive || reservationUpdateReadPending
    val canAcceptUploadReservation = !reservationUpdaterBusy
    streamDeq.ready := uploadActive && Mux(uploadPhase === uploadPhaseL2Accesses, canAcceptUploadReservation, true.B)

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
    val issueUploadReservation = streamDeq.fire && uploadPhase === uploadPhaseL2Accesses
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

          // 1 L2Access is transferred per 512-bit beat, so we can store 1 L2Access per beat
          when(accessStoreCount < key.maxL2AccessEntries.U) {
            accessStore.write(accessStoreTail, uploadBits)
            accessStoreTail := Mux(accessStoreTail === (key.maxL2AccessEntries - 1).U, 0.U, accessStoreTail + 1.U)
            accessStoreCount := accessStoreCount + 1.U
            uploadStoredCount := uploadStoredCount + 1.U
          }.otherwise {
            // track to indicate when we overflow and can't store all L2 accesses
            uploadOverflow := true.B
          }

          when(!uploadReservationInWindow) {
            uploadOverflow := true.B
          }

          // move on to blocked warp bitmap when we've received all the L2 accesses
          // and indicate that L2 access upload is done
          uploadRecvCount := uploadRecvCount + 1.U
          when(uploadRecvCount + 1.U === uploadCount) {
            uploadPhase := uploadPhaseBlockedWarpBitmap
            uploadDone := true.B
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

    target.blockedWarpBitmapReady := blockedWarpUploadDone
    target.uploadDone := uploadDone

    val minIssueCycle = RegInit(0.U(32.W))
    target.minIssueCycle := minIssueCycle
    target.accessStoreCount := accessStoreCount

    //// STEP 4: TG is running issue schedule, respond to requests to reads for L2 accesses and blocked warps

    // target drives accessReadAddr and accessReadEn to request L2 access data from backing store
    // compute the address in the ring buffer for access store
    val accessReadLogicalAddrInRange = target.accessReadAddr < accessStoreCount
    val accessReadPhysicalAddr =
      Mux(
        accessStoreHead + target.accessReadAddr >= key.maxL2AccessEntries.U,
        accessStoreHead + target.accessReadAddr - key.maxL2AccessEntries.U,
        accessStoreHead + target.accessReadAddr,
      )
    val accessReadData = accessStore.read(accessReadPhysicalAddr, target.accessReadEn && accessReadLogicalAddrInRange)
    val accessReadDataValid = RegNext(target.accessReadEn && accessReadLogicalAddrInRange, false.B)
    // return L2 access data requested by the Traffic Generator from the backing store
    target.accessReadData := accessReadData
    target.accessReadDataValid := accessReadDataValid
    

    // respond to target query as to whether warp is blocked by accessing blocked warp bitmap
    val blockedWarpQueryWordIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.indexBits - 1, BlockedWarpBitmap.streamBeatOffsetBits)
    val blockedWarpQueryBitIdx = target.blockedWarpQueryIdx(BlockedWarpBitmap.streamBeatOffsetBits - 1, 0)
    val blockedWarpQueryRespReg = RegInit(false.B)

    when(target.blockedWarpQueryEn) {
      blockedWarpQueryRespReg := blockedWarpUploadDone && blockedWarpBitmap(blockedWarpQueryWordIdx)(blockedWarpQueryBitIdx)
    }
    target.blockedWarpQueryResp := blockedWarpQueryRespReg
    target.blockedWarpQueryRespValid := RegNext(target.blockedWarpQueryEn, false.B)

    // when the target consumes L2 accesses, move the head pointer and decrease the count of stored accesses accordingly
    when(target.accessStoreConsumeEn && target.accessStoreConsumeCount =/= 0.U) {
      val consumeCount = Mux(
        target.accessStoreConsumeCount > accessStoreCount,
        accessStoreCount,
        target.accessStoreConsumeCount,
      )
      val wrappedHead = accessStoreHead + consumeCount
      accessStoreHead := Mux(
        wrappedHead >= key.maxL2AccessEntries.U,
        wrappedHead - key.maxL2AccessEntries.U,
        wrappedHead,
      )
      accessStoreCount := accessStoreCount - consumeCount
    }

    // when the target advances the reservation window, we need to advance the base cycle and index for the reservedSubPartitionsByCycle table accordingly
    when(reservationWindowAdvanceEn && reservationWindowAdvanceCycle > reservedSubPartitionsBaseCycle) {
      val cycleDelta = reservationWindowAdvanceCycle - reservedSubPartitionsBaseCycle
      val clampedDelta = Mux(cycleDelta >= reservedSubPartitionEntries.U, reservedSubPartitionEntries.U, cycleDelta)
      val nextBaseIdxWide = reservedSubPartitionsBaseIdx + clampedDelta(reservedSubPartitionIdxWidth - 1, 0)
      reservedSubPartitionsBaseIdx := Mux(
        nextBaseIdxWide >= reservedSubPartitionEntries.U,
        nextBaseIdxWide - reservedSubPartitionEntries.U,
        nextBaseIdxWide,
      )
      reservedSubPartitionsBaseCycle := reservationWindowAdvanceCycle
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
      issuedAccessStreamReadPending := false.B
      issuedAccessStreamDataValid := false.B
      reservationUpdateReadPending := false.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      accessStoreHead := 0.U
      accessStoreTail := 0.U
      accessStoreCount := 0.U
      completedBundleIds.foreach(_ := 0.U)
      completedBundleCount := 0.U
      completedBundleIdsValid := true.B
      completedBundleCountValid := true.B
      startTrafficGenLatched := false.B
    }

    /////////// MMIO registers for bridge driver interaction ///////////

    // for target program to trigger traffic generation
    genROReg(startTrafficGenLatched, "start_trafficgen")

    // for bridge driver to read to determine if target is still running the previous traffic pattern
    genROReg(uploadActive || target.targetBusy, "target_busy")
    genROReg(target.hasPendingWork, "has_pending_work")

    // bridge driver toggles to pause/resume target while generating traffic patterns 
    Pulsify(genWORegInit(pauseTarget, "pause_target", false.B), pulseLength = 1)

    // bridge driver pulses this when a freshly uploaded scheduling round is ready to issue
    val startRound = Wire(Bool())
    Pulsify(genWORegInit(startRound, "start_round", false.B), pulseLength = 1)
    target.startRound := startRound

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

    genROReg(target.roundComplete, "round_complete")
    genROReg(uploadDone, "upload_done")
    genROReg(uploadOverflow, "upload_overflow")
    genROReg(blockedWarpUploadDone, "blocked_warp_upload_done")

    // bridge driver writes the min_issue_cycle for the traffic generator to stop at
    genWORegInit(minIssueCycle, "min_issue_cycle", 0.U)

    // when completed bundle IDs or count are available, bridge driver can read them
    genROReg(completedBundleIdsValid, "completed_bundle_ids_valid")
    genROReg(completedBundleCountValid, "completed_bundle_count_valid")

    Pulsify(genWORegInit(readCompletedBundleIds, "read_completed_bundle_ids", false.B), pulseLength = 1)
    Pulsify(genWORegInit(readIssuedAccessWriteback, "read_issued_access_writeback", false.B), pulseLength = 1)
    
    genROReg(target.currentCycleAfterIssue, "current_cycle_after_issue")
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
