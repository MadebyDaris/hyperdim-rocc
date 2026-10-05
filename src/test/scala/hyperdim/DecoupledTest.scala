package hyperdim

import chisel3._
import chisel3.simulator.EphemeralSimulator._  
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.util.Decoupled

class OneEntryBuffer extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Decoupled(UInt(8.W)))  // upstream -> us: we are the consumer
        val out = Decoupled(UInt(8.W))           // us -> downstream: we are the producer
    })
    val dataReg = Reg(UInt(8.W))
    val fullReg = RegInit(false.B)

    io.in.ready := !fullReg
    io.out.valid := fullReg
    io.out.bits := dataReg

    when(io.in.fire) {
        dataReg := io.in.bits
        fullReg := true.B
    } .elsewhen(io.out.fire) {
        fullReg := false.B
    }
}



class DecoupledTest extends AnyFlatSpec {
    behavior of "OneEntryBuffer"

    it should "pass data through when ready" in {
        simulate(new OneEntryBuffer) { dut =>
            dut.io.in.bits.poke(42.U)
            dut.io.in.valid.poke(true.B)
            dut.clock.step()
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.expect(42.U)
        }
    }

    it should "not accept new data when full" in {
        simulate(new OneEntryBuffer) { dut =>
            dut.io.in.bits.poke(42.U)
            dut.io.in.valid.poke(true.B)
            dut.clock.step()
            dut.io.in.ready.expect(false.B)
        }
    }

    it should "not produce data when empty" in {
        simulate(new OneEntryBuffer) { dut =>
            dut.io.out.valid.expect(false.B)
        }
    }
}