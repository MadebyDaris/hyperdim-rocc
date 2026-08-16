package hyperdim

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.HellaCacheReq
import org.chipsalliance.cde.config.Parameters

import hyperdim.ops.HammingOp
import hyperdim.mem.VectorStreamer
import hyperdim.isa.HyperDimISA

class HyperDimRoCC(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  val params = p(HyperDimParamsKey)
  override lazy val module = new HyperDimRoCCModuleImp(this)
}

class HyperDimRoCCModuleImp(outer: HyperDimRoCC)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer) {

  val params   = outer.params
  val numWords = params.vectorBits / 64

  val streamerA = Module(new VectorStreamer(numWords))
  val streamerB = Module(new VectorStreamer(numWords))
  val hammingOp = Module(new HammingOp(numWords))

  val cmd = Queue(io.cmd)

  object State extends ChiselEnum {
    val sIdle, sLoad, sRespond = Value
  }
  import State._
  val state = RegInit(sIdle)

  val respRd   = RegInit(0.U(5.W))
  val respData = RegInit(0.U(64.W))

  streamerA.io.out <> hammingOp.io.streamA
  streamerB.io.out <> hammingOp.io.streamB

  hammingOp.io.len := numWords.U

  streamerA.io.baseAddr := cmd.bits.rs1
  streamerA.io.len      := numWords.U
  streamerA.io.streamId := 0.U

  streamerB.io.baseAddr := cmd.bits.rs2
  streamerB.io.len      := numWords.U
  streamerB.io.streamId := 1.U

  val doHamming = cmd.bits.inst.funct === HyperDimISA.OP_HAMMING

  streamerA.io.start := cmd.fire && doHamming
  streamerB.io.start := cmd.fire && doHamming
  hammingOp.io.start := cmd.fire && doHamming

  val reqArb = Module(new RRArbiter(new HellaCacheReq, 2))
  reqArb.io.in(0) <> streamerA.io.req
  reqArb.io.in(1) <> streamerB.io.req
  io.mem.req <> reqArb.io.out
  streamerA.io.resp := io.mem.resp
  streamerB.io.resp := io.mem.resp

  cmd.ready := state === sIdle

  when (cmd.fire) {
    respRd := cmd.bits.inst.rd
    when (doHamming) {
      state := sLoad
    }.otherwise {
      respData := 0.U
      state    := sRespond
    }
  }

  when (state === sLoad) {
    when (hammingOp.io.result.valid) {
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

  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
}