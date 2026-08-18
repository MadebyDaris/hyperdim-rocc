package hyperdim.mem

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.{HellaCacheReq, HellaCacheResp}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import org.chipsalliance.cde.config.Parameters

/**
 * VectorStreamer
 *
 * Streams `len` consecutive 64-bit words starting at `baseAddr` and emits
 * them in order on `io.out`.
 *
 * Requests are issued in windows of at most `windowWords` words: the reorder
 * buffer has one slot per window word and the cache tag encodes only
 * {streamId, slot}, so a window must be fully drained before the next one is
 * issued. Responses within a window may complete out of order. Streaming an
 * arbitrarily long region (e.g. a whole associative memory) therefore works
 * with a small, tag-bounded reorder buffer.
 */
class VectorStreamer(windowWords: Int)(implicit p: Parameters) extends Module with MemoryOpConstants {
  require(windowWords > 1, "windowWords must be at least 2")

  val slotBits = log2Ceil(windowWords)
  val cntBits  = log2Ceil(windowWords + 1)

  val io = IO(new Bundle {
    val start    = Input(Bool())
    val baseAddr = Input(UInt(64.W))
    val len      = Input(UInt(32.W))   // total words to stream
    val streamId = Input(UInt(1.W))
    val done     = Output(Bool())

    val req  = Decoupled(new HellaCacheReq)
    val resp = Input(Valid(new HellaCacheResp))

    val out = Decoupled(UInt(64.W))
  })

  val tagBits = io.req.bits.tag.getWidth
  require(windowWords <= (1 << (tagBits - 1)),
    s"windowWords ($windowWords) exceeds tag index capacity (${1 << (tagBits - 1)})")

  object State extends ChiselEnum {
    val sIdle, sRun = Value
  }
  import State._
  val state = RegInit(sIdle)

  val regBase     = Reg(UInt(64.W))
  val totalLen    = Reg(UInt(32.W))
  val regStreamId = Reg(UInt(1.W))

  val winBase   = Reg(UInt(32.W))        // global word index of current window
  val issueIdx  = Reg(UInt(cntBits.W))   // words issued within the window
  val commitIdx = Reg(UInt(cntBits.W))   // words drained within the window

  val dataBuf  = Reg(Vec(windowWords, UInt(64.W)))
  val validBuf = RegInit(VecInit(Seq.fill(windowWords)(false.B)))

  val remaining = totalLen - winBase
  val winSize   = Mux(remaining >= windowWords.U, windowWords.U, remaining)

  // ---------------- Issuer ----------------
  io.req.bits := DontCare
  io.req.bits.addr     := regBase + ((winBase + issueIdx) << 3.U)
  io.req.bits.tag      := Cat(regStreamId, issueIdx(slotBits - 1, 0))
  io.req.bits.cmd      := M_XRD
  io.req.bits.size     := log2Ceil(8).U
  io.req.bits.signed   := false.B
  io.req.bits.phys     := false.B
  io.req.bits.dprv     := 0.U(2.W)
  io.req.bits.dv       := false.B
  io.req.bits.no_alloc := false.B
  io.req.bits.no_xcpt  := false.B
  io.req.bits.no_resp  := false.B

  io.req.valid := (state === sRun) && (issueIdx < winSize)
  when (io.req.fire) {
    issueIdx := issueIdx + 1.U
  }

  // ---------------- Completer ----------------
  // Responses are broadcast to every streamer; match on streamId and only
  // accept while running (a stale response must not corrupt an idle buffer).
  val respStreamId = io.resp.bits.tag(tagBits - 1)
  val respSlot     = io.resp.bits.tag(slotBits - 1, 0)

  when ((state === sRun) && io.resp.valid && (respStreamId === regStreamId)) {
    dataBuf(respSlot)  := io.resp.bits.data
    validBuf(respSlot) := true.B
  }

  // ---------------- Drainer ----------------
  val winDrained = commitIdx === winSize
  val lastWindow = (winBase + winSize) === totalLen

  io.out.valid := (state === sRun) && !winDrained && validBuf(commitIdx(slotBits - 1, 0))
  io.out.bits  := dataBuf(commitIdx(slotBits - 1, 0))
  when (io.out.fire) {
    commitIdx := commitIdx + 1.U
  }

  io.done := (state === sRun) && winDrained && lastWindow

  // ---------------- Window control ----------------
  when (state === sIdle) {
    when (io.start) {
      regBase     := io.baseAddr
      totalLen    := io.len
      regStreamId := io.streamId
      winBase     := 0.U
      issueIdx    := 0.U
      commitIdx   := 0.U
      validBuf.foreach(_ := false.B)
      state := sRun
    }
  }.elsewhen (io.done) {
    state := sIdle
  }.elsewhen (winDrained) {
    // Window fully drained with words remaining: slide to the next window.
    winBase   := winBase + winSize
    issueIdx  := 0.U
    commitIdx := 0.U
    validBuf.foreach(_ := false.B)
  }
}
