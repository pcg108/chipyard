// Protocol-v7 original-request accounting. Memory timing and wake decisions stay
// in the target/bridge; this ledger translates their physical events for GPU.
#ifndef TRAFFICGEN_LRC_LEDGER_H
#define TRAFFICGEN_LRC_LEDGER_H
#include "trafficgen_socket_protocol.h"
#include "trafficgen_request_key.h"
#include <algorithm>
#include <array>
#include <limits>
#include <map>
#include <set>
#include <stdexcept>
#include <vector>

class trafficgen_lrc_ledger {
public:
  using access_t = trafficgen_socket::socket_l2_access_t;
  using action_t = trafficgen_socket::LrcBatchAction;
  using key_t = trafficgen_request_key_t;
  using result_t = trafficgen_socket::IssueScheduleResult;
  using descriptor_t = std::array<std::uint64_t, 8>;
  struct request_t {
    access_t access;
    std::uint64_t physical = 0;
    bool issued = false, completed = false;
  };
  struct service_t {
    access_t first;
    std::uint64_t physical = 0, token = 0, issue_cycle = 0, return_cycle = 0;
    bool issued = false, returned = false, reported_physical_issue = false;
    std::vector<key_t> members;
  };
  struct bundle_t {
    std::uint16_t slot = 0;
    std::uint64_t handle = 0;
    std::uint64_t floor = 0;
    bool wake = false;
    std::set<std::uint64_t> services;
    std::vector<key_t> members;
  };
  struct event_t {
    std::string event;
    std::uint64_t cycle = 0, launch = 0, uid = 0, physical = 0, recorded = 0, bundle = 0, address = 0;
    bool write = false;
    action_t action = action_t::None;
    std::uint64_t source_ready_cycle = 0;
  };

  explicit trafficgen_lrc_ledger(std::size_t capacity = 65536) : capacity_(capacity) {
    if (!capacity || capacity > 65536) throw std::invalid_argument("LRC handle capacity must be 1..65536");
    for (std::size_t n = capacity; n; --n) { free_services_.push_back(n-1); free_bundles_.push_back(n-1); }
  }
  bool batch_active() const { return batch_; }
  bool lrc_commands_active() const { return lrc_wire_; }
  const std::map<std::uint64_t, bundle_t> &bundles() const { return bundles_; }
  const std::map<std::uint64_t, service_t> &services() const { return services_; }
  const std::map<key_t, request_t> &requests() const { return requests_; }
  std::size_t active_service_slots() const { return capacity_ - free_services_.size(); }
  std::size_t active_bundle_slots() const { return capacity_ - free_bundles_.size(); }
  std::size_t service_high_water() const { return service_high_water_; }
  std::size_t bundle_high_water() const { return bundle_high_water_; }
  std::size_t pending_request_count() const { return active_requests_.size(); }
  std::size_t pending_service_count() const { return unreturned_count_; }
  bool has_pending() const {
    return !bundles_.empty() || !active_requests_.empty();
  }
  bool has_launch(std::uint64_t launch) const {
    const auto it = active_requests_.lower_bound({launch,0});
    if (it != active_requests_.end() && it->launch_id == launch) return true;
    for (const auto &[id,b] : bundles_) {
      (void)id;
      if (!b.members.empty() && b.members.front().launch_id == launch) return true;
    }
    return false;
  }
  bool drain_only() const {
    if (!pending_services_.empty()) return false;
    for (const auto &key : active_requests_) if (!requests_.at(key).issued) return false;
    return true;
  }
  std::uint64_t arrival_horizon() const {
    std::uint64_t result = 0;
    for (const auto &key : active_requests_) {
      const auto &r = requests_.at(key); if (!r.issued) result = std::max(result, r.access.mCycleCount);
    }
    return result;
  }
  trafficgen_socket::L2SubpartitionReservationsByCycle reservations(std::uint64_t current_cycle) const {
    trafficgen_socket::L2SubpartitionReservationsByCycle result;
    for (const auto id : pending_services_) {
      const auto &s = services_.at(id); result[s.first.mCycleCount].insert(s.first.mSubpartition);
    }
    if (lrc_wire_) for (auto it = reservation_services_.lower_bound(current_cycle); it != reservation_services_.end(); ++it)
      for (const auto id : it->second) {
      const auto &s = services_.at(id);
      // The v6 batch calendar retains a consumed slot at the current cycle;
      // only strictly older reservations disappear between scheduler rounds.
      if (s.reported_physical_issue)
        result[s.first.mCycleCount].insert(s.first.mSubpartition);
    }
    return result;
  }
  static key_t key_of(const access_t &a) { return {a.launchId, a.mUniqueId}; }
  static const char *action_name(action_t a) {
    switch (a) { case action_t::None: return "None"; case action_t::Issue: return "Issue";
      case action_t::Attach: return "Attach"; case action_t::Reuse: return "Reuse"; }
    return "Invalid";
  }

