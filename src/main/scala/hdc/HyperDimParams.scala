package hyperdim

import org.chipsalliance.cde.config.Field

case object HyperDimParamsKey extends Field[HyperDimParams](HyperDimParams())

/**
 * @param vectorBits  maximum hypervector width in bits (multiple of 64).
 *                    Sets the AM-search query buffer: vectorBits/64 words.
 *                    The runtime length (set via OP_SETCFG) may be smaller.
 * @param streamWords reorder-buffer window of each VectorStreamer, in words.
 *                    Must fit in the cache tag: <= 2^(tagBits-1), typically 64.
 *                    Longer vectors/AM regions are streamed in windows of
 *                    this size, so any runtime length works.
 * @param latency     reserved pipeline-latency hint (unused today)
 */
case class HyperDimParams(
  vectorBits: Int = 256,
  streamWords: Int = 16,
  latency: Int = 1
)
