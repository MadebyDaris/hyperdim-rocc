package HyperDimRoCC.ops


// TODO: Use as a base for the other operatios
import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

class HammingOp(maxWords: Int) extends Module {
    val io = IO(new DistanceOpIO(maxWords))

    object State extends ChiselEnum {
        val sIdle, sStream, sCompute, sResp = Value
    }
    import State._
    val state = RegInit(sIdle)

    val accumulator = RegInit(0.U(64.W))
    val reg_len = RegInit(0.U(io.maxIdxWidth.W))
    val count = RegInit(0.U(log2Ceil(maxWords).W))

    io.busy := (state =/= sIdle)
    io.result.valid := (state === sResp)
    io.result.bits  := accumulator

    // Checking for firing
    val can_fire = (state === sCompute) && io.streamA.valid && io.streamB.valid

    switch (state) {
        is (sIdle) {
            when (io.start) {
                accumulator := 0.U
                reg_len := io.len
                count := 0.U
                when(io.len === 0.U) {
                    state := sResp
                } .otherwise {
                    state := sStream
                }
            }
        }
        is (sStream) {
            when (io.streamA.fire && io.streamB.fire) {
                count := count + 1.U
                when (count === io.len) {
                    state := sCompute
                }
            }
        }
        is (sCompute) {
            when(can_fire) {
                accumulator := accumulator + (io.streamA.bits ^ io.streamB.bits).countSetBits()
                count := count + 1.U
                when (count === reg_len - 1.U) { // -1 becasuse we start from 0
                    state := sResp
                }
            }
        }
        is (sResp) {
            io.valid := true.B
            io.result := accumulator
            state := sIdle
        }
    }
    io.busy := state =/= sIdle
}