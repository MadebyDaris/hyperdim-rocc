import chisel3._
import freechips.rocketchip.tile._

// Config key so you can change the vector size from Chipyard's top-level configs 
// without touching the hardware code.

case object HyperDimParamsKey extends Field[HyperDimParams]

case class HyperDimParams(
    numVectors: Int,
    vectorBits: Int,
    latency: Int,
    opcodeSet: OpcodeSet,
    vectorStreamerParams: VectorStreamerParams,
    HyperDimISA32: HyperDimISA32Params,
)