# HyperDim RoCC Accelerator

A custom Rocket Custom Coprocessor (RoCC) generator for the [Chipyard](https://github.com/ucb-bar/chipyard) framework.

This project implements a hardware accelerator for Hyperdimensional Computing (HDC) operations. It efficiently computes the Hamming Distance (XOR + Popcount) between two memory-resident hypervectors, streaming data directly from the L1 data cache.

## Directory Structure

```
src/main/scala/
  config/Configs.scala       — Chipyard config fragment (WithHyperDimRoCC)
  hdc/
    HyperDimParams.scala     — Parameter case class + CDE key
    HyperDimRoCC.scala       — LazyRoCC wrapper + module FSM
    isa/HyperDimISA.scala    — funct opcode constants
    ops/
      OpIO.scala             — shared streaming op interface
      HammingOp.scala        — Hamming distance datapath
      *.scala                — reserved for future ops
    mem/
      VectorStreamer.scala   — L1$ streaming engine with reorder buffer
      VectorWriter.scala     — reserved
sw/tests/
    rocc.h                   — standard RoCC instruction macros
    hyperdim.h               — HDC-specific ISA constants + macros
    main.c                   — bare-metal test program
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

## Software and Testing

A bare-metal C test is provided in `sw/tests/`. It sends a custom instruction to the accelerator and checks the result.

### Building the Test

Requires `riscv64-unknown-elf-gcc` (included in the Chipyard conda environment).

```bash
cd sw/tests
make
```

This produces `hyperdim_test.riscv`.

### Running the Test

Build the Verilator simulator for the config that includes the accelerator:

```bash
cd sims/verilator
make CONFIG=HyperDimRoCCConfig
```

Run the test binary:

```bash
make CONFIG=HyperDimRoCCConfig run-binary BINARY=../../generators/hyperdim-rocc/sw/tests/hyperdim_test.riscv
```

## ISA

The accelerator uses the `custom0` opcode. The 7-bit `funct` field selects the operation.

The instruction takes two source registers (base addresses of vectors A and B) and returns the Hamming distance in the destination register. Vector length is fixed at elaboration time (default: 256 bits = 4 × 64-bit words).

See `docs/architecture.md` for a detailed walkthrough of the design.