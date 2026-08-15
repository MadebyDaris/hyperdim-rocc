# Chipyard RoCC Accelerator Setup

[![Chipyard Version](https://img.shields.io/badge/Chipyard-v1.14.0-blue.svg)](https://github.com/ucb-bar/chipyard)
[![RISC-V](https://img.shields.io/badge/Architecture-RISC--V-orange.svg)](https://riscv.org/)

This repository documents the complete process of standing up a cycle-accurate RISC-V Rocket Core simulator using [Chipyard](https://github.com/ucb-bar/chipyard) on a modern Linux distribution (Fedora) and functions as a knowledge base basic documenton how to design Coprocessors (RoCC) using Chisel. 

With other details like the fetching of vectors directly from the L1 Data Cache.

## The Fedora / Chipyard Guide
Building Chipyard on a cutting-edge distro like Fedora introduces severe environment conflicts (specifically with Python 3.14+ and `tmpfs` RAM exhaustion). Here is the battle-tested setup sequence to successfully compile the toolchain and Verilator simulator.

### 1. The Conda / Python 3.10 Sandbox Trap
Fedora's Python versions will cause Conda to panic and hallucinate dependencies. You must bypass `conda-lock` and force a strict Python 3.10 environment.

```bash
# Clone Chipyard
git clone [https://github.com/ucb-bar/chipyard.git](https://github.com/ucb-bar/chipyard.git)
cd chipyard
./scripts/init-submodules-no-riscv-tools.sh

# Force a strict Python 3.10 sandbox and pull pre-compiled tools
conda create -y -p ./.conda-env -c ucb-bar -c conda-forge python=3.10 riscv-tools
conda env update -p ./.conda-env -f conda-reqs/chipyard-base.yaml

# Hijack the terminal path to force the use of the Conda tools
export RISCV=$(pwd)/.conda-env
export PATH=$RISCV/bin:$PATH
```
### 2. Fixing the libgloss Makefile Sanity Check
When compiling toolchain collateral, the libgloss Makefile will fail a hardcoded sanity check due to Conda's isolated paths (throwing a libdir is not in... error).
To fix this, surgically delete the if $(filter... block inside toolchains/libgloss/Makefile.in before running the setup script.

### 3. Bypassing tmpfs RAM Exhaustion (FireSim)
At Step 6 of the Chipyard setup, the ctags utility will attempt to sort LLVM's source code in your /tmp directory. On Fedora, /tmp is mapped to RAM. This will cause a Disk quota exceeded crash.

```bash
# Reroute TMPDIR to the physical hard drive
mkdir -p disk_tmp
export TMPDIR=$(pwd)/disk_tmp

# Run the setup script (skipping the broken step 1)
./scripts/build-setup.sh -s 1
```

# The RoCC Interface Basics

The Rocket Custom Coprocessor (RoCC) interface allows you to define custom instructions to offload to your custom silicon.

- The CPU pauses and hands over two source registers (rs1, rs2).

- Your hardware does the computation.

We trigger this from C code using GCC's inline assembly .insn directive, bypassing the need for complex header files.

## The Chipyard ```hello world```

```scala
class MyAccumulatorImp(outer: MyAccumulator)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) {
  val regfile = RegInit(0.U(64.W))
  val cmd = Queue(io.cmd)
  
  val do_add = cmd.fire && (cmd.bits.inst.funct === 0.U)
  when (do_add) { regfile := regfile + cmd.bits.rs1 }

  io.resp.valid := cmd.valid
  io.resp.bits.rd := cmd.bits.inst.rd
  io.resp.bits.data := regfile + cmd.bits.rs1
  cmd.ready := io.resp.ready
}
```

```c
// custom0 opcode is 0x0B
#define ROCC_ACCUMULATE(rd, rs1) \
    asm volatile (".insn r 0x0B, 0x7, 0, %0, %1, x0" : "=r" (rd) : "r" (rs1))

int main() {
    uint64_t result;
    ROCC_ACCUMULATE(result, 10); // result = 10
    ROCC_ACCUMULATE(result, 25); // result = 35
    return 0;
}
```

### Compiling and Running

```bash
cd sims/verilator
make -j$(nproc) CONFIG=MyAccumulatorConfig EXTRA_SIM_CXXFLAGS="-Wno-error"

riscv64-unknown-elf-gcc test_acc.c -o test_acc.riscv
```

Running
```bash
./simulator-chipyard.harness-MyAccumulatorConfig $RISCV/riscv64-unknown-elf/bin/pk test_acc.riscv
```
