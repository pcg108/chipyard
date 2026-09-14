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
    val hPort = IO(HostPort(new TrafficGenBridgeTargetIO(key.numLanes, key.useRTL)))
    val target = hPort.hBits.trafficgen

    /*
     * Bridge-driver control and target clock gating.

      1. target asserts target.startTrafficGen for 1 cycle
          1.1 startTrafficGenLatched set to true (usage: read by bridge driver)
          1.2 boundaryPaused set to true (controls fire gating)
      2. boundaryPaused:
          2.1 Read in bridge driver as mmio_addrs.target_paused to transition out of IDLE
          2.2 Gates fire (!boundaryPaused)
      3. resumeTarget from mmio_addrs.resume_target sets startTrafficGenLatched and boundaryPaused false
     
     */
    val resumeTarget = RegInit(false.B)
    val boundaryPaused = RegInit(false.B)
    val accessReadTargetPaused = RegInit(false.B)
    val trafficGenDone = RegInit(false.B)
    val trafficGenDonePulse = Wire(Bool())
    trafficGenDonePulse := false.B

    val fire = hPort.toHost.hValid &&
      hPort.fromHost.hReady &&
      !boundaryPaused &&
      !accessReadTargetPaused

    // Latch the target's start doorbell and synchronously stop the target on the
    // token that contains it. The driver explicitly resumes after it has
    // prepared the first round, so host timing cannot change the boundary.
    val startTrafficGenLatched = RegInit(false.B)
    when(resumeTarget) {
      startTrafficGenLatched := false.B
      boundaryPaused := false.B
    }
    when(fire && target.startTrafficGen) {
      startTrafficGenLatched := true.B
      trafficGenDone := false.B
      boundaryPaused := true.B
    }
    when(trafficGenDonePulse) {
      trafficGenDone := true.B
    }
    target.trafficGenDone := trafficGenDone


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
    val issuedBatchPackedLanes = target.issuedAccessBatch.bits.accesses.asTypeOf(Vec(replayLanes, UInt(issuedStreamWidth.W)))
    val issuedBatchIncomingCount = PopCount(target.issuedAccessBatch.bits.validMask)

    // check that every valid lane in incoming issued batch has space in corresponding writeback lane bank
    // e.g. batch doesn't contain that lane, or lane write-back bank not full
    val issuedBatchFits = VecInit((0 until replayLanes).map { lane =>
      !target.issuedAccessBatch.bits.validMask(lane) ||
        issuedAccessWritebackLaneCounts(lane) < laneBankDepth.U
    }).asUInt.andR

    // ensure that bridge has space to capture issued-batch 
    target.issuedAccessBatch.ready := fire && issuedBatchFits
    // fire signal for issued batch
    val issuedBatchCapture = target.issuedAccessBatch.valid && target.issuedAccessBatch.ready
    when(fire && target.issuedAccessBatch.valid) {
      assert(issuedBatchFits,
        "TrafficGenBridge issued-access batch exceeded the writeback store capacity")
    }

    when(issuedBatchCapture) {
      assert(target.issuedAccessBatch.bits.validMask.orR, "TrafficGenBridge captured an empty issued-access batch")
      assert(target.issuedAccessBatch.bits.batchId === issuedBatchExpectedId, "TrafficGenBridge captured an out-of-order issued-access batch")
      issuedBatchExpectedId := issuedBatchExpectedId + 1.U
      
      // write into writeback store and increment lane counts
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
      val laneConsumeMask = target.accessReadConsumeMask(laneBase + replaySlotsPerLane - 1, laneBase)
      when(fire) {
        assert(PopCount(laneConsumeMask) <= 1.U,
          "TrafficGenBridge consumed more than one slot from a replay lane")
      }
      // check that target consumed expected slot in the lane
      when(fire && laneConsumeMask.orR) {
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
        accessReadConsume(flat) := fire && target.accessReadConsumeMask(flat)
        queue.io.deq.ready := accessReadConsume(flat)
        when(accessReadConsume(flat)) {
          assert(queue.io.deq.valid, "TrafficGenBridge consumed an invalid prefetched replay slot")
          assert(queue.io.deq.bits(191, 128) <= target.accessReadCycle, "TrafficGenBridge consumed a replay slot before its scheduled cycle")
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

    target.uploadReady := uploadReady

    when(fire && target.roundStarted) {
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
    when(fire && target.roundStarted) {
      startRoundPending := false.B
    }
    target.startRound := startRoundPending

    // Expose completion only after the final target batch has crossed HostPort
    // and all lane-bank writes from that batch have committed.
    val roundCompleteLatched = RegInit(false.B)
    val roundCompletePending = RegInit(false.B)
    val roundStopCycle = RegInit(0.U(64.W))
    val roundStopCycleValid = RegInit(false.B)
    when(fire && target.roundStarted) {
      roundCompleteLatched := false.B
      roundCompletePending := false.B
      roundStopCycleValid := false.B
    }.elsewhen(roundCompletePending && !issuedWriteCommitPending) {
      roundCompleteLatched := true.B
      roundCompletePending := false.B
    }.elsewhen(fire && target.roundComplete) {
      roundCompletePending := true.B
      roundStopCycle := target.currentCycleAfterIssue
      roundStopCycleValid := true.B
      boundaryPaused := true.B
    }

    val boundaryPausedPrev = RegNext(boundaryPaused, false.B)
    val roundStopCycleValidPrev = RegNext(roundStopCycleValid, false.B)
    val roundStopCyclePrev = RegNext(roundStopCycle, 0.U)
    when(boundaryPaused) {
      assert(!fire, "TrafficGenBridge target token fired while boundary-paused")
    }
    when(boundaryPaused && boundaryPausedPrev &&
         roundStopCycleValid && roundStopCycleValidPrev && !resumeTarget) {
      assert(roundStopCycle === roundStopCyclePrev,
        "TrafficGenBridge round stop cycle changed while boundary-paused")
    }



    val targetReset = fire && hPort.hBits.reset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire

    when(targetReset) {
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
      roundStopCycle := 0.U
      roundStopCycleValid := false.B
      boundaryPaused := false.B
      trafficGenDone := false.B
      currentRound := 0.U
    }

    when(trafficGenDonePulse) {
      accessReadReset := true.B
      accessReadTargetPaused := false.B
      accessReplayActive := false.B
      accessReadPrefillPending := false.B
      uploadReady := false.B
      roundStopCycleValid := false.B
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
    genROReg(target.hasPendingWork, "has_pending_work")
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
      roundStopCycleValid, roundStopCycle, target.currentCycleAfterIssue)
    genROReg(reportedCycleAfterIssue(31, 0), "current_cycle_after_issue_low")
    genROReg(reportedCycleAfterIssue(63, 32), "current_cycle_after_issue_high")
    genROReg(target.roundExitReason, "round_exit_reason")
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
