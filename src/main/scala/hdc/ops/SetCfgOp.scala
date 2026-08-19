package hyperdim.ops

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.{HellaCacheReq, HellaCacheResp}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import org.chipsalliance.cde.config.Parameters

/**
 * SetCfg Operation.
 *
 * Issues a single 64-bit store to a fixed configuration register address in
 * the D-cache, then signals done. Uses no_resp=true so no response slot needs
 * to be allocated; the write is fire-and-forget once the request is accepted.
 *
 * @param cfgAddr  Physical address of the configuration register to write.
 */
class HyperDim_SetCfgOp(
    val cfgAddr: BigInt = 0x80000000L,
)(implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {
    val start   = Input(Bool())
    val cfgData = Input(UInt(64.W))
    val done    = Output(Bool())

    val req  = Decoupled(new HellaCacheReq)
    // resp is not used (no_resp = true), but kept for interface consistency
    val resp = Input(Valid(new HellaCacheResp))
  })

  // ── State Machine ─────────────────────────────────────────────────────────
  object State extends ChiselEnum {
    val sIdle, sRun = Value
  }
  import State._
  val state = RegInit(sIdle)

  val regCfgData = Reg(UInt(64.W))

  // ── Cache Request ─────────────────────────────────────────────────────────
  io.req.bits          := DontCare
  io.req.bits.addr     := cfgAddr.U(64.W)
  io.req.bits.tag      := 0.U
  io.req.bits.cmd      := M_XWR
  io.req.bits.size     := log2Ceil(8).U   // 8-byte (64-bit) write
  io.req.bits.data     := regCfgData
  io.req.bits.signed   := false.B
  io.req.bits.phys     := true.B          // physical address — bypass MMU
  io.req.bits.dprv     := 3.U(2.W)        // machine privilege
  io.req.bits.dv       := false.B
  io.req.bits.no_alloc := false.B
  io.req.bits.no_xcpt  := false.B
  io.req.bits.no_resp  := true.B          // fire-and-forget; no response expected

  // Request is valid only while waiting for the cache to accept it
  io.req.valid := state === sRun

  // ── FSM ──────────────────────────────────────────────────────────────────
  switch(state) {
    is(sIdle) {
      when(io.start) {
        regCfgData := io.cfgData
        state      := sRun
      }
    }
    is(sRun) {
      when(io.req.fire) {
        state := sIdle
      }
    }
  }

  // Done pulses for one cycle once the request has been accepted by the cache
  io.done := io.req.fire
}