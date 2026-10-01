#include <stdint.h>
#include <stdio.h>
#include "mmio.h"
#include "trafficgen.h"

/* 1: single registry entry; 2: Rodinia + short ResNet; 4: four datasets; 5: idle and slot reuse. */
#ifndef TRAFFICGEN_TEST_CASE
#define TRAFFICGEN_TEST_CASE 2
#endif
#if TRAFFICGEN_TEST_CASE != 1 && TRAFFICGEN_TEST_CASE != 2 && TRAFFICGEN_TEST_CASE != 4 && TRAFFICGEN_TEST_CASE != 5
#error "TRAFFICGEN_TEST_CASE must be 1, 2, 4, or 5"
#endif
#ifndef TRAFFICGEN_SINGLE_REGISTRY_ID
#define TRAFFICGEN_SINGLE_REGISTRY_ID 1106
#endif

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
  const unsigned count = TRAFFICGEN_TEST_CASE == 1 ? 1 : TRAFFICGEN_TEST_CASE == 4 ? 4 : 2;

  // 4 kernels to test
  const uint64_t registries[4] = {
    TRAFFICGEN_TEST_CASE == 1 ? TRAFFICGEN_SINGLE_REGISTRY_ID : 1106, 1003, 1044, 1001
  };

  // check ABI version and slot count
  uint64_t launches[4] = {0};
  printf("[target] TrafficGen launch ABI 3 test=%u\n", TRAFFICGEN_TEST_CASE);
  if (read_reg(TG_ABI_VERSION) != 3 || read_reg(TG_SLOT_COUNT) != 4) {
    printf("[target] ERROR: requires TrafficGen RTL launch ABI 3 with four slots\n");
    return 1;
  }

  if (TRAFFICGEN_TEST_CASE == 1) {
    // Keep the active interval small: stage and atomically submit once, then
    // use the historical diagnostic/done polling pattern. Read launch status
    // and print only after replay finishes, avoiding extra shared-cache traffic.
    write_reg(TG_SLOT(0) + TG_REGISTRY_LOW, (uint32_t)registries[0]);
    write_reg(TG_SLOT(0) + TG_REGISTRY_HIGH, (uint32_t)(registries[0] >> 32));
    fence_io();
    write_reg(TG_SLOT(0) + TG_SUBMIT_AND_CLOSE, 1);
    for (;;) {
      if (read_reg(TG_ENGINE_STATE) == 0xdead0001U) {
        printf("[target] ERROR: TrafficGen engine reported a trace error\n");
        return 1;
      }
      if (read_reg(TG_GLOBAL_DONE) & 1U) break;
    }
    // Protocol failures and stalled sessions are bounded by the simulator's
    // host-side limits, as in the historical application's active polling loop.
    if (check_errors()) return 1;
    if (read_reg(TG_SLOT(0) + TG_STATUS) != TG_COMPLETE ||
        !(read_reg(TG_SESSION_STATUS) & TG_SESSION_COMPLETE)) {
      printf("[target] ERROR: global completion preceded launch completion\n");
      return 1;
    }
    launches[0] = read_counter(TG_SLOT(0) + TG_LAUNCH_LOW, TG_SLOT(0) + TG_LAUNCH_HIGH);
    if (!launches[0]) return 1;
    printf("[target] SUBMIT slot=0 registry=%llu launch=%llu\n",
           (unsigned long long)registries[0], (unsigned long long)launches[0]);
    printf("[target] PASS TrafficGen test=%u final_cycle=%llu\n", TRAFFICGEN_TEST_CASE,
           (unsigned long long)read_counter(TG_CYCLE_LOW, TG_CYCLE_HIGH));
    return 0;
  }

  // submit first kernel
  launches[0] = submit(0, registries[0], 0);
  // Multi-launch tests require overlap; a single launch may already be complete.
  if (!launches[0] || wait_slot(0, 0)) return 1;
  if (count > 1 && read_reg(TG_SLOT(0) + TG_STATUS) == TG_COMPLETE) {
    printf("[target] ERROR: first launch finished before staggered submission\n");
    return 1;
  }

  // submit remaining kernels
  for (unsigned i = 1; i < count; ++i) launches[i] = submit(i, registries[i], 0);


  /* Print after submissions so UART traffic cannot serialize the launches. */
  for (unsigned i = 0; i < count; ++i) {
    if (!launches[i]) return 1;
    printf("[target] SUBMIT slot=%u registry=%llu launch=%llu\n", i,
           (unsigned long long)registries[i], (unsigned long long)launches[i]);
  }

  if (TRAFFICGEN_TEST_CASE == 5) {
    // wait for initial launches to finish
    for (unsigned i = 0; i < count; ++i) if (wait_slot(i, 1)) return 1;
    // confirm no active work and leave it idle for 1024 cycles
    if (wait_session(TG_SESSION_IDLE)) return 1;
    const uint64_t idle_cycle = read_counter(TG_CYCLE_LOW, TG_CYCLE_HIGH);
    while (read_counter(TG_CYCLE_LOW, TG_CYCLE_HIGH) - idle_cycle < 1024) {
      if (check_errors()) return 1;
    }
    // reuse slot 0 for a new launch
    const uint64_t previous = launches[0];
    launches[0] = submit(0, registries[1], 0);
    // check that new launch has a higher launch ID than the previous one
    if (launches[0] <= previous) return 1;
    printf("[target] REUSE slot=0 registry=%llu launch=%llu\n",
           (unsigned long long)registries[1], (unsigned long long)launches[0]);
  }
  // close the session and wait for all slots to complete
  if (count > 1) {
    fence_io();
    write_reg(TG_CLOSE, 1);
  }
  for (unsigned i = 0; i < count; ++i) if (wait_slot(i, 1)) return 1;
  if (wait_session(TG_SESSION_COMPLETE)) return 1;
  printf("[target] PASS TrafficGen test=%u final_cycle=%llu\n", TRAFFICGEN_TEST_CASE,
         (unsigned long long)read_counter(TG_CYCLE_LOW, TG_CYCLE_HIGH));
  return 0;
}
