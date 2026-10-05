package hyperdim

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import org.chipsalliance.cde.config.Parameters

import hyperdim.ops.{AmSearchOp, HammingOp, CosineOp, DotProductOp, SetCfgOp}
import hyperdim.mem.{StreamReq, StreamResp, VectorStreamer}
import hyperdim.isa.HyperDimISA

class HyperDimRoCC(opcodes: OpcodeSet)(implicit p: Parameters)
    extends LazyRoCC(opcodes) {
  val params = p(HyperDimParamsKey)
  override lazy val module = new HyperDimRoCCModuleImp(this)
}

class HyperDimRoCCModuleImp(outer: HyperDimRoCC)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

  val params = outer.params
  val maxQueryWords = params.vectorBits / 64
  val streamWords = params.streamWords
  val tagBits = io.mem.req.bits.tag.getWidth

  // Configuration registers (written by OP_SETCFG)
  //   cfgWords      -- 64-bit words per hypervector (runtime length)
  //   cfgNumClasses -- number of class hypervectors in the assoc. memory
  val cfgWords = RegInit(maxQueryWords.U(32.W))
  val cfgNumClasses = RegInit(1.U(32.W))
  val cfgMetric = RegInit(0.U(2.W))

  // Submodules
  val streamerA = Module(new VectorStreamer(streamWords, tagBits))
  val streamerB = Module(new VectorStreamer(streamWords, tagBits))
  val hammingOp = Module(new HammingOp)
  // val dotProductOp = Module(new DotProductOp)
  // val cosineOp = Module(new CosineOp)
  val amSearchOp = Module(new AmSearchOp(maxQueryWords, params.numClasses))
  val setCfgOp = Module(new SetCfgOp)
  setCfgOp.io.resp := DontCare

  // Queue for incoming commands
  val cmd = Queue(io.cmd)

  object State extends ChiselEnum {
    val sIdle, sRun, sRespond, sWaitConfig = Value
  }
  import State._
  val state = RegInit(sIdle)

  val respRd = RegInit(0.U(5.W))
  val respXd = RegInit(false.B)
  val respData = RegInit(0.U(64.W))

  val funct = cmd.bits.inst.funct
  val isHamming = funct === HyperDimISA.OP_HAMMING
  val isAmSearch = funct === HyperDimISA.OP_AM_SEARCH
  val isSetCfg = funct === HyperDimISA.OP_SETCFG

  // Which op owns the streams for the instruction in flight
  val activeIsAm = RegInit(false.B)
  // Stream routing: Hamming owns the streams unless an AM search is live
  streamerA.io.out.ready := Mux(
    activeIsAm,
    amSearchOp.io.query.ready,
    hammingOp.io.streamA.ready
  )
  streamerB.io.out.ready := Mux(
    activeIsAm,
    amSearchOp.io.classes.ready,
    hammingOp.io.streamB.ready
  )

  hammingOp.io.streamA.valid := streamerA.io.out.valid && !activeIsAm
  hammingOp.io.streamA.bits := streamerA.io.out.bits
  hammingOp.io.streamB.valid := streamerB.io.out.valid && !activeIsAm
  hammingOp.io.streamB.bits := streamerB.io.out.bits

  amSearchOp.io.query.valid := streamerA.io.out.valid && activeIsAm
  amSearchOp.io.query.bits := streamerA.io.out.bits
  amSearchOp.io.classes.valid := streamerB.io.out.valid && activeIsAm
  amSearchOp.io.classes.bits := streamerB.io.out.bits

  // --------------------------------------------------------------------
  // Streamer setup (latched by each streamer when `start` fires).
  // AM search: A streams the query (cfgWords), B streams the whole AM
  // (cfgNumClasses x cfgWords).
  // --------------------------------------------------------------------
  val amTotalWords = (cfgWords * cfgNumClasses)(31, 0)

  streamerA.io.baseAddr := cmd.bits.rs1
  streamerA.io.len := cfgWords
  streamerA.io.streamId := 0.U

  streamerB.io.baseAddr := cmd.bits.rs2
  streamerB.io.len := Mux(isAmSearch, amTotalWords, cfgWords)
  streamerB.io.streamId := 1.U

