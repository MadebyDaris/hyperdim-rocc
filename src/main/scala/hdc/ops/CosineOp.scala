package hyperdim.ops

import chisel3._
import chisel3.util._
import hardfloat._

class CosineOp extends Module {
  val io = IO(new OpIO)

  object State extends ChiselEnum {
    val sIdle, sRun, sSqrt, sWaitSqrt, sMul, sDiv, sWaitDiv, sDone = Value
  }
  import State._
  val state = RegInit(sIdle)

  val sim_sum = RegInit(0.U(128.W))
  val norm_sumA = RegInit(0.U(128.W))
  val norm_sumB = RegInit(0.U(128.W))

  val count = RegInit(0.U(32.W))
  val regLen = RegInit(0.U(32.W))

  io.streamA.ready := state === sRun
  io.streamB.ready := state === sRun

  val fire = io.streamA.fire && io.streamB.fire

  // Hardfloat Converters (Truncating 128-bit accumulators to 64-bit)
  val intToRecFN_sim = Module(new INToRecFN(64, 11, 53))
  intToRecFN_sim.io.signedIn := false.B
  intToRecFN_sim.io.in := sim_sum(63, 0)
  intToRecFN_sim.io.roundingMode := "b000".U
  intToRecFN_sim.io.detectTininess := 1.U

  val intToRecFN_A = Module(new INToRecFN(64, 11, 53))
  intToRecFN_A.io.signedIn := false.B
  intToRecFN_A.io.in := norm_sumA(63, 0)
  intToRecFN_A.io.roundingMode := "b000".U
  intToRecFN_A.io.detectTininess := 1.U

  val intToRecFN_B = Module(new INToRecFN(64, 11, 53))
  intToRecFN_B.io.signedIn := false.B
  intToRecFN_B.io.in := norm_sumB(63, 0)
  intToRecFN_B.io.roundingMode := "b000".U
  intToRecFN_B.io.detectTininess := 1.U

  // Square Root Modules
  val sqrtA = Module(new DivSqrtRecFN_small(11, 53, 0))
  sqrtA.io.a := intToRecFN_A.io.out
  sqrtA.io.b := DontCare
  sqrtA.io.sqrtOp := true.B
  sqrtA.io.roundingMode := "b000".U
  sqrtA.io.detectTininess := 1.U
  sqrtA.io.inValid := state === sSqrt

  val sqrtB = Module(new DivSqrtRecFN_small(11, 53, 0))
  sqrtB.io.a := intToRecFN_B.io.out
  sqrtB.io.b := DontCare
  sqrtB.io.sqrtOp := true.B
  sqrtB.io.roundingMode := "b000".U
  sqrtB.io.detectTininess := 1.U
  sqrtB.io.inValid := state === sSqrt

  val regSqrtA = RegInit(0.U(65.W))
  val regSqrtB = RegInit(0.U(65.W))
  val sqrtA_done = RegInit(false.B)
  val sqrtB_done = RegInit(false.B)

  when(sqrtA.io.outValid_sqrt) {
    regSqrtA := sqrtA.io.out
    sqrtA_done := true.B
  }
  when(sqrtB.io.outValid_sqrt) {
    regSqrtB := sqrtB.io.out
    sqrtB_done := true.B
  }

  // Multiplier for sqrt(A) * sqrt(B)
  val mul = Module(new MulAddRecFN(11, 53))
  mul.io.op := 0.U(2.W) // a * b + c
  mul.io.a := regSqrtA
  mul.io.b := regSqrtB
  mul.io.c := 0.U(65.W) // +0.0 in RecFN
  mul.io.roundingMode := "b000".U
  mul.io.detectTininess := 1.U

  val mulResult = RegInit(0.U(65.W))

  // Divider for sim_sum / (sqrt(A) * sqrt(B))
  val div = Module(new DivSqrtRecFN_small(11, 53, 0))
  div.io.a := intToRecFN_sim.io.out
  div.io.b := mulResult
  div.io.sqrtOp := false.B
  div.io.roundingMode := "b000".U
  div.io.detectTininess := 1.U
  div.io.inValid := state === sDiv

  val finalResult = RegInit(0.U(65.W))

  switch(state) {
    is(sIdle) {
      when(io.start) {
        sim_sum := 0.U
        norm_sumA := 0.U
        norm_sumB := 0.U
        regLen := io.len
        count := 0.U
        sqrtA_done := false.B
        sqrtB_done := false.B
        when(io.len === 0.U) {
          state := sDone
        }.otherwise {
          state := sRun
        }
      }
    }
    is(sRun) {
      when(fire) {
        sim_sum := sim_sum + (io.streamA.bits * io.streamB.bits)
        norm_sumA := norm_sumA + (io.streamA.bits * io.streamA.bits)
        norm_sumB := norm_sumB + (io.streamB.bits * io.streamB.bits)
        when(count === regLen - 1.U) {
          state := sSqrt
        }.otherwise {
          count := count + 1.U
        }
      }
    }
    is(sSqrt) {
      when(sqrtA.io.inReady && sqrtB.io.inReady) {
        state := sWaitSqrt
      }
    }
    is(sWaitSqrt) {
      when(sqrtA_done && sqrtB_done) {
        state := sMul
      }
    }
    is(sMul) {
      // MulAddRecFN is combinational, we can latch it immediately
      mulResult := mul.io.out
      state := sDiv
    }
    is(sDiv) {
      when(div.io.inReady) {
        state := sWaitDiv
      }
    }
    is(sWaitDiv) {
      when(div.io.outValid_div) {
        finalResult := div.io.out
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle
    }
  }

  io.result.valid := state === sDone
  io.result.bits := fNFromRecFN(11, 53, finalResult)
  io.busy := state =/= sIdle
}
