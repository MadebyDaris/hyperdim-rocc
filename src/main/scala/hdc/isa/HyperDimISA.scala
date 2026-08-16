package hyperdim.isa

import chisel3._

object HyperDimISA {
  val OP_HAMMING = 0.U(7.W)
  val OP_COSINE  = 1.U(7.W)
  val OP_BIND    = 2.U(7.W)
  val OP_BUNDLE  = 3.U(7.W)
  val OP_PERMUTE = 4.U(7.W)
  val OP_DOT     = 5.U(7.W)
}