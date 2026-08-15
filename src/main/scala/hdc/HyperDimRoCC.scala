package HyperDimRoCC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._

// FIX 1: Modern Chipyard uses CDE for config
import org.chipsalliance.cde.config._ 
// FIX 2: Import MemoryOpConstants for M_XRD
import freechips.rocketchip.rocket.constants.MemoryOpConstants 

class HyperDimRoCC(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
    override lazy val module = new HyperDimRoCCModuleImp(this)
}

// FIX 3: Mix in MemoryOpConstants to expose M_XRD
class HyperDimRoCCModuleImp(outer: HyperDimRoCC)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

    // Define the command queue to hold incoming RoCC commands 
    val cmd = Queue(io.cmd)

    // Internal Registers
    val ptr_a = Reg(UInt(64.W))
    val ptr_b = Reg(UInt(64.W))
    val count = RegInit(0.U(3.W)) // Will count 0 to 4
    val accum = RegInit(0.U(64.W)) // Holds the total Hamming Distance
    
    val word_a = Reg(UInt(64.W))

    // State Machine for processing commands (removed unused s_compute)
    object State extends ChiselEnum {
        val s_idle, s_req_a, s_wait_a, s_req_b, s_wait_b, s_done = Value
    }
    val state = RegInit(State.s_idle)

    // Default values for the RoCC interface signals
    cmd.ready := (state === s_idle)
    
    // FIX 4: Use .fire instead of .fire()
    when (cmd.fire) { 
        ptr_a := cmd.bits.rs1
        ptr_b := cmd.bits.rs2
        count := 0.U
        accum := 0.U
        state := s_req_a
    }

    io.mem.req.valid := (state === s_req_a) || (state === s_req_b)
    // Adding .U to the shift prevents potential width-inference warnings
    io.mem.req.bits.addr := Mux(state === s_req_a, ptr_a + (count << 3.U), ptr_b + (count << 3.U))
    io.mem.req.bits.cmd := M_XRD // Memory Read
    io.mem.req.bits.size := log2Ceil(8).U // 8 bytes = 64 bits
    
    // FIX 5: Drive missing required memory request fields! (FIRRTL will crash otherwise)
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
            accum := accum + PopCount(xor_val) // Hardware Popcount tree!
            
            when(count === 3.U) { // 4 words total
                state := s_done
            } .otherwise {
                count := count + 1.U
                state := s_req_a
            }
        }
    }
    
    io.resp.valid := (state === s_done)
    io.resp.bits.rd := cmd.bits.inst.rd
    io.resp.bits.data := accum

    when(io.resp.fire) { state := s_idle }
    
    // FIX 6: Drive missing required RoCC interface signals!
    io.busy := (state =/= s_idle)
    io.interrupt := false.B
}