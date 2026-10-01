#include "bridges/trafficgen_bundle_recovery.h"
#include "bridges/trafficgen_bundle_tracking.h"

#include <cassert>
#include <fstream>
#include <iostream>
#include <limits>
#include <set>
#include <string>

namespace recovery = trafficgen_bundle_recovery;
using recovery::bundle_key_t;

// Match the driver's field names so these tests exercise its exact policy and
// accounting templates without requiring a simulator or XDMA implementation.
struct Access {
  std::uint64_t id;
  std::uint64_t cycle_count;
  std::uint32_t bundle_generation;
  std::uint64_t m_bundle_id;
  std::size_t assigned_lane;
  std::uint16_t bundle_issue_count;
  std::uint64_t address = 0;
  bool m_is_write = false;
  std::uint64_t launch_id = 0;
  bool operator==(const Access &) const = default;
};
struct Issued { std::uint64_t request_uid; std::uint64_t cycle_issued; std::uint64_t launch_id = 0; };
using Pending = std::map<std::uint64_t, std::vector<Access>>;

template <class F> void must_fail(F function) {
  bool failed = false;
  try { function(); } catch (const std::runtime_error &) { failed = true; }
  assert(failed);
}

std::unordered_map<trafficgen_request_key_t, std::uint64_t, trafficgen_request_key_hash> index_of(const Pending &pending) {
  std::unordered_map<trafficgen_request_key_t, std::uint64_t, trafficgen_request_key_hash> index;
  for (const auto &[cycle, accesses] : pending)
    for (const auto &access : accesses) assert(index.emplace(trafficgen_request_key_t{access.launch_id, access.id}, cycle).second);
  return index;
}

void selection_and_upload_tests() {
  const bundle_key_t a{1, 10}, b{1, 11}, c{2, 10}, d{1, 12};
  recovery::pending_bundles_t bundles{
      {a, {0, 3, 80, 4}}, {b, {0, 2, 90, 4}},
      {c, {1, 1, 120, 3}}, {d, {0, 2, 90, 4}}};
  recovery::promotions_t promoted(2);
  recovery::select_promotions(bundles, 3, 70, promoted);
  assert(promoted[0] == a && promoted[1] == c); // Earliest remaining finish.
  recovery::select_promotions(bundles, 1, 100, promoted);
  assert(promoted[0] == b && promoted[1] == c); // Fewest, then generation/ID.
  bundles.emplace(bundle_key_t{0, 99}, recovery::pending_bundle_t{0, 2, 90, 4});
  recovery::select_promotions(bundles, 1, 100, promoted);
  assert((promoted[0] == bundle_key_t{0, 99}));
  must_fail([&] { recovery::select_promotions(bundles, 0, 0, promoted); });
  must_fail([&] { recovery::select_promotions(bundles, 4, 0, promoted); });
  const auto saved = promoted;
  must_fail([&] { recovery::select_promotions({}, 1, 0, promoted); });
  assert(promoted == saved);

  // Write B at S=10 is intentionally passed by read A at S=30 on the same
  // address. The full original access is copied, including future timestamps.
  Access write{1, 10, 1, 11, 0, 1, 0x1000, true};
  Access read{2, 30, 1, 10, 0, 3, 0x1000, false};
  Access read2{3, 40, 1, 10, 0, 3, 0x1020, false};
  Access independent{4, 15, 2, 10, 1, 2, 0x2000, false};
  Pending pending{{10, {write}}, {15, {independent}}, {30, {read}}, {40, {read2}}};
  promoted = {a, std::nullopt};
  auto upload = recovery::build_promoted_upload(pending, promoted, 1);
  assert((upload == std::vector<Access>{read, independent}));
  assert(recovery::maximum_scheduled_cycle(upload) == 30); // Last is S=15.
  auto index = index_of(pending);
  recovery::remove_fresh_issued(upload, std::vector<Issued>{{2, 35}}, pending, index, false);
  recovery::retire_issued_promotions(pending, promoted);
  assert(promoted[0] == a); // Survives a horizon/wake boundary and partial chunk.
  pending[5].push_back({5, 5, 3, 10, 0, 1}); // A new scheduler generation.
  index.emplace(trafficgen_request_key_t{0, 5}, 5);
  upload = recovery::build_promoted_upload(pending, promoted, 3);
  assert((upload == std::vector<Access>{read2, pending.at(5)[0], write, independent}));
  recovery::remove_fresh_issued(upload, std::vector<Issued>{{3, 40}}, pending, index, false);
  recovery::retire_issued_promotions(pending, promoted);
  assert(!recovery::has_promotions(promoted));
  assert(index.size() == 3); // No wholesale deletion of partially consumed upload.

  // Outstanding completion must not clear promotion: only its last pending UID
  // does. A second lane can acquire its own promotion while the first persists.
  promoted = {a, std::nullopt};
  recovery::select_promotions(bundles, 2, 100, promoted);
  assert(promoted[0] == a && promoted[1] == c);
  promoted[0] = bundle_key_t{999, 999};
  must_fail([&] { recovery::build_promoted_upload(pending, promoted, 1); });
}

