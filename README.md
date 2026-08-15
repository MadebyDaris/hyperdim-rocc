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
lazy val hyperdimrocc = (project in file("generators/hyperdim-rocc"))
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