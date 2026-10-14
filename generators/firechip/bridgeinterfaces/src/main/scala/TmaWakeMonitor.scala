package firechip.bridgeinterfaces

import chisel3._
import chisel3.util._

class TmaTimer(val slotBits: Int) extends Bundle {
  val cycle = UInt(64.W)
  val slot = UInt(slotBits.W)
}

class TmaTimerHeap(val entries: Int) extends Module {
  require(isPow2(entries) && entries >= 4)
  val bits = log2Ceil(entries)
  require((new TmaTimer(bits)).getWidth == 64 + bits)
  val io = IO(new Bundle {
    val push = Flipped(Decoupled(new TmaTimer(bits)))
    val pop = Decoupled(new TmaTimer(bits))
    val count = Output(UInt((bits + 1).W))
    val idle = Output(Bool())
  })
  // Keep each row a single physical word. Aggregate memories are split by
  // FIRRTL and cannot retain Golden Gate's one-target RAMStyleHint annotation.
  val mem = SyncReadMem(entries, UInt((64 + bits).W))
  val idle :: upRead :: upCompare :: upRoot :: lastRead :: lastCapture :: childRead :: childCapture :: siblingRead :: siblingCompare :: Nil = Enum(10)
  val state = RegInit(idle)
  val count = RegInit(0.U((bits + 1).W))
  val root = Reg(new TmaTimer(bits))
  val held = Reg(new TmaTimer(bits))
  val cursor = Reg(UInt(bits.W))
  val child = Reg(new TmaTimer(bits))
  val childIndex = Reg(UInt(bits.W))
  val readEn = WireDefault(false.B)
  val readAddr = WireDefault(0.U(bits.W))
  val data = mem.read(readAddr, readEn).asTypeOf(new TmaTimer(bits))
  val writeEn = WireDefault(false.B)
  val writeAddr = WireDefault(0.U(bits.W))
  val writeData = WireDefault(0.U.asTypeOf(new TmaTimer(bits)))
  when(writeEn) { mem.write(writeAddr, writeData.asUInt) }
  def before(a: TmaTimer, b: TmaTimer): Bool = a.cycle < b.cycle || (a.cycle === b.cycle && a.slot < b.slot)
  def put(index: UInt, value: TmaTimer): Unit = {
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

class TmaServiceHead extends Bundle {
  val token = UInt(64.W)
  val epoch = UInt(32.W)
  val head = UInt(19.W)
}
class TmaDependencyEdge extends Bundle {
  val group = UInt(12.W)
  val next = UInt(19.W)
}
class TmaGroupRow extends Bundle {
  val remaining = UInt(32.W)
  val floor = UInt(64.W)
  val conservative = Bool()
  val active = Bool()
}

/** Indexed wake dependencies, independent of public bundles. The target token
  * stays held while synchronous RAM visits only the groups affected by returns.
  * Metadata opcodes 4..8 are installed while paused; epoch commit is atomic.
  */
class TmaWakeMonitor(val lanes: Int, val servicesCount: Int = 65536,
    val groupsCount: Int = 4096, val edgesCount: Int = 262144) extends Module {
  require(isPow2(servicesCount) && servicesCount <= 65536)
  require(isPow2(groupsCount) && groupsCount >= 4 && groupsCount <= 4096)
  require(isPow2(edgesCount) && edgesCount <= 262144)
  require((new TmaServiceHead).getWidth == 115)
  require((new TmaDependencyEdge).getWidth == 31)
  require((new TmaGroupRow).getWidth == 98)
  val sb = log2Ceil(servicesCount); val gb = log2Ceil(groupsCount); val eb = log2Ceil(edgesCount)
  val io = IO(new Bundle {
    val metadata = Flipped(Decoupled(UInt(512.W)))
    val metadataIdle = Output(Bool())
    val tokenValid = Input(Bool())
    val recheck = Input(Bool())
    val cycle = Input(UInt(64.W))
    val returns = Input(UInt(lanes.W))
    val tokens = Input(Vec(lanes, UInt(64.W)))
    val returnCycles = Input(Vec(lanes, UInt(64.W)))
    val tokenReady = Output(Bool())
    val tokenIdle = Output(Bool())
    val tokenConsumed = Input(Bool())
    val wake = Output(Bool())
    val phaseWake = Output(Bool())
    val transferWake = Output(Bool())
    val pending = Output(Bool())
    val honorDeadline = Output(Bool())
    val batchSemantics = Output(Bool())
    val report = Decoupled(UInt(512.W))
    val error = Output(UInt(32.W))
    val groupCount = Output(UInt(13.W))
    val edgeCount = Output(UInt(19.W))
    val timerCount = Output(UInt(13.W))
  })
  val heads = SyncReadMem(servicesCount, UInt(115.W))
  val edges = SyncReadMem(edgesCount, UInt(31.W))
  val groups = SyncReadMem(groupsCount, UInt(98.W))
  val clearHeap = WireDefault(false.B)
  val heap = withReset(reset.asBool || clearHeap) { Module(new TmaTimerHeap(groupsCount)) }
  val init :: idle :: pushTimer :: headRead :: headCapture :: edgeRead :: edgeCapture :: groupRead :: groupCapture :: timerCheck :: timerRead :: timerCapture :: report :: done :: Nil = Enum(14)
  val state = RegInit(init)
  val cursor = RegInit(0.U(sb.W))
  val epoch = RegInit(0.U(32.W))
  val stagedEpoch = RegInit(0.U(32.W))
  val published = RegInit(true.B)
  val expectedGroups = RegInit(0.U(13.W)); val expectedEdges = RegInit(0.U(19.W))
  val installedGroups = RegInit(0.U(13.W)); val installedEdges = RegInit(0.U(19.W))
  val activeGroups = RegInit(0.U(13.W))
  val honorDeadline = RegInit(false.B)
  val batchSemantics = RegInit(false.B)
  val error = RegInit(0.U(32.W))
  val cycle = Reg(UInt(64.W))
  val mask = Reg(UInt(lanes.W)); val tokens = Reg(Vec(lanes,UInt(64.W)))
  val returnCycles = Reg(Vec(lanes,UInt(64.W)))
  val nextEdge = Reg(UInt(19.W)); val edge = Reg(new TmaDependencyEdge)
  val slot = Reg(UInt(gb.W)); val row = Reg(new TmaGroupRow)
  val afterPush = Reg(UInt(4.W))
  val phaseWake = RegInit(false.B); val transferWake = RegInit(false.B)
  val hRead = WireDefault(false.B); val hAddr = WireDefault(0.U(sb.W))
  val hData = heads.read(hAddr,hRead).asTypeOf(new TmaServiceHead)
  val eRead = WireDefault(false.B); val eAddr = WireDefault(0.U(eb.W))
  val eData = edges.read(eAddr,eRead).asTypeOf(new TmaDependencyEdge)
  val gRead = WireDefault(false.B); val gAddr = WireDefault(0.U(gb.W))
  val gData = groups.read(gAddr,gRead).asTypeOf(new TmaGroupRow)
  val hWrite = WireDefault(false.B); val hWriteAddr = WireDefault(0.U(sb.W)); val hWriteData = WireDefault(0.U(115.W))
  val gWrite = WireDefault(false.B); val gWriteAddr = WireDefault(0.U(gb.W)); val gWriteData = WireDefault(0.U(98.W))
  when(hWrite) { heads.write(hWriteAddr,hWriteData) }
  when(gWrite) { groups.write(gWriteAddr,gWriteData) }
  def writeHead(index: UInt, value: UInt): Unit = { hWrite:=true.B; hWriteAddr:=index; hWriteData:=value }
  def writeGroup(index: UInt, value: UInt): Unit = { gWrite:=true.B; gWriteAddr:=index; gWriteData:=value }
  def fail(code: Int): Unit = { error := code.U; assert(false.B,"TMA wake metadata or completion invariant failed") }
  def queueTimer(value: TmaGroupRow, index: UInt, resume: UInt): Unit = {
    row := value; slot := index; afterPush := resume; state := pushTimer
  }
  io.metadata.ready := (state === idle || state === done) && heap.io.idle && !io.tokenValid && error === 0.U
  io.metadataIdle := (state === idle || state === done) && heap.io.idle && error === 0.U
  io.tokenReady := state === done && published && error === 0.U
  io.tokenIdle := state === idle && heap.io.idle && published
  io.phaseWake := phaseWake; io.transferWake := transferWake; io.wake := phaseWake || transferWake
  io.pending := activeGroups =/= 0.U
  io.honorDeadline := honorDeadline
  io.batchSemantics := batchSemantics
  io.groupCount := activeGroups; io.edgeCount := installedEdges; io.timerCount := heap.io.count
  io.error := error
  io.report.valid := state === report && error === 0.U
  io.report.bits := Cat(cycle,epoch,0.U(20.W),slot.pad(12),Mux(row.conservative,4.U(8.W),3.U(8.W)))
  heap.io.push.valid := state === pushTimer && error === 0.U
  heap.io.push.bits.cycle := row.floor; heap.io.push.bits.slot := slot
  heap.io.pop.ready := state === timerCheck && heap.io.pop.bits.cycle <= cycle && error === 0.U
  when(io.metadata.fire) {
    val d=io.metadata.bits
    switch(d(7,0)) {
      is(4.U) {
        when(!published || d(39,8) === 0.U || d(39,8) <= epoch || d(52,40)>groupsCount.U || d(71,53)>edgesCount.U) { fail(1) }
        stagedEpoch := d(39,8); expectedGroups := d(52,40); expectedEdges := d(71,53)
        installedGroups := 0.U; installedEdges := 0.U; activeGroups := 0.U
        honorDeadline := d(72); batchSemantics := d(73); published := false.B; clearHeap := true.B
      }
      is(5.U) {
        when(published || d(19,8) =/= installedGroups || installedGroups >= expectedGroups) { fail(2) }
        val r=Wire(new TmaGroupRow); r.remaining:=d(51,20); r.floor:=d(115,52)
        r.conservative:=d(116); r.active:=true.B
        writeGroup(d(8+gb-1,8),r.asUInt)
        installedGroups:=installedGroups+1.U; activeGroups:=activeGroups+1.U
        when(r.remaining === 0.U) { queueTimer(r,d(8+gb-1,8),state) }
      }
      is(6.U) {
        when(published || d(25,8) =/= installedEdges || installedEdges >= expectedEdges ||
          d(37,26) >= expectedGroups || (d(56,38) =/= 262144.U && d(56,38) >= installedEdges)) { fail(3) }
        val e=Wire(new TmaDependencyEdge); e.group:=d(37,26); e.next:=d(56,38)
        edges.write(d(8+eb-1,8),e.asUInt); installedEdges:=installedEdges+1.U
      }
      is(7.U) {
        val e = Mux(published,epoch,stagedEpoch)
        when(d(103,72) =/= e || e === 0.U || d(122,104) >= expectedEdges ||
          d(23,8) >= servicesCount.U || d(71,24) === 0.U) { fail(4) }
        val h=Wire(new TmaServiceHead); h.token:=d(71,8); h.epoch:=e; h.head:=d(122,104)
        writeHead(d(8+sb-1,8),h.asUInt)
      }
      is(8.U) {
        when(published || d(39,8) =/= stagedEpoch || installedGroups =/= expectedGroups || installedEdges =/= expectedEdges) { fail(5) }
        epoch:=stagedEpoch; published:=true.B
      }
    }
    when(d(7,0)<4.U || d(7,0)>8.U) { fail(6) }
  }
  when((state === idle && io.tokenValid) || (state === done && io.recheck)) {
    assert(published,"TMA token evaluated before atomic policy publication")
    cycle:=io.cycle; mask:=io.returns; tokens:=io.tokens; returnCycles:=io.returnCycles
    phaseWake:=false.B; transferWake:=false.B; state:=headRead
  }
  switch(state) {
    is(init) {
      writeHead(cursor,0.U(115.W)); cursor:=cursor+1.U
      when(cursor === (servicesCount-1).U) { state:=idle }
    }
    is(pushTimer) { when(heap.io.push.fire) { state:=afterPush } }
    is(headRead) {
      when(mask.orR) { hRead:=true.B; hAddr:=tokens(PriorityEncoder(mask))(sb-1,0); state:=headCapture }
        .otherwise { state:=timerCheck }
    }
    is(headCapture) {
      val lane=PriorityEncoder(mask)
      when(returnCycles(lane)>cycle) { fail(7) }
      mask:=mask & ~UIntToOH(lane,lanes)
      when(hData.epoch === epoch && epoch =/= 0.U && hData.token === tokens(lane)) {
        nextEdge:=hData.head; writeHead(tokens(lane)(sb-1,0),0.U(115.W)); state:=edgeRead
      }.otherwise { state:=headRead }
    }
    is(edgeRead) {
      when(nextEdge === 262144.U) { state:=headRead }
        .elsewhen(nextEdge >= installedEdges) { fail(8) }
        .otherwise { eRead:=true.B; eAddr:=nextEdge(eb-1,0); state:=edgeCapture }
    }
    is(edgeCapture) { edge:=eData; nextEdge:=eData.next; slot:=eData.group; state:=groupRead }
    is(groupRead) { gRead:=true.B; gAddr:=slot; state:=groupCapture }
    is(groupCapture) {
      when(!gData.active || gData.remaining === 0.U) { fail(9) }
      val r=WireDefault(gData); r.remaining:=gData.remaining-1.U
      writeGroup(slot,r.asUInt)
      when(gData.remaining === 1.U) { queueTimer(r,slot,edgeRead) }.otherwise { state:=edgeRead }
    }
    is(timerCheck) {
      when(heap.io.pop.fire) { slot:=heap.io.pop.bits.slot; state:=timerRead }
        .elsewhen(heap.io.idle && (!heap.io.pop.valid || heap.io.pop.bits.cycle>cycle)) { state:=done }
    }
    is(timerRead) { gRead:=true.B; gAddr:=slot; state:=timerCapture }
    is(timerCapture) {
      when(!gData.active || gData.remaining =/= 0.U || gData.floor>cycle) { fail(10) }
      row:=gData; state:=report
    }
    is(report) {
      when(io.report.fire) {
        val r=WireDefault(row); r.active:=false.B; writeGroup(slot,r.asUInt)
        activeGroups:=activeGroups-1.U
        when(row.conservative) { transferWake:=true.B }.otherwise { phaseWake:=true.B }
        state:=timerCheck
      }
    }
    is(done) { when(io.tokenConsumed && !io.recheck) { state:=idle } }
  }
  when(io.tokenConsumed) { assert(io.tokenReady,"TMA token consumed before accounting finished") }
  when(state =/= idle && state =/= init && state =/= done && state =/= pushTimer) {
    assert(io.cycle === cycle,"Target cycle advanced during TMA bookkeeping")
  }
}
