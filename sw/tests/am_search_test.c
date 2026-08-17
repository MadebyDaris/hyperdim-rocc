/*
 * am_search_test.c -- OP_AM_SEARCH (nearest-class search) smoke test.
 *
 * 3 classes x 4 words (256-bit hypervectors). The query is class 1 with
 * 3 bits flipped, so the accelerator should return class index 1.
 */
#include <stdint.h>
#include <stdio.h>
#include "hyperdim.h"

#define NUM_WORDS   4
#define NUM_CLASSES 3

static uint64_t query[NUM_WORDS];
static uint64_t am[NUM_CLASSES][NUM_WORDS];

static inline uint64_t read_cycles() {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r" (cycles));
    return cycles;
}

int main() {
    /* class 0 */
    am[0][0] = 0xFFFF0000FFFF0000ULL; am[0][1] = 0xF0F0F0F0F0F0F0F0ULL;
    am[0][2] = 0x00000000FFFFFFFFULL; am[0][3] = 0x1234567890ABCDEFULL;
    /* class 1 */
    am[1][0] = 0x0000FFFF0000FFFFULL; am[1][1] = 0x0F0F0F0F0F0F0F0FULL;
    am[1][2] = 0xFFFFFFFF00000000ULL; am[1][3] = 0xFEDCBA0987654321ULL;
    /* class 2 */
    am[2][0] = 0xAAAAAAAAAAAAAAAAULL; am[2][1] = 0x5555555555555555ULL;
    am[2][2] = 0xCCCCCCCCCCCCCCCCULL; am[2][3] = 0x3333333333333333ULL;

    /* query = class 1 with 3 bits flipped */
    for (int i = 0; i < NUM_WORDS; i++) query[i] = am[1][i];
    query[0] ^= 0x7ULL;

    /* words per vector = 4, number of classes = 3 */
    HYPERDIM_SETCFG(NUM_WORDS, NUM_CLASSES);

    uint64_t predicted = 0xdeadbeefULL;

    printf("Starting HyperDim RoCC AM search...\n");
    uint64_t start_cycles = read_cycles();

    HYPERDIM_AM_SEARCH(predicted, query, am);

    uint64_t total_cycles = read_cycles() - start_cycles;

    printf("AM search result (class index): %lu (expected 1)\n", predicted);
    printf("Hardware Clock Cycles Taken: %lu\n", total_cycles);

    if (predicted == 1) {
        printf("STATUS: SUCCESS!\n");
    } else {
        printf("STATUS: FAILED!\n");
    }

    return 0;
}
