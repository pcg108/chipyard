// Real collector driver with an independently supplied frozen-record MMIO model.
#include "bridges/l2perf.h"
#include "core/config.h"
#include <array>
#include <cassert>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>

simif_t::simif_t(const TargetConfig &c) : config(c) {}
simif_t::~simif_t() = default;
CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() { throw std::runtime_error("unused"); }
FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() { throw std::runtime_error("unused"); }
int simif_t::run(simulation_t &) { return 0; }
widget_t::widget_t(simif_t &s, const void *k) : simif(s), kind(k) {}
widget_t::~widget_t() = default;
void bridge_driver_t::write(size_t addr, uint32_t data) { simif.write(addr, data); }
uint32_t bridge_driver_t::read(size_t addr) { return simif.read(addr); }
static const TargetConfig config{{1, 32, 32}, {1, 64, 64}, 1, std::nullopt, std::nullopt, {1, 1}, "test"};

struct Mock : simif_t {
  L2PERFBRIDGEMODULE_struct mmio{};
  std::vector<l2perf::Record> records;
  size_t head = 0, index = 0, reads = 0;
  std::array<unsigned, 2> low_reads{}, high_reads{};
  bool initialized = false;
  Mock() : simif_t(::config) {
    uint64_t address = 0;
#define REG(field) mmio.field = address++
    REG(init_done); REG(schema_version); REG(counter_count); REG(record_valid); REG(record_kind);
    REG(sequence_low); REG(sequence_high); REG(cycle_low); REG(cycle_high);
    REG(window_low); REG(window_high); REG(marker_sequence); REG(record_errors); REG(sticky_errors);
    REG(counter_index); REG(counter_low); REG(counter_high); REG(record_ack);
#undef REG
  }
  uint32_t read(size_t address) override {
    ++reads;
    if (address == mmio.schema_version) return 1;
    if (address == mmio.counter_count) return 2;
    if (address == mmio.record_valid) return initialized && head < records.size();
    if (address == mmio.sticky_errors) return 0;
    const auto &r = records.at(head);
    if (address == mmio.record_kind) return r.kind;
    if (address == mmio.marker_sequence) return r.marker_sequence;
    if (address == mmio.record_errors) return r.errors;
    if (address == mmio.sequence_low) return r.sequence;
    if (address == mmio.sequence_high) return r.sequence >> 32;
    if (address == mmio.cycle_low) return r.cycle;
    if (address == mmio.cycle_high) return r.cycle >> 32;
    if (address == mmio.window_low) return r.window;
    if (address == mmio.window_high) return r.window >> 32;
    if (address == mmio.counter_low) { ++low_reads.at(index); return r.values.at(index); }
    if (address == mmio.counter_high) { ++high_reads.at(index); return r.values.at(index) >> 32; }
    throw std::runtime_error("unknown read");
  }
  void write(size_t address, uint32_t data) override {
    if (address == mmio.init_done) initialized = data;
    else if (address == mmio.counter_index) { assert(data < 2); index = data; }
    else if (address == mmio.record_ack) {
      assert(data == 1);
      for (size_t i = 0; i < 2; ++i) assert(low_reads[i] == 1 && high_reads[i] == 1);
      low_reads.fill(0); high_reads.fill(0); ++head;
    } else throw std::runtime_error("unknown write");
  }
};
static l2perf::Record record(uint64_t seq, uint32_t kind, uint64_t cycle, uint64_t count,
                             uint64_t gauge, uint64_t window = (1ULL << 48) + 9) {
  l2perf::Record r;
  r.sequence = seq; r.marker_sequence = seq + 1; r.kind = kind; r.cycle = cycle;
  r.window = window; r.values = {count, gauge}; return r;
}
int main(int argc, char **argv) {
  assert(argc == 2);
  const uint64_t max = std::numeric_limits<uint64_t>::max();
  Mock mock;
  // Independent observations: wrap between boundaries, carry-in/out gauge,
  // two intermediate snapshots, and a separate drain interval.
  mock.records = {record(0, l2perf::START, 100, max - 4, 2),
                  record(1, l2perf::SNAPSHOT, 102, max - 1, 4),
                  record(2, l2perf::SNAPSHOT, 104, 3, 1),
                  record(3, l2perf::STOP, 106, 9, 3),
                  record(4, l2perf::DRAIN_END, 110, 12, 0),
                  record(5, l2perf::START, 112, 12, 0, 10),
                  record(6, l2perf::STOP, 113, 12, 0, 10),
                  record(7, l2perf::DRAIN_END, 114, 12, 0, 10)};
  const std::vector<l2perf_t::Counter> counters = {
      {0, "accepted", "transactions", "trafficgen", false},
      {0, "outstanding", "entries", "all", true}};
  l2perf_t driver(mock, mock.mmio, 0, {std::string("+l2perf-output0=") + argv[1]}, counters);
  driver.init();
  assert(mock.head == 0);
  driver.tick(); assert(mock.head == 2); // bounded service lets other bridges run
  driver.tick(); assert(mock.head == 4);
  driver.finish(); assert(mock.head == 8 && driver.exit_code() == 0);
  std::ifstream summary(std::string(argv[1]) + ".summary.json");
  const std::string data((std::istreambuf_iterator<char>(summary)), {});
  assert(data.find("\"complete\":true") != std::string::npos);
  assert(data.find("\"delta\":14") != std::string::npos); // 9 - (max-4), modulo 2^64
  assert(data.find("\"kind\":\"drain\"") != std::string::npos);
  assert(l2perf::Collector::delta(max, 0) == 1);
  l2perf::Collector invalid;
  invalid.accept(record(0, l2perf::SNAPSHOT, 0, 0, 0)); invalid.finish();
  assert(!invalid.complete() && invalid.errors.size() == 2);
  l2perf::Collector missing;
  missing.accept(record(0, l2perf::START, 0, 0, 0)); missing.finish();
  assert(!missing.complete());
  l2perf::Collector reset;
  reset.accept(record(0, l2perf::START, 0, 0, 0));
  reset.accept(record(1, l2perf::RESET, 10, 1, 1)); reset.finish();
  assert(!reset.complete());
  l2perf::Collector lost;
  lost.accept(record(0, l2perf::START, 0, 0, 0));
  lost.accept(record(2, l2perf::STOP, 10, 1, 1)); lost.finish();
  assert(!lost.complete());
  l2perf::Collector idle_reset;
  idle_reset.accept(record(0, l2perf::START, 0, 0, 0));
  idle_reset.accept(record(1, l2perf::STOP, 1, 1, 0));
  idle_reset.accept(record(2, l2perf::DRAIN_END, 2, 1, 0));
  idle_reset.accept(record(3, l2perf::RESET, 3, 1, 0));
  auto after_reset = record(4, l2perf::START, 4, 1, 0);
  after_reset.marker_sequence = 1; idle_reset.accept(after_reset);
  after_reset = record(5, l2perf::STOP, 5, 1, 0);
  after_reset.marker_sequence = 2; idle_reset.accept(after_reset);
  after_reset = record(6, l2perf::DRAIN_END, 6, 1, 0);
  after_reset.marker_sequence = 3; idle_reset.accept(after_reset);
  idle_reset.finish(); assert(idle_reset.complete());
  // FireSim does not re-read bridge exit codes after finish(). Therefore missing
  // final boundaries must throw from the real driver's finish, after preserving
  // the final evidence, even if no tick-time hardware error occurred.
  for (bool include_stop : {false, true}) {
    Mock incomplete;
    incomplete.records = {record(0, l2perf::START, 0, 0, 0)};
    if (include_stop) incomplete.records.push_back(record(1, l2perf::STOP, 4, 3, 1));
    const std::string output = std::string(argv[1]) +
        (include_stop ? "-missing-drain" : "-missing-stop");
    l2perf_t unfinished(incomplete, incomplete.mmio, 0,
        {std::string("+l2perf-output0=") + output}, counters);
    unfinished.init();
    assert(unfinished.exit_code() == 0 && !unfinished.terminate());
    bool propagated = false;
    try { unfinished.finish(); }
    catch (const std::runtime_error &error) {
      propagated = std::string(error.what()).find("final collection failed") != std::string::npos;
    }
    assert(propagated && unfinished.terminate() && unfinished.exit_code() != 0);
    assert(incomplete.head == incomplete.records.size());
    std::ifstream failure_summary(output + ".summary.json");
    const std::string evidence((std::istreambuf_iterator<char>(failure_summary)), {});
    assert(evidence.find("\"finished\":true") != std::string::npos);
    assert(evidence.find("\"complete\":false") != std::string::npos);
    assert(evidence.find(include_stop ? "drain window missing DRAIN_END" :
                                        "measurement window missing STOP") != std::string::npos);
  }
  std::cout << "PASS L2Perf real driver: frozen indexed/split reads, snapshots, wrap, gauge, drain, repeated/empty windows, missing/invalid/reset/lost records and final-boundary failure propagation\n";
}
