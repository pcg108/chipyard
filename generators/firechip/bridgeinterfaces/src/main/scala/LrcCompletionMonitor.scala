package firechip.bridgeinterfaces

import chisel3._
import chisel3.util._

/** A synchronous-memory min heap. Operations take host cycles, never target cycles. */
class LrcTimer(val slotBits: Int) extends Bundle {
  val cycle = UInt(64.W)
  val token = UInt(64.W)
  val slot = UInt(slotBits.W)
}

class LrcTimerHeap(val entries: Int) extends Module {
  require(isPow2(entries) && entries >= 4)
  val bits = log2Ceil(entries)
  require((new LrcTimer(bits)).getWidth == 128 + bits)
  val io = IO(new Bundle {
    val push = Flipped(Decoupled(new LrcTimer(bits)))
    val pop = Decoupled(new LrcTimer(bits))
    val count = Output(UInt((bits + 1).W))
    val idle = Output(Bool())
  })
  // Keep each row a single physical word. Aggregate memories are split by
  // FIRRTL and cannot retain Golden Gate's one-target RAMStyleHint annotation.
  val mem = SyncReadMem(entries, UInt((128 + bits).W))
  val idle :: upRead :: upCompare :: upRoot :: lastRead :: lastCapture :: childRead :: childCapture :: siblingRead :: siblingCompare :: Nil = Enum(10)
  val state = RegInit(idle)
  val count = RegInit(0.U((bits + 1).W))
  val root = Reg(new LrcTimer(bits))
  val held = Reg(new LrcTimer(bits))
  val cursor = Reg(UInt(bits.W))
  val child = Reg(new LrcTimer(bits))
  val childIndex = Reg(UInt(bits.W))
  val readEn = WireDefault(false.B)
  val readAddr = WireDefault(0.U(bits.W))
  val data = mem.read(readAddr, readEn).asTypeOf(new LrcTimer(bits))
  val writeEn = WireDefault(false.B)
  val writeAddr = WireDefault(0.U(bits.W))
  val writeData = WireDefault(0.U.asTypeOf(new LrcTimer(bits)))
  when(writeEn) { mem.write(writeAddr, writeData.asUInt) }
  def before(a: LrcTimer, b: LrcTimer): Bool = a.cycle < b.cycle || (a.cycle === b.cycle && a.slot < b.slot)
  def put(index: UInt, value: LrcTimer): Unit = {
    writeEn := true.B; writeAddr := index; writeData := value
    when(index === 0.U) { root := value }
  }
  io.count := count
  io.idle := state === idle
  io.push.ready := state === idle && count < entries.U
  io.pop.valid := state === idle && count =/= 0.U && !io.push.valid
  io.pop.bits := root
  when(io.push.fire) {
    held := io.push.bits
    cursor := count(bits - 1, 0)
    count := count + 1.U
    when(count === 0.U) { put(0.U, io.push.bits) }
      .otherwise { state := upRead }
  }.elsewhen(io.pop.fire) {
    count := count - 1.U
    when(count > 1.U) { state := lastRead }
  }
  switch(state) {
    is(upRead) { readEn := true.B; readAddr := (cursor - 1.U) >> 1; state := upCompare }
    is(upCompare) {
      val parent = (cursor - 1.U) >> 1
      when(before(held, data)) {
        put(cursor, data)
        when(parent === 0.U) { state := upRoot }
          .otherwise { cursor := parent; state := upRead }
      }.otherwise { put(cursor, held); state := idle }
    }
    is(upRoot) { put(0.U, held); state := idle }
    is(lastRead) { readEn := true.B; readAddr := count(bits - 1, 0); state := lastCapture }
    is(lastCapture) { held := data; cursor := 0.U; state := childRead }
    is(childRead) {
      val left = (cursor << 1) +& 1.U
      when(left >= count) { put(cursor, held); state := idle }
        .otherwise { readEn := true.B; readAddr := left(bits - 1, 0); childIndex := left; state := childCapture }
    }
    is(childCapture) { child := data; state := siblingRead }
    is(siblingRead) {
      when((childIndex +& 1.U) < count) {
        readEn := true.B; readAddr := childIndex + 1.U; state := siblingCompare
      }.otherwise {
        when(before(child, held)) { put(cursor, child); cursor := childIndex; state := childRead }
          .otherwise { put(cursor, held); state := idle }
      }
    }
    is(siblingCompare) {
      val chooseRight = before(data, child)
      val selected = Mux(chooseRight, data, child)
      val selectedIndex = Mux(chooseRight, childIndex + 1.U, childIndex)
      when(before(selected, held)) { put(cursor, selected); cursor := selectedIndex; state := childRead }
        .otherwise { put(cursor, held); state := idle }
    }
  }
}

