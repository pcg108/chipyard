#ifndef AMPERE_L2_PERF_H
#define AMPERE_L2_PERF_H
#include <stdint.h>

/* Target MMIO control only. Counters and atomic snapshot vectors live in the
 * FireSim host bridge. Exactly one target coordinator must use this interface.
 * START/STOP cover [START, STOP); events on START are included, on STOP excluded.
 * SNAPSHOT captures the same pre-event cumulative vector, without reset.
 */
#define L2PERF_BASE ((uintptr_t)0x02010000UL)
#define L2PERF_WINDOW_LO 0x100
#define L2PERF_WINDOW_HI 0x104
#define L2PERF_COMMAND 0x108
#define L2PERF_ACK_SEQUENCE 0x110
#define L2PERF_STATE 0x118
#define L2PERF_ERRORS 0x120
#define L2PERF_START 1U
#define L2PERF_SNAPSHOT 2U
#define L2PERF_STOP 3U
#define L2PERF_DRAIN_END 4U
#define L2PERF_IDLE 0U
#define L2PERF_ACTIVE 1U
#define L2PERF_DRAINING 2U
#define L2PERF_BAD_COMMAND 1U
#define L2PERF_BAD_STATE 2U

static inline void l2perf_fence(void) {
  __asm__ volatile ("fence iorw,iorw" ::: "memory");
}
static inline uint32_t l2perf_read(unsigned offset) {
  return *(volatile uint32_t *)(L2PERF_BASE + offset);
}
static inline void l2perf_write(unsigned offset, uint32_t value) {
  *(volatile uint32_t *)(L2PERF_BASE + offset) = value;
  l2perf_fence();
}
static inline void l2perf_stage_window(uint64_t window_id) {
  l2perf_write(L2PERF_WINDOW_LO, (uint32_t)window_id);
  l2perf_write(L2PERF_WINDOW_HI, (uint32_t)(window_id >> 32));
}
/* Sticky errors are deliberately not silently cleared. Return -1 on an
 * invalid command/state, -2 on acknowledgement timeout, 0 on success. */
static inline int l2perf_command(uint32_t command) {
  uint32_t before = l2perf_read(L2PERF_ACK_SEQUENCE);
  l2perf_write(L2PERF_COMMAND, command);
  for (unsigned n = 0; n < 1000000; ++n) {
    if (l2perf_read(L2PERF_ACK_SEQUENCE) != before)
      return l2perf_read(L2PERF_ERRORS) ? -1 : 0;
  }
  return -2;
}
static inline int l2perf_start(uint64_t window_id) {
  l2perf_stage_window(window_id);
  return l2perf_command(L2PERF_START);
}
static inline int l2perf_snapshot(void) { return l2perf_command(L2PERF_SNAPSHOT); }
static inline int l2perf_stop(void) { return l2perf_command(L2PERF_STOP); }
static inline int l2perf_drain_end(void) { return l2perf_command(L2PERF_DRAIN_END); }
#endif
