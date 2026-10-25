// See LICENSE for license details.
#include "l2perf.h"
#include <cstdio>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>

char l2perf_t::KIND;
namespace {
std::string json(const std::string &s) {
  std::ostringstream out; out << '"';
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(c);
    else out << c;
  }
  out << '"'; return out.str();
}
}
l2perf_t::l2perf_t(simif_t &simif, const L2PERFBRIDGEMODULE_struct &addresses,
                   int instance, const std::vector<std::string> &args,
                   const std::vector<Counter> &descriptors)
    : bridge_driver_t(simif, &KIND), mmio(addresses), counters(descriptors),
      prefix("l2perf" + std::to_string(instance)) {
  const auto option = "+l2perf-output" + std::to_string(instance) + "=";
  for (const auto &arg : args)
    if (arg.rfind(option, 0) == 0) prefix = arg.substr(option.size());
}
uint64_t l2perf_t::read64(uint64_t low, uint64_t high) {
  const uint64_t lo = read(low);
  return lo | (uint64_t(read(high)) << 32);
}
void l2perf_t::init() {
  if (read(mmio.schema_version) != 1 || read(mmio.counter_count) != counters.size())
    throw std::runtime_error("L2Perf driver/bitstream schema mismatch");
  csv.open(prefix + ".csv", std::ios::out | std::ios::trunc);
  if (!csv) throw std::runtime_error("cannot open L2Perf CSV: " + prefix);
  csv << "schema_version,window_id,sequence,marker_sequence,marker_type,target_cycle,errors,bank,requester,counter,unit,kind,value\n";
  write_schema(); write_summary();
  write(mmio.init_done, 1);
}
void l2perf_t::tick() {
  // Bound each tick so other bridges continue to be serviced during long runs.
  for (unsigned record_index = 0; record_index < 2 && read(mmio.record_valid); ++record_index) {
    l2perf::Record record;
    record.sequence = read64(mmio.sequence_low, mmio.sequence_high);
    record.cycle = read64(mmio.cycle_low, mmio.cycle_high);
    record.window = read64(mmio.window_low, mmio.window_high);
    record.marker_sequence = read(mmio.marker_sequence);
    record.kind = read(mmio.record_kind);
    record.errors = read(mmio.record_errors);
    for (size_t i = 0; i < counters.size(); ++i) {
      write(mmio.counter_index, i);
      record.values.push_back(read64(mmio.counter_low, mmio.counter_high));
      const auto &c = counters[i];
      csv << "1," << record.window << ',' << record.sequence << ','
          << record.marker_sequence << ',' << l2perf::marker_name(record.kind) << ','
          << record.cycle << ',' << record.errors << ',' << c.bank << ','
          << c.requester << ',' << c.name << ',' << c.unit << ','
          << (c.gauge ? "gauge" : "cumulative") << ',' << record.values.back() << '\n';
    }
    // The producer cannot replace the queue head until this write.
    write(mmio.record_ack, 1);
    collector.accept(record);
    csv.flush();
    if (!csv) { failed = true; throw std::runtime_error("L2Perf CSV write failed"); }
    write_summary();
    if (!collector.errors.empty()) failed = true;
  }
}
void l2perf_t::write_schema() {
  std::ofstream out(prefix + ".schema.json");
  if (!out) throw std::runtime_error("cannot open L2Perf schema");
  out << "{\"schema_version\":1,\"counter_width\":64,\"event_width\":32,"
         "\"boundary_convention\":\"pre-increment snapshots; [START,STOP)\","
         "\"cycle_origin\":\"accepted target-clock tokens since host reset, including target reset\","
         "\"shared_stalls_overlap\":true,\"counters\":[";
  for (size_t i = 0; i < counters.size(); ++i) {
    const auto &c = counters[i];
    if (i) out << ',';
    out << "{\"index\":" << i << ",\"bank\":" << c.bank << ",\"name\":" << json(c.name)
        << ",\"unit\":" << json(c.unit) << ",\"requester\":" << json(c.requester)
        << ",\"gauge\":" << (c.gauge ? "true" : "false") << '}';
  }
  out << "]}\n";
  if (!out) throw std::runtime_error("L2Perf schema write failed");
}
void l2perf_t::write_summary() {
  const auto path = prefix + ".summary.json";
  const auto temporary = path + ".tmp";
  std::ofstream out(temporary);
  if (!out) throw std::runtime_error("cannot open L2Perf summary");
  out << "{\"schema_version\":1,\"boundary_convention\":\"[START,STOP)\",\"complete\":"
      << (collector.complete() ? "true" : "false") << ",\"finished\":" << (finished ? "true" : "false")
      << ",\"errors\":[";
  for (size_t i = 0; i < collector.errors.size(); ++i) {
    if (i) out << ',';
    out << json(collector.errors[i]);
  }
  out << "],\"intervals\":[";
  for (size_t n = 0; n < collector.intervals.size(); ++n) {
    const auto &interval = collector.intervals[n];
    if (n) out << ',';
    out << "{\"kind\":" << json(interval.kind) << ",\"window_id\":" << interval.begin.window
        << ",\"start_sequence\":" << interval.begin.sequence << ",\"end_sequence\":" << interval.end.sequence
        << ",\"start_cycle\":" << interval.begin.cycle << ",\"end_cycle\":" << interval.end.cycle
        << ",\"counters\":[";
    for (size_t i = 0; i < counters.size(); ++i) {
      const auto &c = counters[i];
      if (i) out << ',';
      out << "{\"bank\":" << c.bank << ",\"requester\":" << json(c.requester)
          << ",\"name\":" << json(c.name) << ",\"unit\":" << json(c.unit)
          << ",\"gauge\":" << (c.gauge ? "true" : "false")
          << ",\"start\":" << interval.begin.values.at(i) << ",\"end\":" << interval.end.values.at(i);
      if (!c.gauge) out << ",\"delta\":" << l2perf::Collector::delta(interval.begin.values.at(i), interval.end.values.at(i));
      out << '}';
    }
    out << "]}";
  }
  out << "]}\n";
  out.close();
  if (!out || std::rename(temporary.c_str(), path.c_str()) != 0)
    throw std::runtime_error("L2Perf summary write failed");
}
void l2perf_t::finish() {
  if (finished) return;
  while (read(mmio.record_valid)) tick();
  collector.finish();
  if (read(mmio.sticky_errors) && collector.errors.empty())
    collector.errors.emplace_back("hardware reported an uncollected marker error");
  finished = true;
  failed = failed || !collector.errors.empty();
  write_summary(); csv.flush();
  for (const auto &error : collector.errors) std::cerr << "L2Perf: " << error << '\n';
}