class LrcServiceRow(val slotBits: Int) extends Bundle {
  val token = UInt(64.W)
  val active = Bool()
  val mask = UInt(4.W)
  val bundles = Vec(4, UInt(64.W))
}
class LrcBundleRow extends Bundle {
  val token = UInt(64.W)
  val handle = UInt(64.W)
  val active = Bool()
  val pending = UInt(17.W)
  val floor = UInt(64.W)
  val wake = Bool()
  val timer = Bool()
}

/** Reserve every possible report before starting a held token. Once accepted,
  * processing owns that reservation until tokenReady, even while reports fill.
  */
class LrcReportCapacityGate(val lanes: Int, val capacity: Int = 131072) extends Module {
  val io = IO(new Bundle {
    val enabled = Input(Bool())
    val tokenIdle = Input(Bool())
    val count = Input(UInt(32.W))
    val bundles = Input(UInt(17.W))
    val returns = Input(UInt(lanes.W))
    val drainRequired = Output(Bool())
  })
  io.drainRequired := io.enabled && io.tokenIdle &&
    (io.count +& io.bundles +& PopCount(io.returns)) > capacity.U
}

/** A control request must stop native replay even when no scheduler round is
  * active, or when an infinite deadline and blocked issue ports cannot progress.
  * All outputs describe the fully accounted held target snapshot.
  */
class LrcNativeFence extends Module {
  val io = IO(new Bundle {
    val enabled = Input(Bool())
    val accounted = Input(Bool())
    val parked = Input(Bool())
    val checkpoint = Input(Bool())
    val active = Input(Bool())
    val controlPending = Input(Bool())
    val scheduling = Input(Bool())
    val refill = Input(Bool())
    val fence = Output(Bool())
    val control = Output(Bool())
    val idleControl = Output(Bool())
  })
  io.fence := io.enabled && io.accounted && !io.parked && !io.checkpoint &&
    (io.controlPending || (io.active && (io.scheduling || io.refill)))
  io.control := io.fence && io.controlPending
  io.idleControl := io.control && !io.active
}

/** Native v6 completion accounting. A held target token is a transaction: all
  * of its physical returns and due timers commit before tokenReady is raised.
  * The bridge must consume that token exactly once, even when it wakes a warp.
  * Dense table slots are host allocated; full tokens guard slot reuse.
  */
