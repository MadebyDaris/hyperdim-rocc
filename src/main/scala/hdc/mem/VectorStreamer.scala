package hyperdim.mem

import chisel3._
import chisel3.util._

/** Read request issued by a VectorStreamer. The owner converts it to a cache request. */
class StreamReq(tagBits: Int) extends Bundle {
  val addr = UInt(64.W)
  val tag = UInt(tagBits.W)
}

/** Read response delivered to a VectorStreamer. */
class StreamResp(tagBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val data = UInt(64.W)
}

/** VectorStreamer
  *
  * Streams `len` consecutive 64-bit words starting at `baseAddr` and emits them
  * in order on `io.out`.
  *
  * Requests are issued in windows of at most `windowWords` words: the reorder
  * buffer has one slot per window word and the request tag encodes only
  * {streamId, slot}, so a window must be fully drained before the next one is
  * issued. Responses within a window may complete out of order. Streaming an
  * arbitrarily long region (e.g. a whole associative memory) therefore works
  * with a small, tag-bounded reorder buffer.
  *
  * @param windowWords words per window (the reorder buffer size)
  * @param tagBits     width of the request/response tag
  */
class VectorStreamer(windowWords: Int, tagBits: Int) extends Module {
  require(windowWords > 1, "windowWords must be at least 2")

  val slotBits = log2Ceil(windowWords)
  val cntBits = log2Ceil(windowWords + 1)

  require(
    windowWords <= (1 << (tagBits - 1)),
    s"windowWords ($windowWords) exceeds tag index capacity (${1 << (tagBits - 1)})"
  )
  require(tagBits >= slotBits + 2, s"tagBits ($tagBits) leaves no padding above $slotBits slot bits")

  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddr = Input(UInt(64.W))
    val len = Input(UInt(32.W)) // total words to stream
    val streamId = Input(UInt(1.W))
    val done = Output(Bool())

    // Handshakes and data with the cache for each word in the stream.
    val req = Decoupled(new StreamReq(tagBits))
    val resp = Input(Valid(new StreamResp(tagBits)))

    val out = Decoupled(UInt(64.W))
  })

  object State extends ChiselEnum {
    val sIdle, sRun = Value
  }
  import State._
  val state = RegInit(sIdle)

  val regBase = Reg(UInt(64.W))
  val totalLen = Reg(UInt(32.W))
  val regStreamId = Reg(UInt(1.W))

  val winBase = Reg(UInt(32.W))       // global word index of current window start
  val issueIdx = Reg(UInt(cntBits.W)) // words issued within the window
  val commitIdx = Reg(UInt(cntBits.W))// words drained within the window

  // Ordered buffer of words in the current window, and valid bits for each slot.
  val dataBuf = Reg(Vec(windowWords, UInt(64.W)))
  val validBuf = RegInit(VecInit(Seq.fill(windowWords)(false.B)))

  val remaining = totalLen - winBase
  val winSize = Mux(remaining >= windowWords.U, windowWords.U, remaining)
  // number of words in current window (saturates at windowWords)

  // ---------------- Issuer ----------------
  // byte address in memory to fetch the next word
  io.req.bits.addr := regBase + ((winBase + issueIdx) << 3.U)

  // tag = { streamId (MSB), zero-pad, issueIdx slot (LSBs) }.
  // The stream-ID bit lands at tagBits-1, where the completer reads it back.
  io.req.bits.tag := Cat(
    regStreamId,
    0.U((tagBits - slotBits - 1).W),
    issueIdx(slotBits - 1, 0)
  )

  io.req.valid := (state === sRun) && (issueIdx < winSize)
  when(io.req.fire) {
    issueIdx := issueIdx + 1.U
  }

  // ---------------- Completer ----------------
  // Responses are broadcast to every streamer; match on streamId and only
  // accept while running (a stale response must not corrupt an idle buffer).

  val respStreamId = io.resp.bits.tag(tagBits - 1)
  val respSlot = io.resp.bits.tag(slotBits - 1, 0)

  when((state === sRun) && io.resp.valid && (respStreamId === regStreamId)) {
    dataBuf(respSlot) := io.resp.bits.data
    validBuf(respSlot) := true.B
  }

  // ---------------- Drainer ----------------
  val winDrained = commitIdx === winSize // window is fully drained
  val lastWindow = (winBase + winSize) === totalLen

  io.out.valid := (state === sRun) && !winDrained && validBuf(
    commitIdx(slotBits - 1, 0)
  )
  io.out.bits := dataBuf(commitIdx(slotBits - 1, 0))
  when(io.out.fire) {
    commitIdx := commitIdx + 1.U
  }

  io.done := (state === sRun) && winDrained && lastWindow

  // ---------------- Window control ----------------
  when(state === sIdle) {
    when(io.start) {
      regBase := io.baseAddr
      totalLen := io.len
      regStreamId := io.streamId
      winBase := 0.U
      issueIdx := 0.U
      commitIdx := 0.U
      validBuf.foreach(_ := false.B)
      state := sRun
    }
  }.elsewhen(io.done) {
    state := sIdle
  }.elsewhen(winDrained) {
    // Window fully drained with words remaining: slide to the next window.
    winBase := winBase + winSize
    issueIdx := 0.U
    commitIdx := 0.U
    validBuf.foreach(_ := false.B)
  }
}
