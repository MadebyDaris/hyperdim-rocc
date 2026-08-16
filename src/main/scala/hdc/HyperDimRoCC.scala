package HyperDimRoCC

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket.constants.MemoryOpConstants

// Made this a class param instead of a magic number so it's easy to
// bump when you move to real hypervector dimensions later.
class HyperDimRoCC(opcodes: OpcodeSet, numWords: Int = 4)(implicit p: Parameters)
    extends LazyRoCC(opcodes) {
  override lazy val module = new HyperDimRoCCModuleImp(this, numWords)
}

class HyperDimRoCCModuleImp(outer: HyperDimRoCC, numWords: Int)
    extends LazyRoCCModuleImp(outer)
    with MemoryOpConstants {

  val cmd = Queue(io.cmd)

  val ptr_a = Reg(UInt(64.W))
  val ptr_b = Reg(UInt(64.W))
  val count = RegInit(0.U(log2Ceil(numWords).W))
  val accum = RegInit(0.U(64.W))
  val word_a = Reg(UInt(64.W))

  // FIX: latch rd (and anything else from cmd.bits you need after dequeue)
  // at accept time. cmd.bits is only valid for the currently-queued entry;
  // it can change out from under you the instant cmd.fire dequeues it.
  val resp_rd = Reg(UInt(5.W))

  object State extends ChiselEnum {
    val s_idle, s_req_a, s_wait_a, s_req_b, s_wait_b, s_done = Value
  }
  import State._

  val state = RegInit(s_idle)

  cmd.ready := (state === s_idle)

  when(cmd.fire) {
    ptr_a := cmd.bits.rs1
    ptr_b := cmd.bits.rs2
    resp_rd := cmd.bits.inst.rd // <-- latched now, not read later
    count := 0.U
    accum := 0.U
    state := s_req_a
  }

  // FIX: default-init the whole bundle first so every field of
  // HellaCacheReq is driven, regardless of rocket-chip version /
  // which extra fields it happens to carry (dprv, dv, no_alloc, ...).
  io.mem.req.bits := DontCare
  io.mem.req.valid := (state === s_req_a) || (state === s_req_b)
  io.mem.req.bits.addr := Mux(
    state === s_req_a,
    ptr_a + (count << 3.U),
    ptr_b + (count << 3.U)
  )
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.phys := false.B

  when(io.mem.req.fire && state === s_req_a) { state := s_wait_a }
  when(io.mem.req.fire && state === s_req_b) { state := s_wait_b }

  when(io.mem.resp.valid) {
    when(state === s_wait_a) {
      word_a := io.mem.resp.bits.data
      state := s_req_b
    }
    when(state === s_wait_b) {
      val word_b = io.mem.resp.bits.data
      val xor_val = word_a ^ word_b
      accum := accum + PopCount(xor_val)

      when(count === (numWords - 1).U) {
        state := s_done
      }.otherwise {
        count := count + 1.U
        state := s_req_a
      }
    }
  }

  // FIX: default-init response bundle too.
  io.resp.bits := DontCare
  io.resp.valid := (state === s_done)
  io.resp.bits.rd := resp_rd // <-- use latched value, not cmd.bits.inst.rd
  io.resp.bits.data := accum

  when(io.resp.fire) { state := s_idle }

  io.busy := (state =/= s_idle)
  io.interrupt := false.B
}