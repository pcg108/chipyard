#include <stdint.h>
#include <stdio.h>
#include "mmio.h"
#include "trafficgen.h"

static uint32_t read_reg(unsigned offset) { return reg_read32(TG_BASE + offset); }
static void write_reg(unsigned offset, uint32_t value) {
  reg_write32(TG_BASE + offset, value);
}
static void fence_io(void) { __asm__ volatile ("fence iorw, iorw" ::: "memory"); }
static uint64_t read_counter(unsigned low, unsigned high) {
  uint32_t first, second, bottom;
  do {
    first = read_reg(high);
    bottom = read_reg(low);
    second = read_reg(high);
  } while (first != second);
  return ((uint64_t)first << 32) | bottom;
}
static uint64_t cpu_cycle(void) {
  uint64_t value;
  __asm__ volatile ("rdcycle %0" : "=r" (value));
  return value;
}

static int check_errors(void) {
  const uint32_t session = read_reg(TG_SESSION_STATUS);
  if ((session & (TG_SESSION_ERROR | TG_SESSION_TRUNCATED)) || read_reg(TG_SESSION_ERRORS)) {
    printf("[target] ERROR: TrafficGen session status=0x%x errors=0x%x\n",
           session, read_reg(TG_SESSION_ERRORS));
    return -1;
  }
  for (unsigned i = 0; i < 4; ++i) {
    const uint32_t state = read_reg(TG_SLOT(i) + TG_STATUS);
    if (state == TG_REJECTED || state == TG_ABORTED || read_reg(TG_SLOT(i) + TG_SLOT_ERRORS)) {
      printf("[target] ERROR: TrafficGen slot=%u state=%u errors=0x%x\n",
             i, state, read_reg(TG_SLOT(i) + TG_SLOT_ERRORS));
      return -1;
    }
  }
  return 0;
}

// waits for each slot to have a valid state (TG_ACCEPTED, TG_DISPATCHED, or TG_COMPLETE)
// errors are -1
static int wait_slot(unsigned slot, int completion) {
  const uint64_t start = cpu_cycle();
  for (;;) {
    const uint32_t state = read_reg(TG_SLOT(slot) + TG_STATUS);
    if (check_errors()) return -1;
    if (completion ? state == TG_COMPLETE :
        (state == TG_ACCEPTED || state == TG_DISPATCHED || state == TG_COMPLETE)) return 0;
    if (cpu_cycle() - start > 250000000ULL) {
      printf("[target] ERROR: timeout waiting for slot=%u state=%u\n", slot, state);
      return -1;
    }
  }
}

static int wait_session(uint32_t flag) {
  const uint64_t start = cpu_cycle();
  while (!(read_reg(TG_SESSION_STATUS) & flag)) {
    if (check_errors()) return -1;
    if (cpu_cycle() - start > 250000000ULL) {
      printf("[target] ERROR: timeout waiting for session flag=0x%x\n", flag);
      return -1;
    }
  }
  return check_errors();
}

// write register ID (low/high), fence, write slot submit doorbell, fence, read allocated launch ID
static uint64_t submit(unsigned slot, uint64_t registry, int close_submissions) {
  write_reg(TG_SLOT(slot) + TG_REGISTRY_LOW, (uint32_t)registry);
  write_reg(TG_SLOT(slot) + TG_REGISTRY_HIGH, (uint32_t)(registry >> 32));
  fence_io();
  write_reg(TG_SLOT(slot) + (close_submissions ? TG_SUBMIT_AND_CLOSE : TG_SUBMIT), 1);
  fence_io();
  return read_counter(TG_SLOT(slot) + TG_LAUNCH_LOW, TG_SLOT(slot) + TG_LAUNCH_HIGH);
}

int main(void) {
  if (read_reg(TG_ABI_VERSION) != 3 || read_reg(TG_SLOT_COUNT) != 4) return 1;
  uint64_t previous = 0;
  for (unsigned i = 0; i < 8; ++i) {
    const uint64_t id = submit(0, 1106 + (i % 2), i == 7);
    if (id <= previous || wait_slot(0, 1)) return 1;
    previous = id;
    if (i != 7 && wait_session(TG_SESSION_IDLE)) return 1;
  }
  if (wait_session(TG_SESSION_COMPLETE)) return 1;
  printf("[target] PASS TrafficGen eight alternating Rodinia launches final_cycle=%llu\n",
         (unsigned long long)read_counter(TG_CYCLE_LOW, TG_CYCLE_HIGH));
  return 0;
}
