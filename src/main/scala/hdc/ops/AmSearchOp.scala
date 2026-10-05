package hyperdim.ops

import chisel3._
import chisel3.util._
import hardfloat._

/** Distance/similarity metric IDs (2-bit cfgMetric). */
object AmMetric {
  val HAMMING = 0.U(2.W) // argmin  Σ popcount(class ^ query)
  val DOT     = 1.U(2.W) // argmax  Σ int8-elementwise product
  val COSINE  = 2.U(2.W) // argmax  dot / ||class||  (||query|| is constant)
}

/** AmSearchOp -- associative-memory search (nearest-centroid classification).
  *
  * Buffers one query hypervector (numWords words), then streams the class
  * hypervectors -- concatenated in memory as numClasses x numWords words -- and
  * returns the index of the best class according to `dist_func`:
  *
  *   - HAMMING: bit-packed words (1 bit/dim), argmin of popcount(XOR).
  *   - DOT:     int8-packed words (8 signed dims/word), argmax of Σ q_i*c_i.
  *   - COSINE:  int8-packed words, argmax of (Σ q_i*c_i) / ||c||.
  *
  * numWords must not exceed the query buffer (HyperDimParams.vectorBits/64);
  * numClasses must not exceed maxClasses (HyperDimParams.numClasses).
  */
class AmSearchOp(maxQueryWords: Int, maxClasses: Int) extends Module {
  require(maxQueryWords > 1, "query buffer must hold at least 2 words")
  require(maxClasses >= 1, "maxClasses must be >= 1")

  val qIdxBits = log2Ceil(maxQueryWords)
  val cIdxBits = log2Ceil(maxClasses)

  val io = IO(new Bundle {
    val query      = Flipped(Decoupled(UInt(64.W))) // query hypervector words
    val classes    = Flipped(Decoupled(UInt(64.W))) // concatenated class words
    val start      = Input(Bool())
    val numWords   = Input(UInt(32.W))              // words per hypervector
    val numClasses = Input(UInt(32.W))              // class hypervector count
    val result     = Output(Valid(UInt(64.W)))      // predicted class index
    val busy       = Output(Bool())
    val dist_func  = Input(UInt(2.W))               // AmMetric
  })

  object State extends ChiselEnum {
    val sIdle, sLoadQuery, sSearch, sFinalizeSqrt, sFinalizeWaitSqrt,
        sFinalizeDiv, sFinalizeWaitDiv, sDone = Value
  }
  import State._
  val state = RegInit(sIdle)

  val queryBuf = Reg(Vec(maxQueryWords, UInt(64.W)))

  val qCount   = RegInit(0.U(32.W))
  val wordIdx  = RegInit(0.U(32.W))
  val classIdx = RegInit(0.U(32.W))

  // per-class accumulators (cleared at every class boundary)
  val accHamming = RegInit(0.U(64.W))
  val accDot     = RegInit(0.S(64.W))
  val accSqC     = RegInit(0.S(64.W))

  // running argmin/argmax for hamming/dot (resolved during the single pass)
  val bestScore = RegInit(0.S(64.W))
  val bestIdx   = RegInit(0.U(64.W))
  val haveBest  = RegInit(false.B)

  // per-class dot / squared-norm, kept for the cosine finalize pass
  val classDot = Reg(Vec(maxClasses, SInt(64.W)))
  val classSq  = Reg(Vec(maxClasses, SInt(64.W)))

  val finalIdx      = RegInit(0.U(32.W))
  val finalBest     = RegInit(0.U(65.W)) // RecFN (double) of best cosine score
  val finalBestIdx  = RegInit(0.U(64.W))
  val finalHaveBest = RegInit(false.B)

  val isHamming = io.dist_func === AmMetric.HAMMING
  val isCosine  = io.dist_func === AmMetric.COSINE

  io.query.ready   := state === sLoadQuery
  io.classes.ready := state === sSearch

  // ---- int8 elementwise helpers (8 signed lanes per 64-bit word) ----
  def int8Lane(x: UInt, i: Int): SInt = x(8 * i + 7, 8 * i).asSInt
  def int8Dot(a: UInt, b: UInt): SInt =
    (0 until 8).map(i => int8Lane(a, i) * int8Lane(b, i)).reduce(_ +& _)
  def int8Sq(a: UInt): SInt =
    (0 until 8).map(i => { val l = int8Lane(a, i); l * l }).reduce(_ +& _)

  // ---- hardfloat pipeline for the cosine finalize pass ----
  val dotToRec = Module(new INToRecFN(64, 11, 53))
  dotToRec.io.signedIn := true.B
  dotToRec.io.in := classDot(finalIdx(cIdxBits - 1, 0)).asUInt
  dotToRec.io.roundingMode := 0.U
  dotToRec.io.detectTininess := 1.U

  val sqToRec = Module(new INToRecFN(64, 11, 53))
  sqToRec.io.signedIn := false.B
  sqToRec.io.in := classSq(finalIdx(cIdxBits - 1, 0)).asUInt
  sqToRec.io.roundingMode := 0.U
  sqToRec.io.detectTininess := 1.U