  // Return only the physical transactions, with all logical obligations already
  // registered. No descriptor may execute until this whole method succeeds.
  std::vector<access_t> add_schedule(const std::vector<access_t> &commands,
      const std::set<trafficgen_socket::socket_warp_key_t> &blocked,
      std::uint64_t current_cycle, bool force_native = false) {
    if (has_result_) throw std::logic_error("LRC result was not consumed before new socket schedule");
    has_result_ = true;
    advance_dirty_ = true;
    const bool old_batch = batch_;
    for (const auto &a : commands) lrc_wire_ |= a.mBatchAction != action_t::None;
    batch_ |= lrc_wire_ || force_native;
    if (!old_batch && batch_) {
      // Native accounting may have retired an ordinary row before the legacy
      // completion channel publishes its bundle ID. The held-cycle checkpoint
      // must own that still-public obligation after migration. Re-arm only
      // these fully returned rows, with a fresh generation and no service refs.
      for (const auto id : ordinary_native_retired_) {
        auto &b = bundles_.at(id);
        for (const auto service : b.services)
          if (!services_.at(service).returned || services_.at(service).token)
            throw std::logic_error("LRC migration rearmed a live ordinary service");
        const auto generation = ++bundle_generations_[b.slot];
        if (generation >= (std::uint64_t{1} << 48)) throw std::runtime_error("LRC bundle generation exhausted");
        b.handle = (generation << 16) | b.slot;
        b.floor = std::max(b.floor,current_cycle);
        dirty_bundles_.insert(id);
      }
      ordinary_native_retired_.clear();
    }
    std::set<std::uint64_t> new_bundles;
    std::set<key_t> seen;
    for (const auto &command : commands) {
      const auto members = members_of(command);
      for (const auto &a : members) {
        const auto key = key_of(a);
        if (!key.launch_id || requests_.count(key) || !seen.insert(key).second ||
            (command.mBatchAction != action_t::None && a.mCycleCount < current_cycle) ||
            !a.mContributors.empty())
          throw std::runtime_error("LRC duplicate, stale, or malformed original request");
        if (command.mBatchAction == action_t::Reuse) {
          if (a.mBundleId || a.mWakeRelevantBundle || !a.mHasLrcService)
            throw std::runtime_error("LRC reuse creates a bundle obligation");
        } else {
          if (!a.mBundleId || bundles_.count(a.mBundleId) || completed_bundles_.count(a.mBundleId))
            throw std::runtime_error("LRC duplicate or invalid logical bundle");
          new_bundles.insert(a.mBundleId);
        }
      }
    }
    if (new_bundles.size() > free_bundles_.size())
      throw std::runtime_error("LRC logical bundle/timer table capacity exceeded before resume: occupied=" +
          std::to_string(active_bundle_slots()) + " new=" + std::to_string(new_bundles.size()) +
          " capacity=" + std::to_string(capacity_));
    for (const auto id : new_bundles) {
      bundle_t bundle; bundle.slot = free_bundles_.back(); free_bundles_.pop_back();
      const auto generation = ++bundle_generations_[bundle.slot];
      if (generation >= (std::uint64_t{1} << 48)) throw std::runtime_error("LRC bundle generation exhausted");
      bundle.handle = (generation << 16) | bundle.slot;
      bundles_.emplace(id, std::move(bundle));
      dirty_bundles_.insert(id);
    }
    bundle_high_water_ = std::max(bundle_high_water_,active_bundle_slots());
    std::vector<access_t> physical;
    for (const auto &command : commands) {
      const bool ordinary = command.mBatchAction == action_t::None;
      const auto action = ordinary ? action_t::Issue : command.mBatchAction;
      const auto id = ordinary ? next_ordinary_++ : command.mPhysicalId;
      if (!id || (ordinary && !(id >> 63))) throw std::runtime_error("LRC physical identity exhausted");
      if (action == action_t::Issue) {
        if (services_.count(id)) throw std::runtime_error("LRC duplicate physical issue");
        if (command.mHasLrcService && recorded_.count({command.launchId, command.mLrcServiceId}))
          throw std::runtime_error("LRC duplicate recorded service");
        service_t s; s.first = command; s.first.mContributors.clear(); s.physical = id;
        services_.emplace(id, std::move(s));
        pending_services_.insert(id); ++unreturned_count_;
        reservation_services_[command.mCycleCount].insert(id);
        if (command.mHasLrcService) recorded_.emplace(key_t{command.launchId,command.mLrcServiceId}, id);
        auto issued = command; issued.mContributors.clear(); issued.mPhysicalId = id;
        physical.push_back(std::move(issued));
      }
      const auto found = services_.find(id);
      if (found == services_.end()) throw std::runtime_error("LRC attachment to unknown service");
      auto &service = found->second;
      if ((action == action_t::Reuse) != service.returned ||
          (!ordinary && (command.launchId != service.first.launchId ||
            command.mAddress != service.first.mAddress || command.mIsWrite != service.first.mIsWrite ||
            command.mSubpartition != service.first.mSubpartition ||
            command.mLrcServiceId != service.first.mLrcServiceId ||
            command.mHasLrcService != service.first.mHasLrcService ||
            command.mLrcLatency != service.first.mLrcLatency)) ||
          (action != action_t::Issue && !service.first.mHasLrcService))
        throw std::runtime_error("LRC invalid attachment state or service metadata");
      auto members = members_of(command);
      if (service.first.mHasLrcService && service.members.size() + members.size() > 4)
        throw std::runtime_error("LRC service exceeds four contributors");
      for (auto a : members) {
        if (!ordinary && (a.mPhysicalId != id || a.launchId != command.launchId ||
            a.mLrcServiceId != command.mLrcServiceId || a.mHasLrcService != command.mHasLrcService ||
            a.mAddress != command.mAddress || a.mIsWrite != command.mIsWrite ||
            a.mSubpartition != command.mSubpartition || a.mSourceReadyCycle > a.mCycleCount ||
            a.mCycleCount < service.first.mCycleCount))
          throw std::runtime_error("LRC inconsistent contributor metadata");
        if (ordinary) a.mSourceReadyCycle = a.mCycleCount;
        const auto key = key_of(a);
        const bool later = !service.members.empty();
        service.members.push_back(key);
        requests_.emplace(key, request_t{a, id, false, false});
        active_requests_.insert(key);
        if (a.mBundleId) {
          auto &bundle = bundles_.at(a.mBundleId);
          bundle.members.push_back(key); bundle.services.insert(id);
          bundle.floor = std::max(bundle.floor, a.mCycleCount);
        }
        events_.push_back(event("scheduled", a, a.mCycleCount, id));
        if (a.mLrcEnabled) {
          auto &stats = result_.lrcStatsByLaunch[a.launchId];
          if (action == action_t::Reuse) ++stats.reusedContributors;
          else if (a.mHasLrcService && later) {
            ++stats.joinedContributors; if (a.mPriorRoundJoin) ++stats.crossRoundJoins;
          }
        }
      }
      if (service.token && !service.returned) dirty_services_.insert(id);
    }
    for (auto &[id, bundle] : bundles_) {
      const bool old_wake = bundle.wake;
      bundle.wake = false;
      for (const auto &key : bundle.members) {
        const auto &a = requests_.at(key).access;
        bundle.wake |= a.mWakeRelevantBundle && blocked.count({a.smId,a.schedulerId,a.warpId,a.launchId});
      }
      if (old_wake != bundle.wake && !ordinary_native_retired_.count(id)) dirty_wakes_.insert(id);
    }
    return physical;
  }

