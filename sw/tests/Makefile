CC = riscv64-unknown-elf-gcc
CFLAGS = -O2 -static -std=gnu99 -Wall

TARGETS = hyperdim_test.riscv am_search_test.riscv
HEADERS = rocc.h hyperdim.h

all: $(TARGETS)

hyperdim_test.riscv: main.c $(HEADERS)
	$(CC) $(CFLAGS) -o $@ main.c

am_search_test.riscv: am_search_test.c $(HEADERS)
	$(CC) $(CFLAGS) -o $@ am_search_test.c
clean:
	rm -f $(TARGETS)
