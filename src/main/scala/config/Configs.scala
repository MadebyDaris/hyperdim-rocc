package hyperdim

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule

class WithHyperDimRoCC extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val hyperdim = LazyModule(new HyperDimRoCC(OpcodeSet.custom0)(p))
      hyperdim
    }
  )
})