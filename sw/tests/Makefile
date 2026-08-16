CC = riscv64-unknown-elf-gcc
CFLAGS = -O2 -static -std=gnu99 -Wall

TARGET = hyperdim_test.riscv

all: $(TARGET)

$(TARGET): main.c
	$(CC) $(CFLAGS) -o $@ $^

clean:
	rm -f $(TARGET)
