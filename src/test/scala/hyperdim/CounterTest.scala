package hyperdim

import chisel3._
import chisel3.simulator.EphemeralSimulator._  
import org.scalatest.flatspec.AnyFlatSpec

class Counter extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  val count = RegInit(0.U(32.W))

  when(io.in =/= 0.U) {
    count := count + io.in
  }

  io.out := count
}

class CounterTest extends AnyFlatSpec {
  behavior of "Counter"
  it should "count up when inc is high" in {
    simulate(new Counter) { c =>
      c.io.in.poke(0.U)
      c.clock.step()
      c.io.out.expect(0.U)
      c.io.in.poke(42.U)
      c.clock.step()
      c.io.out.expect(42.U)
      println("Last output value : " + c.io.out.peek().litValue)
    }
  }
}