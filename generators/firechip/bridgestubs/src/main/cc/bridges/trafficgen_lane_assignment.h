#ifndef __TRAFFICGEN_LANE_ASSIGNMENT_H
#define __TRAFFICGEN_LANE_ASSIGNMENT_H

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <map>
#include <stdexcept>
#include <utility>
#include <vector>

namespace trafficgen_lane_assignment {

using bundle_key_t = std::pair<std::uint32_t, std::uint64_t>;

struct lane_balance_t {
  std::vector<std::size_t> access_counts;
  std::vector<std::size_t> bundle_counts;
};

inline lane_balance_t assign_bundles(
    const std::map<bundle_key_t, std::size_t> &pending_bundle_counts,
    std::size_t lane_count,
    std::map<bundle_key_t, std::size_t> &owners) {
  if (lane_count == 0) {
    throw std::invalid_argument("TrafficGen requires at least one bundle lane");
  }

  lane_balance_t balance{
      std::vector<std::size_t>(lane_count, 0),
      std::vector<std::size_t>(lane_count, 0)};
  std::vector<std::pair<bundle_key_t, std::size_t>> unowned;
  for (const auto &[key, count] : pending_bundle_counts) {
    const auto owner = owners.find(key);
    if (owner == owners.end()) {
      unowned.emplace_back(key, count);
      continue;
    }
    if (owner->second >= lane_count) {
      throw std::runtime_error("TrafficGen bundle has invalid persistent lane owner");
    }
    balance.access_counts[owner->second] += count;
    ++balance.bundle_counts[owner->second];
  }

  std::sort(unowned.begin(), unowned.end(), [](const auto &lhs, const auto &rhs) {
    if (lhs.second != rhs.second) return lhs.second > rhs.second;
    return lhs.first < rhs.first;
  });
  for (const auto &[key, count] : unowned) {
    std::size_t selected = 0;
    for (std::size_t lane = 1; lane < lane_count; ++lane) {
      if (balance.access_counts[lane] < balance.access_counts[selected] ||
          (balance.access_counts[lane] == balance.access_counts[selected] &&
           balance.bundle_counts[lane] < balance.bundle_counts[selected])) {
        selected = lane;
      }
    }
    owners.emplace(key, selected);
    balance.access_counts[selected] += count;
    ++balance.bundle_counts[selected];
  }
  return balance;
}

inline std::uint16_t remember_member_count(
    std::map<bundle_key_t, std::uint16_t> &member_counts,
    const bundle_key_t &key,
    std::size_t pending_count) {
  if (pending_count == 0 ||
      pending_count > std::numeric_limits<std::uint16_t>::max()) {
    throw std::runtime_error("TrafficGen bundle member count exceeds packed width");
  }
  const auto [it, inserted] = member_counts.emplace(
      key, static_cast<std::uint16_t>(pending_count));
  (void)inserted;
  return it->second;
}

// Appendable entries from an overflowing cycle must be a literal prefix. The
// caller supplies the bank occupancy accumulated by earlier complete cycles.
inline std::size_t fitting_lane_prefix(
    const std::vector<std::size_t> &lanes,
    std::vector<std::size_t> &lane_entries,
    std::size_t lane_capacity) {
  std::size_t prefix = 0;
  for (const auto lane : lanes) {
    if (lane >= lane_entries.size()) {
      throw std::runtime_error("TrafficGen pending access has invalid replay lane");
    }
    if (lane_entries[lane] == lane_capacity) break;
    ++lane_entries[lane];
    ++prefix;
  }
  return prefix;
}

} // namespace trafficgen_lane_assignment

#endif // __TRAFFICGEN_LANE_ASSIGNMENT_H
