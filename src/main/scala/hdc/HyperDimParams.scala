package hyperdim

import org.chipsalliance.cde.config.Field

case object HyperDimParamsKey extends Field[HyperDimParams](HyperDimParams())

/**
 * Top-level architectural parameters for the HyperDim RoCC accelerator.
 *
 * @param vectorBits  Maximum hypervector width in bits (multiple of 64).
 *                    Sets the AM-search query buffer: vectorBits/64 words.
 *                    The runtime length (set via OP_SETCFG) may be smaller.
 * @param streamWords Reorder-buffer window of each VectorStreamer, in words.
 *                    Must fit in the cache tag: <= 2^(tagBits-1), typically 64.
 *                    Longer vectors/AM regions are streamed in windows of
 *                    this size, so any runtime length works.
 * @param dataWidth   Width of one memory word / streaming chunk. Must match the
 *                    D-cache data bus width (64 bits for RV64).
 * @param numClasses  Default number of class hypervectors (overridden at runtime
 *                    via OP_SETCFG).
 */
case class HyperDimParams(
  vectorBits  : Int = 4096,
  streamWords : Int = 16,
  dataWidth   : Int = 64,
  numClasses  : Int = 10,
) {
  require(vectorBits % dataWidth == 0,
    s"vectorBits ($vectorBits) must be divisible by dataWidth ($dataWidth)")

  /** Number of dataWidth-wide words per hypervector (max query buffer size). */
  val numWords: Int = vectorBits / dataWidth
}
