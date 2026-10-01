// Produce independent v0 archives and current wrapper archives for reader tests.
#include "bridges/trafficgen_socket_protocol.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;
namespace wire = trafficgen_socket;

// These intentionally remain independent of the v2 types and carry implicit
// Boost class version 0. Deriving them from v2 would not test old archives.
struct LegacyAccess {
  std::uint64_t uid = 0, address = 0, cycle = 0;
  unsigned subpartition = 0, set = 0;
  std::uint64_t tag = 0;
  unsigned mask = 1, sm = 0, scheduler = 0, warp = 0;
  bool write = false;
  std::uint64_t bundle = 0;
  bool wake = true;
  std::string folder = "kernel_1_test";
  template <class Archive> void serialize(Archive &ar, unsigned) {
    ar & uid; ar & address; ar & cycle; ar & subpartition; ar & set;
    ar & tag; ar & mask; ar & sm; ar & scheduler; ar & warp; ar & write;
    ar & bundle; ar & wake; ar & folder;
  }
};
struct LegacyIssued {
  std::uint64_t uid = 0, cycle = 0, address = 0;
  bool write = false;
  template <class Archive> void serialize(Archive &ar, unsigned) {
    ar & uid; ar & cycle; ar & address; ar & write;
  }
};
struct LegacyScheduledSnapshot {
  std::vector<LegacyAccess> accesses;
  template <class Archive> void serialize(Archive &ar, unsigned) { ar & accesses; }
};
struct LegacyIssuedSnapshot {
  std::vector<LegacyIssued> issued;
  template <class Archive> void serialize(Archive &ar, unsigned) { ar & issued; }
};
struct LegacyCycleSnapshot {
  std::uint64_t cycle = 0;
  template <class Archive> void serialize(Archive &ar, unsigned) { ar & cycle; }
};

template <typename T> void snapshot(const fs::path &path, const T &value) {
  std::ofstream output(path, std::ios::binary);
  if (!output) throw std::runtime_error("cannot write fixture " + path.string());
  boost::archive::binary_oarchive archive(output);
  archive << value;
}

wire::socket_l2_access_t access(std::uint64_t launch, std::uint64_t registry,
                              std::uint64_t uid, std::uint64_t bundle,
                              std::uint64_t address, std::uint64_t cycle, bool write) {
  wire::socket_l2_access_t result;
  result.launchId = launch;
  result.registryId = registry;
  result.mUniqueId = uid;
  result.mBundleId = bundle;
  result.mAddress = address;
  result.mCycleCount = cycle;
  result.mMask = 1;
  result.mIsWrite = write;
  result.mWakeRelevantBundle = !write;
  result.mKernelFolder = "kernel_1_test";
  return result;
}

wire::IssuedAccessPoint issue(const wire::socket_l2_access_t &scheduled, std::uint64_t cycle) {
  wire::IssuedAccessPoint result;
  result.launchId = scheduled.launchId;
  result.requestUid = scheduled.mUniqueId;
  result.cycleIssued = cycle;
  result.address = scheduled.mAddress;
  result.isWrite = scheduled.mIsWrite;
  return result;
}

void round(const fs::path &dir, const std::vector<wire::socket_l2_access_t> &scheduled,
           const std::vector<wire::IssuedAccessPoint> &issued,
           const std::vector<std::uint64_t> &completed, std::uint64_t cycle) {
  fs::create_directories(dir);
  snapshot(dir / "all_l2_trace_steps.bin", wire::SocketAllL2TraceStepsSnapshot{scheduled});
  snapshot(dir / "issued_accesses.bin", wire::IssuedAccessesSnapshot{issued});
  snapshot(dir / "completed_bundle_ids.bin", wire::CompletedBundleIdsSnapshot{completed});
  snapshot(dir / "current_cycle_after_issue.bin", wire::CurrentCycleAfterIssueSnapshot{cycle});
  // Intentionally reorder all seven columns: the reader must use the header.
  std::ofstream lanes(dir / "lane_assignments.csv");
  lanes << "lane,request_uid,launch_id,registry_id,bundle_id,member_count,bundle_generation\n";
  for (const auto &entry : scheduled)
    lanes << (entry.launchId == 2 ? 0 : 1) << ',' << entry.mUniqueId << ',' << entry.launchId
          << ',' << entry.registryId << ',' << entry.mBundleId << ",2,1\n";
}

void modern(const fs::path &root, const std::string &kind) {
  constexpr auto highLaunch = UINT64_C(0x8000000000000042);
  const auto a = access(2, 1106, 77, 501, 0x1000, 10, false);
  const auto b = access(2, 1106, 78, 501, 0x1080, 11, false);
  const auto c = access(highLaunch, 1044, 77, 502, 0x2000, 20, true);
  const auto d = access(highLaunch, 1044, 78, 502, 0x2080, 21, true);
  const bool early = kind == "early" || kind == "early_then_valid";
  round(root / "round_000001", {a, b}, {issue(a, 12)}, early ?
      std::vector<std::uint64_t>{501} : std::vector<std::uint64_t>{}, 14);
  std::vector<std::uint64_t> completions = kind == "early" ?
      std::vector<std::uint64_t>{502} : std::vector<std::uint64_t>{501, 502};
  if (kind == "unknown") completions.push_back(999);
  if (kind == "duplicate") completions.push_back(501);
  round(root / "round_000002", {c, d}, {issue(b, 22), issue(c, 23), issue(d, 24)}, completions, 42);
}

void legacy(const fs::path &root) {
  const auto dir = root / "round_000001";
  fs::create_directories(dir);
  LegacyAccess a, b;
  a.uid = 77; a.address = 0x3000; a.cycle = 30; a.bundle = 601;
  b.uid = 78; b.address = 0x3080; b.cycle = 31; b.bundle = 601;
  snapshot(dir / "all_l2_trace_steps.bin", LegacyScheduledSnapshot{{a, b}});
  snapshot(dir / "issued_accesses.bin", LegacyIssuedSnapshot{{
      {77, 32, 0x3000, false}, {78, 33, 0x3080, false}}});
  snapshot(dir / "current_cycle_after_issue.bin", LegacyCycleSnapshot{40});
  std::ofstream lanes(dir / "lane_assignments.csv");
  lanes << "request_uid,bundle_generation,bundle_id,lane,member_count\n"
        << "77,1,601,0,2\n78,1,601,0,2\n";
}

int main(int argc, char **argv) try {
  if (argc != 2) throw std::runtime_error("usage: test-snapshots OUTPUT_ROOT");
  const fs::path root(argv[1]);
  for (const auto &kind : {"valid", "unknown", "duplicate", "early", "early_then_valid"})
    modern(root / kind, kind);
  legacy(root / "legacy");
  return 0;
} catch (const std::exception &error) {
  std::cerr << "test-snapshots: " << error.what() << '\n';
  return 1;
}
