package HyperDimRoCC.mem

import chisel3._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

class VectorStreamer(
  maxWords: Int = 4
)(implicit p: Parameters) extends Module with MemoryOpConstants {
  val maxIdxWidth = log2Ceil(maxWords)
  val io = IO(new Bundle {
    // Control Interface
    val start = Input(Bool())
    val base_addr = Input(UInt(64.W))
    val len = Input(UInt((maxIdxWidth + 1).W)) // How many words to fetch
    val stream_id = Input(UInt(1.W))           // 0 for A, 1 for B
    val done = Output(Bool())

    // Memory Interface
    val mem = new HellaCacheIO

    // Stream Output
    val out = Decoupled(UInt(64.W))
  })

  object State extends ChiselEnum {
    val s_idle, s_streaming = Value
  }
  import State._

  val state = RegInit(s_idle)

  val reg_base_addr = Reg(UInt(64.W))
  val reg_len       = Reg(UInt((maxIdxWidth + 1).W))
  val reg_stream_id = Reg(UInt(1.W))

  // Pointers
  val issue_idx  = RegInit(0.U(maxIdxWidth.W))
  val commit_idx = RegInit(0.U(maxIdxWidth.W))

//// REORDER BUFFER (ROB)
  val data_buf  = Reg(Vec(maxWords, UInt(64.W)))
  val valid_buf = RegInit(VecInit(Seq.fill(maxWords)(false.B)))

  // Start FSM
  io.done := false.B
  when(state === s_idle) {
      when(io.start) {
          reg_base_addr := io.base_addr
          reg_len       := io.len
          reg_stream_id := io.stream_id
          issue_idx     := 0.U
          commit_idx    := 0.U
          valid_buf.foreach(_ := false.B) // Clear ROB
          state         := s_streaming
        }
  } .elsewhen(state === s_streaming) {
      when(commit_idx === reg_len && reg_len =/= 0.U) {
        io.done := true.B
        state := s_idle
      }
  }

  // THE ISSUER (Fires requests blindly)
  //
  io.mem.req.valid := state === s_streaming && issue_idx < reg_len
  io.mem.req.bits.addr := reg_base_addr + (issue_idx << 3.U)
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.tag := issue_idx
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.phys := false.B
  
  // Encode Tag: [Bit 7: StreamID] | [Bits 6:0: Word Index]
  io.mem.req.bits.tag := Cat(reg_stream_id, issue_idx)
  when(io.mem.req.fire) {
    issue_idx := issue_idx + 1.U
  }

  // THE COMPLETER (Fills the ROB)
  //
  val resp_tag = io.mem.resp.bits.tag
  val resp_stream_id = resp_tag(maxIdxWidth)
  val resp_idx = resp_tag(maxIdxWidth - 1, 0)

  when(io.mem.resp.fire) {
    when(resp_stream_id === reg_stream_id) {
        data_buf(resp_idx) := io.mem.resp.bits.data
        valid_buf(resp_idx) := true.B
    }
  }

  // THE EXITER (Sends data to consumer)
  //
  io.out.valid := valid_buf(commit_idx) || valid_buf(commit_idx + 1.U)
  io.out.bits := data_buf(commit_idx) || data_buf(commit_idx + 1.U)
  when(io.out.fire) {
    commit_idx := commit_idx + 1.U
  }

  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
}