  val sqrtUnit = Module(new DivSqrtRecFN_small(11, 53, 0))
  sqrtUnit.io.a := sqToRec.io.out
  sqrtUnit.io.b := DontCare
  sqrtUnit.io.sqrtOp := true.B
  sqrtUnit.io.roundingMode := 0.U
  sqrtUnit.io.detectTininess := 1.U
  sqrtUnit.io.inValid := state === sFinalizeSqrt

  val sqrtReg = RegInit(0.U(65.W))
  when(sqrtUnit.io.outValid_sqrt) { sqrtReg := sqrtUnit.io.out }

  val divUnit = Module(new DivSqrtRecFN_small(11, 53, 0))
  divUnit.io.a := dotToRec.io.out
  divUnit.io.b := sqrtReg
  divUnit.io.sqrtOp := false.B
  divUnit.io.roundingMode := 0.U
  divUnit.io.detectTininess := 1.U
  divUnit.io.inValid := state === sFinalizeDiv

  val cmp = Module(new CompareRecFN(11, 53))
  cmp.io.a := divUnit.io.out
  cmp.io.b := finalBest
  cmp.io.signaling := false.B

  // ---- main FSM ----
  switch(state) {
    is(sIdle) {
      when(io.start) {
        assert(io.numWords <= maxQueryWords.U,
          "AmSearchOp: numWords exceeds query buffer (raise HyperDimParams.vectorBits)")
        assert(io.numClasses <= maxClasses.U,
          "AmSearchOp: numClasses exceeds maxClasses (raise HyperDimParams.numClasses)")
        when(io.numWords === 0.U || io.numClasses === 0.U) {
          bestIdx := 0.U
          state := sDone
        }.otherwise {
          qCount := 0.U
          state := sLoadQuery
        }
      }
    }
    is(sLoadQuery) {
      when(io.query.fire) {
        queryBuf(qCount(qIdxBits - 1, 0)) := io.query.bits
        when(qCount === io.numWords - 1.U) {
          wordIdx := 0.U
          classIdx := 0.U
          accHamming := 0.U
          accDot := 0.S
          accSqC := 0.S
          haveBest := false.B
          bestScore := 0.S
          bestIdx := 0.U
          state := sSearch
        }.otherwise {
          qCount := qCount + 1.U
        }
      }
    }
    is(sSearch) {
      when(io.classes.fire) {
        val qWord = queryBuf(wordIdx(qIdxBits - 1, 0))
        val wordHamming = PopCount(io.classes.bits ^ qWord)
        val wordDot = int8Dot(io.classes.bits, qWord)
        val wordSq = int8Sq(io.classes.bits)

        val totalHamming = accHamming + wordHamming
        val totalDot = accDot + wordDot
        val totalSq = accSqC + wordSq

        when(isHamming) {
          accHamming := totalHamming
        }.otherwise {
          accDot := totalDot
          when(isCosine) { accSqC := totalSq }
        }

        val lastWord = wordIdx === io.numWords - 1.U
        when(lastWord) {
          when(isHamming) {
            when(!haveBest || (totalHamming.asSInt < bestScore)) {
              bestScore := totalHamming.asSInt
              bestIdx := classIdx
              haveBest := true.B
            }
          }.elsewhen(isCosine) {
            classDot(classIdx(cIdxBits - 1, 0)) := totalDot
            classSq(classIdx(cIdxBits - 1, 0)) := totalSq
          }.otherwise { // DOT
            when(!haveBest || (totalDot > bestScore)) {
              bestScore := totalDot
              bestIdx := classIdx
              haveBest := true.B
            }
          }

          accHamming := 0.U
          accDot := 0.S
          accSqC := 0.S
          wordIdx := 0.U
          classIdx := classIdx + 1.U

          when(classIdx === io.numClasses - 1.U) {
            when(isCosine) {
              finalIdx := 0.U
              finalHaveBest := false.B
              finalBestIdx := 0.U
              state := sFinalizeSqrt
            }.otherwise {
              state := sDone
            }
          }
        }.otherwise {
          wordIdx := wordIdx + 1.U
        }
      }
    }
    is(sFinalizeSqrt) {
      when(sqrtUnit.io.inReady) { state := sFinalizeWaitSqrt }
    }
    is(sFinalizeWaitSqrt) {
      when(sqrtUnit.io.outValid_sqrt) { state := sFinalizeDiv }
    }
    is(sFinalizeDiv) {
      when(divUnit.io.inReady) { state := sFinalizeWaitDiv }
    }
    is(sFinalizeWaitDiv) {
      when(divUnit.io.outValid_div) {
        when(!finalHaveBest || cmp.io.gt) {
          finalBest := divUnit.io.out
          finalBestIdx := finalIdx
          finalHaveBest := true.B
        }
        when(finalIdx === io.numClasses - 1.U) {
          bestIdx := finalBestIdx
          state := sDone
        }.otherwise {
          finalIdx := finalIdx + 1.U
          state := sFinalizeSqrt
        }
      }
    }
    is(sDone) {
      state := sIdle
    }
  }

  io.result.valid := state === sDone
  io.result.bits := bestIdx
  io.busy := state =/= sIdle

  val dbgPrev = RegNext(state)
  when(state =/= dbgPrev) {
    printf("[AmSearch] %d -> %d word=%d class=%d q=%d metric=%d\n",
      dbgPrev.asUInt, state.asUInt, wordIdx, classIdx, qCount, io.dist_func)
  }
}