class LrcCompletionMonitor(val lanes: Int, val entries: Int = 65536) extends Module {
  require(entries <= 65536 && isPow2(entries))
  val bits = log2Ceil(entries)
  require((new LrcServiceRow(bits)).getWidth == 325)
  require((new LrcBundleRow).getWidth == 212)
  val io = IO(new Bundle {
    val metadata = Flipped(Decoupled(UInt(512.W)))
    val metadataIdle = Output(Bool())
    val tokenValid = Input(Bool())
    // Re-evaluate a parked cycle after the host atomically adds admissions.
    // This never consumes a target token or invents a physical return.
    val recheck = Input(Bool())
    val cycle = Input(UInt(64.W))
    val returns = Input(UInt(lanes.W))
    val tokens = Input(Vec(lanes, UInt(64.W)))
    val returnCycles = Input(Vec(lanes, UInt(64.W)))
    val tokenReady = Output(Bool())
    val tokenIdle = Output(Bool())
    val tokenConsumed = Input(Bool())
    val wake = Output(Bool())
    val pending = Output(Bool())
    val report = Decoupled(UInt(512.W))
    val error = Output(UInt(32.W))
    val serviceCount = Output(UInt(17.W))
    val bundleCount = Output(UInt(17.W))
    val timerCount = Output(UInt(17.W))
  })
  // Packed storage also gives each table one BRAM write port, rather than a
  // separately inferred memory for every Bundle field.
  val services = SyncReadMem(entries, UInt(325.W))
  val bundles = SyncReadMem(entries, UInt(212.W))
  val heap = Module(new LrcTimerHeap(entries))
  val init :: idle :: metaService :: metaBundle :: metaWake :: returnRead :: returnCapture :: returnReport :: edgeRead :: edgeCapture :: bundleReport :: timerPush :: timerCheck :: timerCapture :: timerBundle :: done :: Nil = Enum(16)
  val state = RegInit(init)
  val initSlot = RegInit(0.U(bits.W))
  val descriptor = Reg(UInt(512.W))
  val metadataResume = Reg(UInt(4.W))
  val cycle = Reg(UInt(64.W))
  val returnMask = Reg(UInt(lanes.W))
  val tokens = Reg(Vec(lanes, UInt(64.W)))
  val returnCycles = Reg(Vec(lanes, UInt(64.W)))
  val service = Reg(new LrcServiceRow(bits))
  val serviceSlot = Reg(UInt(bits.W))
  val serviceCycle = Reg(UInt(64.W))
  val edge = RegInit(0.U(3.W))
  val bundle = Reg(new LrcBundleRow)
  val bundleSlot = Reg(UInt(bits.W))
  val expectedBundleHandle = Reg(UInt(64.W))
  val completionCycle = Reg(UInt(64.W))
  val afterBundle = Reg(UInt(4.W))
  val pendingTimer = Reg(new LrcTimer(bits))
  val timer = Reg(new LrcTimer(bits))
  val wake = RegInit(false.B)
  val error = RegInit(0.U(32.W))
  val serviceCount = RegInit(0.U(17.W))
  val bundleCount = RegInit(0.U(17.W))
  val serviceReadEn = WireDefault(false.B)
  val serviceReadAddr = WireDefault(0.U(bits.W))
  val serviceData = services.read(serviceReadAddr, serviceReadEn).asTypeOf(new LrcServiceRow(bits))
  val bundleReadEn = WireDefault(false.B)
  val bundleReadAddr = WireDefault(0.U(bits.W))
  val bundleData = bundles.read(bundleReadAddr, bundleReadEn).asTypeOf(new LrcBundleRow)
  val serviceWriteEn = WireDefault(false.B)
  val serviceWriteAddr = WireDefault(0.U(bits.W))
  val serviceWriteData = WireDefault(0.U.asTypeOf(new LrcServiceRow(bits)))
  val bundleWriteEn = WireDefault(false.B)
  val bundleWriteAddr = WireDefault(0.U(bits.W))
  val bundleWriteData = WireDefault(0.U.asTypeOf(new LrcBundleRow))
  when(serviceWriteEn) { services.write(serviceWriteAddr, serviceWriteData.asUInt) }
  when(bundleWriteEn) { bundles.write(bundleWriteAddr, bundleWriteData.asUInt) }
  def writeService(slot: UInt, row: LrcServiceRow): Unit = {
    serviceWriteEn := true.B; serviceWriteAddr := slot; serviceWriteData := row
  }
  def writeBundle(slot: UInt, row: LrcBundleRow): Unit = {
    bundleWriteEn := true.B; bundleWriteAddr := slot; bundleWriteData := row
  }
  io.metadata.ready := (state === idle || state === done) && heap.io.idle && !io.tokenValid && error === 0.U
  io.metadataIdle := (state === idle || state === done) && heap.io.idle && error === 0.U
  io.tokenReady := state === done && error === 0.U
  io.tokenIdle := state === idle && heap.io.idle
  io.wake := wake
  io.pending := serviceCount =/= 0.U || bundleCount =/= 0.U
  io.serviceCount := serviceCount
  io.bundleCount := bundleCount
  io.timerCount := heap.io.count
  io.error := error
  io.report.valid := false.B
  io.report.bits := 0.U
  heap.io.push.valid := state === timerPush
  heap.io.push.bits := pendingTimer
  heap.io.pop.ready := state === timerCheck && heap.io.pop.valid && heap.io.pop.bits.cycle <= cycle
  def fail(code: Int): Unit = { error := code.U }
  def finishBundle(row: LrcBundleRow, slot: UInt, at: UInt, resume: UInt): Unit = {
    bundle := row; bundleSlot := slot; completionCycle := at; afterBundle := resume
    state := bundleReport
  }
  def scheduleTimer(row: LrcBundleRow, slot: UInt, resume: UInt): Unit = {
    pendingTimer.cycle := row.floor; pendingTimer.token := row.handle; pendingTimer.slot := slot
    afterBundle := resume; state := timerPush
  }
  when(state === init) {
    writeService(initSlot, 0.U.asTypeOf(new LrcServiceRow(bits)))
    writeBundle(initSlot, 0.U.asTypeOf(new LrcBundleRow))
    when(initSlot === (entries - 1).U) { state := idle }.otherwise { initSlot := initSlot + 1.U }
  }
  when(io.metadata.fire) {
    descriptor := io.metadata.bits
    metadataResume := state
    val slot = io.metadata.bits(8 + bits - 1, 8)
    switch(io.metadata.bits(7, 0)) {
      is(1.U) { serviceReadEn := true.B; serviceReadAddr := slot; state := metaService }
      is(2.U) { bundleReadEn := true.B; bundleReadAddr := slot; state := metaBundle }
      is(3.U) { bundleReadEn := true.B; bundleReadAddr := slot; state := metaWake }
    }
    when(io.metadata.bits(7, 0) < 1.U || io.metadata.bits(7, 0) > 3.U) { fail(1) }
  }
  when((state === idle && io.tokenValid || state === done && io.recheck) &&
    !io.metadata.valid && error === 0.U) {
    cycle := io.cycle; returnMask := io.returns; tokens := io.tokens; returnCycles := io.returnCycles
    wake := false.B; state := returnRead
  }
  val metaSlot = descriptor(8 + bits - 1, 8)
  val metaToken = descriptor(87, 24)
  switch(state) {
    is(metaService) {
      val row = Wire(new LrcServiceRow(bits))
      row.token := metaToken; row.active := true.B; row.mask := descriptor(91, 88)
      for (i <- 0 until 4) { row.bundles(i) := descriptor(155 + 64 * i, 92 + 64 * i) }
      when(metaToken(bits - 1, 0) =/= metaSlot) { fail(2) }
      when(serviceData.active && serviceData.token =/= metaToken) { fail(3) }
      when(!serviceData.active) { serviceCount := serviceCount + 1.U }
      writeService(metaSlot, row); state := metadataResume
    }
    is(metaBundle) {
      val row = Wire(new LrcBundleRow)
      row.token := metaToken; row.active := true.B; row.pending := descriptor(104, 88)
      row.handle := descriptor(233, 170)
      row.floor := descriptor(168, 105); row.wake := descriptor(169); row.timer := descriptor(104, 88) === 0.U
      val existingTimer = bundleData.active && bundleData.handle === row.handle && bundleData.timer
      when(row.handle(bits - 1, 0) =/= metaSlot ||
        (bundleData.active && (bundleData.handle =/= row.handle || bundleData.token =/= row.token))) { fail(4) }
      when(existingTimer && (row.pending =/= 0.U || row.floor =/= bundleData.floor)) { fail(5) }
      when(!bundleData.active) { bundleCount := bundleCount + 1.U }
      writeBundle(metaSlot, row)
      when(row.pending === 0.U && !existingTimer) { scheduleTimer(row, metaSlot, metadataResume) }
        .otherwise { state := metadataResume }
    }
    is(metaWake) {
      val row = WireDefault(bundleData)
      row.wake := descriptor(88)
      when(!row.active || row.handle =/= metaToken) { fail(6) }
      writeBundle(metaSlot, row); state := metadataResume
    }
    is(returnRead) {
      when(returnMask.orR) {
        val lane = PriorityEncoder(returnMask)
        val slot = tokens(lane)(bits - 1, 0)
        serviceSlot := slot; serviceCycle := returnCycles(lane)
        serviceReadEn := true.B; serviceReadAddr := slot; state := returnCapture
      }.otherwise { state := timerCheck }
    }
    is(returnCapture) {
      val lane = PriorityEncoder(returnMask)
      when(!serviceData.active || serviceData.token =/= tokens(lane) || serviceCycle > cycle) { fail(7) }
        .otherwise {
          service := serviceData
          val row = WireDefault(serviceData); row.active := false.B
          writeService(serviceSlot, row)
          serviceCount := serviceCount - 1.U
          returnMask := returnMask & ~UIntToOH(lane, lanes)
          edge := 0.U; state := returnReport
        }
    }
    is(returnReport) {
      io.report.valid := error === 0.U; io.report.bits := Cat(serviceCycle, service.token, 1.U(8.W))
      when(io.report.fire) { state := edgeRead }
    }
    is(edgeRead) {
      when(edge === 4.U) { state := returnRead }
        .elsewhen(!service.mask(edge(1, 0))) { edge := edge + 1.U }
        .otherwise {
          bundleSlot := service.bundles(edge(1, 0))(bits - 1, 0)
          expectedBundleHandle := service.bundles(edge(1, 0))
          bundleReadEn := true.B; bundleReadAddr := service.bundles(edge(1, 0))(bits - 1, 0); state := edgeCapture
        }
    }
    is(edgeCapture) {
      val row = WireDefault(bundleData)
      when(!bundleData.active || bundleData.pending === 0.U || bundleData.handle =/= expectedBundleHandle) { fail(8) }
        .otherwise {
          row.pending := bundleData.pending - 1.U
          edge := edge + 1.U
          when(bundleData.pending === 1.U) {
            when(row.floor <= cycle) { finishBundle(row, bundleSlot, Mux(row.floor > serviceCycle, row.floor, serviceCycle), edgeRead) }
              .otherwise { row.timer := true.B; scheduleTimer(row, bundleSlot, edgeRead) }
          }.otherwise { state := edgeRead }
          writeBundle(bundleSlot, row)
        }
    }
    is(bundleReport) {
      io.report.valid := error === 0.U; io.report.bits := Cat(completionCycle, bundle.token, 2.U(8.W))
      when(io.report.fire) {
        val row = WireDefault(bundle); row.active := false.B; row.timer := false.B
        writeBundle(bundleSlot, row)
        bundleCount := bundleCount - 1.U
        wake := wake || bundle.wake
        state := afterBundle
      }
    }
    is(timerPush) {
      when(heap.io.push.fire) { state := afterBundle }
      when(heap.io.idle && heap.io.count === entries.U) { fail(9) }
    }
    is(timerCheck) {
      when(heap.io.pop.fire) { timer := heap.io.pop.bits; state := timerCapture }
        .elsewhen(heap.io.idle && (!heap.io.pop.valid || heap.io.pop.bits.cycle > cycle)) { state := done }
    }
    is(timerCapture) {
      bundleReadEn := true.B; bundleReadAddr := timer.slot; state := timerBundle
    }
    is(timerBundle) {
      when(!bundleData.active || !bundleData.timer || bundleData.handle =/= timer.token || bundleData.pending =/= 0.U) { fail(10) }
        .otherwise { finishBundle(bundleData, timer.slot, timer.cycle, timerCheck) }
    }
    is(done) { when(io.tokenConsumed && !io.recheck) { state := idle } }
  }
  when(io.tokenConsumed) { assert(io.tokenReady, "LRC token consumed before completion accounting committed") }
}