void accounting_tests() {
  Access a{1, 10, 1, 1, 0, 2}, b{2, 11, 1, 1, 0, 2};
  Pending pending{{10, {a}}, {11, {b}}};
  auto index = index_of(pending);
  const auto original = pending;
  const std::vector<Access> upload{a, b};
  must_fail([&] { recovery::remove_fresh_issued(upload,
      std::vector<Issued>{{1, 10}, {1, 10}}, pending, index, false); });
  must_fail([&] { recovery::remove_fresh_issued(upload,
      std::vector<Issued>{{99, 10}}, pending, index, false); });
  must_fail([&] { recovery::remove_fresh_issued(std::vector<Access>{b},
      std::vector<Issued>{{1, 10}}, pending, index, false); });
  must_fail([&] { recovery::remove_fresh_issued(upload,
      std::vector<Issued>{{1, 9}}, pending, index, false); });
  must_fail([&] { recovery::remove_fresh_issued(upload,
      std::vector<Issued>{{1, 10}}, pending, index, true); });
  assert(pending == original && index.size() == 2); // Validate before mutation.
  recovery::remove_fresh_issued(upload, std::vector<Issued>{{1, 10}}, pending, index, false);
  must_fail([&] { recovery::remove_fresh_issued(upload,
      std::vector<Issued>{{1, 10}}, pending, index, false); });
  recovery::remove_fresh_issued(std::vector<Access>{b},
      std::vector<Issued>{{2, 11}}, pending, index, true);
  assert(pending.empty() && index.empty());

  std::map<bundle_key_t, std::size_t> owners{{{1, 1}, 0}};
  std::map<bundle_key_t, std::uint16_t> counts{{{1, 1}, 2}};
  pending = original;
  assert(recovery::summarize_pending(pending, owners, counts, 1).at({1, 1}).remaining == 2);
  owners.at({1, 1}) = 1;
  must_fail([&] { recovery::summarize_pending(pending, owners, counts, 1); });
  owners.at({1, 1}) = 0;
  counts.at({1, 1}) = 1;
  must_fail([&] { recovery::summarize_pending(pending, owners, counts, 1); });
}

