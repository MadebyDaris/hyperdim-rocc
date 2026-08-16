#include <stdint.h>
#include <stdio.h>
#include "hyperdim.h"

static inline uint64_t read_cycles() {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r" (cycles));
    return cycles;
}

int main() {
    uint64_t vectorA[4] = { 0xFF00FF00FF00FF00, 0xAAAAAAAAAAAAAAAA, 0x1234567890ABCDEF, 0x0 };
    uint64_t vectorB[4] = { 0x00FF00FF00FF00FF, 0x5555555555555555, 0x1234567890ABCDEF, 0x1 };

    uint64_t hw_distance = 0;

    printf("Starting HyperDim RoCC Hamming Distance...\n");

    uint64_t start_cycles = read_cycles();

    HYPERDIM_HAMMING(hw_distance, vectorA, vectorB);

    uint64_t end_cycles = read_cycles();
    uint64_t total_cycles = end_cycles - start_cycles;

    printf("Hardware Result (Hamming Distance): %lu\n", hw_distance);
    printf("Hardware Clock Cycles Taken: %lu\n", total_cycles);

    if (hw_distance == 129) {
        printf("STATUS: SUCCESS!\n");
    } else {
        printf("STATUS: FAILED!\n");
    }

    return 0;
}