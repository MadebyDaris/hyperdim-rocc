CC = riscv64-unknown-elf-gcc
CFLAGS = -O2 -static -std=gnu99 -Wall

TARGET = hyperdim_test.riscv
HEADERS = rocc.h hyperdim.h

all: $(TARGET)

$(TARGET): main.c $(HEADERS)
	$(CC) $(CFLAGS) -o $@ main.c

clean:
	rm -f $(TARGET)
