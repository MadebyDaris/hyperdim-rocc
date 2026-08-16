package HyperDimRoCC

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket.constants.MemoryOpConstants


import hyperdim_rocc.mem._
import hyperdim_rocc.ops._

class HyperDimRoCC(opcodes: OpcodeSet)(implicit p: Parameters)
    extends LazyRoCC(opcodes) {
  override lazy val module = new HyperDimRoCCModuleImp(this)
}

class HyperDimRoCCModuleImp(outer: HyperDimRoCC)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer)
    with MemoryOpConstants {

  val streamerA = Module(new VectorStreamer(128))
  val streamerB = Module(new VectorStreamer(128)) 

  val hammingOp = Module(new HammingOp(128))
  
  val cmd = Queue(io.cmd)
  val reg_rd = Reg(chiselTypeOf(cmd.bits.inst.rd))
  val reg_result = Reg(UInt(64.W))  

  object State extends ChiselEnum {
    val s_idle, s_req_a, s_wait_a, s_req_b, s_wait_b, s_done = Value
  }
  import State._
  val state = RegInit(s_idle)

  cmd.ready := (state === s_idle)
  
  // TODO: Use the length from the command
  val test_vector_len = 4.U 

  streamerA.io.base_addr := cmd.bits.rs1
  streamerA.io.len       := test_vector_len
  streamerA.io.stream_id := 0.U

  streamerB.io.base_addr := cmd.bits.rs2
  streamerB.io.len       := test_vector_len
  streamerB.io.stream_id := 1.U

  hammingOp.io.len := test_vector_len

  // DATAPATH: Wire Streamers to the Math Op
  streamerA.io.out <> hammingOp.io.streamA
  streamerB.io.out <> hammingOp.io.streamB

  streamerA.io.start := cmd.fire && state === s_idle
  streamerB.io.start := cmd.fire && state === s_idle
  hammingOp.io.start := cmd.fire && state === s_idle

  when(cmd.fire) {
    reg_rd := cmd.bits.inst.rd
    state := s_streaming
  }

  when (state === s_streaming) {
    when (hammingOp.io.result.valid) {
      reg_result := hammingOp.io.result.bits
      state := s_respond
    }
  }

  when(state === s_respond) {
    io.resp.valid := true.B
    io.resp.bits.rd := reg_rd
    io.resp.bits.data := reg_result
  }

  // MEMORY: Connect Streamers to HellaCache
  val reqArb = Module(new RRArbiter(new HellaCacheReq(p(CacheName).get.coreParams)))
  
  reqArb.io.in(0) <> streamerA.io.mem.req
  reqArb.io.in(1) <> streamerB.io.mem.req
  
  io.mem.req <> reqArb.io.out
  streamerA.io.mem.resp := io.mem.resp
  streamerB.io.mem.resp := io.mem.resp


  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B

  // CPU WRITEBACK ENGINE
  io.resp.bits := DontCare // Prevents FIRRTL wire errors

  io.resp.valid := (state === s_respond)
  io.resp.bits.rd := reg_rd
  io.resp.bits.data := reg_result

  when (io.resp.fire) { state := s_idle } 
    
  io.busy := (state =/= s_idle)
  io.interrupt := false.B
}