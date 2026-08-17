package hyperdim

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule

/**
 * Mix-in config fragment. Example for the MIT-BIH model (D = 10000 bits,
 * padded to 157 words = 10048 bits):
 *
 *   new hyperdim.WithHyperDimRoCC(HyperDimParams(vectorBits = 10048))
 */
class WithHyperDimRoCC(params: HyperDimParams = HyperDimParams()) extends Config((site, here, up) => {
  case HyperDimParamsKey => params
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val hyperdim = LazyModule(new HyperDimRoCC(OpcodeSet.custom0)(p))
      hyperdim
    }
  )
})