// Firing of instructions to submodules
  val startStreams = cmd.fire && (isHamming || isAmSearch)
  streamerA.io.start := startStreams
  streamerB.io.start := startStreams

  hammingOp.io.start := cmd.fire && isHamming
  hammingOp.io.len := cfgWords

  amSearchOp.io.start := cmd.fire && isAmSearch
  amSearchOp.io.numWords := cfgWords
  amSearchOp.io.numClasses := cfgNumClasses
  amSearchOp.io.dist_func := cfgMetric

  assert(
    !(cmd.fire && isAmSearch) || cfgWords <= maxQueryWords.U,
    "HyperDimRoCC: cfgWords exceeds query buffer (raise HyperDimParams.vectorBits)"
  )

  setCfgOp.io.start := cmd.fire && isSetCfg
  setCfgOp.io.cfgData := Cat(
    cmd.bits.rs1(47, 32), // upper bits of rs1 for metric
    cmd.bits.rs2(15, 0), // lower bits of rs2 for classes
    cmd.bits.rs1(31, 0) // lower bits of rs1 for words
  )

  // --------------------------------------------------------------------
  // Command FSM
  // --------------------------------------------------------------------
  cmd.ready := state === sIdle

  when(cmd.fire) {
    respRd := cmd.bits.inst.rd
    respXd := cmd.bits.inst.xd
    activeIsAm := isAmSearch
    when(isHamming || isAmSearch) {
      state := sRun
    }.elsewhen(isSetCfg) {
      cfgWords := Mux(cmd.bits.rs1(31, 0) === 0.U, 1.U, cmd.bits.rs1(31, 0))
      cfgMetric := cmd.bits.rs1(63, 32)
      cfgNumClasses := Mux(
        cmd.bits.rs2(31, 0) === 0.U,
        1.U,
        cmd.bits.rs2(31, 0)
      )

      state := sWaitConfig
    }.otherwise {
      // Unknown funct: respond 0 rather than hang the core.
      respData := 0.U
      state := sRespond
    }
  }

  when(state === sRun) {
    when(activeIsAm && amSearchOp.io.result.valid) {
      respData := amSearchOp.io.result.bits
      state := sRespond
    }.elsewhen(!activeIsAm && hammingOp.io.result.valid) {
      respData := hammingOp.io.result.bits
      state := sRespond
    }
  }

  when(state === sRespond) {
    when(io.resp.fire) {
      state := sIdle
    }
  }

  when(state === sWaitConfig) {
    when(setCfgOp.io.done) {
      when(respXd) {
        respData := 0.U
        state := sRespond
      }.otherwise {
        state := sIdle
      }
    }
  }

  io.resp.valid := state === sRespond
  io.resp.bits.rd := respRd
  io.resp.bits.data := respData

  io.busy := state =/= sIdle
  io.interrupt := false.B

  // --------------------------------------------------------------------
  // Shared cache port
  //
  // io.mem is already wrapped in its own SimpleHellaCacheIF (with replay
  // and nack handling) by the framework in HasLazyRoCCModule, so it can be
  // driven directly here -- wrapping it in a second SimpleHellaCacheIF
  // double-buffers requests/responses and deadlocks the accelerator as
  // soon as it issues real memory traffic.
  // --------------------------------------------------------------------
  // setCfgOp never issues a request (its valid is tied low), so it is not arbitrated.
  setCfgOp.io.req.ready := false.B

  val reqArb = Module(new RRArbiter(new StreamReq(tagBits), 2))
  reqArb.io.in(0) <> streamerA.io.req
  reqArb.io.in(1) <> streamerB.io.req

  io.mem.req.valid := reqArb.io.out.valid
  reqArb.io.out.ready := io.mem.req.ready
  io.mem.req.bits := DontCare
  io.mem.req.bits.addr := reqArb.io.out.bits.addr
  io.mem.req.bits.tag := reqArb.io.out.bits.tag
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.dprv := 3.U(2.W)
  io.mem.req.bits.dv := false.B
  io.mem.req.bits.no_resp := false.B
  io.mem.req.bits.no_alloc := false.B
  io.mem.req.bits.no_xcpt := false.B

  val memResp = Wire(Valid(new StreamResp(tagBits)))
  memResp.valid := io.mem.resp.valid
  memResp.bits.tag := io.mem.resp.bits.tag
  memResp.bits.data := io.mem.resp.bits.data
  streamerA.io.resp := memResp
  streamerB.io.resp := memResp

  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B

  val dbgPrev = RegNext(state)
  when(state =/= dbgPrev) {
    printf("[RoCC] %d -> %d funct=%d aOutV=%d aReqV=%d aOutR=%d bOutV=%d bReqV=%d\n",
      dbgPrev.asUInt, state.asUInt, funct,
      streamerA.io.out.valid, streamerA.io.req.valid, streamerA.io.out.ready,
      streamerB.io.out.valid, streamerB.io.req.valid)
  }
}