void frozen_round911_test(const char *fixture_path) {
  std::ifstream input(fixture_path);
  if (!input) throw std::runtime_error(std::string("missing round-911 fixture: ") + fixture_path);
  std::string line;
  std::getline(input, line); // Header.
  Pending pending;
  std::map<bundle_key_t, std::size_t> owners;
  std::map<bundle_key_t, std::uint16_t> counts;
  std::unordered_map<std::uint64_t, Access> original;
  while (std::getline(input, line)) {
    std::vector<std::uint64_t> fields;
    std::istringstream row(line);
    std::string field;
    while (std::getline(row, field, ',')) fields.push_back(std::stoull(field));
    if (fields.at(2) == 826226) continue; // Sole issue in the captured round-911 prefix.
    Access access{fields.at(2), fields.at(3), static_cast<std::uint32_t>(fields.at(4)),
                  fields.at(5), static_cast<std::size_t>(fields.at(0)),
                  static_cast<std::uint16_t>(fields.at(6)), 0, fields.at(7) != 0};
    pending[access.cycle_count].push_back(access);
    owners.emplace(recovery::key_of(access), access.assigned_lane);
    counts.emplace(recovery::key_of(access), access.bundle_issue_count);
    assert(original.emplace(access.id, access).second);
  }
  auto index = index_of(pending);
  assert(index.size() == 7355);
  auto bundles = recovery::summarize_pending(pending, owners, counts, 16);
  // Model the finite live bundle tables and accepted final completions. The
  // policy itself is production code; this fixture supplies the frozen RTL
  // state and executes legal FIFO issues with immediate memory completions.
  std::vector<std::map<bundle_key_t, std::size_t>> resident(16);
  std::size_t resident_count = 0;
  for (const auto &[key, bundle] : bundles) {
    if (bundle.remaining < bundle.original_count) {
      resident[bundle.lane].emplace(key, bundle.remaining);
      ++resident_count;
    }
  }
  assert(resident_count == 272);
  for (const auto &lane : resident) assert(lane.size() == 17);
  recovery::promotions_t promoted(16);
  recovery::select_promotions(bundles, 0xffff, 36822, promoted);
  std::set<std::uint64_t> issued_once;
  std::size_t boundaries = 0, recoveries = 1, capacity_refills = 0;
  std::uint64_t current_cycle = 36822;
  while (!pending.empty()) {
    assert(++boundaries < 10000);
    // Deliberately tiny banks force repeated uploads of the selected bundle.
    const auto upload = recovery::build_promoted_upload(pending, promoted, 11);
    std::vector<Issued> issued;
    std::vector<std::vector<const Access *>> lanes(16);
    std::vector<std::size_t> positions(16, 0);
    for (const auto &access : upload) {
      assert(access == original.at(access.id));
      lanes[access.assigned_lane].push_back(&access);
    }
    std::uint32_t mask = 0;
    while (true) {
      bool any_head = false, any_due = false;
      auto next_cycle = std::numeric_limits<std::uint64_t>::max();
      for (std::size_t lane = 0; lane < 16; ++lane) {
        if (positions[lane] == lanes[lane].size()) continue;
        const auto &head = *lanes[lane][positions[lane]];
        any_head = true;
        any_due |= head.cycle_count <= current_cycle;
        next_cycle = std::min(next_cycle, head.cycle_count);
      }
      if (!any_head) break;
      if (!any_due) current_cycle = next_cycle;
      bool due_blocked = false;
      mask = 0;
      // Match the RTL global combinational issue gate: discover every blocked
      // head before allowing any lane to issue on this target cycle. The
      // reported mask includes future-dated blocked heads too.
      for (std::size_t lane = 0; lane < 16; ++lane) {
        if (positions[lane] == lanes[lane].size()) continue;
        const auto &head = *lanes[lane][positions[lane]];
        const auto &table = resident[lane];
        if (table.count(recovery::key_of(head)) == 0 && table.size() == 17) {
          mask |= std::uint32_t{1} << lane;
          due_blocked |= head.cycle_count <= current_cycle;
        }
      }
      if (due_blocked) break;
      mask = 0;
      for (std::size_t lane = 0; lane < 16; ++lane) {
        if (positions[lane] == lanes[lane].size()) continue;
        const auto &access = *lanes[lane][positions[lane]];
        if (access.cycle_count > current_cycle) continue;
        auto &table = resident[lane];
        const auto key = recovery::key_of(access);
        auto slot = table.find(key);
        if (slot == table.end()) {
          assert(table.size() < 17);
          slot = table.emplace(key, access.bundle_issue_count).first;
        }
        issued.push_back({access.id, current_cycle});
        assert(issued_once.insert(access.id).second);
        ++positions[lane];
        assert(slot->second > 0);
        if (--slot->second == 0) table.erase(slot);
      }
      ++current_cycle;
    }
    assert(!issued.empty() || mask != 0);
    recovery::remove_fresh_issued(upload, issued, pending, index, mask == 0);
    recovery::retire_issued_promotions(pending, promoted);
    if (mask != 0) {
      bundles = recovery::summarize_pending(pending, owners, counts, 16);
      recovery::select_promotions(bundles, mask, current_cycle, promoted);
      ++recoveries;
    } else if (!pending.empty()) {
      ++capacity_refills;
    }
  }
  assert(index.empty() && issued_once.size() == 7355 && capacity_refills > 0);
  for (const auto &lane : resident) assert(lane.empty());
  std::cout << "round-911 fixture: 7,355 unique requests issued; 272 initial residents; "
            << recoveries << " recoveries, " << capacity_refills << " capacity refills, "
            << boundaries << " boundaries\n";
}

void multi_launch_accounting_test() {
  Access a{7, 10, 1, 80, 0, 2}, a2{8, 30, 1, 80, 0, 2};
  Access b{7, 11, 2, 81, 0, 1};
  a.launch_id = a2.launch_id = (1ULL << 50) + 1;
  b.launch_id = (1ULL << 50) + 2;
  Pending pending{{10, {a}}, {11, {b}}, {30, {a2}}};
  auto index = index_of(pending);
  trafficgen_bundle_tracker tracker;
  tracker.add_schedule(std::vector<Access>{a, a2});
  tracker.add_schedule(std::vector<Access>{b});
  must_fail([&] { tracker.add_schedule(std::vector<Access>{a}); });
  std::vector<Issued> first{{7, 10, a.launch_id}, {7, 11, b.launch_id}};
  recovery::remove_fresh_issued(std::vector<Access>{a, b}, first, pending, index, true);
  tracker.record_issued(first);
  assert(index.size() == 1 && index.count({a.launch_id, 8}) == 1);
  must_fail([&] { tracker.record_issued(first); });
  // The earlier request completed while another bundle member is unissued.
  must_fail([&] { tracker.complete({80}); });
  tracker.complete({81}); // A store/non-waking bundle is still reported.
  must_fail([&] { tracker.complete({81}); });
  assert(tracker.has_launch(a.launch_id) && !tracker.has_launch(b.launch_id));
  std::vector<Issued> last{{8, 30, a.launch_id}};
  recovery::remove_fresh_issued(std::vector<Access>{a2}, last, pending, index, true);
  tracker.record_issued(last);
  must_fail([&] { tracker.complete({80, 80}); });
  assert(tracker.has_pending()); // Failed batch does not partially retire.
  tracker.complete({80});
  assert(!tracker.has_pending() && index.empty() && pending.empty());
}

int main(int argc, char **argv) {
  assert(argc == 2);
  selection_and_upload_tests();
  accounting_tests();
  multi_launch_accounting_test();
  frozen_round911_test(argv[1]);
  std::cout << "TrafficGen bundle recovery tests passed\n";
}
