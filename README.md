# HyperDim RoCC Accelerator

A custom Rocket Custom Coprocessor (RoCC) generator using the[Chipyard](https://github.com/ucb-bar/chipyard) framework.

This project implements a hardware accelerator for Hyperdimensional Computing (HDC) operations. Specifically, it efficiently computes the Hamming Distance (using XOR and Popcount) between two memory vectors.

## Directory Structure

- `src/main/scala/hdc`: Contains the Chisel implementation of the `HyperDimRoCC` coprocessor.
- `src/main/scala/config`: Contains Chipyard configuration fragments to instantiate the RoCC accelerator.
- `sw/`: Software utilities and drivers for interacting with the accelerator.
- `docs/`: Additional documentation.

## Integration

This generator is designed to be integrated into a standard Chipyard workspace. Ensure this folder is placed under `generators/hyperdim-rocc` in your Chipyard directory, and mix in its configuration within your Chipyard SoC configs.

```scala
lazy val hyperdim_rocc = (project in file("generators/hyperdim-rocc"))
  .dependsOn(rocketchip, chipyard)
  .settings(commonSettings)
  .settings(chiselLibrary)
```

```scala
// Example of how to mix it in to your SoC config
class MySystem(conf: SystemConfig) extends BaseSubsystem {
  // ...
  
  // Add the accelerator to the RoCC port list
  // Replace "core0" with your specific core name if different
  val rocc = Seq(LazyRoCC(outer.core0, "hyperdimrocc"))
  
  // Or if using the Config helper:
  val app = new HyperDimRoCCConfig
}
```

## Software and Testing

A sample C program is provided in `sw/tests/main.c` to test the hardware accelerator. It sends custom instructions to the coprocessor and checks the results.

### Building the Test

To compile the test, you'll need the RISC-V GNU toolchain installed (`riscv64-unknown-elf-gcc`). 

Navigate to the test directory and run `make`:

```bash
cd sw/tests
make
```

This produces the `hyperdim_test.riscv` binary.

### Running the Test

First, you need to build a Verilator simulator for a Chipyard configuration that includes the HyperDim RoCC accelerator. The provided example config is `HyperDimRoCCConfig` in the `HyperDimRoCC` package.

```bash
# Go to the verilator simulation directory
cd ../../../sims/verilator

# Build the simulator executable for this config
make CONFIG=HyperDimRoCCConfig
```

Once the simulator is built and the test binary is compiled, you can run the baremetal test on it:

```bash
# Run the compiled test binary on the simulator
make CONFIG=HyperDimRoCCConfig run-binary BINARY=../../generators/hyperdim-rocc/sw/tests/hyperdim_test.riscv
```

You can also run the binary using `spike` (RISC-V ISA simulator) with the Proxy Kernel (`pk`), assuming Spike has been compiled with a model of your RoCC extension.