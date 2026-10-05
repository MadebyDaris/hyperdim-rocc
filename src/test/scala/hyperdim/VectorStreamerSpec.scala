package hyperdim

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class VectorStreamerSpec extends AnyFlatSpec {
  val tagBits = 8

  case class Run(
      received: Seq[BigInt],
      requested: Seq[BigInt],
      doneCount: Int,
      lateFires: Int,
      cycles: Int
  )

  // Runs one stream to completion, with randomness driven by `seed`.
  def runStream(
      w: Int,
      slots: Int,
      base: BigInt,
      len: Int,
      streamId: Int,
      seed: Long,
      reqAllowP: Double = 1.0,
      outReadyP: Double = 1.0,
      maxLatency: Int = 1,
      maxCycles: Int = 2000
  ): Run = {
    var result: Run = null
    simulate(new VectorStreamerHarness(w, slots, tagBits)) { dut =>
      val rnd = new Random(seed)

      dut.io.start.poke(false.B)
      dut.io.baseAddr.poke(base.U(64.W))
      dut.io.len.poke(len.U(32.W))
      dut.io.streamId.poke(streamId.U(1.W))
      dut.io.reqAllow.poke(false.B)
      dut.io.outReady.poke(false.B)
      dut.io.latency.poke(1.U)
      dut.clock.step(1)

      dut.io.start.poke(true.B)
      dut.clock.step(1)
      dut.io.start.poke(false.B)

      val received = ArrayBuffer[BigInt]()
      val requested = ArrayBuffer[BigInt]()
      var doneCount = 0
      var lateFires = 0
      var stopAt = -1
      var cycles = 0

      // Keep running a few cycles after done, so a second done or stray fire is caught.
      while (cycles < maxCycles && (stopAt < 0 || cycles < stopAt)) {
        dut.io.reqAllow.poke((rnd.nextDouble() < reqAllowP).B)
        dut.io.outReady.poke((rnd.nextDouble() < outReadyP).B)
        dut.io.latency.poke((1 + rnd.nextInt(maxLatency)).U(4.W))

        val reqFire = dut.io.reqFire.peek().litValue == 1
        val outFire = dut.io.outFire.peek().litValue == 1
        val done = dut.io.done.peek().litValue == 1

        if (reqFire) requested += dut.io.reqAddr.peek().litValue
        if (outFire) {
          if (stopAt >= 0) lateFires += 1
          received += dut.io.outBits.peek().litValue
        }
        if (done) {
          doneCount += 1
          if (stopAt < 0) stopAt = cycles + 10
        }

        dut.clock.step(1)
        cycles += 1
      }

      result = Run(received.toSeq, requested.toSeq, doneCount, lateFires, cycles)
    }
    result
  }

  def expectedAddrs(base: BigInt, len: Int): Seq[BigInt] =
    (0 until len).map(k => base + 8 * k)

  def check(run: Run, base: BigInt, len: Int, label: String): Unit = {
    val expected = expectedAddrs(base, len)
    assert(run.doneCount == 1, s"$label: done pulsed ${run.doneCount} times")
    assert(run.lateFires == 0, s"$label: ${run.lateFires} words delivered after done")
    assert(run.requested == expected, s"$label: requests were ${run.requested}, expected $expected")
    assert(run.received == expected, s"$label: received ${run.received}, expected $expected")
  }

  val base = BigInt(0x80002000L)

  // Group 1: basic transfer, latency 1, always ready.
  "VectorStreamer" should "stream a single window in order" in {
    check(runStream(w = 4, slots = 4, base = base, len = 4, streamId = 0, seed = 1), base, 4, "single window")
  }

  it should "stream two full windows" in {
    check(runStream(w = 4, slots = 4, base = base, len = 8, streamId = 0, seed = 1), base, 8, "two windows")
  }

  it should "stream an uneven total (short last window)" in {
    check(runStream(w = 4, slots = 4, base = base, len = 10, streamId = 0, seed = 1), base, 10, "uneven")
  }

  it should "finish cleanly for zero length" in {
    val run = runStream(w = 4, slots = 4, base = base, len = 0, streamId = 0, seed = 1)
    assert(run.received.isEmpty, s"zero length delivered ${run.received}")
    assert(run.requested.isEmpty, s"zero length requested ${run.requested}")
    assert(run.doneCount == 1, s"zero length: done pulsed ${run.doneCount} times")
  }

  // Group 4: responses come back in a scrambled order.
  it should "deliver in order when responses arrive out of order" in {
    for (seed <- 1L to 10L) {
      check(
        runStream(w = 4, slots = 4, base = base, len = 8, streamId = 0, seed = seed, maxLatency = 4),
        base, 8, s"out-of-order seed $seed"
      )
    }
  }

  // Group 5: backpressure on each side.
  it should "not lose or reorder words when the consumer stalls" in {
    for (seed <- 1L to 5L) {
      check(
        runStream(w = 4, slots = 4, base = base, len = 8, streamId = 0, seed = seed, outReadyP = 0.5, maxLatency = 3),
        base, 8, s"consumer stall seed $seed"
      )
    }
  }

  it should "retry requests when the memory refuses them" in {
    for (seed <- 1L to 5L) {
      check(
        runStream(w = 4, slots = 4, base = base, len = 8, streamId = 0, seed = seed, reqAllowP = 0.5),
        base, 8, s"request stall seed $seed"
      )
    }
  }

  // Group 6: stream identity.
  it should "work for stream B (streamId 1)" in {
    check(runStream(w = 4, slots = 4, base = base, len = 8, streamId = 1, seed = 1), base, 8, "stream B")
  }

  // Group 7: sweep over window size and random everything.
  it should "stream a larger vector with W = 16 under random timing" in {
    for (seed <- 1L to 3L) {
      check(
        runStream(
          w = 16, slots = 16, base = base, len = 100, streamId = 0, seed = seed,
          reqAllowP = 0.7, outReadyP = 0.7, maxLatency = 4, maxCycles = 5000
        ),
        base, 100, s"W=16 seed $seed"
      )
    }
  }
}
