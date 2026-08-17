package hyperdim

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.HellaCacheReq
import org.chipsalliance.cde.config.Parameters

import hyperdim.ops.{AmSearchOp, HammingOp}
import hyperdim.mem.VectorStreamer
import hyperdim.isa.HyperDimISA

class HyperDimRoCC(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  val params = p(HyperDimParamsKey)
  override lazy val module = new HyperDimRoCCModuleImp(this)
}

class HyperDimRoCCModuleImp(outer: HyperDimRoCC)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer) {

  val params        = outer.params
  val maxQueryWords = params.vectorBits / 64
  val streamWords   = params.streamWords

  // --------------------------------------------------------------------
  // Configuration registers (written by OP_SETCFG)
  //   cfgWords      -- 64-bit words per hypervector (runtime length)
  //   cfgNumClasses -- number of class hypervectors in the assoc. memory
  // --------------------------------------------------------------------
  val cfgWords      = RegInit(maxQueryWords.U(32.W))
  val cfgNumClasses = RegInit(1.U(32.W))

  // --------------------------------------------------------------------
  // Submodules
  // --------------------------------------------------------------------
  val streamerA  = Module(new VectorStreamer(streamWords))
  val streamerB  = Module(new VectorStreamer(streamWords))
  val hammingOp  = Module(new HammingOp)
  val amSearchOp = Module(new AmSearchOp(maxQueryWords))

  val cmd = Queue(io.cmd)

  object State extends ChiselEnum {
    val sIdle, sRun, sRespond = Value
  }
  import State._
  val state = RegInit(sIdle)

  val respRd   = RegInit(0.U(5.W))
  val respData = RegInit(0.U(64.W))

  val funct      = cmd.bits.inst.funct
  val isHamming  = funct === HyperDimISA.OP_HAMMING
  val isAmSearch = funct === HyperDimISA.OP_AM_SEARCH
  val isSetCfg   = funct === HyperDimISA.OP_SETCFG

  // Which op owns the streams for the instruction in flight
  val activeIsAm = RegInit(false.B)

  // --------------------------------------------------------------------
  // Stream routing: Hamming owns the streams unless an AM search is live
  // --------------------------------------------------------------------
  streamerA.io.out.ready := Mux(activeIsAm, amSearchOp.io.query.ready,   hammingOp.io.streamA.ready)
  streamerB.io.out.ready := Mux(activeIsAm, amSearchOp.io.classes.ready, hammingOp.io.streamB.ready)

  hammingOp.io.streamA.valid := streamerA.io.out.valid && !activeIsAm
  hammingOp.io.streamA.bits  := streamerA.io.out.bits
  hammingOp.io.streamB.valid := streamerB.io.out.valid && !activeIsAm
  hammingOp.io.streamB.bits  := streamerB.io.out.bits

  amSearchOp.io.query.valid   := streamerA.io.out.valid && activeIsAm
  amSearchOp.io.query.bits    := streamerA.io.out.bits
  amSearchOp.io.classes.valid := streamerB.io.out.valid && activeIsAm
  amSearchOp.io.classes.bits  := streamerB.io.out.bits

  // --------------------------------------------------------------------
  // Streamer setup (latched by each streamer when `start` fires).
  // AM search: A streams the query (cfgWords), B streams the whole AM
  // (cfgNumClasses x cfgWords).
  // --------------------------------------------------------------------
  val amTotalWords = (cfgWords * cfgNumClasses)(31, 0)

  streamerA.io.baseAddr := cmd.bits.rs1
  streamerA.io.len      := cfgWords
  streamerA.io.streamId := 0.U

  streamerB.io.baseAddr := cmd.bits.rs2
  streamerB.io.len      := Mux(isAmSearch, amTotalWords, cfgWords)
  streamerB.io.streamId := 1.U

  val startStreams = cmd.fire && (isHamming || isAmSearch)
  streamerA.io.start := startStreams
  streamerB.io.start := startStreams

  hammingOp.io.start := cmd.fire && isHamming
  hammingOp.io.len   := cfgWords

  amSearchOp.io.start      := cmd.fire && isAmSearch
  amSearchOp.io.numWords   := cfgWords
  amSearchOp.io.numClasses := cfgNumClasses

  assert(!(cmd.fire && isAmSearch) || cfgWords <= maxQueryWords.U,
    "HyperDimRoCC: cfgWords exceeds query buffer (raise HyperDimParams.vectorBits)")

  // --------------------------------------------------------------------
  // Command FSM
  // --------------------------------------------------------------------
  cmd.ready := state === sIdle

  when (cmd.fire) {
    respRd     := cmd.bits.inst.rd
    activeIsAm := isAmSearch
    when (isHamming || isAmSearch) {
      state := sRun
    }.elsewhen (isSetCfg) {
      cfgWords      := Mux(cmd.bits.rs1(31, 0) === 0.U, 1.U, cmd.bits.rs1(31, 0))
      cfgNumClasses := Mux(cmd.bits.rs2(31, 0) === 0.U, 1.U, cmd.bits.rs2(31, 0))
      // Only respond if the instruction actually has a destination register
      // (the SETCFG macro uses the SS form, xd = 0).
      when (cmd.bits.inst.xd) {
        respData := 0.U
        state    := sRespond
      }
    }.otherwise {
      // Unknown funct: respond 0 rather than hang the core.
      respData := 0.U
      state    := sRespond
    }
  }

  when (state === sRun) {
    when (activeIsAm && amSearchOp.io.result.valid) {
      respData := amSearchOp.io.result.bits
      state    := sRespond
    }.elsewhen (!activeIsAm && hammingOp.io.result.valid) {
      respData := hammingOp.io.result.bits
      state    := sRespond
    }
  }

  when (state === sRespond) {
    when (io.resp.fire) {
      state := sIdle
    }
  }

  io.resp.valid     := state === sRespond
  io.resp.bits.rd   := respRd
  io.resp.bits.data := respData

  io.busy      := state =/= sIdle
  io.interrupt := false.B

  // --------------------------------------------------------------------
  // Shared cache port
  // --------------------------------------------------------------------
  val reqArb = Module(new RRArbiter(new HellaCacheReq, 2))
  reqArb.io.in(0) <> streamerA.io.req
  reqArb.io.in(1) <> streamerB.io.req
  io.mem.req <> reqArb.io.out
  streamerA.io.resp := io.mem.resp
  streamerB.io.resp := io.mem.resp

  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
}
