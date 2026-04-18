// See LICENSE for license details

package firechip.goldengateimplementations

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

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

    // table that maps cycle to an occupancy mask, where each bit indicates whether that L2 subpartition is reserved by an L2 access in that cycle
    val reservedSubPartitionsByCycle = RegInit(VecInit(Seq.tabulate(1024)(i => i.U(256.W))))
    val reservedSubPartitionsBaseIdx = RegInit(0.U(log2Ceil(reservedSubPartitionsByCycle.length).W))
    val reservedSubPartitionsBaseCycle = RegInit(0.U(64.W))

    // trigger for bridge driver to read reservedSubPartitionsByCycle
    val readReservedSubPartitions = Wire(Bool())

    // packing 2 elements of reservedSubPartitionsByCycle (2x256 = 512)
    val reservedSubPartitionBeats = reservedSubPartitionsByCycle.length / 2
    val streamBeatIdx = RegInit(0.U(log2Ceil(reservedSubPartitionBeats).W))
    val reservedWordBaseIdx = Cat(streamBeatIdx, 0.U(1.W))
    val reservedWordNextIdx = reservedWordBaseIdx + 1.U
    val reservedStreamBits =
      Cat(reservedSubPartitionsByCycle(reservedWordNextIdx), reservedSubPartitionsByCycle(reservedWordBaseIdx))
    
    
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

    // stream bits are either the packed reservedSubPartitionsByCycle or the packed completedBundleIds
    streamEnq.bits := Mux(
      streamSource === streamSourceCompletedBundleIds,
      completedBundleIdsPacked(streamBeatIdx(0)), // only 2 beats, so only need the least significant bit of streamBeatIdx to index
      reservedStreamBits,
    )
    val streamActive = streamSource =/= streamSourceIdle
    val doStreamEnq = DecoupledHelper(streamActive, fire, streamEnq.ready)
    streamEnq.valid := doStreamEnq.fire(streamEnq.ready)

    val streamLastBeat = Mux(
      streamSource === streamSourceCompletedBundleIds,
      streamBeatIdx === (completedBundleIdBeats - 1).U,
      streamBeatIdx === (reservedSubPartitionBeats - 1).U,
    )

    // state machine to stream reservedSubPartitionsByCycle or completedBundleIds when triggered by bridge driver, and to keep track of stream beat index
    when(readReservedSubPartitions && !streamActive) {
      streamSource := streamSourceReservedSubPartitions
      streamBeatIdx := 0.U
    }.elsewhen(readCompletedBundleIds && completedBundleIdsValid && completedBundleCountValid && !streamActive) {
      streamSource := streamSourceCompletedBundleIds
      streamBeatIdx := 0.U
    }.elsewhen(doStreamEnq.fire()) {
      when(streamLastBeat) {
        streamSource := streamSourceIdle
        streamBeatIdx := 0.U
      }.otherwise {
        streamBeatIdx := streamBeatIdx + 1.U
      }
    }
    

    //// store L2Access vector from bridge driver to a backing store in the bridge module
    val accessStore = SyncReadMem(key.maxL2AccessEntries, new L2Access)
    val blockedWarpBitmap = RegInit(VecInit(Seq.fill(BlockedWarpBitmap.streamBeatCount)(0.U(L2Access.streamWidthBits.W))))
    val uploadActive = RegInit(false.B)
    val uploadDone = RegInit(true.B)
    val uploadOverflow = RegInit(false.B)
    val blockedWarpUploadDone = RegInit(false.B)
    val uploadRecvCount = RegInit(0.U(32.W))
    val uploadStoredCount = RegInit(0.U(32.W))
    val blockedWarpBeatCount = RegInit(0.U(BlockedWarpBitmap.streamBeatIdxBits.W))
    val uploadPhaseIdle :: uploadPhaseL2Accesses :: uploadPhaseBlockedWarpBitmap :: Nil = Enum(3)
    val uploadPhase = RegInit(uploadPhaseIdle)

    // stream interface for bridge driver to write L2 access to backing store
    val uploadCount = RegInit(0.U(32.W))
    val minIssueCycle = RegInit(0.U(32.W))
    val uploadStart = Wire(Bool())
    val trafficComplete = Wire(Bool())
    val uploadBits = L2Access.unpack(streamDeq.bits)
    streamDeq.ready := uploadActive

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

    when(streamDeq.fire && !uploadStart) {
      switch(uploadPhase) {

        // first, stream L2 access data into backing store
        is(uploadPhaseL2Accesses) {
          val canStore = uploadStoredCount < key.maxL2AccessEntries.U
          when(canStore) {
            accessStore.write(uploadStoredCount, uploadBits)
            uploadStoredCount := uploadStoredCount + 1.U
          }.otherwise {
            uploadOverflow := true.B
          }

          uploadRecvCount := uploadRecvCount + 1.U
          when(uploadRecvCount + 1.U === uploadCount) {
            uploadPhase := uploadPhaseBlockedWarpBitmap
          }
        }

        // next, stream blocked warp bitmap data into registers in the bridge module
        is(uploadPhaseBlockedWarpBitmap) {
          blockedWarpBitmap(blockedWarpBeatCount) := streamDeq.bits
          when(blockedWarpBeatCount === (BlockedWarpBitmap.streamBeatCount - 1).U) {
            uploadActive := false.B
            uploadDone := true.B
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
      completedBundleIds.foreach(_ := 0.U)
      completedBundleCount := 0.U
      completedBundleIdsValid := true.B
      completedBundleCountValid := true.B
    }


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

    // when completed bundle IDs or count are available, bridge driver can read them
    genROReg(completedBundleIdsValid, "completed_bundle_ids_valid")
    genROReg(completedBundleCountValid, "completed_bundle_count_valid")

    // generate register definitions for the bridge driver to interact with 
    genWORegInit(uploadCount, "upload_count", 0.U)
    genWORegInit(minIssueCycle, "min_issue_cycle", 0.U)
    Pulsify(genWORegInit(trafficComplete, "traffic_complete", false.B), pulseLength = 1)
    Pulsify(genWORegInit(readCompletedBundleIds, "read_completed_bundle_ids", false.B), pulseLength = 1)
    
    genROReg(target.currentCycleAfterIssue, "current_cycle_after_issue")
    genROReg(uploadDone, "upload_done")
    genROReg(uploadOverflow, "upload_overflow")
    genROReg(blockedWarpUploadDone, "blocked_warp_upload_done")
    
    
    
    genROReg(completedBundleCount, "completed_bundle_count")
    

    Pulsify(genWORegInit(uploadStart, "upload_start", false.B), pulseLength = 1)

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
