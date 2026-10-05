# HyperDim RoCC Specification

This document is the reference for what the accelerator should do: its instruction
set, parameters, microarchitecture.

HyperDim RoCC is a RISC-V RoCC (Rocket Custom Coprocessor) accelerator for Hyperdimensional
Computing (HDC) primitives: Hamming distance between two bit-packed hypervectors, and nearest-class
search against an associative memory (AM) of class hypervectors.

It is attached to a single Rocket core via `OpcodeSet.custom0` and streams its operands directly from the core's L1 data cache rather
than receiving them in registers `rs1`/`rs2` only ever carry base addresses.


| Parameter     | Default | Meaning |
|---------------|---------|---------|
| `vectorBits`  | 4096    | Maximum hypervector width in bits. Sizes the AM-search query register file (`vectorBits/64` words). The *runtime* length (set via `OP_SETCFG`) may be smaller, but not larger. |
| `streamWords` | 16      | Reorder-buffer window size of each `VectorStreamer`, in 64-bit words. Must satisfy `streamWords <= 2^(tagBits-1)` for the elaborated D-cache tag width (checked at elaboration). Vectors/AM regions longer than one window are streamed in successive windows. |
| `dataWidth`   | 64      | Width of one streamed word; must match the D-cache data bus width on RV64. Not meant to be changed. |
| `numClasses`  | 10      | Maximum number of class hypervectors `AmSearchOp` can hold internal per-class accumulators for (`classDot`/`classSq` register arrays). The *runtime* class count (`OP_SETCFG`'s `rs2`) may be smaller, but not larger — exceeding it fails an elaboration-time-sized assertion in hardware. |

`vectorBits` must be a multiple of `dataWidth`. Both the query buffer and the per-class cosine
accumulators are sized at elaboration time from these parameters; runtime configuration can only
shrink them.

One RISC-V custom instruction (`custom0`, opcode `0b0001011`) is used for everything; the 7-bit
`funct` field selects the operation. Defined in `src/main/scala/hdc/isa/HyperDimISA.scala`, mirrored
in `sw/tests/hyperdim.h`.

| funct | Name            | Status |
|-------|-----------------|--------|
| 0     | `OP_HAMMING`    | Implemented |
| 1     | `OP_COSINE`     | Reserved — no dispatch in `HyperDimRoCC`; `CosineOp` module exists but is unused |
| 2     | `OP_BIND`       | Reserved — `BindOp` module exists but is unused |
| 3     | `OP_BUNDLE`     | Reserved — `BundleOp` module exists but is unused |
| 4     | `OP_PERMUTE`    | Reserved — `PermuteOp` module exists but is unused |
| 5     | `OP_DOT`        | Reserved — `DotProductOp` module exists but is unused |
| 6     | `OP_SETCFG`     | Implemented (see §3.1 caveat) |
| 7     | `OP_GETCFG`     | Reserved — not wired to any dispatch path |
| 8     | `OP_AM_SEARCH`  | Implemented |

Any other `funct` value is accepted (so the core never stalls on an unrecognized instruction) and
simply returns `rd = 0`.

```
HyperDimRoCC (LazyRoCC)
└── HyperDimRoCCModuleImp
    ├── VectorStreamer A
    ├── VectorStreamer B
    ├── RRArbiter (2-way, A and B)
    ├── HammingOp          XOR + popcount over two streams, lockstep consumption
    ├── AmSearchOp         query buffer + per-metric running best over the AM
    └── SetCfgOp           inert placeholder; never actually issues a request
```

### 4.1 Top-level command FSM

States: `sIdle`, `sRun`, `sRespond`, `sWaitConfig`.

- `sIdle`: `cmd.ready` is asserted. On `cmd.fire`, decode `funct`; `OP_HAMMING`/`OP_AM_SEARCH` → `sRun`,
  `OP_SETCFG` → `sWaitConfig`, anything else → respond `0` immediately via `sRespond`.
- `sRun`: waits for `hammingOp.io.result.valid` or `amSearchOp.io.result.valid` (selected by the
  latched `activeIsAm` bit), then captures the result and moves to `sRespond`.
- `sRespond`: asserts `io.resp.valid`; returns to `sIdle` on `io.resp.fire`.
- `sWaitConfig`: waits for `setCfgOp.io.done` (a single-cycle pulse, since its request path is
  inert), then goes straight to `sIdle` (or `sRespond` with `rd = 0` if the instruction had `xd = 1`,
  which the provided C macros never do).

`io.busy` is `state =/= sIdle`. `io.interrupt` is tied low.

### 4.2 Memory interface

The streamers speak plain `StreamReq`/`StreamResp` bundles, so they have no dependency on the cache's
types. `HyperDimRoCC` converts at the boundary: an `RRArbiter(StreamReq, 2)` picks between the two
streamers, and the winner is written into `io.mem.req` as a `HellaCacheReq` (`cmd = M_XRD`,
`size = 3`, `dprv = 3`, `phys = 0`). Responses from `io.mem.resp` are converted to `StreamResp` and
fanned out to both streamers, each of which filters on its stream-ID bit.

**Do not wrap `io.mem` in an additional `SimpleHellaCacheIF`.** The rocket-chip tile framework
(`HasLazyRoCCModule` in `generators/rocket-chip/src/main/scala/tile/LazyRoCC.scala`) already wraps
every RoCC's `io.mem` in its own `SimpleHellaCacheIF` (nack/replay handling) before connecting it to
the real D-cache arbiter. An earlier revision of this accelerator added a second, internal
`SimpleHellaCacheIF` around the same port; that double-wrapping deadlocked the accelerator
permanently as soon as it issued real memory traffic (see `docs/TESTBENCH.md` §4 for how this was
diagnosed). `io.mem` should always be driven as a plain `HellaCacheIO`, exactly like
`AccumulatorExample` and the other reference RoCCs in `LazyRoCC.scala`.

### 4.3 `VectorStreamer` (`src/main/scala/hdc/mem/VectorStreamer.scala`)

Streams `len` consecutive 64-bit words from `baseAddr` and delivers them in order on `out`.
Parameters: `windowWords` (= `streamWords`, default 16) and `tagBits`. Requires
`windowWords <= 2^(tagBits-1)` and `tagBits >= log2(windowWords) + 2`.

**Ports.** Inputs: `start` (one-cycle pulse, honoured only when idle), `baseAddr`, `len`, `streamId`,
`resp` (`Valid(StreamResp)`, no `ready`). Outputs: `req` (`Decoupled(StreamReq)`), `out`
(`Decoupled(UInt(64))`), `done` (one-cycle pulse after the last word is drained).

**Layout.** Word `k` lives at `baseAddr + 8k`. The stream is split into windows of `W` words; a window
has `W` slots, and word `winBase + s` occupies slot `s`. The last window may be shorter.

**Tag.** `tag = {streamId, zero padding, slot}`. The stream bit is the top bit, so responses can be
matched to a stream; the window number is not in the tag.

**Behaviour.**
- *Issue:* requests for the current window are offered in slot order. A request is held until the
  memory accepts it.
- *Complete:* a response is written into `dataBuf[slot]` and marks `validBuf[slot]`. Responses may
  arrive in any order, and the streamer always accepts them (there is no back-pressure on `resp`).
- *Drain:* the slot at `commitIdx` is offered on `out` once valid. Words leave strictly in slot order,
  and a word the consumer does not take is held.
- *Slide:* when a window is fully drained and more words remain, the next window starts (one idle
  cycle). After the last word, `done` pulses and the streamer returns to idle.

**Handshakes.** `req` transfers when `valid && ready` and advances the issue counter. `resp` has no
`ready`, so a response is always taken. `out` transfers when `valid && ready` and advances the
drain counter.

Two instances (A and B) run independently and share one memory port through the arbiter.

### 4.4 `HammingOp` (`src/main/scala/hdc/ops/HammingOp.scala`)

Consumes one word from stream A and one from stream B per cycle in **lockstep**: both streams'
`ready` are gated on *both* being `valid` simultaneously (`io.streamA.ready := (state === sRun) &&
io.streamA.valid && io.streamB.valid`, symmetric for B). This is a correctness requirement, not an
optimization — see `docs/TESTBENCH.md` §4 for why an unconditional per-stream `ready` deadlocks this
op. Accumulates `popcount(A_word XOR B_word)` into a 64-bit register; pulses `result.valid` after
`len` words.

### 4.5 `AmSearchOp` (`src/main/scala/hdc/ops/AmSearchOp.scala`)

States: `sIdle → sLoadQuery → sSearch → (sFinalizeSqrt → sFinalizeWaitSqrt → sFinalizeDiv →
sFinalizeWaitDiv)* → sDone`. The finalize loop only runs for the COSINE metric.

- `sLoadQuery`: buffers `numWords` words from stream A into `queryBuf` (a register file sized
  `maxQueryWords`, i.e. `HyperDimParams.vectorBits/64`). Stream B is backpressured
  (`io.classes.ready := state === sSearch`) during this phase, so its `VectorStreamer` simply holds
  partially-filled window data until `sSearch` begins draining it.
  (This is itself a source of latency: streamerB can accumulate up to a full window of fetched,
  unread data behind that backpressure before any of it is consumed.)
- `sSearch`: for each incoming class word, computes `wordHamming` / `wordDot` / `wordSq` combinatorially
  against the matching buffered query word, and folds the **current** word's contribution into the
  comparison on the last word of each class (`totalHamming`/`totalDot`/`totalSq`, not the
  not-yet-updated accumulator register) before updating the running best. HAMMING/DOT resolve
  argmin/argmax online, in one pass; COSINE instead stashes each class's `(dot, sq)` pair
  (`classDot`/`classSq`, sized `maxClasses = HyperDimParams.numClasses`) for the finalize loop.
- Finalize loop (COSINE only): one class at a time, `sqrt(sq)` then `dot / sqrt(sq)` via
  `hardfloat`'s `DivSqrtRecFN_small`, compared (`CompareRecFN`) against the running best.
- `sDone`: pulses `result.valid` with the winning class index; returns to `sIdle`.
