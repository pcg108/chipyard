// See LICENSE for license details.
#ifndef L2PERF_RECORDS_H
#define L2PERF_RECORDS_H
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace l2perf {
enum Marker : uint32_t { START = 1, SNAPSHOT = 2, STOP = 3, DRAIN_END = 4, RESET = 5 };
inline const char *marker_name(uint32_t kind) {
  switch (kind) {
  case START: return "START";
  case SNAPSHOT: return "SNAPSHOT";
  case STOP: return "STOP";
  case DRAIN_END: return "DRAIN_END";
  case RESET: return "RESET";
  default: return "INVALID";
  }
}
struct Record {
  uint64_t sequence = 0, cycle = 0, window = 0;
  uint32_t marker_sequence = 0, kind = 0, errors = 0;
  std::vector<uint64_t> values;
};
struct Interval { std::string kind; Record begin, end; };
/** Host-side validation uses the frozen records, not RTL increment equations. */
class Collector {
public:
  std::vector<Interval> intervals;
  std::vector<std::string> errors;
  void accept(const Record &record) {
    if (record.sequence != next_sequence++)
      errors.emplace_back("missing, duplicated, or reordered bridge snapshot");
    if (previous && record.cycle < previous->cycle)
      errors.emplace_back("target cycle moved backwards");
    if (previous && record.values.size() != previous->values.size())
      errors.emplace_back("counter vector size changed");
    previous = record;
    if (record.kind != RESET && record.marker_sequence != next_marker_sequence++)
      errors.emplace_back("missing, duplicated, or reordered target marker");
    if (record.errors) errors.emplace_back("hardware marker error " + std::to_string(record.errors));
    if (record.kind == RESET) {
      if (start || stop) errors.emplace_back("target reset interrupted a measurement or drain window");
      start.reset(); last.reset(); stop.reset(); next_marker_sequence = 1; return;
    }
    if (record.kind == START) {
      if (start || stop) { errors.emplace_back("START before prior window DRAIN_END"); return; }
      start = record; last = record; stop.reset(); ++starts;
    } else if (record.kind == SNAPSHOT || record.kind == STOP) {
      if (!start || start->window != record.window) {
        errors.emplace_back("SNAPSHOT/STOP without matching START"); return;
      }
      if (record.kind == SNAPSHOT) {
        intervals.push_back({"snapshot", *last, record});
        last = record;
      } else {
        intervals.push_back({"window", *start, record});
        stop = record; start.reset(); last.reset();
      }
    } else if (record.kind == DRAIN_END) {
      if (!stop || stop->window != record.window || start) {
        errors.emplace_back("DRAIN_END without matching STOP"); return;
      }
      intervals.push_back({"drain", *stop, record}); stop.reset();
    } else {
      errors.emplace_back("unknown marker kind");
    }
  }
  void finish() {
    if (!starts) errors.emplace_back("no START marker observed");
    if (start) errors.emplace_back("measurement window missing STOP");
    if (stop) errors.emplace_back("drain window missing DRAIN_END");
  }
  bool complete() const { return starts && !start && !stop && errors.empty(); }
  static uint64_t delta(uint64_t begin, uint64_t end) { return end - begin; }
private:
  uint64_t next_sequence = 0, starts = 0;
  uint32_t next_marker_sequence = 1;
  std::optional<Record> start, last, stop, previous;
};
} // namespace l2perf
#endif
