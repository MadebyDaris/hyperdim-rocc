package HyperDimRoCC.ops

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

// A shared interface so the top-level FSM doesn't care which op it's talking to
class DistanceOp(maxWords: Int) extends Bundle {
    // Inputs from memory
    val streamA = Flipped(Decoupled(UInt(64.W)))
    val streamB = Flipped(Decoupled(UInt(64.W)))

    // Control signals from top-level FSM
    val start   = Input(Bool())
    val len     = Input(UInt(log2Ceil(maxWords).W))
  
    // Outputs
    val valid   = Output(Bool())
    val result  = Output(UInt(64.W))
    val busy    = Output(Bool())
}