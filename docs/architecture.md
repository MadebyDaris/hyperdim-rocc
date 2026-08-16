# HyperDim RoCC Architecture

This document explains what the HyperDim RoCC accelerator does and how the pieces fit together.

## What is a RoCC?

A RoCC (Rocket Custom Coprocessor) is a hardware accelerator attached directly to a Rocket CPU core. The CPU sees it as a set of *custom instructions*. When issued one of these instructions, it pauses, hands the accelerator two source registers (`rs1`, `rs2`), and waits for a response.

From the hardware side, a RoCC is a module that talks to the core through a fixed interface:

| Signal | Direction (from accelerator) | Meaning |
|--------|------------------------------|---------|
| `io.cmd` | in | Decoded custom instruction + `rs1`/`rs2` values |
| `io.resp` | out | Write-back: result register + data |
| `io.mem` | in/out | Direct access to the core's L1 data cache (via `HellaCacheIO`) |
| `io.busy` | out | Tells the core the accelerator is still working |
| `io.interrupt` | out | Optional interrupt request |

This project uses the memory interface (`io.mem`) to fetch the two vectors it needs to compare, instead of passing data in registers. The registers only carry the two *base addresses*.

## The ISA

The accelerator responds to a single `custom0` instruction. The 7-bit `funct` field selects the HDC operation:

| funct | Operation |
|-------|-----------|
| `0` | Hamming distance (`OP_HAMMING`) |
| `1` | Cosine similarity (`OP_COSINE`) |
| `2` | Bind (`OP_BIND`) |
| `3` | Bundle (`OP_BUNDLE`) |
| `4` | Permute (`OP_PERMUTE`) | reserved |
| `5` | Dot product (`OP_DOT`) | reserved |

The instruction semantics for the implemented op are:

```
rd  = hamming_distance(rs1, rs2)
rs1 = base address of vector A (64-bit words)
rs2 = base address of vector B (64-bit words)
```

The number of words is fixed at elaboration time by `HyperDimParams.vectorBits` (default `256` bits = `4` words).

## Module hierarchy

```
HyperDimRoCC (LazyRoCC)
└── HyperDimRoCCModuleImp (the FSM + wiring)
    ├── VectorStreamer A   ── fetches vector A from L1$
    ├── VectorStreamer B   ── fetches vector B from L1$
    ├── RRArbiter          ── shares the single RoCC cache port
    └── HammingOp          ── consumes the two streams, computes distance
```

## Data flow

1. The core issues the custom instruction. `cmd.fire` latches `rs1`/`rs2` into the two streamers and starts the Hamming op.
2. Each streamer issues cache loads (`M_XRD`) for `len` consecutive 64-bit words starting at its base address.
3. Cache responses can come back **out of order**, so each streamer keeps a small reorder buffer (ROB) indexed by word position.
4. Each streamer drains its ROB in order, emitting one word per cycle on `io.out`.
5. `HammingOp` consumes one word from each stream per cycle, XORs them, counts set bits (`PopCount`), and accumulates.
6. After `len` words, `HammingOp` asserts `result.valid` for one cycle. The top FSM captures the result and writes it back to the core via `io.resp`.

## The streamer and its reorder buffer

The interesting part is `VectorStreamer`. It has three cooperating pieces:

- **Issuer**: fires cache requests as fast as the cache accepts them. Each request is tagged with `{streamId, wordIndex}` in the cache request tag.
- **Completer**: matches each response against `streamId` and stores the data at `dataBuf[wordIndex]`, marking it valid.
- **Drainer**: walks `commitIdx` from `0` upward and only emits a word once its buffer slot is valid, guaranteeing in-order delivery.

The cache tag is `dcacheReqTagBits + log2Ceil(dcacheArbPorts)` bits wide (7 bits for a single-RoCC config). The streamer reserves the top bit for `streamId` and uses the rest for the word index, then checks at elaboration time that `maxWords` fits.

## Configuration

`HyperDimParams` is exposed through the Chipyard/Diplomacy `Parameters` system via `HyperDimParamsKey`:

| Field | Default | Meaning |
|-------|---------|---------|
| `vectorBits` | `256` | Hypervector width in bits (must be a multiple of 64) |
| `latency` | `1` | Reserved pipeline-latency hint (unused today) |

`WithHyperDimRoCC` is the config fragment that adds the accelerator to the `BuildRoCC` list. To build a complete SoC you compose it with a core config, e.g.:

```scala
class HyperDimRoCCConfig extends Config(
  new hyperdim.WithHyperDimRoCC ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig)
```

Add that class to your Chipyard config directory (e.g. `generators/chipyard/src/main/scala/config/`).

## How to add a new operation

1. Add the `funct` constant to `HyperDimISA.scala` and `hyperdim.h`.
2. Create `hdc/ops/CosineOp.scala` implementing `OpIO` (consume `streamA`/`streamB`, drive `result`/`busy`).
3. In `HyperDimRoCCModuleImp`, instantiate the new op, wire its streams, and dispatch on `cmd.bits.inst.funct`.

## Known simplifications

- Word count is a compile-time parameter, not per-instruction. A real accelerator would encode the length in the instruction or a CSR.
- Only the Hamming op is implemented; the module always computes Hamming regardless of `funct`.
