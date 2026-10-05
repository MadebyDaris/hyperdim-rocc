CC = riscv64-unknown-elf-gcc

# Bare-metal HTIF link, matching chipyard's tests/CMakeLists.txt so the
# binaries place _start at 0x80000000 and expose tohost/fromhost (plus a
# working _write) that the testchipip harness needs. Requires a complete
# riscv64-unknown-elf toolchain with the htif_nano.specs + libgloss_htif
# installed under $RISCV/riscv64-unknown-elf.
CFLAGS = -O2 -std=gnu99 -Wall -fno-common -fno-builtin-printf \
         -march=rv64imafd -mabi=lp64d -mcmodel=medany -specs=htif_nano.specs
LDFLAGS = -static -T htif.ld

TARGETS = hyperdim_test.riscv am_search_test.riscv test_printf.riscv
HEADERS = rocc.h hyperdim.h

all: $(TARGETS)

hyperdim_test.riscv: main.c $(HEADERS) htif.ld
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

%.riscv: %.c $(HEADERS) htif.ld
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

clean:
	rm -f $(TARGETS)