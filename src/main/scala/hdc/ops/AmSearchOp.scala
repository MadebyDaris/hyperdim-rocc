package hyperdim.ops

import chisel3._
import chisel3.util._

/**
 * AmSearchOp -- associative-memory search (nearest-centroid classification).
 *
 * Buffers one query hypervector (numWords words), then streams the class
 * hypervectors -- concatenated in memory as numClasses x numWords words --
 * and returns the index of the class with the smallest Hamming distance to
 * the query. Ties resolve to the lowest class index.
 *
 * numWords must not exceed the query buffer (HyperDimParams.vectorBits/64).
 */
class AmSearchOp(maxQueryWords: Int) extends Module {
  require(maxQueryWords > 1, "query buffer must hold at least 2 words")
  val qIdxBits = log2Ceil(maxQueryWords)

  val io = IO(new Bundle {
    val query      = Flipped(Decoupled(UInt(64.W))) // query hypervector words
    val classes    = Flipped(Decoupled(UInt(64.W))) // concatenated class words
    val start      = Input(Bool())
    val numWords   = Input(UInt(32.W))              // words per hypervector
    val numClasses = Input(UInt(32.W))              // class hypervector count
    val result     = Output(Valid(UInt(64.W)))      // predicted class index
    val busy       = Output(Bool())
  })

  object State extends ChiselEnum {
    val sIdle, sLoadQuery, sSearch, sDone = Value
  }
  import State._
  val state = RegInit(sIdle)

  val queryBuf = Reg(Vec(maxQueryWords, UInt(64.W)))

  val qCount   = RegInit(0.U(32.W))
  val wordIdx  = RegInit(0.U(32.W))
  val classIdx = RegInit(0.U(32.W))
  val acc      = RegInit(0.U(64.W))
  val bestDist = RegInit(0.U(64.W))
  val bestIdx  = RegInit(0.U(64.W))

  io.query.ready   := state === sLoadQuery
  io.classes.ready := state === sSearch

  switch (state) {
    is (sIdle) {
      when (io.start) {
        assert(io.numWords <= maxQueryWords.U,
          "AmSearchOp: numWords exceeds query buffer (raise HyperDimParams.vectorBits)")
        when (io.numWords === 0.U || io.numClasses === 0.U) {
          bestIdx := 0.U
          state   := sDone
        }.otherwise {
          qCount := 0.U
          state  := sLoadQuery
        }
      }
    }
    is (sLoadQuery) {
      when (io.query.fire) {
        queryBuf(qCount(qIdxBits - 1, 0)) := io.query.bits
        when (qCount === io.numWords - 1.U) {
          wordIdx  := 0.U
          classIdx := 0.U
          acc      := 0.U
          bestDist := "hFFFFFFFFFFFFFFFF".U(64.W)
          bestIdx  := 0.U
          state    := sSearch
        }.otherwise {
          qCount := qCount + 1.U
        }
      }
    }
    is (sSearch) {
      when (io.classes.fire) {
        val dist = acc + PopCount(io.classes.bits ^ queryBuf(wordIdx(qIdxBits - 1, 0)))
        val lastWord  = wordIdx === io.numWords - 1.U
        val lastClass = classIdx === io.numClasses - 1.U
        when (lastWord) {
          // end of one class hypervector: update the running argmin
          when (dist < bestDist) {
            bestDist := dist
            bestIdx  := classIdx
          }
          acc      := 0.U
          wordIdx  := 0.U
          classIdx := classIdx + 1.U
          when (lastClass) {
            state := sDone
          }
        }.otherwise {
          acc     := dist
          wordIdx := wordIdx + 1.U
        }
      }
    }
    is (sDone) {
      state := sIdle
    }
  }

  io.result.valid := state === sDone
  io.result.bits  := bestIdx
  io.busy         := state =/= sIdle
}
