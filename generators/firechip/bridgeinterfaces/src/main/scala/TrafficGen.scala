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
  // Number of members of this bundle selected for the current logical replay
  // round.  The RTL uses this to prevent a fast member from completing the
  // bundle before members on backpressured lanes have issued.
  val bundleIssueCount = UInt(16.W)
}

object L2Access {
  val packedWidth = 507
  val streamWidthBits = 512
  private val paddingWidth = streamWidthBits - packedWidth

  // pack the L2Access fields into a single UInt for streaming
  def pack(access: L2Access): UInt = {
    Cat(
      0.U(paddingWidth.W),
      access.bundleIssueCount,
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
    access.bundleIssueCount := bits(506, 491)
    access
  }
}

/** Minimal access state required by the synthesizable traffic generator.
  *
  * DPI-only trace metadata deliberately does not appear here so RTL replay
  * stores and target-side queues do not pay for unused fields.
  */
class RTLL2Access extends Bundle {
  val id = UInt(64.W)
  val address = UInt(64.W)
  val cycleCount = UInt(64.W)
  val mBundleId = UInt(64.W)
  val mWakeRelevantBundle = Bool()
  val mIsWrite = Bool()
  val mWarpBlocked = Bool()
  val bundleIssueCount = UInt(16.W)
  // Transport identity for the GPU scheduling round that introduced this
  // access. Pending accesses keep this value across later host round trips
  // and BRAM-capacity refills.
  val bundleGeneration = UInt(32.W)
}

object RTLL2Access {
  val streamWidthBits = 307

  def pack(access: RTLL2Access): UInt = Cat(
    access.bundleGeneration,
    access.bundleIssueCount,
    access.mWarpBlocked,
    access.mIsWrite,
    access.mWakeRelevantBundle,
    access.mBundleId,
    access.cycleCount,
    access.address,
    access.id,
  )

  def unpack(bits: UInt): RTLL2Access = {
    require(bits.getWidth == streamWidthBits, s"RTLL2Access.unpack expects ${streamWidthBits}b input")
    val access = Wire(new RTLL2Access)
    access.id := bits(63, 0)
    access.address := bits(127, 64)
    access.cycleCount := bits(191, 128)
    access.mBundleId := bits(255, 192)
    access.mWakeRelevantBundle := bits(256)
    access.mIsWrite := bits(257)
    access.mWarpBlocked := bits(258)
    access.bundleIssueCount := bits(274, 259)
    access.bundleGeneration := bits(306, 275)
    access
  }
}

/** The only per-access fields returned by either traffic-generator engine. */
class IssuedAccess extends Bundle {
  val requestUid = UInt(64.W)
  val cycleIssued = UInt(64.W)
  val address = UInt(64.W)
  val isWrite = Bool()
}

object IssuedAccess {
  val streamWidthBits = 193

  def pack(access: IssuedAccess): UInt = Cat(
    access.isWrite,
    access.address,
    access.cycleIssued,
    access.requestUid,
  )

  def unpack(bits: UInt): IssuedAccess = {
    require(bits.getWidth == streamWidthBits, s"IssuedAccess.unpack expects ${streamWidthBits}b input")
    val access = Wire(new IssuedAccess)
    access.requestUid := bits(63, 0)
    access.cycleIssued := bits(127, 64)
    access.address := bits(191, 128)
    access.isWrite := bits(192)
    access
  }
}

object TrafficGenAccessBatch {
  val lanes = 16
}

class IssuedAccessBatch extends Bundle {
  val batchId = UInt(32.W)
  val validMask = UInt(TrafficGenAccessBatch.lanes.W)
  // Golden Gate's bridge extraction cannot lower aggregate fields nested
  // inside Decoupled[IssuedAccessBatch], so the compact issued records cross
  // HostPort as one ground UInt.
  val accesses = UInt((TrafficGenAccessBatch.lanes * IssuedAccess.streamWidthBits).W)
}

object CompletedBundleIds {
  val capacity = 32768
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

class TrafficGenPortIO(useRTL: Boolean) extends Bundle {
  val targetBusy = Output(Bool())
  val hasPendingWork = Output(Bool())
  val startTrafficGen = Output(Bool())
  val roundStarted = Output(Bool())
  val roundComplete = Output(Bool())
  val roundExitReason = Output(UInt(2.W))
  val currentCycleAfterIssue = Output(UInt(64.W))
  val dpiState = Output(UInt(32.W))
  val issuedAccessBatch = Decoupled(new IssuedAccessBatch)
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
  val accessReadRespValid = Input(Bool())
  val accessReadRespId = Input(UInt(32.W))
  private val accessStreamWidth =
    if (useRTL) RTLL2Access.streamWidthBits else L2Access.streamWidthBits
  val accessReadData = Input(Vec(TrafficGenAccessBatch.lanes, UInt(accessStreamWidth.W)))
  val accessReadDataValid = Input(Vec(TrafficGenAccessBatch.lanes, Bool()))
  val accessReadBucketDone = Input(Bool())
  val accessReadReady = Input(Bool())
  val accessReadConsumeMask = Output(UInt(TrafficGenAccessBatch.lanes.W))
  val accessReadLaneDoneMask = Input(UInt(TrafficGenAccessBatch.lanes.W))
  val accessReadPrefetchPauseReq = Input(Bool())
  val accessReadPrefetchPauseAck = Output(Bool())
  val accessStoreCount = Input(UInt(32.W))
  val accessStoreMaxCycle = Input(UInt(64.W))
  val accessStoreHasEntries = Input(Bool())
  val accessStoreHasMore = Input(Bool())
  val uploadReady = Input(Bool())
}

case class TrafficGenBridgeKey(maxL2AccessEntries: Int, useRTL: Boolean)

class TrafficGenBridgeTargetIO(useRTL: Boolean) extends Bundle {
  val clock = Input(Clock())
  val trafficgen = Flipped(new TrafficGenPortIO(useRTL))
  val reset = Input(Bool())
}
