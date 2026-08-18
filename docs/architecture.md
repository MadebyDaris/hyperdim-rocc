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

This project uses the memory interface (`io.mem`) to fetch the vectors it needs, instead of passing data in registers. The registers only carry the two *base addresses*.

## The ISA

The accelerator responds to a single `custom0` instruction. The 7-bit `funct` field selects the HDC operation:

| funct | Operation | Status |
|-------|-----------|--------|
| `0` | `OP_HAMMING` — Hamming distance | implemented |
| `1` | `OP_COSINE` — Cosine similarity | reserved |
| `2` | `OP_BIND` — Bind (XOR) | reserved |
| `3` | `OP_BUNDLE` — Bundle (majority) | reserved |
| `4` | `OP_PERMUTE` — Permute | reserved |
| `5` | `OP_DOT` — Dot product | reserved |
| `6` | `OP_SETCFG` — set runtime config | implemented |
| `7` | `OP_AM_SEARCH` — nearest-class search | implemented |

### OP_SETCFG (funct 6)

Writes the accelerator's configuration registers. No destination register (use the `SS` macro form).

```
rs1 = words per hypervector   (0 is clamped to 1)
rs2 = number of class hypervectors in the associative memory
```

This replaces the old compile-time-only vector length: the same bitstream handles any hypervector size up to `HyperDimParams.vectorBits`.

### OP_HAMMING (funct 0)

```
rd  = hamming_distance(rs1, rs2)
rs1 = base address of vector A (64-bit words)
rs2 = base address of vector B (64-bit words)
```

Length = configured words per vector (default: elaborated `vectorBits`/64).

### OP_AM_SEARCH (funct 7)

One-shot nearest-centroid classification — the HDC inference primitive.

```
rd  = argmin_i hamming(query, AM[i])    (predicted class index)
rs1 = base address of the query hypervector
rs2 = base address of the associative memory:
      class hypervectors stored contiguously, row-major
      (numClasses x words-per-vector x 8 bytes)
```

Requires a prior `OP_SETCFG`. Ties resolve to the lowest class index.

## Module hierarchy

```
HyperDimRoCC (LazyRoCC)
└── HyperDimRoCCModuleImp (FSM, config regs, stream routing)
    ├── VectorStreamer A   ── fetches vector A / query from L1$
    ├── VectorStreamer B   ── fetches vector B / AM region from L1$
    ├── RRArbiter          ── shares the single RoCC cache port
    ├── HammingOp          ── XOR + popcount over two streams
    └── AmSearchOp         ── query buffer + running argmin over the AM
```

## Data flow: Hamming

1. The core issues the custom instruction. `cmd.fire` latches `rs1`/`rs2` into the two streamers and starts the Hamming op.
2. Each streamer issues cache loads (`M_XRD`) for consecutive 64-bit words starting at its base address.
3. Cache responses can come back **out of order**, so each streamer keeps a small reorder buffer indexed by word position.
4. Each streamer drains its reorder buffer in order, emitting one word per cycle on `io.out`.
5. `HammingOp` consumes one word from each stream per cycle, XORs them, counts set bits (`PopCount`), and accumulates.
6. After `len` words, `HammingOp` pulses `result.valid`; the top FSM captures it and writes back via `io.resp`.

## Data flow: AM search

1. `cmd.fire` starts streamer A on the query (`cfgWords` words) and streamer B on the whole AM region (`cfgNumClasses * cfgWords` words), plus `AmSearchOp`.
2. `AmSearchOp` first buffers the entire query into a register file (`sLoadQuery`). Streamer B fills its window meanwhile and waits on backpressure.
3. In `sSearch`, each incoming class word is XORed with the matching buffered query word and popcount-accumulated. At the end of each class, the total is compared against the running minimum; the winner's index is kept.
4. After the last class, `result.valid` pulses with the predicted class index.

The stream mux: `activeIsAm` (latched at `cmd.fire`) steers both streamer outputs to either `HammingOp` or `AmSearchOp` and routes the correct `ready` back.

## The streamer and its windows

`VectorStreamer` fetches in **windows** of at most `windowWords` (default 16) words:

- **Issuer**: fires cache requests for the current window. Each request is tagged with `{streamId, slot}` where `slot` is the word's position inside the window.
- **Completer**: matches each response against `streamId` (and only while running, so stale responses can't corrupt an idle buffer) and stores data at `dataBuf[slot]`.
- **Drainer**: walks `commitIdx` from 0 upward and only emits a word once its slot is valid, guaranteeing in-order delivery.
- **Window slide**: once a window is fully drained, `winBase` advances and the slots are reused for the next window. A slot is never re-tagged while a response for it could still be in flight.

Because tags only encode a window slot, the reorder buffer stays small and tag-bounded (≤ 2^(tagBits−1), typically 64) while `len` is a full 32-bit runtime value — streaming a 10,000-bit query or a multi-class AM region needs no extra tag space.

## Configuration

`HyperDimParams` is exposed through the Chipyard/Diplomacy `Parameters` system via `HyperDimParamsKey`:

| Field | Default | Meaning |
|-------|---------|---------|
| `vectorBits` | `256` | Maximum hypervector width in bits (multiple of 64); sets the AM-search query buffer size |
| `streamWords` | `16` | Streamer reorder-buffer window in words; must fit the cache tag (≤ 64) |
| `latency` | `1` | Reserved pipeline-latency hint (unused today) |

`WithHyperDimRoCC` now takes the params, so a larger model is a one-liner — e.g. for a 10,000-dim encoder (157 words):

```scala
class HyperDimRoCCConfig extends Config(
  new hyperdim.WithHyperDimRoCC(HyperDimParams(vectorBits = 10048)) ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig)
```

## How to add a new operation

1. Add the `funct` constant to `HyperDimISA.scala` and `hyperdim.h`.
2. Create the op under `hdc/ops/` (consume the streamer outputs, pulse `result`).
3. In `HyperDimRoCCModuleImp`, extend the stream mux (`activeIsAm` becomes a small enum), set the streamer lengths for your op, and add it to the `sRun` completion dispatch.

Vector-producing ops (bind, bundle, permute) additionally need the reserved `VectorWriter` to store results back to memory, and a destination-address register set via `OP_SETCFG`-style configuration.

## Known simplifications

- Bind/bundle/permute are not implemented: they produce vectors, so they need the store path (`VectorWriter`) and a destination-address config register.
- `AmSearchOp` re-reads the AM from memory on every inference; keeping the class hypervectors in an on-chip scratchpad would cut repeat-inference latency dramatically.
- `result.valid` pulses for one cycle and is caught by the top FSM; a `Decoupled` result would be more robust as ops multiply.
