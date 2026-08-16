package HyperDimRoCC

import chisel3.util._

object HyperDimISA32 {
  val OP_HAMMING = 0.U
  val OP_COSINE = 1.U
  val OP_BIND = 2.U

  val CMD_READ = 0.U
  val CMD_WRITE = 1.U

  val CMD_MEM_SIZE = 32
}