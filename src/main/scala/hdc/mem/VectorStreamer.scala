package hyperdim.mem

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.{HellaCacheReq, HellaCacheResp}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import org.chipsalliance.cde.config.Parameters

class VectorStreamer(maxWords: Int)(implicit p: Parameters) extends Module with MemoryOpConstants {
  val idxBits = log2Ceil(maxWords)
  val lenBits = log2Ceil(maxWords + 1)

  val io = IO(new Bundle {
    val start    = Input(Bool())
    val baseAddr = Input(UInt(64.W))
    val len      = Input(UInt(lenBits.W))
    val streamId = Input(UInt(1.W))
    val done     = Output(Bool())

    val req  = Decoupled(new HellaCacheReq)
    val resp = Input(Valid(new HellaCacheResp))

    val out = Decoupled(UInt(64.W))
  })

  val tagBits    = io.req.bits.tag.getWidth
  val tagIdxBits = tagBits - 1
  require(maxWords <= (1 << tagIdxBits),
    s"maxWords ($maxWords) exceeds tag index capacity (${1 << tagIdxBits})")

  object State extends ChiselEnum {
    val sIdle, sRun = Value
  }
  import State._
  val state = RegInit(sIdle)

  val regBase     = Reg(UInt(64.W))
  val regLen      = Reg(UInt(lenBits.W))
  val regStreamId = Reg(UInt(1.W))

  val issueIdx  = Reg(UInt(tagIdxBits.W))
  val commitIdx = Reg(UInt(tagIdxBits.W))

  val dataBuf  = Reg(Vec(maxWords, UInt(64.W)))
  val validBuf = RegInit(VecInit(Seq.fill(maxWords)(false.B)))

  io.req.bits := DontCare
  io.req.bits.addr   := regBase + (issueIdx << 3.U)
  io.req.bits.tag    := Cat(regStreamId, issueIdx)
  io.req.bits.cmd    := M_XRD
  io.req.bits.size   := log2Ceil(8).U
  io.req.bits.signed := false.B
  io.req.bits.phys   := false.B
  io.req.bits.dprv   := 0.U(2.W)
  io.req.bits.dv     := false.B
  io.req.bits.no_alloc := false.B
  io.req.bits.no_xcpt  := false.B
  io.req.bits.no_resp  := false.B

  io.req.valid := (state === sRun) && (issueIdx < regLen)
  when (io.req.fire) {
    issueIdx := issueIdx + 1.U
  }

  val respStreamId = io.resp.bits.tag(tagBits - 1)
  val respIdx      = io.resp.bits.tag(idxBits - 1, 0)

  when (io.resp.valid && respStreamId === regStreamId) {
    dataBuf(respIdx)  := io.resp.bits.data
    validBuf(respIdx) := true.B
  }

  val readIdx = commitIdx(idxBits - 1, 0)

  io.out.valid := (state === sRun) && (commitIdx < regLen) && validBuf(readIdx)
  io.out.bits  := dataBuf(readIdx)
  when (io.out.fire) {
    commitIdx := commitIdx + 1.U
  }

  io.done := (state === sRun) && (commitIdx === regLen)

  when (state === sIdle) {
    when (io.start) {
      regBase     := io.baseAddr
      regLen      := io.len
      regStreamId := io.streamId
      issueIdx    := 0.U
      commitIdx   := 0.U
      validBuf.foreach(_ := false.B)
      state := sRun
    }
  }.elsewhen (io.done) {
    state := sIdle
  }
}