/* Example: one coordinator brackets concurrent work on any target backend. */
#include "l2_perf.h"
extern void launch_backend_work(void);
extern void wait_for_work(void);
extern void wait_for_drain(void);
int measured_region(uint64_t window_id) {
  if (l2perf_start(window_id)) return 1;
  launch_backend_work();
  if (l2perf_snapshot()) return 2;
  wait_for_work();
  if (l2perf_stop()) return 3;
  /* This function is application-specific; STOP does not flush the cache. */
  wait_for_drain();
  return l2perf_drain_end() ? 4 : 0;
}
