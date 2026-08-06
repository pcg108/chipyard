#include "bridges/trafficgen_lane_assignment.h"

#include <cassert>
#include <map>
#include <vector>

using namespace trafficgen_lane_assignment;

int main() {
  const bundle_key_t a{1, 10};
  const bundle_key_t b{1, 11};
  const bundle_key_t c{1, 12};
  const bundle_key_t d{2, 1};

  std::map<bundle_key_t, std::size_t> owners;
  const auto first = assign_bundles({{a, 8}, {b, 5}, {c, 5}, {d, 2}}, 3, owners);
  assert(owners.at(a) == 0); // Largest bundle, then lowest lane.
  assert(owners.at(b) == 1); // Equal weights sort by generation and ID.
  assert(owners.at(c) == 2);
  assert(owners.at(d) == 1); // Access tie broken by bundle count, then lane.
  assert((first.access_counts == std::vector<std::size_t>{8, 7, 5}));

  // A refill sees fewer pending members but cannot change an existing owner.
  const auto saved = owners;
  assign_bundles({{a, 2}, {b, 1}, {c, 4}}, 3, owners);
  assert(owners == saved);

  std::map<bundle_key_t, std::uint16_t> member_counts;
  assert(remember_member_count(member_counts, a, 8) == 8);
  assert(remember_member_count(member_counts, a, 2) == 8);

  // Lane 0 is already full. Selection stops immediately instead of skipping
  // to the otherwise empty lane 1, so later same-cycle accesses wait too.
  std::vector<std::size_t> lane_entries{2, 0};
  assert(fitting_lane_prefix({0, 1, 1}, lane_entries, 2) == 0);
  lane_entries = {1, 0};
  assert(fitting_lane_prefix({0, 0, 1}, lane_entries, 2) == 1);
  assert((lane_entries == std::vector<std::size_t>{2, 0}));
  return 0;
}