  void suppress_tma_wakes(const std::set<trafficgen_socket::TmaRequestIdentity> &known) {
    for (auto &[id,b] : bundles_) for (const auto &key : b.members) {
      if (known.count({key.launch_id,key.request_uid})) {
        if (b.wake) { b.wake=false; dirty_wakes_.insert(id); }
        break;
      }
    }
  }
  std::uint64_t stage_service(const key_t &source) {
    auto &s = services_.at(requests_.at(source).physical);
    if (s.returned) throw std::logic_error("LRC tried to stage returned service");
    if (!s.token) {
      if (free_services_.empty()) throw std::runtime_error("LRC service table full before physical upload: occupied=" +
          std::to_string(active_service_slots()) + " new=1 capacity=" + std::to_string(capacity_));
      const auto slot = free_services_.back(); free_services_.pop_back();
      const auto generation = ++generations_[slot];
      if (generation >= (std::uint64_t{1} << 48)) throw std::runtime_error("LRC service generation exhausted");
      s.token = (generation << 16) | slot;
      tokens_.emplace(s.token, s.physical);
      dirty_services_.insert(s.physical);
      service_high_water_ = std::max(service_high_water_,active_service_slots());
    }
    return s.token;
  }
  std::vector<descriptor_t> descriptors(bool dirty_only = false) const {
    std::vector<descriptor_t> result;
    for (const auto &[id, b] : bundles_) {
      if (ordinary_native_retired_.count(id)) continue;
      if (dirty_only && !dirty_bundles_.count(id)) {
        if (dirty_wakes_.count(id)) {
          descriptor_t d{}; bits(d,0,8,3); bits(d,8,16,b.slot);
          bits(d,24,64,b.handle); bits(d,88,1,b.wake); result.push_back(d);
        }
        continue;
      }
      std::size_t remaining = 0;
      for (const auto service : b.services) remaining += !services_.at(service).returned;
      if (remaining > 65536) throw std::runtime_error("LRC bundle dependency capacity exceeded before resume: occupied=0 new=" +
          std::to_string(remaining) + " capacity=65536");
      descriptor_t d{}; bits(d,0,8,2); bits(d,8,16,b.slot); bits(d,24,64,id);
      bits(d,88,17,remaining); bits(d,105,64,b.floor); bits(d,169,1,b.wake);
      bits(d,170,64,b.handle); result.push_back(d);
    }
    for (const auto &[token, id] : tokens_) {
      if (dirty_only && !dirty_services_.count(id)) continue;
      (void)token; const auto &s = services_.at(id);
      std::set<std::uint64_t> edges;
      for (const auto &key : s.members) {
        const auto bundle = requests_.at(key).access.mBundleId;
        const auto it = bundles_.find(bundle); if (it != bundles_.end()) edges.insert(it->second.handle);
      }
      if (edges.size() > 4) throw std::runtime_error("LRC service monitor edge overflow");
      descriptor_t d{}; bits(d,0,8,1); bits(d,8,16,s.token & 0xffff); bits(d,24,64,s.token);
      bits(d,88,4,(std::uint64_t{1} << edges.size()) - 1);
      unsigned n = 0; for (const auto handle : edges) bits(d,92+64*n++,64,handle);
      result.push_back(d);
    }
    return result;
  }
  // The monitor already applies returns to existing counters. Republishing
  // unchanged rows would spend host cycles rewriting the entire live set on
  // every scheduler round; only new rows, Attach edges, and changed wake bits
  // require metadata. Clear after all chunks commit, before target resume.
  void descriptors_committed() { dirty_bundles_.clear(); dirty_services_.clear(); dirty_wakes_.clear(); }
  void issued(const key_t &source, std::uint64_t token, std::uint64_t cycle) {
    auto &s = services_.at(requests_.at(source).physical);
    if (s.issued || s.token != token || cycle < s.first.mCycleCount)
      throw std::runtime_error("LRC duplicate or invalid physical issue");
    s.issued = true; s.issue_cycle = cycle;
    advance_dirty_ = true;
    pending_services_.erase(s.physical);
    if (lrc_wire_) {
      result_.physicalAccesses.push_back({s.physical,s.first.mLrcServiceId,s.first.mUniqueId,
          cycle,s.first.mAddress,s.first.mIsWrite,s.first.launchId,s.first.mHasLrcService});
      s.reported_physical_issue = true;
    }
    events_.push_back(event("physical_issue",s.first,cycle,s.physical));
    if (s.first.mLrcEnabled) {
      auto &stats = result_.lrcStatsByLaunch[s.first.launchId];
      if (s.first.mIsWrite) ++stats.physicalWrites; else ++stats.physicalReads;
    }
    if (s.first.mBatchAction == action_t::None) admit(requests_.at(source),cycle);
  }
  void returned(std::uint64_t token, std::uint64_t cycle) {
    const auto it = tokens_.find(token);
    if (it == tokens_.end()) throw std::runtime_error("LRC unknown or stale physical return token");
    auto &s = services_.at(it->second);
    if (!s.issued || s.returned || cycle < s.issue_cycle)
      throw std::runtime_error("LRC premature or duplicate physical return");
    s.returned = true; s.return_cycle = cycle;
    advance_dirty_ = true;
    --unreturned_count_;
    trafficgen_socket::PhysicalCompletionPoint point{s.physical,s.first.mLrcServiceId,s.first.launchId,cycle,{}};
    events_.push_back(event("physical_return",s.first,cycle,s.physical));
    for (const auto &key : s.members) {
      point.requestUids.push_back(key.request_uid);
      events_.push_back(event("member",requests_.at(key).access,cycle,s.physical));
    }
    if (s.reported_physical_issue || s.first.mLrcEnabled) result_.physicalCompletions.push_back(std::move(point));
    free_services_.push_back(token & 0xffff); tokens_.erase(it); s.token = 0;
    dirty_services_.erase(s.physical);
  }
  void advance(std::uint64_t cycle) {
    // A boundary folds every return first. The following per-bundle reports
    // must not each rescan tens of thousands of live original requests.
    if (!advance_dirty_ && cycle <= last_advance_cycle_) return;
    advance_dirty_ = false;
    last_advance_cycle_ = std::max(last_advance_cycle_,cycle);
    // Logical arrival and credit events are timestamped independently of host
    // readback order and physical-service issue backpressure.
    for (auto it = active_requests_.begin(); it != active_requests_.end();) {
      auto &r = requests_.at(*it); const auto &s = services_.at(r.physical);
      if (!r.issued && r.access.mBatchAction != action_t::None && r.access.mCycleCount <= cycle)
        admit(r,r.access.mCycleCount);
      if (!r.completed && r.issued && s.returned &&
          std::max(s.return_cycle,r.access.mCycleCount) <= cycle) {
        const auto at = std::max(s.return_cycle,r.access.mCycleCount);
        r.completed = true; result_.completedAccesses.push_back(point(r.access,at));
        events_.push_back(event("completed",r.access,at,r.physical));
      }
      if (r.completed) it = active_requests_.erase(it); else ++it;
    }
  }
  void complete_bundle(std::uint64_t id, std::uint64_t cycle) {
    advance(cycle);
    const auto it = bundles_.find(id);
    if (it == bundles_.end()) throw std::runtime_error("LRC unknown or duplicate logical completion");
    for (const auto &key : it->second.members) {
      const auto &r = requests_.at(key);
      if (!r.completed || std::max(services_.at(r.physical).return_cycle,r.access.mCycleCount) > cycle)
        throw std::runtime_error("LRC logical bundle completed before original obligations");
    }
    result_.completedBundleIds.push_back(id);
    const auto &a = requests_.at(it->second.members.front()).access;
    auto e = event("bundle_complete",a,cycle,0); e.uid = 0; events_.push_back(e);
    free_bundles_.push_back(it->second.slot); bundles_.erase(it); completed_bundles_.insert(id);
    dirty_bundles_.erase(id);
    dirty_wakes_.erase(id);
    ordinary_native_retired_.erase(id);
  }
  void ordinary_native_retired(std::uint64_t id, std::uint64_t cycle) {
    if (batch_) throw std::logic_error("LRC ordinary native retirement after migration");
    const auto it = bundles_.find(id);
    if (it == bundles_.end() || !ordinary_native_retired_.insert(id).second)
      throw std::runtime_error("LRC unknown or duplicate ordinary native completion");
    for (const auto &key : it->second.members) {
      const auto &r = requests_.at(key); const auto &s = services_.at(r.physical);
      if (!s.returned || std::max(s.return_cycle,r.access.mCycleCount) > cycle)
        throw std::runtime_error("LRC ordinary native bundle completed prematurely");
    }
    dirty_bundles_.erase(id); dirty_wakes_.erase(id);
  }
  result_t take_result(std::uint64_t cycle) {
    advance(cycle); result_.currentCycleAfterIssue = cycle;
    for (const auto &[launch,stats] : result_.lrcStatsByLaunch) { (void)launch; result_.lrcStats += stats; }
    auto result = std::move(result_); result_ = {}; has_result_ = false; return result;
  }
  std::vector<event_t> take_events() { auto e = std::move(events_); events_.clear(); return e; }
  static void bits(descriptor_t &d, unsigned offset, unsigned width, std::uint64_t value) {
    for (unsigned n = 0; n < width; ++n)
      if ((value >> n) & 1) d[(offset+n)/64] |= std::uint64_t{1} << ((offset+n)%64);
  }
  static std::uint64_t bits(const descriptor_t &d, unsigned offset, unsigned width) {
    std::uint64_t result = 0;
    for (unsigned n = 0; n < width; ++n)
      result |= ((d[(offset+n)/64] >> ((offset+n)%64)) & 1) << n;
    return result;
  }
private:
  static std::vector<access_t> members_of(const access_t &a) {
    if (a.mBatchAction == action_t::None) {
      if (!a.mContributors.empty() || a.mLrcEnabled) throw std::runtime_error("LRC missing batch command");
      return {a};
    }
    if (a.mBatchAction != action_t::Issue && a.mBatchAction != action_t::Attach && a.mBatchAction != action_t::Reuse)
      throw std::runtime_error("LRC invalid command action");
    if (a.mContributors.empty() || !a.mPhysicalId) throw std::runtime_error("LRC empty batch command");
    return a.mContributors;
  }
  static trafficgen_socket::IssuedAccessPoint point(const access_t &a, std::uint64_t at) {
    return {a.mUniqueId,at,a.mAddress,a.mIsWrite,a.launchId};
  }
  static event_t event(const char *name, const access_t &a, std::uint64_t at, std::uint64_t physical) {
    return {name,at,a.launchId,a.mUniqueId,physical,a.mLrcServiceId,a.mBundleId,a.mAddress,a.mIsWrite,a.mBatchAction,a.mSourceReadyCycle};
  }
  void admit(request_t &r, std::uint64_t at) {
    if (r.issued) throw std::runtime_error("LRC duplicate original admission");
    r.issued = true; result_.issuedAccesses.push_back(point(r.access,at));
    events_.push_back(event("issued",r.access,at,r.physical));
    if (lrc_wire_) result_.sourceReadyAccesses.push_back(point(r.access,r.access.mSourceReadyCycle));
    events_.push_back(event("source_ready",r.access,r.access.mSourceReadyCycle,r.physical));
    if (r.access.mBatchAction == action_t::Reuse) {
      result_.reusedAccesses.push_back(point(r.access,at)); events_.push_back(event("reused",r.access,at,r.physical));
    }
    if (r.access.mLrcEnabled) {
      auto &stats = result_.lrcStatsByLaunch[r.access.launchId];
      if (r.access.mIsWrite) ++stats.sourceWrites; else ++stats.sourceReads;
    }
  }
  std::size_t capacity_;
  std::size_t service_high_water_ = 0, bundle_high_water_ = 0;
  std::size_t unreturned_count_ = 0;
  bool batch_ = false, lrc_wire_ = false, has_result_ = false;
  bool advance_dirty_ = true;
  std::uint64_t last_advance_cycle_ = 0;
  std::uint64_t next_ordinary_ = std::uint64_t{1} << 63;
  std::map<key_t, request_t> requests_;
  std::set<key_t> active_requests_;
  std::map<std::uint64_t, service_t> services_;
  std::set<std::uint64_t> pending_services_;
  std::map<std::uint64_t,std::set<std::uint64_t>> reservation_services_;
  std::map<key_t,std::uint64_t> recorded_;
  std::map<std::uint64_t,std::uint64_t> tokens_;
  std::map<std::uint64_t,bundle_t> bundles_;
  std::set<std::uint64_t> dirty_services_, dirty_bundles_, dirty_wakes_;
  std::set<std::uint64_t> completed_bundles_;
  std::set<std::uint64_t> ordinary_native_retired_;
  std::vector<std::uint16_t> free_services_,free_bundles_;
  std::map<std::uint16_t,std::uint64_t> generations_;
  std::map<std::uint16_t,std::uint64_t> bundle_generations_;
  result_t result_;
  std::vector<event_t> events_;
};
#endif
