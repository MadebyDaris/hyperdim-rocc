#ifndef HYPERDIM_H
#define HYPERDIM_H

#include "rocc.h"

#define HYPERDIM_CUSTOM 0

#define HYPERDIM_OP_HAMMING   0
#define HYPERDIM_OP_COSINE    1
#define HYPERDIM_OP_BIND      2
#define HYPERDIM_OP_BUNDLE    3
#define HYPERDIM_OP_PERMUTE   4
#define HYPERDIM_OP_DOT       5
#define HYPERDIM_OP_SETCFG    6
#define HYPERDIM_OP_AM_SEARCH 7

/* rd = hamming_distance(rs1, rs2); vector length comes from the config
 * register (defaults to the elaborated vectorBits/64). */
#define HYPERDIM_HAMMING(rd, rs1, rs2) \
    ROCC_INSTRUCTION_DSS(HYPERDIM_CUSTOM, rd, rs1, rs2, HYPERDIM_OP_HAMMING)

/* Set runtime configuration: rs1 = words per hypervector,
 * rs2 = number of class hypervectors. No destination register. */
#define HYPERDIM_SETCFG(num_words, num_classes) \
    ROCC_INSTRUCTION_SS(HYPERDIM_CUSTOM, num_words, num_classes, HYPERDIM_OP_SETCFG)

/* rd = argmin_i hamming(rs1, rs2 + i * words * 8).
 * Requires HYPERDIM_SETCFG first. rs1 = query, rs2 = AM base (classes
 * stored contiguously, row-major). */
#define HYPERDIM_AM_SEARCH(rd, query, am_base) \
    ROCC_INSTRUCTION_DSS(HYPERDIM_CUSTOM, rd, query, am_base, HYPERDIM_OP_AM_SEARCH)

#endif
