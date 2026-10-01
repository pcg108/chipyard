// Analyze TrafficGen Boost binary round snapshots without depending on gpu_model
// source files.  The serialized field order mirrors scheduler.h/traffic_gen.h.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#include <boost/archive/binary_iarchive.hpp>
#include <boost/serialization/access.hpp>
#include <boost/serialization/string.hpp>
#include <boost/serialization/vector.hpp>
#include "../generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen_socket_protocol.h"

namespace fs = std::filesystem;

using ScheduledAccess = trafficgen_socket::socket_l2_access_t;
using IssuedAccess = trafficgen_socket::IssuedAccessPoint;
using ScheduledSnapshot = trafficgen_socket::SocketAllL2TraceStepsSnapshot;
using IssuedSnapshot = trafficgen_socket::IssuedAccessesSnapshot;
using CycleSnapshot = trafficgen_socket::CurrentCycleAfterIssueSnapshot;
using RequestKey = std::pair<std::uint64_t, std::uint64_t>;

template <typename T>
T read_snapshot(const fs::path &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) throw std::runtime_error("cannot open snapshot: " + path.string());
  boost::archive::binary_iarchive archive(input);
  T result{};
  archive >> result;
  return result;
}

struct AccessRecord {
  ScheduledAccess scheduled;
  std::optional<IssuedAccess> issued;
  struct LaneAssignment {
    std::uint32_t generation = 0;
    std::uint64_t bundle_id = 0;
    std::size_t lane = 0;
    std::uint16_t member_count = 0;
  };
  std::optional<LaneAssignment> lane_assignment;
  std::uint64_t round = 0;
};

struct RunData {
  std::string label;
  fs::path root;
  std::map<RequestKey, AccessRecord> accesses;
  std::set<RequestKey> duplicate_scheduled;
  std::set<RequestKey> duplicate_issued;
  std::set<RequestKey> issued_without_schedule;
  std::set<RequestKey> duplicate_lane_assignment;
  std::set<std::pair<std::uint32_t, std::uint64_t>> split_bundle_keys;
  std::set<std::pair<std::uint32_t, std::uint64_t>> unstable_member_count_keys;
  std::map<std::pair<std::uint32_t, std::uint64_t>, std::size_t> bundle_lanes;
  std::map<std::pair<std::uint32_t, std::uint64_t>, std::uint16_t> bundle_member_counts;
  std::map<std::size_t, std::size_t> lane_access_counts;
  std::size_t scheduled_rows = 0;
  std::size_t issued_rows = 0;
  std::size_t lane_assignment_rows = 0;
  std::size_t lane_assignment_files = 0;
  std::map<std::uint64_t, std::size_t> scheduled_bundle_members;
  std::map<std::uint64_t, std::size_t> issued_bundle_members;
  std::set<std::uint64_t> completed_bundles;
  std::size_t invalid_bundle_completions = 0;
  std::size_t decreasing_cycles = 0;
  bool launch_qualified = false;
  std::size_t rounds = 0;
  std::uint64_t final_cycle = 0;
};

std::vector<std::string> split_csv_line(const std::string &line) {
  std::vector<std::string> fields;
  std::stringstream stream(line);
  for (std::string field; std::getline(stream, field, ',');) {
    fields.push_back(field);
  }
  return fields;
}

std::uint64_t round_number(const fs::path &path) {
  const std::string name = path.filename().string();
  return std::stoull(name.substr(std::string("round_").size()));
}

std::vector<fs::path> round_dirs(const fs::path &root) {
  if (!fs::is_directory(root)) {
    throw std::runtime_error("round-log root is not a directory: " + root.string());
  }
  std::vector<fs::path> result;
  for (const auto &entry : fs::directory_iterator(root)) {
    const std::string name = entry.path().filename().string();
    if (entry.is_directory() && name.size() == 12 && name.rfind("round_", 0) == 0 &&
        std::all_of(name.begin() + 6, name.end(), ::isdigit)) {
      result.push_back(entry.path());
    }
  }
  std::sort(result.begin(), result.end());
  return result;
}

RunData load_run(std::string label, const fs::path &root) {
  RunData run{std::move(label), root};
  const auto rounds = round_dirs(root);
  run.rounds = rounds.size();
  for (const auto &dir : rounds) {
    const auto round = round_number(dir);
    const auto scheduled = read_snapshot<ScheduledSnapshot>(dir / "all_l2_trace_steps.bin");
    const auto issued = read_snapshot<IssuedSnapshot>(dir / "issued_accesses.bin");
    run.scheduled_rows += scheduled.steps.size();
    run.issued_rows += issued.issuedAccesses.size();
    for (const auto &access : scheduled.steps) {
      const auto [it, inserted] = run.accesses.emplace(
          RequestKey{access.launchId, access.mUniqueId}, AccessRecord{access, std::nullopt, std::nullopt, round});
      if (!inserted) run.duplicate_scheduled.insert({access.launchId, access.mUniqueId});
      run.launch_qualified = run.launch_qualified || access.launchId != 0;
      ++run.scheduled_bundle_members[access.mBundleId];
    }
    for (const auto &access : issued.issuedAccesses) {
      auto it = run.accesses.find({access.launchId, access.requestUid});
      if (it == run.accesses.end()) {
        run.issued_without_schedule.insert({access.launchId, access.requestUid});
      } else if (it->second.issued) {
        run.duplicate_issued.insert({access.launchId, access.requestUid});
      } else {
        it->second.issued = access;
        ++run.issued_bundle_members[it->second.scheduled.mBundleId];
      }
    }
    const fs::path final_path = dir / "current_cycle_after_issue.bin";
    if (fs::exists(final_path)) {
      const auto cycle = read_snapshot<CycleSnapshot>(final_path).currentCycleAfterIssue;
      if (cycle < run.final_cycle) ++run.decreasing_cycles;
      run.final_cycle = cycle;
    }

    const auto completions_path = dir / "completed_bundle_ids.bin";
    if (run.launch_qualified && fs::exists(completions_path)) {
      const auto completed = read_snapshot<trafficgen_socket::CompletedBundleIdsSnapshot>(completions_path);
      for (const auto bundle : completed.completedBundleIds) {
        if (run.completed_bundles.count(bundle) ||
            !run.scheduled_bundle_members.count(bundle) ||
            run.issued_bundle_members[bundle] != run.scheduled_bundle_members[bundle]) {
          ++run.invalid_bundle_completions;
        } else {
          run.completed_bundles.insert(bundle);
        }
      }
    }

    const fs::path assignments_path = dir / "lane_assignments.csv";
    if (fs::exists(assignments_path)) {
      ++run.lane_assignment_files;
      std::ifstream assignments(assignments_path);
      if (!assignments) {
        throw std::runtime_error("cannot open lane assignments: " +
                                 assignments_path.string());
      }
      std::string line;
      std::getline(assignments, line);
      const auto columns = split_csv_line(line);
      std::map<std::string, std::size_t> column;
      for (std::size_t i = 0; i < columns.size(); ++i) column.emplace(columns[i], i);
      while (std::getline(assignments, line)) {
        if (line.empty()) continue;
        const auto fields = split_csv_line(line);
        if (fields.size() != columns.size()) {
          throw std::runtime_error("malformed lane assignment row: " + line);
        }
        const auto number = [&](const std::string &name) {
          return std::stoull(fields.at(column.at(name)));
        };
        const RequestKey uid{column.count("launch_id") ? number("launch_id") : 0,
                             number("request_uid")};
        AccessRecord::LaneAssignment assignment{
            static_cast<std::uint32_t>(number("bundle_generation")),
            number("bundle_id"),
            static_cast<std::size_t>(number("lane")),
            static_cast<std::uint16_t>(number("member_count"))};
        ++run.lane_assignment_rows;
        auto access = run.accesses.find(uid);
        if (access == run.accesses.end()) {
          throw std::runtime_error(
              "lane assignment references unknown request UID " +
              std::to_string(uid.first) + ":" + std::to_string(uid.second));
        }
        if (access->second.lane_assignment) {
          run.duplicate_lane_assignment.insert(uid);
        } else {
          access->second.lane_assignment = assignment;
          ++run.lane_access_counts[assignment.lane];
        }
        const auto key = std::make_pair(assignment.generation,
                                        assignment.bundle_id);
        const auto [lane, lane_inserted] =
            run.bundle_lanes.emplace(key, assignment.lane);
        if (!lane_inserted && lane->second != assignment.lane) {
          run.split_bundle_keys.insert(key);
        }
        const auto [count, count_inserted] =
            run.bundle_member_counts.emplace(key, assignment.member_count);
        if (!count_inserted && count->second != assignment.member_count) {
          run.unstable_member_count_keys.insert(key);
        }
      }
    }
  }
  return run;
}

std::int64_t drift(const AccessRecord &record) {
  return static_cast<std::int64_t>(record.issued->cycleIssued) -
         static_cast<std::int64_t>(record.scheduled.mCycleCount);
}

std::vector<std::int64_t> drifts(const RunData &run) {
  std::vector<std::int64_t> values;
  values.reserve(run.accesses.size());
  for (const auto &[uid, access] : run.accesses) {
    (void)uid;
    if (access.issued) values.push_back(drift(access));
  }
  std::sort(values.begin(), values.end());
  return values;
}

double percentile(const std::vector<std::int64_t> &values, double p) {
  if (values.empty()) return 0.0;
  const double index = p * static_cast<double>(values.size() - 1);
  const auto low = static_cast<std::size_t>(std::floor(index));
  const auto high = static_cast<std::size_t>(std::ceil(index));
  const double fraction = index - static_cast<double>(low);
  return static_cast<double>(values[low]) * (1.0 - fraction) +
         static_cast<double>(values[high]) * fraction;
}

std::string json_string(const std::string &value) {
  std::ostringstream out;
  out << '"';
  for (char c : value) {
    if (c == '"' || c == '\\') out << '\\';
    out << c;
  }
  out << '"';
  return out.str();
}

std::size_t missing_issued(const RunData &run) {
  return std::count_if(run.accesses.begin(), run.accesses.end(),
                       [](const auto &entry) { return !entry.second.issued; });
}

std::size_t missing_lane_assignments(const RunData &run) {
  if (run.lane_assignment_files == 0) return 0;
  return std::count_if(run.accesses.begin(), run.accesses.end(),
                       [](const auto &entry) {
                         return !entry.second.lane_assignment;
                       });
}

std::map<std::uint64_t, std::size_t> issue_histogram(const RunData &run) {
  std::map<std::uint64_t, std::size_t> result;
  for (const auto &[uid, access] : run.accesses) {
    (void)uid;
    if (access.issued) ++result[access.issued->cycleIssued];
  }
  return result;
}

void write_run_metrics(std::ostream &out, const RunData &run, unsigned indent) {
  const std::string pad(indent, ' ');
  const auto values = drifts(run);
  const auto histogram = issue_histogram(run);
  const double sum = std::accumulate(values.begin(), values.end(), 0.0);
  const auto count_le = [&values](std::int64_t limit) {
    return static_cast<std::size_t>(std::upper_bound(values.begin(), values.end(), limit) - values.begin());
  };
  const auto percent = [&values](std::size_t count) {
    return values.empty() ? 0.0 : 100.0 * static_cast<double>(count) / values.size();
  };
  std::size_t nonzero = 0;
  for (auto value : values) if (value != 0) ++nonzero;
  const std::uint64_t issue_min = histogram.empty() ? 0 : histogram.begin()->first;
  const std::uint64_t issue_max = histogram.empty() ? 0 : histogram.rbegin()->first;
  const std::uint64_t issue_span = histogram.empty() ? 0 : issue_max - issue_min + 1;
  std::map<std::size_t, std::size_t> lane_bundle_counts;
  for (const auto &[key, lane] : run.bundle_lanes) {
    (void)key;
    ++lane_bundle_counts[lane];
  }
  std::size_t observed_lane_count = 0;
  if (!run.lane_access_counts.empty()) {
    observed_lane_count = run.lane_access_counts.rbegin()->first + 1;
  }
  std::size_t lane_access_min = 0;
  std::size_t lane_access_max = 0;
  std::size_t lane_bundle_min = 0;
  std::size_t lane_bundle_max = 0;
  if (observed_lane_count != 0) {
    lane_access_min = std::numeric_limits<std::size_t>::max();
    lane_bundle_min = std::numeric_limits<std::size_t>::max();
    for (std::size_t lane = 0; lane < observed_lane_count; ++lane) {
      lane_access_min = std::min(lane_access_min, run.lane_access_counts.count(lane)
          ? run.lane_access_counts.at(lane) : 0);
      lane_access_max = std::max(lane_access_max, run.lane_access_counts.count(lane)
          ? run.lane_access_counts.at(lane) : 0);
      lane_bundle_min = std::min(lane_bundle_min, lane_bundle_counts.count(lane)
          ? lane_bundle_counts.at(lane) : 0);
      lane_bundle_max = std::max(lane_bundle_max, lane_bundle_counts.count(lane)
          ? lane_bundle_counts.at(lane) : 0);
    }
  }

  out << pad << "{\n";
  out << pad << "  \"label\": " << json_string(run.label) << ",\n";
  out << pad << "  \"root\": " << json_string(run.root.string()) << ",\n";
  out << pad << "  \"rounds\": " << run.rounds << ",\n";
  out << pad << "  \"scheduled_rows\": " << run.scheduled_rows << ",\n";
  out << pad << "  \"scheduled_unique\": " << run.accesses.size() << ",\n";
  out << pad << "  \"issued_rows\": " << run.issued_rows << ",\n";
  out << pad << "  \"joined_count\": " << values.size() << ",\n";
  out << pad << "  \"missing_issued\": " << missing_issued(run) << ",\n";
  out << pad << "  \"issued_without_schedule\": " << run.issued_without_schedule.size() << ",\n";
  out << pad << "  \"duplicate_scheduled_uids\": " << run.duplicate_scheduled.size() << ",\n";
  out << pad << "  \"duplicate_issued_uids\": " << run.duplicate_issued.size() << ",\n";
  out << pad << "  \"lane_ownership\": {\n";
  out << pad << "    \"assignment_files\": " << run.lane_assignment_files << ",\n";
  out << pad << "    \"assignment_rows\": " << run.lane_assignment_rows << ",\n";
  out << pad << "    \"missing_assignments\": " << missing_lane_assignments(run) << ",\n";
  out << pad << "    \"duplicate_assignment_uids\": " << run.duplicate_lane_assignment.size() << ",\n";
  out << pad << "    \"unique_bundle_keys\": " << run.bundle_lanes.size() << ",\n";
  out << pad << "    \"split_bundle_keys\": " << run.split_bundle_keys.size() << ",\n";
  out << pad << "    \"unstable_member_count_keys\": " << run.unstable_member_count_keys.size() << ",\n";
  out << pad << "    \"observed_lane_count\": " << observed_lane_count << ",\n";
  out << pad << "    \"access_load_min\": " << lane_access_min << ",\n";
  out << pad << "    \"access_load_max\": " << lane_access_max << ",\n";
  out << pad << "    \"access_load_imbalance\": " << lane_access_max - lane_access_min << ",\n";
  out << pad << "    \"bundle_load_min\": " << lane_bundle_min << ",\n";
  out << pad << "    \"bundle_load_max\": " << lane_bundle_max << ",\n";
  out << pad << "    \"bundle_load_imbalance\": " << lane_bundle_max - lane_bundle_min << "\n";
  out << pad << "  },\n";
  out << pad << "  \"decreasing_cycles\": " << run.decreasing_cycles << ",\n";
  out << pad << "  \"completed_bundles\": " << run.completed_bundles.size() << ",\n";
  out << pad << "  \"invalid_bundle_completions\": " << run.invalid_bundle_completions << ",\n";
  out << pad << "  \"unfinished_bundles\": " << (run.launch_qualified ? run.scheduled_bundle_members.size() - run.completed_bundles.size() : 0) << ",\n";
  out << pad << "  \"final_cycle\": " << run.final_cycle << ",\n";
  out << pad << "  \"issue_cycle_min\": " << issue_min << ",\n";
  out << pad << "  \"issue_cycle_max\": " << issue_max << ",\n";
  out << pad << "  \"issue_cycle_span\": " << issue_span << ",\n";
  out << pad << "  \"accesses_per_target_cycle\": "
      << (issue_span ? static_cast<double>(values.size()) / issue_span : 0.0) << ",\n";
  out << pad << "  \"drift\": {\n";
  out << pad << "    \"nonzero_count\": " << nonzero << ",\n";
  out << pad << "    \"min\": " << (values.empty() ? 0 : values.front()) << ",\n";
  out << pad << "    \"mean\": " << (values.empty() ? 0.0 : sum / values.size()) << ",\n";
  out << pad << "    \"p50\": " << percentile(values, 0.50) << ",\n";
  out << pad << "    \"p90\": " << percentile(values, 0.90) << ",\n";
  out << pad << "    \"p95\": " << percentile(values, 0.95) << ",\n";
  out << pad << "    \"p99\": " << percentile(values, 0.99) << ",\n";
  out << pad << "    \"max\": " << (values.empty() ? 0 : values.back()) << ",\n";
  out << pad << "    \"percent_zero\": " << percent(values.size() - nonzero) << ",\n";
  out << pad << "    \"percent_le_1\": " << percent(count_le(1)) << ",\n";
  out << pad << "    \"percent_le_5\": " << percent(count_le(5)) << ",\n";
  out << pad << "    \"percent_le_10\": " << percent(count_le(10)) << "\n";
  out << pad << "  }\n";
  out << pad << "}";
}

struct SetComparison {
  std::size_t only_primary = 0;
  std::size_t only_baseline = 0;
  std::size_t common = 0;
};

SetComparison compare_access_sets(const RunData &primary, const RunData &baseline) {
  using Key = std::tuple<std::uint64_t, std::uint64_t, bool>;
  std::set<Key> left;
  std::set<Key> right;
  for (const auto &[uid, access] : primary.accesses) {
    (void)uid;
    if (access.issued) left.emplace(access.issued->launchId, access.issued->address, access.issued->isWrite);
  }
  for (const auto &[uid, access] : baseline.accesses) {
    (void)uid;
    if (access.issued) right.emplace(access.issued->launchId, access.issued->address, access.issued->isWrite);
  }
  std::vector<Key> only_left;
  std::vector<Key> only_right;
  std::set_difference(left.begin(), left.end(), right.begin(), right.end(),
                      std::back_inserter(only_left));
  std::set_difference(right.begin(), right.end(), left.begin(), left.end(),
                      std::back_inserter(only_right));
  return {only_left.size(), only_right.size(), left.size() - only_left.size()};
}

std::string optional_u64(const std::optional<std::uint64_t> &value) {
  return value ? std::to_string(*value) : "";
}

int main(int argc, char **argv) try {
  if (argc < 4) {
    std::cerr << "usage: " << argv[0]
              << " PRIMARY_LABEL PRIMARY_ROUND_ROOT OUTPUT_DIR [LABEL=ROUND_ROOT ...]\n";
    return 2;
  }
  const fs::path output_dir = argv[3];
  fs::create_directories(output_dir);
  RunData primary = load_run(argv[1], argv[2]);
  std::vector<RunData> baselines;
  for (int i = 4; i < argc; ++i) {
    const std::string arg = argv[i];
    const auto separator = arg.find('=');
    if (separator == std::string::npos) {
      throw std::runtime_error("baseline must be LABEL=ROUND_ROOT: " + arg);
    }
    baselines.push_back(load_run(arg.substr(0, separator), arg.substr(separator + 1)));
  }

  std::ofstream metrics(output_dir / "metrics.json");
  metrics << std::fixed << std::setprecision(6);
  metrics << "{\n  \"primary\": ";
  write_run_metrics(metrics, primary, 2);
  metrics << ",\n  \"baselines\": [\n";
  for (std::size_t i = 0; i < baselines.size(); ++i) {
    metrics << "    ";
    write_run_metrics(metrics, baselines[i], 4);
    metrics << (i + 1 == baselines.size() ? "\n" : ",\n");
  }
  metrics << "  ],\n  \"comparisons\": [\n";
  for (std::size_t i = 0; i < baselines.size(); ++i) {
    const auto sets = compare_access_sets(primary, baselines[i]);
    std::size_t common_uids = 0;
    std::size_t changed_scheduled = 0;
    std::size_t changed_issued = 0;
    std::size_t changed_drift = 0;
    for (const auto &[uid, access] : primary.accesses) {
      const auto it = baselines[i].accesses.find(uid);
      if (it == baselines[i].accesses.end()) continue;
      ++common_uids;
      if (access.scheduled.mCycleCount != it->second.scheduled.mCycleCount) ++changed_scheduled;
      if (access.issued && it->second.issued) {
        if (access.issued->cycleIssued != it->second.issued->cycleIssued) ++changed_issued;
        if (drift(access) != drift(it->second)) ++changed_drift;
      }
    }
    metrics << "    {\"baseline\": " << json_string(baselines[i].label)
            << ", \"common_uids\": " << common_uids
            << ", \"changed_scheduled_cycle\": " << changed_scheduled
            << ", \"changed_issued_cycle\": " << changed_issued
            << ", \"changed_drift\": " << changed_drift
            << ", \"address_rw_only_primary\": " << sets.only_primary
            << ", \"address_rw_only_baseline\": " << sets.only_baseline
            << ", \"address_rw_common\": " << sets.common << "}"
            << (i + 1 == baselines.size() ? "\n" : ",\n");
  }
  metrics << "  ]\n}\n";

  std::ofstream per_uid(output_dir / "per_uid.csv");
  per_uid << "launch_id,request_uid,registry_id,address,is_write,bundle_id,bundle_generation,lane,member_count,"
             "primary_scheduled,primary_issued,primary_drift";
  for (const auto &baseline : baselines) {
    per_uid << ',' << baseline.label << "_scheduled," << baseline.label << "_issued,"
            << baseline.label << "_drift,scheduled_delta_vs_" << baseline.label
            << ",issued_delta_vs_" << baseline.label << ",drift_delta_vs_" << baseline.label;
  }
  per_uid << '\n';
  for (const auto &[uid, access] : primary.accesses) {
    per_uid << uid.first << ',' << uid.second << ',' << access.scheduled.registryId << ',' << access.scheduled.mAddress << ',' << access.scheduled.mIsWrite << ','
            << access.scheduled.mBundleId << ',';
    if (access.lane_assignment) {
      per_uid << access.lane_assignment->generation << ','
              << access.lane_assignment->lane << ','
              << access.lane_assignment->member_count;
    } else {
      per_uid << ",,";
    }
    per_uid << ',' << access.scheduled.mCycleCount << ',';
    if (access.issued) per_uid << access.issued->cycleIssued << ',' << drift(access);
    else per_uid << ',';
    for (const auto &baseline : baselines) {
      const auto it = baseline.accesses.find(uid);
      if (it == baseline.accesses.end()) {
        per_uid << ",,,,,,";
        continue;
      }
      const auto &old = it->second;
      per_uid << ',' << old.scheduled.mCycleCount << ',';
      if (old.issued) per_uid << old.issued->cycleIssued << ',' << drift(old);
      else per_uid << ',';
      per_uid << ',' << (static_cast<std::int64_t>(access.scheduled.mCycleCount) -
                          static_cast<std::int64_t>(old.scheduled.mCycleCount)) << ',';
      if (access.issued && old.issued) {
        per_uid << (static_cast<std::int64_t>(access.issued->cycleIssued) -
                    static_cast<std::int64_t>(old.issued->cycleIssued)) << ','
                << (drift(access) - drift(old));
      } else {
        per_uid << ',';
      }
    }
    per_uid << '\n';
  }

  std::ofstream cycles(output_dir / "issue_cycles.csv");
  cycles << "cycle,access_count\n";
  for (const auto &[cycle, count] : issue_histogram(primary)) cycles << cycle << ',' << count << '\n';

  std::map<std::size_t, std::size_t> lane_bundle_counts;
  for (const auto &[key, lane] : primary.bundle_lanes) {
    (void)key;
    ++lane_bundle_counts[lane];
  }
  std::ofstream lane_load(output_dir / "lane_load.csv");
  lane_load << "lane,access_count,bundle_count\n";
  const std::size_t observed_lane_count = primary.lane_access_counts.empty()
      ? 0 : primary.lane_access_counts.rbegin()->first + 1;
  for (std::size_t lane = 0; lane < observed_lane_count; ++lane) {
    lane_load << lane << ',' << primary.lane_access_counts[lane] << ','
              << lane_bundle_counts[lane] << '\n';
  }

  std::cout << "wrote " << (output_dir / "metrics.json") << ", "
            << (output_dir / "per_uid.csv") << ", and "
            << (output_dir / "issue_cycles.csv") << ", and "
            << (output_dir / "lane_load.csv") << '\n';
  return 0;
} catch (const std::exception &error) {
  std::cerr << "trafficgen-snapshot-analyzer: " << error.what() << '\n';
  return 1;
}
