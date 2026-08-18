package hyperdim.ops

import chisel3._
import chisel3.util._

/** Shared interface for two-stream ops (Hamming today, bind/bundle later). */
class OpIO extends Bundle {
  val streamA = Flipped(Decoupled(UInt(64.W)))
  val streamB = Flipped(Decoupled(UInt(64.W)))
  val start   = Input(Bool())
  val len     = Input(UInt(32.W))   // runtime word count (from config reg)
  val result  = Output(Valid(UInt(64.W)))
  val busy    = Output(Bool())
}
