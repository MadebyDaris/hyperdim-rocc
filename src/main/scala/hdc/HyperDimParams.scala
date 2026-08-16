package hyperdim

import org.chipsalliance.cde.config.Field

case object HyperDimParamsKey extends Field[HyperDimParams](HyperDimParams())

case class HyperDimParams(
  vectorBits: Int = 256,
  latency: Int = 1
)