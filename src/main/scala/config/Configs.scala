package hyperdimrocc

import freechips.rocketchip.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.rocket.WithNBigCores

import chipyard.config.AbstractConfig

// This config fragment adds our HyperDimRoCC to the SoC
class WithHyperDimRoCC extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val hyperdimrocc = LazyModule(new HyperDimRoCC(OpcodeSet.custom0)(p))
      hyperdimrocc
    }
  )
})
class HyperDimRoCCConfig extends Config(
  new hyperdimrocc.WithHyperDimRoCC ++
  new WithNBigCores(1) ++
  new AbstractConfig
)
