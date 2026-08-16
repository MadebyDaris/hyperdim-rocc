package HyperDimRoCC

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._

import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.rocket.WithNBigCores
import freechips.rocketchip.system.BaseConfig
import freechips.rocketchip.rocket.constants.MemoryOpConstants 

// This config fragment adds our HyperDimRoCC to the SoC
class WithHyperDimRoCC extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val hyperdim_rocc = LazyModule(new HyperDimRoCC(OpcodeSet.custom0)(p))
      hyperdim_rocc
    }
  )
})
