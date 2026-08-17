# HyperDim RoCC Accelerator

A custom Rocket Custom Coprocessor (RoCC) generator for the [Chipyard](https://github.com/ucb-bar/chipyard) framework.

This project implements a hardware accelerator for Hyperdimensional Computing (HDC) operations. It computes the Hamming Distance (XOR + Popcount) between two memory-resident hypervectors, and can classify a query hypervector against a whole associative memory of class hypervectors in a single instruction (`OP_AM_SEARCH`), streaming data directly from the L1 data cache.

## Directory Structure

```
src/main/scala/
  config/Configs.scala       — Chipyard config fragment (WithHyperDimRoCC)
  hdc/
    HyperDimParams.scala     — Parameter case class + CDE key
    HyperDimRoCC.scala       — LazyRoCC wrapper + module FSM + config regs
    isa/HyperDimISA.scala    — funct opcode constants
    ops/
      OpIO.scala             — shared streaming op interface
      HammingOp.scala        — Hamming distance datapath
      AmSearchOp.scala       — nearest-class (argmin Hamming) search
      *.scala                — reserved for future ops
    mem/
      VectorStreamer.scala   — L1$ streaming engine (windowed, reorder buffer)
      VectorWriter.scala     — reserved
sw/tests/
    rocc.h                   — standard RoCC instruction macros
    hyperdim.h               — HDC-specific ISA constants + macros
    main.c                   — bare-metal Hamming test program
    am_search_test.c         — bare-metal AM search (classification) test
    Makefile
docs/
    setup.md                 — Fedora/Chipyard environment setup guide
    architecture.md          — design walkthrough
```

## Integration

This generator is registered in Chipyard's top-level `build.sbt` as `hyperdim_rocc`. It depends on `rocketchip`.

To use it in a SoC config, mix in `hyperdim.WithHyperDimRoCC`:

```scala
// In your Chipyard config file (e.g. generators/chipyard/src/main/scala/config/):
import org.chipsalliance.cde.config.Config

class HyperDimRoCCConfig extends Config(
  new hyperdim.WithHyperDimRoCC ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig
)
```

For larger hypervectors (e.g. a 10,000-dim encoder padded to 157 words):

```scala
new hyperdim.WithHyperDimRoCC(HyperDimParams(vectorBits = 10048))
```

## ISA

The accelerator uses the `custom0` opcode. The 7-bit `funct` field selects the operation.

| funct | Operation |
|-------|-----------|
| `0` | `OP_HAMMING` — `rd = hamming(rs1, rs2)` |
| `6` | `OP_SETCFG` — set words-per-vector (`rs1`) and class count (`rs2`) |
| `7` | `OP_AM_SEARCH` — `rd = argmin_i hamming(rs1, AM + i·vecBytes)` (rs2 = AM base) |

Vector length was compile-time only; it is now a runtime config register set by `OP_SETCFG` (default: elaborated `vectorBits`/64 words). See `docs/architecture.md` for the full walkthrough.

## Software and Testing

Bare-metal C tests are provided in `sw/tests/`: `main.c` checks the Hamming op, `am_search_test.c` classifies a query against 3 class hypervectors with `OP_AM_SEARCH`.

### Building the Tests

Requires `riscv64-unknown-elf-gcc` (included in the Chipyard conda environment).

```bash
cd sw/tests
make
```

This produces `hyperdim_test.riscv` and `am_search_test.riscv`.

### Running the Tests

Build the Verilator simulator for the config that includes the accelerator:

```bash
cd sims/verilator
make CONFIG=HyperDimRoCCConfig
```

Run a test binary:

```bash
make CONFIG=HyperDimRoCCConfig run-binary BINARY=../../generators/hyperdim-rocc/sw/tests/am_search_test.riscv
```
