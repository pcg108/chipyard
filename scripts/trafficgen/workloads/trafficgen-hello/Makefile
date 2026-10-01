CC=riscv64-unknown-elf-gcc
TEST_CASE ?= pair
KERNEL_REGISTRY_ID ?= 1106
CASE_single = 1
CASE_pair = 2
CASE_four = 4
CASE_reuse = 5
ifeq ($(CASE_$(TEST_CASE)),)
$(error TEST_CASE must be single, pair, four, or reuse)
endif
BARE_COMMON=../../test/bare
CFLAGS=-mcmodel=medany -Wall -O2 -fno-common -fno-builtin -I$(BARE_COMMON) -DTRAFFICGEN_TEST_CASE=$(CASE_$(TEST_CASE))
ifeq ($(TEST_CASE),single)
CFLAGS += -DTRAFFICGEN_SINGLE_REGISTRY_ID=$(KERNEL_REGISTRY_ID)
endif
LDFLAGS=-static -nostdlib -nostartfiles -lgcc

hello: hello.o crt.o syscalls.o
	$(CC) -T $(BARE_COMMON)/link.ld $(LDFLAGS) $^ -o $@

hello.o: hello.c trafficgen.h FORCE
	$(CC) $(CFLAGS) -c $< -o $@

crt.o: $(BARE_COMMON)/crt.S $(BARE_COMMON)/encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

syscalls.o: $(BARE_COMMON)/syscalls.c $(BARE_COMMON)/util.h $(BARE_COMMON)/encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f *.o
	rm -f hello

.PHONY: FORCE clean
FORCE:
