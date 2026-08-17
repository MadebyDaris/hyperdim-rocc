package hyperdim.isa

import chisel3._

object HyperDimISA {
  val OP_HAMMING   = 0.U(7.W)  // rd = hamming(rs1, rs2)
  val OP_COSINE    = 1.U(7.W)  // reserved
  val OP_BIND      = 2.U(7.W)  // reserved
  val OP_BUNDLE    = 3.U(7.W)  // reserved
  val OP_PERMUTE   = 4.U(7.W)  // reserved
  val OP_DOT       = 5.U(7.W)  // reserved
  val OP_SETCFG    = 6.U(7.W)  // cfg: rs1 = words/vector, rs2 = #classes
  val OP_AM_SEARCH = 7.U(7.W)  // rd = argmin_i hamming(rs1, rs2 + i*vecBytes)
}
