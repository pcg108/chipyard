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

    //// toggled by bridge driver to pause/resume target while generating traffic patterns
    val pauseTarget = RegInit(false.B)
    val targetPaused = RegInit(false.B)
    when(pauseTarget.asBool) {
      targetPaused := ~targetPaused
    }

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !targetPaused

    //// reservedSubPartitionsByCycle ////

    val reservedSubPartitionEntries = 4096
    val reservedSubPartitionEntryWidth = 256

    // Table that maps cycle to an occupancy mask. Use SyncReadMem so the larger
    // snapshot stores in RAM resources instead of registers.
    val reservedSubPartitionsByCycle = SyncReadMem(reservedSubPartitionEntries, UInt(reservedSubPartitionEntryWidth.W))
    val reservedSubPartitionsBaseIdx = RegInit(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservedSubPartitionsBaseCycle = RegInit(0.U(64.W))
    val reservedSubPartitionIdxWidth = reservedSubPartitionsBaseIdx.getWidth

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

    // Send one 256-bit entry per 512-bit beat and leave the upper half zeroed so
    // the snapshot path only needs a single SyncReadMem read port.
    val reservedSubPartitionBeats = reservedSubPartitionEntries
    val streamBeatIdx = RegInit(0.U(log2Ceil(reservedSubPartitionBeats).W))

    // indicates that we have issued a read to the SyncReadMem and are waiting for data to return
    val reservedStreamReadPending = RegInit(false.B)

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

    // Upload-side reservation updates use a read/modify/write sequence so
    // multiple accesses to the same cycle accumulate into the 256-bit mask.
    val reservedUploadReadIdx = WireDefault(0.U(log2Ceil(reservedSubPartitionEntries).W))
    val reservedUploadReadEn = WireDefault(false.B)
    val reservedUploadEntryBits = reservedSubPartitionsByCycle.read(
      reservedUploadReadIdx,
      reservedUploadReadEn,
    )
    // we take one cycle to read the current cycle bitmask for reservations and then write on next cycle
    val reservedUploadPending = RegInit(false.B)
    val reservedUploadIdxReg = Reg(UInt(log2Ceil(reservedSubPartitionEntries).W))
    val reservedUploadMaskReg = Reg(UInt(reservedSubPartitionEntryWidth.W))
    
    
    //// completedBundleIds ////

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

    // need 2 beats to stream 32 32-bit bundle IDs (32x32 = 1024 bits = 2x512)
    val completedBundleIdBeats = 2
    // completedBundleIdsPacked(0) contains completedBundleIds(15, 0) and completedBundleIdsPacked(1) contains completedBundleIds(31, 16)
    val completedBundleIdsPacked = Wire(Vec(completedBundleIdBeats, UInt(L2Access.streamWidthBits.W)))
    for (beat <- 0 until completedBundleIdBeats) {
      completedBundleIdsPacked(beat) := Cat((0 until 16).reverse.map(idx => completedBundleIds(beat * 16 + idx)))
    }

    //// streamEnq target->host for reservedSubPartitionsByCycle and completedBundleIds ////

    // stream is used for both reservedSubPartitionsByCycle and completedBundleIds
    val streamSourceIdle :: streamSourceReservedSubPartitions :: streamSourceCompletedBundleIds :: Nil = Enum(3)
    val streamSource = RegInit(streamSourceIdle)

    val streamPayloadValid = Mux(
      streamSource === streamSourceCompletedBundleIds,
      true.B,
      reservedStreamDataValid,
    )

    // stream bits are either the packed reservedSubPartitionsByCycle or the packed completedBundleIds
    streamEnq.bits := Mux(
      streamSource === streamSourceCompletedBundleIds,
      completedBundleIdsPacked(streamBeatIdx(0)), // only 2 beats, so only need the least significant bit of streamBeatIdx to index
      reservedStreamBits,
    )
    val streamActive = streamSource =/= streamSourceIdle
    streamEnq.valid := streamActive && fire && streamPayloadValid
    val doStreamEnq = streamEnq.valid && streamEnq.ready

    val streamLastBeat = Mux(
      streamSource === streamSourceCompletedBundleIds,
      streamBeatIdx === (completedBundleIdBeats - 1).U,
      streamBeatIdx === (reservedSubPartitionBeats - 1).U,
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
    }.elsewhen(doStreamEnq) {
      when(streamSource === streamSourceCompletedBundleIds) {
        when(streamLastBeat) {
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
        }.otherwise {
          streamBeatIdx := streamBeatIdx + 1.U
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
    
    /// streamDeq for host->target streaming of L2Accesses and blocked warp bitmap from bridge driver to bridge module

    //// store L2Access vector from bridge driver to a backing store in the bridge module
    val accessStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)

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
      uploadDone := false.B
      uploadOverflow := false.B

      blockedWarpUploadDone := false.B
      uploadRecvCount := 0.U
      uploadStoredCount := 0.U
      blockedWarpBeatCount := 0.U
      uploadPhase := Mux(uploadCount === 0.U, uploadPhaseBlockedWarpBitmap, uploadPhaseL2Accesses)
      blockedWarpBitmap.foreach(_ := 0.U)
    }

    val uploadBits = L2Access.unpack(streamDeq.bits)

    // while writing the L2 acceses into the backing store, also write them to reservedSubPartitionsByCycle
    // one cycle after we set reservedUploadPending, we read the previous value and OR in the new reservation
    val canAcceptL2Access = !reservedSubPartitionsInitActive && !reservedUploadPending
    streamDeq.ready := uploadActive && Mux(uploadPhase === uploadPhaseL2Accesses, canAcceptL2Access, true.B)
    when(reservedUploadPending) {
      reservedSubPartitionsByCycle.write(
        reservedUploadIdxReg,
        reservedUploadEntryBits | reservedUploadMaskReg,
      )
      reservedUploadPending := false.B
    }
    
    when(streamDeq.fire && !uploadStart) {
      switch(uploadPhase) {

        // first, stream L2 access data into backing store
        is(uploadPhaseL2Accesses) {

          // 1 L2Access is transferred per 512-bit beat, so we can store 1 L2Access per beat
          when(uploadStoredCount < key.maxL2AccessEntries.U) {
            accessStore.write(uploadStoredCount, uploadBits)
            uploadStoredCount := uploadStoredCount + 1.U
          }.otherwise {
            // track to indicate when we overflow and can't store all L2 accesses
            uploadOverflow := true.B
          }

          // calculate which cycle and L2 subpartition in reservedSubPartitionsByCycle this L2 access corresponds to 
          val accessCycle = uploadBits.cycleCount.pad(64)
          val cycleDelta = accessCycle - reservedSubPartitionsBaseCycle
          val cycleBeforeBase = accessCycle < reservedSubPartitionsBaseCycle
          val cycleOutsideWindow = cycleBeforeBase || cycleDelta >= reservedSubPartitionEntries.U
          val wrappedOffset = cycleDelta(reservedSubPartitionIdxWidth - 1, 0)
          val reservationIdx =
            (reservedSubPartitionsBaseIdx + wrappedOffset)(reservedSubPartitionIdxWidth - 1, 0)
          val reservationMask = UIntToOH(
            uploadBits.mSubpartition(log2Ceil(reservedSubPartitionEntryWidth) - 1, 0),
            reservedSubPartitionEntryWidth,
          ).asUInt

          // read the current bitmask for the cycle and subpartition being reserved, so we can OR in the new reservation
          reservedUploadReadIdx := reservationIdx
          reservedUploadReadEn := true.B
          reservedUploadIdxReg := reservationIdx
          reservedUploadMaskReg := reservationMask
          reservedUploadPending := true.B

          when(cycleOutsideWindow) {
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

    target.accessStoredCount := uploadStoredCount
    target.trafficComplete := trafficComplete
    target.minIssueCycle := minIssueCycle
    target.blockedWarpBitmapReady := blockedWarpUploadDone
    target.uploadDone := uploadDone
    target.uploadOverflow := uploadOverflow

    val minIssueCycle = RegInit(0.U(32.W))

    // respond to target query as to whether warp is blocked by accessing blocked warp bitmap
    val blockedWarpQueryWordIdx =
      target.blockedWarpQueryIdx(BlockedWarpBitmap.indexBits - 1, BlockedWarpBitmap.streamBeatOffsetBits)
    val blockedWarpQueryBitIdx =
      target.blockedWarpQueryIdx(BlockedWarpBitmap.streamBeatOffsetBits - 1, 0)
    val blockedWarpQueryRespReg = RegInit(false.B)

    when(target.blockedWarpQueryEn) {
      blockedWarpQueryRespReg := blockedWarpUploadDone &&
        blockedWarpBitmap(blockedWarpQueryWordIdx)(blockedWarpQueryBitIdx)
    }
    target.blockedWarpQueryResp := blockedWarpQueryRespReg
    target.blockedWarpQueryRespValid := RegNext(target.blockedWarpQueryEn, false.B)

    // target drives accessReadAddr and accessReadEn to request L2 access data from backing store
    val accessReadData = accessStore.read(target.accessReadAddr, target.accessReadEn)
    val accessReadDataValid = RegNext(target.accessReadEn, false.B)
    // return L2 access data requested by the Traffic Generator from the backing store
    target.accessReadData := accessReadData
    target.accessReadDataValid := accessReadDataValid

    val targetReset = fire && hPort.hBits.reset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
      streamSource := streamSourceIdle
      streamBeatIdx := 0.U
      reservedStreamReadPending := false.B
      reservedStreamDataValid := false.B
      reservedStreamStartPending := false.B
      reservedUploadPending := false.B
      completedBundleIds.foreach(_ := 0.U)
      completedBundleCount := 0.U
      completedBundleIdsValid := true.B
      completedBundleCountValid := true.B
    }

    val trafficComplete = Wire(Bool())

    /////////// MMIO registers for bridge driver interaction ///////////

    // for target program to trigger traffic generation
    genROReg(target.startTrafficGen, "start_trafficgen")

    // for bridge driver to read to determine if target is still running the previous traffic pattern
    genROReg(uploadActive || target.targetBusy, "target_busy")

    // bridge driver toggles to pause/resume target while generating traffic patterns 
    Pulsify(genWORegInit(pauseTarget, "pause_target", false.B), pulseLength = 1)

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

    genROReg(uploadDone, "upload_done")
    genROReg(uploadOverflow, "upload_overflow")
    genROReg(blockedWarpUploadDone, "blocked_warp_upload_done")

    // bridge driver writes the min_issue_cycle for the traffic generator to stop at
    genWORegInit(minIssueCycle, "min_issue_cycle", 0.U)

    // when completed bundle IDs or count are available, bridge driver can read them
    genROReg(completedBundleIdsValid, "completed_bundle_ids_valid")
    genROReg(completedBundleCountValid, "completed_bundle_count_valid")

    // generate register definitions for the bridge driver to interact with 
    
    
    Pulsify(genWORegInit(trafficComplete, "traffic_complete", false.B), pulseLength = 1)
    Pulsify(genWORegInit(readCompletedBundleIds, "read_completed_bundle_ids", false.B), pulseLength = 1)
    
    genROReg(target.currentCycleAfterIssue, "current_cycle_after_issue")
    
    
    
    
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
