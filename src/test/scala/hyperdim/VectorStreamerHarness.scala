package hyperdim

import chisel3._
import chisel3.util._
import hyperdim.mem.{StreamResp, VectorStreamer}

// VectorStreamer plus a fake memory and a consumer port.
// The fake memory returns, for each request, the byte address itself as the data,
// so word k of a stream starting at base must equal base + 8*k.
class VectorStreamerHarness(windowWords: Int, slots: Int, tagBits: Int) extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())
    val baseAddr = Input(UInt(64.W))
    val len      = Input(UInt(32.W))
    val streamId = Input(UInt(1.W))

    val reqAllow = Input(Bool())      // memory accepts a request this cycle
    val latency  = Input(UInt(4.W))   // latency given to a request accepted this cycle (>= 1)
    val outReady = Input(Bool())      // consumer accepts a word this cycle

    val done     = Output(Bool())
    val reqFire  = Output(Bool())
    val reqAddr  = Output(UInt(64.W))
    val outFire  = Output(Bool())
    val outBits  = Output(UInt(64.W))
  })

  val s = Module(new VectorStreamer(windowWords, tagBits))
  s.io.start := io.start
  s.io.baseAddr := io.baseAddr
  s.io.len := io.len
  s.io.streamId := io.streamId

  // Fake memory: a pool of outstanding requests, each with a countdown.
  val busy  = RegInit(VecInit(Seq.fill(slots)(false.B)))
  val tags  = Reg(Vec(slots, UInt(tagBits.W)))
  val addrs = Reg(Vec(slots, UInt(64.W)))
  val cnt   = Reg(Vec(slots, UInt(4.W)))

  val hasFree   = !busy.reduce(_ && _)
  val freeIdx   = PriorityEncoder(busy.map(!_))
  val readyNow  = busy.zip(cnt).map { case (b, c) => b && c === 0.U }
  val respValid = readyNow.reduce(_ || _)
  val respIdx   = PriorityEncoder(readyNow)

  s.io.req.ready := io.reqAllow && hasFree
  val reqFire = s.io.req.fire

  val resp = Wire(new StreamResp(tagBits))
  resp.tag := tags(respIdx)
  resp.data := addrs(respIdx)
  s.io.resp.valid := respValid
  s.io.resp.bits := resp

  for (i <- 0 until slots) {
    when(busy(i) && cnt(i) =/= 0.U) { cnt(i) := cnt(i) - 1.U }
  }
  when(respValid) { busy(respIdx) := false.B }
  when(reqFire) {
    busy(freeIdx) := true.B
    tags(freeIdx) := s.io.req.bits.tag
    addrs(freeIdx) := s.io.req.bits.addr
    cnt(freeIdx) := io.latency
  }

  // Consumer
  s.io.out.ready := io.outReady

  io.done := s.io.done
  io.reqFire := reqFire
  io.reqAddr := s.io.req.bits.addr
  io.outFire := s.io.out.fire
  io.outBits := s.io.out.bits
}
