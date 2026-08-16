package hyperdim.ops

import chisel3._
import chisel3.util._

class OpIO(maxWords: Int) extends Bundle {
  val streamA = Flipped(Decoupled(UInt(64.W)))
  val streamB = Flipped(Decoupled(UInt(64.W)))
  val start   = Input(Bool())
  val len     = Input(UInt(log2Ceil(maxWords + 1).W))
  val result  = Output(Valid(UInt(64.W)))
  val busy    = Output(Bool())
}