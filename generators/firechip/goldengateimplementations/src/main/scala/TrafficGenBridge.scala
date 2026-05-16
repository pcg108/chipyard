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

    /*
     * Cycle-indexed L2 subpartition reservation table, the snapshot stream
     * state used by the bridge driver, and the reserved subpartition update/clear path
     * driven by uploaded accesses (to populate) and the traffic generator (to depopulate).
     */

    // assuming we have an 8192 cycle window, and up to 256 L2 subpartitions
    val reservedSubPartitionEntries = 8192
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

    // structure to hold a reserved status for a subpartition at a given cycle
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

    // transform absolute cycle number into index and validity (in the window) for reservedSubPartitionsByCycle based on base index and cycle
    def reservationWindowLookup(cycle: UInt): (Bool, UInt) = {
      val cycle64 = cycle.pad(64)
      val cycleDelta = cycle64 - reservedSubPartitionsBaseCycle
      val cycleBeforeBase = cycle64 < reservedSubPartitionsBaseCycle
      val cycleOutsideWindow = cycleBeforeBase || cycleDelta >= reservedSubPartitionEntries.U
      val wrappedOffset = cycleDelta(reservedSubPartitionIdxWidth - 1, 0)
      val reservationIdx = (reservedSubPartitionsBaseIdx + wrappedOffset)(reservedSubPartitionIdxWidth - 1, 0)
      (!cycleOutsideWindow, reservationIdx)
    }

    // trigger for bridge driver to read reservedSubPartitionsByCycle
    val readReservedSubPartitions = Wire(Bool())

    // completedBundleIds will be streamed back from traffic generator to bridge driver.
    // Each 512-bit beat carries eight 64-bit bundle IDs.
    val completedBundleIdBeats = CompletedBundleIds.beats
    val completedBundleBeatIdxWidth = log2Ceil(completedBundleIdBeats)

    // Send one 256-bit entry per 512-bit beat and leave the upper half zeroed so
    // the snapshot path only needs a single SyncReadMem read port.
    val reservedSubPartitionBeats = reservedSubPartitionEntries
    val streamBeatIdxWidth = log2Ceil(math.max(math.max(reservedSubPartitionBeats, key.maxL2AccessEntries), completedBundleIdBeats) + 1)
    val streamBeatIdx = RegInit(0.U(streamBeatIdxWidth.W))

    // indicates that we have issued a read to the reserved subpartition SyncReadMem and are waiting for data to return
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
    // padded stream bits (upper 256 bits are 0, lower bits are from SyncReadMem) to send to bridge driver
    val reservedStreamBits = Cat(0.U(reservedSubPartitionEntryWidth.W), reservedStreamEntryReg)

    // buffer subpartition reservation updates from both uploads and reservation clear requests,
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

    // traffic generator indicates that it has advance its cycle, so old reservations can be cleared
    val reservationWindowAdvanceCycle = target.reservationWindowAdvanceCycle
    val reservationWindowAdvanceEn = target.reservationWindowAdvanceEn
    // need one extra bit to represent 8192 entries
    val reservationClearSweepCountWidth = reservedSubPartitionIdxWidth + 1 
    // current physical ring buffer index being cleared 
    val reservationClearSweepIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W)) 
    // how many entries we still need to clear 
    val reservationClearSweepRemaining = RegInit(reservedSubPartitionEntries.U(reservationClearSweepCountWidth.W)) 
    // sweep-in-progress signal 
    val reservationClearSweepActive = reservationClearSweepRemaining =/= 0.U


    /*
      * target issued accesses are buffered into issuedAccssWritebackStore for logging in the GPU model
    */

    // SyncReadMem to hold target-issued L2 accesses that are written back by target after issue 
    val issuedAccessWritebackStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val issuedAccessWritebackCount = RegInit(0.U(32.W))
    val issuedAccessWritebackIdx = RegInit(0.U(32.W))

    val doIssuedAccessWriteback = target.issuedAccessWriteback.valid && fire &&
      issuedAccessWritebackIdx < key.maxL2AccessEntries.U
    target.issuedAccessWriteback.ready := doIssuedAccessWriteback
    when(doIssuedAccessWriteback) {
      issuedAccessWritebackStore.write(issuedAccessWritebackIdx, target.issuedAccessWriteback.bits)
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
    // staging register to hold issued access read from SyncReadMem before streaming to bridge driver
    val issuedAccessStreamEntryReg = Reg(new L2Access)
    // indicates that we have issued a read to the issued access SyncReadMem and are waiting for data to return
    val issuedAccessStreamReadPending = RegInit(false.B)
    val issuedAccessStreamDataValid = RegInit(false.B)
    
    /*
      * completed bundle IDs are written by the target during/after traffic generator, and used for scheduling next batch of accesses
    */
    
    // Store completed bundle IDs completed by target, to be streamed back to the bridge driver.
    val completedBundleIds = SyncReadMem(completedBundleIdBeats, Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
    val completedBundleIdsValid = RegInit(true.B)
    val completedBundleCount = RegInit(0.U(CompletedBundleIds.countWidth.W))
    val completedBundleCountValid = RegInit(true.B)

    when(target.completedBundleCountWriteEn) {
      completedBundleCount := target.completedBundleCountWriteData
      completedBundleCountValid := true.B
    }
    when(target.completedBundleIdWriteEn) {
      val beatIdx = target.completedBundleIdWriteIdx >> log2Ceil(CompletedBundleIds.idsPerBeat)
      val laneIdx = target.completedBundleIdWriteIdx(log2Ceil(CompletedBundleIds.idsPerBeat) - 1, 0)
      val writeData = Wire(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
      writeData.foreach(_ := target.completedBundleIdWriteData)
      completedBundleIds.write(
        beatIdx(completedBundleBeatIdxWidth - 1, 0),
        writeData,
        UIntToOH(laneIdx, CompletedBundleIds.idsPerBeat).asBools,
      )
      completedBundleIdsValid := true.B
    }

    // trigger for bridge driver to read completedBundleIds 
    val readCompletedBundleIds = Wire(Bool())

    val completedBundleStreamReadIdx = WireDefault(0.U(completedBundleBeatIdxWidth.W))
    val completedBundleStreamReadEn = WireDefault(false.B)
    val completedBundleStreamEntryBits = completedBundleIds.read(
      completedBundleStreamReadIdx,
      completedBundleStreamReadEn,
    )
    val completedBundleStreamEntryReg = Reg(Vec(CompletedBundleIds.idsPerBeat, UInt(64.W)))
    val completedBundleStreamReadPending = RegInit(false.B)
    val completedBundleStreamDataValid = RegInit(false.B)
    val completedBundleStreamBits = Cat(completedBundleStreamEntryReg.reverse)

    /*
     * Following is the stream interface for the bridge module to send data back to bridge driver. This includes:
     * 1. reservedSubpartitionsByCycle
     * 2. completedBundleIds (after traffic generation)
     * 3. issuedAccessWriteback (L2 accesses issued by traffic generator and written back by target after issue)
    */

    // indicate which data we are streaming to the bridge driver 
    val (
      streamSourceIdle ::
      streamSourceReservedSubPartitions ::
      streamSourceCompletedBundleIds ::
      streamSourceIssuedAccessWriteback ::
      Nil
    ) = Enum(4)
    val streamSource = RegInit(streamSourceIdle)

    // set the stream payload valid based on the current stream source
    val streamPayloadValid = MuxLookup(
      streamSource,
      reservedStreamDataValid,
      Seq(
        streamSourceCompletedBundleIds -> completedBundleStreamDataValid,
        streamSourceIssuedAccessWriteback -> issuedAccessStreamDataValid,
      ),
    )

    // stream bits are either the packed reservedSubPartitionsByCycle entry, packed completed bundle IDs, or 1 issued access entry 
    streamEnq.bits := MuxLookup(
      streamSource,
      reservedStreamBits,
      Seq(
        streamSourceCompletedBundleIds -> completedBundleStreamBits,
        streamSourceIssuedAccessWriteback -> L2Access.pack(issuedAccessStreamEntryReg),
      ),
    )

    // set valid for stream enqueue based on stream source and payload valid
    val streamActive = streamSource =/= streamSourceIdle
    streamEnq.valid := streamActive && streamPayloadValid

    // fire signal for stream
    val doStreamEnq = streamEnq.valid && streamEnq.ready

    // determine when we are on the last beat of this stream source 
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
    // however, it is necessary to ensure that the data is stable when streaming to bridge driver 
    when(reservedStreamReadPending) {
      // latch read data from SyncReadMem and indicate valid 
      reservedStreamEntryReg := reservedStreamEntryBits
      reservedStreamReadPending := false.B
      reservedStreamDataValid := true.B
    }
    when(issuedAccessStreamReadPending) {
      // latch read data from SyncReadMem and indicate valid 
      issuedAccessStreamEntryReg := issuedAccessStreamEntryBits
      issuedAccessStreamReadPending := false.B
      issuedAccessStreamDataValid := true.B
    }
    when(completedBundleStreamReadPending) {
      completedBundleStreamEntryReg := completedBundleStreamEntryBits
      completedBundleStreamReadPending := false.B
      completedBundleStreamDataValid := true.B
    }

    // write the updated entry back to the reservedSubPartitionsByCycle table when we get a response from the SyncReadMem for a reservation update read, 
    // and then clear the pending flag to allow next updates
    when(reservationUpdateReadPending) {

      // compute the update entry by applying the update mask to the read bits depending on if this was a set (upload) or clear (access issue)
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

   
    // bridge driver triggers to start streaming reservedSubPartitionsByCycle
    when(readReservedSubPartitions) {
      reservedStreamStartPending := true.B
    }

    when(reservedStreamStartPending && !streamActive && !reservationClearSweepActive) {
      // set initial conditions for when we start streaming reserved subpartition data 

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
      // set initial conditions for when we start streaming completed bundle IDs
      streamSource := streamSourceCompletedBundleIds
      streamBeatIdx := 0.U
      completedBundleStreamReadIdx := 0.U
      completedBundleStreamReadEn := true.B
      completedBundleStreamReadPending := true.B
      completedBundleStreamDataValid := false.B
    }.elsewhen(readIssuedAccessWriteback && issuedAccessWritebackCount =/= 0.U && !streamActive) {
      // set initial conditions for when we start streaming issued access writebacks
      streamSource := streamSourceIssuedAccessWriteback
      streamBeatIdx := 0.U
      issuedAccessStreamReadIdx := 0.U
      issuedAccessStreamReadEn := true.B
      issuedAccessStreamReadPending := true.B
      issuedAccessStreamDataValid := false.B
    }.elsewhen(doStreamEnq) {
      // stream has started 

      when(streamSource === streamSourceCompletedBundleIds) {
        when(streamLastBeat) {
          // reset on last beat 
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
          completedBundleStreamDataValid := false.B
        }.otherwise {
          val nextBeatIdx = streamBeatIdx + 1.U
          streamBeatIdx := nextBeatIdx
          completedBundleStreamReadIdx := nextBeatIdx(completedBundleBeatIdxWidth - 1, 0)
          completedBundleStreamReadEn := true.B
          completedBundleStreamReadPending := true.B
          completedBundleStreamDataValid := false.B
        }
      }.elsewhen(streamSource === streamSourceIssuedAccessWriteback) {
        when(streamLastBeat) {
          // reset on last beat
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
          issuedAccessStreamDataValid := false.B
        }.otherwise {
          // for issued accesses, increment index and issue next read to SyncReadMem for next access
          val nextBeatIdx = streamBeatIdx + 1.U
          streamBeatIdx := nextBeatIdx
          issuedAccessStreamReadIdx := nextBeatIdx
          issuedAccessStreamReadEn := true.B
          issuedAccessStreamReadPending := true.B

          issuedAccessStreamDataValid := false.B
        }
      }.otherwise { // streamSource == streamSourceReservedSubPartitions
        when(streamLastBeat) {
          // reset on last beat 
          streamSource := streamSourceIdle
          streamBeatIdx := 0.U
          reservedStreamDataValid := false.B
        }.otherwise {
          val nextBeatIdx = streamBeatIdx + 1.U
          streamBeatIdx := nextBeatIdx

          // latch next read from SyncReadMem 
          reservedStreamReadIdx := nextBeatIdx
          reservedStreamReadEn := true.B
          reservedStreamReadPending := true.B

          // only stream data once it is latched from SyncReadMem into a register
          reservedStreamDataValid := false.B
        }
      }
    }
    
    /*
     * Setup for uploaded L2 accesses from the bridge driver.
     *
     * Accesses are appended into cycle-indexed linked-list buckets. The same
     * stream phase later accepts the blocked-warp bitmap
     *
     * The L2 access store is managed in multiple parts:
      * 1. accessStore[idx]: SyncReadMem that holds the L2 accesses indexed by an opaque access ID. 
      * 2. cycleHead[cycleIdx]: first accessStore index for a given cycle (i.e. head of the linked list for that cycle's bucket)
      * 3. cycleTail[cycleIdx]: last accessStore index for a given cycle (i.e. tail of the linked list for that cycle's bucket)
      * 4. nextPtr[idx]: SyncReadMem that holds the index of the next access in the same cycle bucket as the current idx 
      * 5. freeList: reusable accessStore indices 
    *
     * Reading:
      * 1. get idx = cycleHead[cycleIdx] to read the first access for that cycle
      * 2. read accessStore[idx]
      * 3. get next_idx = nextPtr[idx] to read next access for cycle bucket
      * 4. repeat 2-3 until next_idx indicates end of list (e.g. invalid index)
    *
    * Writing:
      * 1. get newIdx from freeList 
      * 2. write accessStore[newIdx] = newAccess
      * 3. set nextPtr[newIdx] = invalid
      * 4. if list was empty, set cycleHead[cycleIdx] = cycleTail[cycleIdx] = newIdx
      * 5. Otherwise, set nextPtr[cycleTail[cycleIdx]] = newIdx and cycleTail[cycleIdx] = newIdx
    *
    * Clearing:
      * when a cycle is complete, we walk the linked list for that cycle and free the indices back to freeList
    *
    * Ring Buffer management:
      * cycleHead and cycleTail are managed ring buffers, with a baseCycle and baseIdx the same as reservedSubPartitionsByCycle
     *
    */

    //// store vector of L2 accesses from bridge driver to a backing store in the bridge module
    val accessStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val accessStoreCount = RegInit(0.U(32.W))
    val accessStoreMaxCycle = RegInit(0.U(64.W))
    val accessStoreHasEntries = RegInit(false.B)

    val accessIdxWidth = log2Ceil(key.maxL2AccessEntries + 1)
    val invalidAccessIdx = key.maxL2AccessEntries.U(accessIdxWidth.W)
    
    // linked list poiners and head/tail pointers for cycle buckets
    val nextPtr = SyncReadMem(key.maxL2AccessEntries, UInt(accessIdxWidth.W))
    val cycleHead = SyncReadMem(reservedSubPartitionEntries, UInt(accessIdxWidth.W))
    val cycleTail = SyncReadMem(reservedSubPartitionEntries, UInt(accessIdxWidth.W))

    val accessStoreWriteEn = WireDefault(false.B)
    val accessStoreWriteAddr = WireDefault(0.U(accessIdxWidth.W))
    val accessStoreWriteData = Wire(new L2Access)
    accessStoreWriteData := 0.U.asTypeOf(new L2Access)
    val nextPtrWriteEn = WireDefault(false.B)
    val nextPtrWriteAddr = WireDefault(0.U(accessIdxWidth.W))
    val nextPtrWriteData = WireDefault(0.U(accessIdxWidth.W))
    val cycleHeadWriteEn = WireDefault(false.B)
    val cycleHeadWriteAddr = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val cycleHeadWriteData = WireDefault(0.U(accessIdxWidth.W))
    val cycleTailWriteEn = WireDefault(false.B)
    val cycleTailWriteAddr = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val cycleTailWriteData = WireDefault(0.U(accessIdxWidth.W))

    // free list of access indices to reuse after clearing 
    val freeList = SyncReadMem(key.maxL2AccessEntries, UInt(accessIdxWidth.W))
    val freeListCount = RegInit(0.U(accessIdxWidth.W))
    val nextUnusedAccessIdx = RegInit(0.U(accessIdxWidth.W))

    // indicates whether this cycle has any accesses in the store
    // cheap way to determine if a cycle bucket is empty without needing to read cycleHead first 
    val cycleValid = RegInit(VecInit(Seq.fill(reservedSubPartitionEntries)(false.B)))
    

    // helper function to mark that we have stored an access for a given cycle, and update max cycle if needed
    def markAccessStored(cycle: UInt): Unit = {
      when(!accessStoreHasEntries || cycle > accessStoreMaxCycle) {
        accessStoreMaxCycle := cycle
      }
      accessStoreHasEntries := true.B
    }

    // helper function to wrap cycle index based on the ring buffer size
    def wrapCycleIdx(idx: UInt): UInt =
      Mux(idx === (reservedSubPartitionEntries - 1).U, 0.U, idx + 1.U)

    // state machine to retire completed cycle buckets and free their access store entries
    // state machine runs when reservation/access window advances and old cycle buckets need to be cleared
    val (
      accessRetireIdle ::
      accessRetireReadHead ::
      accessRetireReadNext ::
      accessRetireFreeEntry ::
      accessRetireClearBucket ::
      Nil
    ) = Enum(5)

    // current state of retire FSM
    val accessRetireState = RegInit(accessRetireIdle)
    // physical ring buffer address being retired 
    val accessRetireCycleIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W))
    // how many cycle buckets still need to retire 
    val accessRetireRemaining = RegInit(0.U((reservedSubPartitionIdxWidth + 1).W))
    // current access index being retired, used to read access enty and free it 
    val accessRetireEntryIdx = Reg(UInt(accessIdxWidth.W))
    // indicate retirement is ongoing 
    val accessRetireActive = accessRetireState =/= accessRetireIdle || accessRetireRemaining =/= 0.U
    // for current cycle bucket, find first access-store entry in bucket linked list 
    val accessRetireHeadReadIdx = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val accessRetireHeadReadEn = WireDefault(false.B)
    val accessRetireHeadReadBits = cycleHead.read(accessRetireHeadReadIdx, accessRetireHeadReadEn)
    // use nextPtr to walk the linked list of access-store entries for this cycle bucket, and get next entry
    val accessRetireNextReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val accessRetireNextReadEn = WireDefault(false.B)
    val accessRetireNextReadBits = nextPtr.read(accessRetireNextReadIdx, accessRetireNextReadEn)
    // new logical base cycle and base index after advance
    val pendingBaseCycle = RegInit(0.U(64.W))
    val pendingBaseIdx = RegInit(0.U(reservedSubPartitionIdxWidth.W))
    val baseAdvancePending = RegInit(false.B)

    /*
     * Setup for uploaded blocked warps.
    */

    // store the warps that are currently blocked in the scheduler, for the traffic generator to return on if it unblocks the scheduler
    // stored as a vec of beats to make it easier to stream
    val blockedWarpBitmap = RegInit(VecInit(Seq.fill(BlockedWarpBitmap.streamBeatCount)(0.U(L2Access.streamWidthBits.W))))
    
    // signal to start upload process from bridge driver, which includes both L2 accesses and blocked warp bitmap
    val uploadStart = Wire(Bool())
    // written by bridge driver to indicate how many L2 accesses we are uploading
    val uploadCount = RegInit(0.U(32.W))
    // count of L2 accesses uploaded so far
    val uploadRecvCount = RegInit(0.U(32.W))
    // indicate currently uploading L2 accesses and blocked warps
    val uploadActive = RegInit(false.B)

    // count beats completed for blocked warp bitmap
    val blockedWarpBeatCount = RegInit(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))

    // upload completion and overflow signals 
    val uploadDone = RegInit(true.B)
    val blockedWarpUploadDone = RegInit(false.B)
    val uploadOverflow = RegInit(false.B)

    // state machine states for upload process (IDLE, L2 accesses, Blocked warp bitmap)
    val uploadPhaseIdle :: uploadPhaseL2Accesses :: uploadPhaseBlockedWarpBitmap :: Nil = Enum(3)
    val uploadPhase = RegInit(uploadPhaseIdle)

    // reset the target-side query/read response state when a fresh upload begins
    val accessReadReset = WireDefault(false.B)

    // initial state for uploading
    when(uploadStart) {
      uploadActive := true.B
      uploadDone := uploadCount === 0.U
      uploadRecvCount := 0.U
      uploadOverflow := false.B

      blockedWarpUploadDone := false.B
      blockedWarpBeatCount := 0.U

      accessReadReset := true.B
      issuedAccessWritebackIdx := 0.U
      issuedAccessWritebackCount := 0.U
      uploadPhase := Mux(uploadCount === 0.U, uploadPhaseBlockedWarpBitmap, uploadPhaseL2Accesses)
      blockedWarpBitmap.foreach(_ := 0.U)
    }

    // unpack the incoming stream beat into L2 access information
    val uploadBits = L2Access.unpack(streamDeq.bits)

    // when uploading L2 accesses, also update the reserved sub-partition table based on the sub-partition of each access 

    // determine if the uploaded access cycle is in our current window, and get the index for it in reservedSubPartitionsByCycle
    val (uploadReservationInWindow, uploadReservationIdx) = reservationWindowLookup(uploadBits.cycleCount)
    // get the one-hot mask for the sub-partition of this access to update the reservation table
    val uploadReservationMask = reservationMaskForSubpartition(uploadBits.mSubpartition)

    // cycle bucket index used to read cycleTail SyncReadMem
    val uploadAppendTailReadIdx = WireDefault(0.U(reservedSubPartitionIdxWidth.W))
    val uploadAppendTailReadEn = WireDefault(false.B)

    // returned current tail access index for this cycle bucket 
    val uploadAppendTailReadBits = cycleTail.read(uploadAppendTailReadIdx, uploadAppendTailReadEn)
    // track that a cycleTail read has been issued and append operation is waiting for SyncReadMem result
    val uploadAppendTailReadPending = RegInit(false.B)

    // latch cycle bucket index while waiting for cycleTail read to return 
    val uploadAppendCycleIdxReg = Reg(UInt(reservedSubPartitionIdxWidth.W))

    // latch newly allocated accessStore index while waiting for cycleTail read to return (new linked-list node)
    val uploadAppendNewIdxReg = Reg(UInt(accessIdxWidth.W))

    // latch logical cycle count of uploaded access 
    val uploadAppendCycleCountReg = Reg(UInt(64.W))

    // indicate current uploaded L2 access has completed
    val uploadAccessComplete = WireDefault(false.B)
    // free list has space for new access
    val freeListHasEntry = freeListCount =/= 0.U
    // whether bridge can allocate storage for this uploaded access 
    val canAllocateAccessIdx = freeListHasEntry || nextUnusedAccessIdx < key.maxL2AccessEntries.U

    // free list read index and enable for allocating an accessStore entry for uploaded access
    val freeListReadIdx = WireDefault(0.U(accessIdxWidth.W))
    val freeListReadEn = WireDefault(false.B)
    val freeListReadBits = freeList.read(freeListReadIdx, freeListReadEn)
    // track that we have issued free list read and waiting for result 
    val uploadAllocatePending = RegInit(false.B)
    // latch the uploaded L2 access while waiting for free list read to return
    val uploadAllocateBitsReg = Reg(new L2Access)
    // latch cycle bucket index for uploaded access while waiting for free-list read 
    val uploadAllocateCycleIdxReg = Reg(UInt(reservedSubPartitionIdxWidth.W))

    // check if we are busy updating reservation table (doing RMW read, reservation clear sweep, retiring accesses, or advancing base index/cycle)
    val reservationUpdaterBusy = reservationUpdateReadPending || reservationClearSweepActive || accessRetireActive || baseAdvancePending
    val canAcceptUploadReservation = !reservationUpdaterBusy
    streamDeq.ready := uploadActive && Mux(
      uploadPhase === uploadPhaseL2Accesses,
      canAcceptUploadReservation && !uploadAllocatePending && !uploadAppendTailReadPending && !accessRetireActive && !baseAdvancePending,
      true.B,
    )

    // get the scalar reservation clear stream to update the reservation table
    val clearStreamValid = target.reservationClear.valid && fire
    val clearStreamInWindow = Wire(Bool())
    val clearStreamIdx = Wire(UInt(reservedSubPartitionIdxWidth.W))
    val clearStreamMask = Wire(UInt(reservedSubPartitionEntryWidth.W))
    val (clearWindowOk, clearReservationIdx) = reservationWindowLookup(target.reservationClear.bits.cycle)
    clearStreamInWindow := clearWindowOk
    clearStreamIdx := clearReservationIdx
    clearStreamMask := reservationMaskForSubpartition(target.reservationClear.bits.subpartition)

    // if we are updating reservation for upload, it is a set, otherwise it is a clear 
    val issueUploadReservation = streamDeq.fire && uploadPhase === uploadPhaseL2Accesses &&
      uploadReservationInWindow && canAllocateAccessIdx
    val issueClearReservation = !reservationUpdaterBusy && !issueUploadReservation && clearStreamValid
    target.reservationClear.ready := issueClearReservation

    //// do reservation table updates for uploads and clears (used up where we get reservationUpdateReadBits)
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
    when(issueClearReservation && clearStreamInWindow) {
      reservationUpdateReadIdx := clearStreamIdx
      reservationUpdateReadEn := true.B
      reservationUpdateReqReg.idx := clearStreamIdx
      reservationUpdateReqReg.mask := clearStreamMask
      reservationUpdateReqReg.setNotClear := false.B
      reservationUpdateReadPending := true.B
    }

    /*
      * Upload L2 access path:
        * 1. if we can reuse a previously freed accessStore entry from free list, latch the bits and cycle index for this access
        *    and issue a read to free list to get the accessStore index we can use for this access
        * 2. if we can't reuse from free list, use nextUnusedAccessIdx as the accessStore index for this access, and increment nextUnusedAccessIdx
        * 3. if this is the first access for this cycle bucket, write directly to cycle head and tail; 
              otherwise, append to the existing linked list for this cycle bucket by reading the current tail from cycleTail SyncReadMem, 
              writing the new access to accessStore, writing the next pointer of current tail to point to the new access, 
              and updating the tail pointer to the new access
    */
    
    when(streamDeq.fire && !uploadStart) { // !uploadStart to begin on the cycle after initialization cycle so we have everything else latched
      switch(uploadPhase) {

        // first, stream L2 access data into backing store
        is(uploadPhaseL2Accesses) {

          // 1 L2Access is transferred per 512-bit beat; valid-window accesses
          // are appended into the linked list for their cycle bucket.
          when(uploadReservationInWindow && canAllocateAccessIdx) {
            when(freeListHasEntry) {
              // this path is to reuse a previously freed accessStore entry from free list 
              uploadAllocateBitsReg := uploadBits // latch the stream L2 access bits while waiting for free list read
              uploadAllocateCycleIdxReg := uploadReservationIdx // latch the cycle bucket index for this access while waiting for free list read
              // get the top entry from free list
              freeListReadIdx := freeListCount - 1.U 
              freeListReadEn := true.B
              // complete through uploadAllocatePending
              uploadAllocatePending := true.B
            }.otherwise {
              // use a never-before used slot (free list is empty but store has capacity, so allocate next fresh index from nextUnusedAccessIdx)
              // no free list memory read required
              val newIdx = nextUnusedAccessIdx
              accessStoreWriteEn := true.B
              accessStoreWriteAddr := newIdx
              accessStoreWriteData := uploadBits
              nextPtrWriteEn := true.B
              nextPtrWriteAddr := newIdx
              nextPtrWriteData := invalidAccessIdx
              nextUnusedAccessIdx := nextUnusedAccessIdx + 1.U

              when(!cycleValid(uploadReservationIdx)) {
                // if this is the first access for this cycle bucket, write directly to cycle head and tail
                cycleHeadWriteEn := true.B
                cycleHeadWriteAddr := uploadReservationIdx
                cycleHeadWriteData := newIdx
                cycleTailWriteEn := true.B
                cycleTailWriteAddr := uploadReservationIdx
                cycleTailWriteData := newIdx
                cycleValid(uploadReservationIdx) := true.B
                accessStoreCount := accessStoreCount + 1.U
                markAccessStored(uploadBits.cycleCount)
                uploadAccessComplete := true.B
              }.otherwise {
                // otherwise, append to the existing linked list for this cycle bucket 
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

    // path for reusing a previously freed accessStore entry from free list after reading the free list entry
    when(uploadAllocatePending) {
      // get the new accessStore index from free list read
      val newIdx = freeListReadBits
      // write the uploaded access into the access store at the allocated index
      accessStoreWriteEn := true.B
      accessStoreWriteAddr := newIdx
      accessStoreWriteData := uploadAllocateBitsReg
      // set next pointer for this new entry to invalid to indicate end of list
      nextPtrWriteEn := true.B
      nextPtrWriteAddr := newIdx
      nextPtrWriteData := invalidAccessIdx
      // decrement free list count to indicate we have used one entry from free list
      freeListCount := freeListCount - 1.U

      when(!cycleValid(uploadAllocateCycleIdxReg)) {
        // if this is the first access for this cycle bucket, write directly to cycle head and tail
        cycleHeadWriteEn := true.B
        cycleHeadWriteAddr := uploadAllocateCycleIdxReg
        cycleHeadWriteData := newIdx
        cycleTailWriteEn := true.B
        cycleTailWriteAddr := uploadAllocateCycleIdxReg
        cycleTailWriteData := newIdx
        cycleValid(uploadAllocateCycleIdxReg) := true.B
        accessStoreCount := accessStoreCount + 1.U
        markAccessStored(uploadAllocateBitsReg.cycleCount)
        uploadAccessComplete := true.B
      }.otherwise {
        // otherwise, append to the existing linked list for this cycle bucket 
        uploadAppendCycleIdxReg := uploadAllocateCycleIdxReg
        uploadAppendNewIdxReg := newIdx
        uploadAppendCycleCountReg := uploadAllocateBitsReg.cycleCount
        uploadAppendTailReadIdx := uploadAllocateCycleIdxReg
        uploadAppendTailReadEn := true.B
        uploadAppendTailReadPending := true.B
      }

      uploadAllocatePending := false.B
    }

    // once we get the current tail index, we can update the current tail's next pointer to point to the new entry, 
    // and then update the tail pointer for this cycle bucket to the new entry
    when(uploadAppendTailReadPending) {
      nextPtrWriteEn := true.B
      nextPtrWriteAddr := uploadAppendTailReadBits
      nextPtrWriteData := uploadAppendNewIdxReg
      cycleTailWriteEn := true.B
      cycleTailWriteAddr := uploadAppendCycleIdxReg
      cycleTailWriteData := uploadAppendNewIdxReg
      accessStoreCount := accessStoreCount + 1.U
      markAccessStored(uploadAppendCycleCountReg)
      uploadAppendTailReadPending := false.B
      uploadAccessComplete := true.B
    }

    // track the count of uploaded accesses and transition to blocked warp bitmap phase when done
    when(uploadAccessComplete) {
      uploadRecvCount := uploadRecvCount + 1.U
      when(uploadRecvCount + 1.U === uploadCount) {
        uploadPhase := uploadPhaseBlockedWarpBitmap
        uploadDone := true.B
      }
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

    // upon receiving a read request, first check if requested cycle is in the window, and if so, get the head of the linked list for that cycle
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
    // get the head access entry for this cycle bucket, and indicate we have a pending read of an access entry
    // the next pointer read is done in parallel
    when(accessReadHeadPending) {
      accessReadEntryIdx := accessReadHeadBits
      accessReadEntryEn := true.B
      accessReadNextIdxReg := accessReadHeadBits
      accessReadHeadPending := false.B
      accessReadEntryPending := true.B
    }
    // latch the access entry data for the head and the pointer to the next entry in the bucket linked list 
    // and indicate valid to the target 
    when(accessReadEntryPending) {
      accessReadDataReg := accessReadData
      accessReadNextIdxReg := accessReadNextPtr
      accessReadDataValidReg := true.B
      accessReadEntryPending := false.B
    }
    // when the target accepts the data, issue the next read for the next entry, or indicate the bucket is done 
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
    // when the bucket is done and the target accepts the bucket-done signal, we can mark ready for the next read request
    when(accessReadBucketDoneFire) {
      accessReadBucketDoneReg := false.B
      accessReadReadyReg := true.B
    }
    // reset signals 
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

    // respond to blocked warp query by looking up the corresponding bit in the blocked warp bitmap, 
    // but only after the upload is done and the bitmap is valid; hold the response until the target acknowledges it
    when(blockedWarpQueryReq) {
      blockedWarpQueryReadyReg := false.B
      blockedWarpQueryPending := true.B
      blockedWarpQueryRespValidReg := false.B
      blockedWarpQueryRespReg := blockedWarpUploadDone && blockedWarpBitmap(blockedWarpQueryWordIdx)(blockedWarpQueryBitIdx)
    }
    // hold the response until the target acknowledges it, and then mark ready for the next query
    when(blockedWarpQueryRespReturn) {
      blockedWarpQueryPending := false.B
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
      // calculate the new base cycle and base index after advancing the window
      val nextReservationBaseCycle = reservationWindowAdvanceCycle + 1.U
      val cycleDelta = nextReservationBaseCycle - reservedSubPartitionsBaseCycle
      val clampedDelta = Mux(cycleDelta >= reservedSubPartitionEntries.U, reservedSubPartitionEntries.U, cycleDelta)
      // compute next physical ring buffer index for reservation/access window base
      val nextBaseIdxWide = reservedSubPartitionsBaseIdx +& clampedDelta(reservedSubPartitionIdxWidth - 1, 0)
      pendingBaseIdx := Mux(
        nextBaseIdxWide >= reservedSubPartitionEntries.U,
        nextBaseIdxWide - reservedSubPartitionEntries.U,
        nextBaseIdxWide,
      )(reservedSubPartitionIdxWidth - 1, 0)
      pendingBaseCycle := nextReservationBaseCycle
      baseAdvancePending := true.B
      // track how many cycles need to be retired based on how much we are advancing the window, and start retiring from the current base index
      // for both accesses and reservation entries
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
      // sweep the reservation table for this cycle index to clear all reservations for the new window base cycle, 
      // then advance the sweep index and repeat until we have cleared for all cycles that are advancing out of the window
      reservationTableWriteIdx := reservationClearSweepIdx
      reservationTableWriteBits := 0.U
      reservationTableWriteEn := true.B
      reservationClearSweepIdx := wrapCycleIdx(reservationClearSweepIdx)
      reservationClearSweepRemaining := reservationClearSweepRemaining - 1.U
    }

    // retire old access buckets when reservation/access cycle window advances
    // walk each expired cycle bucket's linked list, return accessStore entries to free list
    // clear bucket head/tail metadata, and move to next bucket

    switch(accessRetireState) {
      is(accessRetireReadHead) {
        when(accessRetireRemaining =/= 0.U) {
          // issue a read of the head of linked list for this cycle bucket to get first access index 
          when(cycleValid(accessRetireCycleIdx)) {
            accessRetireHeadReadIdx := accessRetireCycleIdx
            accessRetireHeadReadEn := true.B
            accessRetireState := accessRetireReadNext
          }.otherwise {
            // if bucket is empty, move to clearing bucket state directly
            accessRetireState := accessRetireClearBucket
          }
        }.otherwise {
          accessRetireState := accessRetireIdle
        }
      }
      is(accessRetireReadNext) {
        // capture head entry index and issue read of nextPtr(head)
        accessRetireEntryIdx := accessRetireHeadReadBits
        accessRetireNextReadIdx := accessRetireHeadReadBits
        accessRetireNextReadEn := true.B
        accessRetireState := accessRetireFreeEntry
      }
      is(accessRetireFreeEntry) {
        // free current entry by writing index into free list and increment free list count
        when(freeListCount < key.maxL2AccessEntries.U) {
          freeList.write(freeListCount, accessRetireEntryIdx)
          freeListCount := freeListCount + 1.U
        }
        // decrement access store count
        when(accessStoreCount =/= 0.U) {
          accessStoreCount := accessStoreCount - 1.U
        }
        when(accessRetireNextReadBits === invalidAccessIdx) {
          // if bucket is done, move to clearing bucket state
          accessRetireState := accessRetireClearBucket
        }.otherwise {
          // move to next entry in linked list to free, and issue read for next entry index
          accessRetireEntryIdx := accessRetireNextReadBits
          accessRetireNextReadIdx := accessRetireNextReadBits
          accessRetireNextReadEn := true.B
          accessRetireState := accessRetireFreeEntry
        }
      }
      is(accessRetireClearBucket) {
        // clear bucket metadata (cycle head/tail and valid bit)
        cycleHeadWriteEn := true.B
        cycleHeadWriteAddr := accessRetireCycleIdx
        cycleHeadWriteData := invalidAccessIdx
        cycleTailWriteEn := true.B
        cycleTailWriteAddr := accessRetireCycleIdx
        cycleTailWriteData := invalidAccessIdx
        cycleValid(accessRetireCycleIdx) := false.B
        // advance to next bucket and decrement remaining count, or go idle if done
        accessRetireCycleIdx := wrapCycleIdx(accessRetireCycleIdx)
        accessRetireRemaining := accessRetireRemaining - 1.U
        accessRetireState := Mux(accessRetireRemaining === 1.U, accessRetireIdle, accessRetireReadHead)
      }
    }

    when(accessStoreWriteEn) {
      accessStore.write(accessStoreWriteAddr, accessStoreWriteData)
    }
    when(nextPtrWriteEn) {
      nextPtr.write(nextPtrWriteAddr, nextPtrWriteData)
    }
    when(cycleHeadWriteEn) {
      cycleHead.write(cycleHeadWriteAddr, cycleHeadWriteData)
    }
    when(cycleTailWriteEn) {
      cycleTail.write(cycleTailWriteAddr, cycleTailWriteData)
    }

    // once we have completed retiring accesses and clearing reservations for new base cycle, 
    // we can update the base index and cycle to advance the window
    when(baseAdvancePending && !accessRetireActive && !reservationClearSweepActive) {
      reservedSubPartitionsBaseIdx := pendingBaseIdx
      reservedSubPartitionsBaseCycle := pendingBaseCycle
      baseAdvancePending := false.B
    }

    // perform the update to the reservation table for either upload reservations or clear requests, 
    // based on the reservationUpdateReqReg that we set in the when statements above
    when(reservationTableWriteEn) {
      reservedSubPartitionsByCycle.write(
        reservationTableWriteIdx,
        reservationTableWriteBits,
      )
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
    // driver polls this MMIO register from the host side. Clear it only after
    // the target/DPI has actually accepted the next round; the bridge-driver
    // start pulse can happen while the target is still paused.
    val roundCompleteLatched = RegInit(false.B)
    when(target.roundStarted) {
      roundCompleteLatched := false.B
    }.elsewhen(target.roundComplete) {
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
      completedBundleStreamReadPending := false.B
      completedBundleStreamDataValid := false.B
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
      completedBundleCount := 0.U
      completedBundleIdsValid := true.B
      completedBundleCountValid := true.B
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
    genROReg(uploadActive || target.targetBusy || accessRetireActive || baseAdvancePending, "target_busy")
    genROReg(target.hasPendingWork, "has_pending_work")
    Pulsify(genWORegInit(trafficGenDonePulse, "trafficgen_done", false.B), pulseLength = 1)

    // bridge driver toggles to pause/resume target while generating traffic patterns 
    Pulsify(genWORegInit(pauseTarget, "pause_target", false.B), pulseLength = 1)

    // bridge driver pulses this when a freshly uploaded scheduling round is ready to issue
    Pulsify(genWORegInit(startRoundPulse, "start_round", false.B), pulseLength = 1)
    genROReg(currentRound(31, 0), "current_round_low")
    genROReg(currentRound(63, 32), "current_round_high")

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
