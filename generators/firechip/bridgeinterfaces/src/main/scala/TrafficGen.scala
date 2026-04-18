// See LICENSE for license details.

package firechip.bridgeinterfaces

import chisel3._
import chisel3.util.Decoupled

// hardware record type to represent an L2 access from host 
class L2Access extends Bundle {
  val id = UInt(32.W)
  val address = UInt(32.W)
  val cycleCount = UInt(32.W)
  val mSubpartition = UInt(32.W)
  val mSetIndex = UInt(32.W)
  val mTag = UInt(32.W)
  val mMask = UInt(32.W)
  val smId = UInt(32.W)
  val schedulerId = UInt(32.W)
  val warpId = UInt(32.W)
  val mBundleId = UInt(32.W)
  val mWakeRelevantBundle = UInt(32.W)
  val mIsWrite = Bool()
}

object L2Access {
  val packedWidth = 32 * 12 + 1
  val streamWidthBits = 512
  private val paddingWidth = streamWidthBits - packedWidth

  // pack the L2Access fields into a single UInt for streaming
  def pack(access: L2Access): UInt = {
    Cat(
      0.U(paddingWidth.W),
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
    access.id := bits(31, 0)
    access.address := bits(63, 32)
    access.cycleCount := bits(95, 64)
    access.mSubpartition := bits(127, 96)
    access.mSetIndex := bits(159, 128)
    access.mTag := bits(191, 160)
    access.mMask := bits(223, 192)
    access.smId := bits(255, 224)
    access.schedulerId := bits(287, 256)
    access.warpId := bits(319, 288)
    access.mBundleId := bits(351, 320)
    access.mWakeRelevantBundle := bits(383, 352)
    access.mIsWrite := bits(384)
    access
  }
}

object BlockedWarpBitmap {
  val smBits = 4
  val schedulerBits = 3
  val warpBits = 6
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

class TrafficGenPortIO(val nGenerators: Int) extends Bundle {
  val targetBusy = Output(Bool())
  val startTrafficGen = Output(Bool())
  val currentCycleAfterIssue = Output(UInt(32.W))
  val completedBundleIdWriteEn = Output(Bool())
  val completedBundleIdWriteIdx = Output(UInt(5.W))
  val completedBundleIdWriteData = Output(UInt(32.W))
  val completedBundleCountWriteEn = Output(Bool())
  val completedBundleCountWriteData = Output(UInt(6.W))
  val trafficComplete = Input(Bool())
  val minIssueCycle = Input(UInt(32.W))
  val blockedWarpBitmapReady = Input(Bool())
  val blockedWarpQueryIdx = Output(UInt(BlockedWarpBitmap.indexBits.W))
  val blockedWarpQueryEn = Output(Bool())
  val blockedWarpQueryResp = Input(Bool())
  val blockedWarpQueryRespValid = Input(Bool())
  val accessReadAddr = Output(UInt(32.W))
  val accessReadEn = Output(Bool())
  val accessReadData = Input(new L2Access)
  val accessReadDataValid = Input(Bool())
  val accessStoredCount = Input(UInt(32.W))
  val uploadDone = Input(Bool())
  val uploadOverflow = Input(Bool())
}

case class TrafficGenBridgeKey(maxL2AccessEntries: Int, nGenerators: Int)

class TrafficGenBridgeTargetIO(key: TrafficGenBridgeKey) extends Bundle {
  val clock = Input(Clock())
  val trafficgen = Flipped(new TrafficGenPortIO(key.nGenerators))
  val reset = Input(Bool())
}
