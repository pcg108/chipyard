// See LICENSE for license details.
#ifndef L2PERF_H
#define L2PERF_H
#include "core/bridge_driver.h"
#include "l2perf_records.h"
#include <fstream>
#include <string>
#include <vector>

struct L2PERFBRIDGEMODULE_struct {
  uint64_t init_done, schema_version, counter_count, record_valid, record_kind;
  uint64_t sequence_low, sequence_high, cycle_low, cycle_high;
  uint64_t window_low, window_high, marker_sequence, record_errors, sticky_errors;
  uint64_t counter_index, counter_low, counter_high, record_ack;
};
class l2perf_t final : public bridge_driver_t {
public:
  static char KIND;
  struct Counter {
    uint32_t bank;
    const char *name;
    const char *unit;
    const char *requester;
    bool gauge;
  };
  l2perf_t(simif_t &, const L2PERFBRIDGEMODULE_struct &, int,
           const std::vector<std::string> &, const std::vector<Counter> &);
  void init() override;
  void tick() override;
  void finish() override;
  bool terminate() override { return failed; }
  int exit_code() override { return failed ? 1 : 0; }
private:
  L2PERFBRIDGEMODULE_struct mmio;
  std::vector<Counter> counters;
  std::string prefix;
  std::ofstream csv;
  l2perf::Collector collector;
  bool failed = false, finished = false;
  uint64_t read64(uint64_t low, uint64_t high);
  void write_summary();
  void write_schema();
};
#endif
