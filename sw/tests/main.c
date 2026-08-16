#include <stdint.h>
#include <stdio.h>
#include "rocc.h" // Get this from riscv-tools or chipyard tests

// Wrapper for custom0 instruction.
// rd  = destination register (result)
// rs1 = query vector pointer
// rs2 = class vector pointer
#define HYPERDIM_ROCC(rd, rs1, rs2) \
    ROCC_INSTRUCTION_DSS(0, rd, rs1, rs2, 0)

// Helper to read CPU cycle counter
static inline uint64_t read_cycles() {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r" (cycles));
    return cycles;
}

int main() {
    // Two 256-bit arrays (4 x 64-bit words)
    uint64_t vectorA[4] = { 0xFF00FF00FF00FF00, 0xAAAAAAAAAAAAAAAA, 0x1234567890ABCDEF, 0x0 };
    uint64_t vectorB[4] = { 0x00FF00FF00FF00FF, 0x5555555555555555, 0x1234567890ABCDEF, 0x1 };
    
    uint64_t hw_distance = 0;
    
    printf("Starting Mini-HDC Hardware Accelerator...\n");

    uint64_t start_cycles = read_cycles();
    
    // Call the accelerator! CPU pauses while state machine runs.
    HYPERDIM_ROCC(hw_distance, vectorA, vectorB);
    
    uint64_t end_cycles = read_cycles();
    uint64_t total_cycles = end_cycles - start_cycles;

    printf("Hardware Result (Hamming Distance): %lu\n", hw_distance);
    printf("Hardware Clock Cycles Taken: %lu\n", total_cycles);

    // Sanity check: VectorA vs VectorB should be exactly 129 bits different 
    // (64 from word 0 + 64 from word 1 + 0 from word 2 + 1 from word 3)
    if (hw_distance == 129) {
        printf("STATUS: SUCCESS!\n");
    } else {
        printf("STATUS: FAILED!\n");
    }

    return 0;
}

