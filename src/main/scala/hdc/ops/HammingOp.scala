package hyperdim.ops

import chisel3._
import chisel3.util._

/**
 * HammingOp -- Hamming distance between two streamed hypervectors.
 *
 * Consumes `len` words from each stream, XORs them word-by-word, and
 * accumulates the popcount. Pulses `result` for one cycle when done.
 */
class HammingOp extends Module {
  val io = IO(new OpIO)

  object State extends ChiselEnum {
    val sIdle, sRun, sDone = Value
  }
  import State._
  val state = RegInit(sIdle)

  val acc    = RegInit(0.U(64.W))
  val count  = RegInit(0.U(32.W))
  val regLen = RegInit(0.U(32.W))

  io.streamA.ready := state === sRun
  io.streamB.ready := state === sRun

  val fire = io.streamA.fire && io.streamB.fire

  switch (state) {
    is (sIdle) {
      when (io.start) {
        acc    := 0.U
        regLen := io.len
        count  := 0.U
        when (io.len === 0.U) {
          state := sDone
        }.otherwise {
          state := sRun
        }
      }
    }
    is (sRun) {
      when (fire) {
        acc := acc + PopCount(io.streamA.bits ^ io.streamB.bits)
        when (count === regLen - 1.U) {
          state := sDone
        }.otherwise {
          count := count + 1.U
        }
      }
    }
    is (sDone) {
      state := sIdle
    }
  }

  io.result.valid := state === sDone
  io.result.bits  := acc
  io.busy         := state =/= sIdle
}
