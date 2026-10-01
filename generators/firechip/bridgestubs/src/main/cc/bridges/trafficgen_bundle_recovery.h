// Host-side policy for the RTL engine's fully drained bundle-table boundary.
#ifndef __TRAFFICGEN_BUNDLE_RECOVERY_H
#define __TRAFFICGEN_BUNDLE_RECOVERY_H

#include "trafficgen_request_key.h"
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace trafficgen_bundle_recovery {

using bundle_key_t = std::pair<std::uint32_t, std::uint64_t>;
using promotions_t = std::vector<std::optional<bundle_key_t>>;

template <class Access> bundle_key_t key_of(const Access &access) {
  return {access.bundle_generation, access.m_bundle_id};
}

struct pending_bundle_t {
  std::size_t lane = 0;
  std::size_t remaining = 0;
  std::uint64_t latest_cycle = 0;
  std::uint16_t original_count = 0;
};
using pending_bundles_t = std::map<bundle_key_t, pending_bundle_t>;

template <class Pending>
pending_bundles_t summarize_pending(
    const Pending &pending,
    const std::map<bundle_key_t, std::size_t> &owners,
    const std::map<bundle_key_t, std::uint16_t> &member_counts,
    std::size_t lane_count) {
  pending_bundles_t bundles;
  for (const auto &[cycle, accesses] : pending) {
    for (const auto &access : accesses) {
      const auto key = key_of(access);
      const auto owner = owners.find(key);
      const auto count = member_counts.find(key);
      if (owner == owners.end() || count == member_counts.end() ||
          owner->second >= lane_count || owner->second != access.assigned_lane ||
          count->second == 0 || count->second != access.bundle_issue_count ||
          cycle != access.cycle_count) {
        throw std::runtime_error("TrafficGen recovery: inconsistent pending bundle metadata");
      }
      auto &bundle = bundles[key];
      bundle.lane = owner->second;
      bundle.original_count = count->second;
      ++bundle.remaining;
      bundle.latest_cycle = std::max(bundle.latest_cycle, access.cycle_count);
      if (bundle.remaining > bundle.original_count) {
        throw std::runtime_error("TrafficGen recovery: pending bundle exceeds original member count");
      }
    }
  }
  return bundles;
}

// Valid only when all issued requests have completed: a partial bundle is then
// exactly a resident entry with members still to issue. Do not infer residency
// at an ordinary scheduling boundary, where fully issued entries can remain live.
inline void select_promotions(const pending_bundles_t &bundles,
                              std::uint32_t lane_mask,
                              std::uint64_t current_cycle,
                              promotions_t &promotions) {
  if (promotions.empty() || promotions.size() > 32 || lane_mask == 0 ||
      (promotions.size() < 32 && (lane_mask >> promotions.size()) != 0)) {
    throw std::runtime_error("TrafficGen recovery: invalid full-bundle-table lane mask");
  }
  auto selected = promotions;
  for (std::size_t lane = 0; lane < promotions.size(); ++lane) {
    if ((lane_mask & (std::uint32_t{1} << lane)) == 0) continue;
    using priority_t = std::tuple<std::uint64_t, std::size_t,
                                  std::uint32_t, std::uint64_t>;
    std::optional<priority_t> best;
    for (const auto &[key, bundle] : bundles) {
      if (bundle.lane != lane || bundle.remaining == 0 ||
          bundle.remaining >= bundle.original_count) continue;
      const priority_t priority{std::max(current_cycle, bundle.latest_cycle),
                                bundle.remaining, key.first, key.second};
      if (!best || priority < *best) {
        best = priority;
        selected[lane] = key;
      }
    }
    if (!best) {
      throw std::runtime_error("TrafficGen recovery: full lane " +
                               std::to_string(lane) + " has no resident bundle");
    }
  }
  promotions = std::move(selected);
}

inline bool has_promotions(const promotions_t &promotions) {
  return std::any_of(promotions.begin(), promotions.end(),
                     [](const auto &key) { return key.has_value(); });
}

template <class Pending>
void retire_issued_promotions(const Pending &pending, promotions_t &promotions) {
  std::vector<bool> present(promotions.size(), false);
  for (const auto &[cycle, accesses] : pending) {
    (void)cycle;
    for (const auto &access : accesses) {
      if (access.assigned_lane >= promotions.size()) {
        throw std::runtime_error("TrafficGen recovery: pending access has invalid lane");
      }
      const auto lane = access.assigned_lane;
      present[lane] = present[lane] ||
          (promotions[lane] && *promotions[lane] == key_of(access));
    }
  }
  for (std::size_t lane = 0; lane < promotions.size(); ++lane) {
    if (!present[lane]) promotions[lane].reset();
  }
}

// Stable per-lane grouping precedes clipping. In particular, the promoted
// members can come from beyond the previous BRAM upload. No timestamps or
// identities are changed, and conflicting accesses may intentionally reorder.
template <class Access>
std::vector<Access> build_promoted_upload(
    const std::map<std::uint64_t, std::vector<Access>> &pending,
    const promotions_t &promotions, std::size_t lane_capacity) {
  if (promotions.empty() || lane_capacity == 0) {
    throw std::runtime_error("TrafficGen recovery: invalid upload capacity");
  }
  std::vector<std::vector<const Access *>> lanes(promotions.size());
  for (const auto &[cycle, accesses] : pending) {
    (void)cycle;
    for (const auto &access : accesses) {
      if (access.assigned_lane >= lanes.size()) {
        throw std::runtime_error("TrafficGen recovery: pending access has invalid lane");
      }
      lanes[access.assigned_lane].push_back(&access);
    }
  }
  std::vector<Access> upload;
  for (std::size_t lane = 0; lane < lanes.size(); ++lane) {
    if (promotions[lane]) {
      const auto split = std::stable_partition(
          lanes[lane].begin(), lanes[lane].end(),
          [&](const Access *access) { return key_of(*access) == *promotions[lane]; });
      if (split == lanes[lane].begin()) {
        throw std::runtime_error("TrafficGen recovery: promoted bundle has no pending members");
      }
    }
    const auto count = std::min(lane_capacity, lanes[lane].size());
    for (std::size_t index = 0; index < count; ++index) {
      upload.push_back(*lanes[lane][index]);
    }
  }
  return upload;
}

template <class Access>
std::uint64_t maximum_scheduled_cycle(const std::vector<Access> &upload) {
  std::uint64_t maximum = 0;
  for (const auto &access : upload) maximum = std::max(maximum, access.cycle_count);
  return maximum;
}

// Validate the complete fresh writeback before mutating either pending index.
// Accumulated writeback records are feedback history, never input to this path.
template <class Access, class Issued>
void remove_fresh_issued(
    const std::vector<Access> &upload, const std::vector<Issued> &issued,
    std::map<std::uint64_t, std::vector<Access>> &pending,
    std::unordered_map<trafficgen_request_key_t, std::uint64_t, trafficgen_request_key_hash> &pending_cycles,
    bool require_complete_upload) {
  std::unordered_set<trafficgen_request_key_t, trafficgen_request_key_hash> uploaded;
  for (const auto &access : upload) {
    if (!uploaded.insert({access.launch_id, access.id}).second) {
      throw std::runtime_error("TrafficGen duplicate upload request_uid=" + trafficgen_request_description({access.launch_id, access.id}));
    }
  }
  std::unordered_set<trafficgen_request_key_t, trafficgen_request_key_hash> fresh;
  for (const auto &access : issued) {
    const trafficgen_request_key_t uid{access.launch_id, access.request_uid};
    const auto pending_cycle = pending_cycles.find(uid);
    if (!fresh.insert(uid).second || uploaded.count(uid) == 0 ||
        pending_cycle == pending_cycles.end()) {
      throw std::runtime_error("TrafficGen duplicate, unknown, or nonpending issued request_uid=" +
                               trafficgen_request_description(uid));
    }
    const auto bucket = pending.find(pending_cycle->second);
    if (bucket == pending.end() ||
        std::count_if(bucket->second.begin(), bucket->second.end(),
                      [uid](const auto &a) { return trafficgen_request_key_t{a.launch_id, a.id} == uid; }) != 1) {
      throw std::runtime_error("TrafficGen inconsistent pending index for request_uid=" +
                               trafficgen_request_description(uid));
    }
    if (access.cycle_issued < pending_cycle->second) {
      throw std::runtime_error("TrafficGen issued before scheduled cycle for request_uid=" +
                               trafficgen_request_description(uid));
    }
  }
  if (require_complete_upload && fresh.size() != uploaded.size()) {
    throw std::runtime_error("TrafficGen capacity exit did not consume the complete upload");
  }
  for (auto bucket = pending.begin(); bucket != pending.end();) {
    auto &accesses = bucket->second;
    accesses.erase(std::remove_if(accesses.begin(), accesses.end(),
                                  [&](const Access &access) {
                                    if (fresh.count({access.launch_id, access.id}) == 0) return false;
                                    pending_cycles.erase({access.launch_id, access.id});
                                    return true;
                                  }), accesses.end());
    if (accesses.empty()) bucket = pending.erase(bucket);
    else ++bucket;
  }
}

} // namespace trafficgen_bundle_recovery
#endif // __TRAFFICGEN_BUNDLE_RECOVERY_H
