// See LICENSE for license details.

package firechip.bridgeinterfaces

import chisel3._
import chisel3.util._

// hardware record type to represent an L2 access from host 
class L2Access extends Bundle {
  val id = UInt(64.W)
  val address = UInt(64.W)
  val cycleCount = UInt(64.W)
  val mSubpartition = UInt(32.W)
  val mSetIndex = UInt(32.W)
  val mTag = UInt(64.W)
  val mMask = UInt(32.W)
  val smId = UInt(32.W)
  val schedulerId = UInt(8.W)
  val warpId = UInt(32.W)
  val mBundleId = UInt(64.W)
  val mWakeRelevantBundle = Bool()
  val mIsWrite = Bool()
  val mWarpBlocked = Bool()
}

object L2Access {
  val packedWidth = 491
  val streamWidthBits = 512
  private val paddingWidth = streamWidthBits - packedWidth

  // pack the L2Access fields into a single UInt for streaming
  def pack(access: L2Access): UInt = {
    Cat(
      0.U(paddingWidth.W),
      access.mWarpBlocked,
      access.mIsWrite,
      access.mWakeRelevantBundle,
      access.mBundleId,
      access.warpId,
      access.schedulerId,
      access.smId,
      access.mMask,
      access.mTag,
      access.mSetIndex,
      access.mSubpartition,
      access.cycleCount,
      access.address,
      access.id,
    )
  }

  def unpack(bits: UInt): L2Access = {
    require(bits.getWidth == streamWidthBits, s"L2Access.unpack expects ${streamWidthBits}b input")
    val access = Wire(new L2Access)
    access.id := bits(63, 0)
    access.address := bits(127, 64)
    access.cycleCount := bits(191, 128)
    access.mSubpartition := bits(223, 192)
    access.mSetIndex := bits(255, 224)
    access.mTag := bits(319, 256)
    access.mMask := bits(351, 320)
    access.smId := bits(383, 352)
    access.schedulerId := bits(391, 384)
    access.warpId := bits(423, 392)
    access.mBundleId := bits(487, 424)
    access.mWakeRelevantBundle := bits(488)
    access.mIsWrite := bits(489)
    access.mWarpBlocked := bits(490)
    access
  }
}

object TrafficGenAccessBatch {
  val lanes = 16
}

object BlockedWarpBitmap {
  val smBits = 8
  val schedulerBits = 2
  val warpBits = 9
  val indexBits = smBits + schedulerBits + warpBits
  val totalBits = 1 << indexBits
  val streamBeatBits = L2Access.streamWidthBits
  val streamBeatCount = totalBits / streamBeatBits
  val streamBeatIdxBits = log2Ceil(streamBeatCount)
  val streamBeatOffsetBits = log2Ceil(streamBeatBits)

  require(totalBits % streamBeatBits == 0, "Blocked warp bitmap must be stream-beat aligned")

  def indexFromFields(smId: UInt, schedulerId: UInt, warpId: UInt): UInt =
    Cat(
      smId(smBits - 1, 0),
      schedulerId(schedulerBits - 1, 0),
      warpId(warpBits - 1, 0),
    )
}

object CompletedBundleIds {
  val capacity = 4096
  val idsPerBeat = 8
  val beats = capacity / idsPerBeat
  val idxWidth = log2Ceil(capacity)
  val countWidth = log2Ceil(capacity + 1)

  require(capacity % idsPerBeat == 0, "Completed bundle ID capacity must be stream-beat aligned")
}

object TrafficGenRoundExitReason {
  val scheduling = 0.U(2.W)
  val capacity = 1.U(2.W)
}

class TrafficGenPortIO extends Bundle {
  val targetBusy = Output(Bool())
  val hasPendingWork = Output(Bool())
  val startTrafficGen = Output(Bool())
  val roundStarted = Output(Bool())
  val roundComplete = Output(Bool())
  val roundExitReason = Output(UInt(2.W))
  val currentCycleAfterIssue = Output(UInt(64.W))
  val dpiState = Output(UInt(32.W))
  val issuedAccessWriteback = Decoupled(new L2Access)
  val completedBundleIdWriteEn = Output(Bool())
  val completedBundleIdWriteIdx = Output(UInt(CompletedBundleIds.idxWidth.W))
  val completedBundleIdWriteData = Output(UInt(64.W))
  val completedBundleCountWriteEn = Output(Bool())
  val completedBundleCountWriteData = Output(UInt(CompletedBundleIds.countWidth.W))
  val startRound = Input(Bool())
  val trafficGenDone = Input(Bool())
  val minIssueCycle = Input(UInt(64.W))
  val accessReadCycle = Output(UInt(64.W))
  val accessReadEn = Output(Bool())
  val accessReadBatchReady = Output(Bool())
  val blockedWarpQueryIdx = Output(UInt(BlockedWarpBitmap.indexBits.W))
  val blockedWarpQueryEn = Output(Bool())
  val blockedWarpQueryRespStored = Output(Bool())
  val accessReadRespValid = Input(Bool())
  val accessReadRespId = Input(UInt(32.W))
  val accessReadData = Input(Vec(TrafficGenAccessBatch.lanes, new L2Access))
  val accessReadDataValid = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))
  val accessReadBucketDone = Input(Bool())
  val accessReadReady = Input(Bool())
  val blockedWarpQueryResp = Input(Bool())
  val blockedWarpQueryRespValid = Input(Bool())
  val blockedWarpQueryReady = Input(Bool())
  val accessStoreCount = Input(UInt(32.W))
  val accessStoreMaxCycle = Input(UInt(64.W))
  val accessStoreHasEntries = Input(Bool())
  val uploadReady = Input(Bool())
}

case class TrafficGenBridgeKey(maxL2AccessEntries: Int)

class TrafficGenBridgeTargetIO extends Bundle {
  val clock = Input(Clock())
  val trafficgen = Flipped(new TrafficGenPortIO)
  val reset = Input(Bool())
}
