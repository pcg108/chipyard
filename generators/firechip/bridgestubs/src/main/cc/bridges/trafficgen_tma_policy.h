// Compile public v7 wake snapshots. This class never decides target boundaries:
// hardware reports are checked here only after a hardware boundary or refill.
#ifndef TRAFFICGEN_TMA_POLICY_H
#define TRAFFICGEN_TMA_POLICY_H
#include "trafficgen_lrc_ledger.h"
class trafficgen_tma_policy {
public:
  using ledger_t = trafficgen_lrc_ledger;
  using descriptor_t = ledger_t::descriptor_t;
  using identity_t = trafficgen_socket::TmaRequestIdentity;
  struct group_t {
    trafficgen_socket::TmaWakeGroup source;
    std::uint64_t floor = 0;
    std::set<std::uint64_t> services;
    bool reported = false;
  };
  struct edge_t { std::uint32_t group, next; };
  static constexpr std::uint32_t edge_limit = 262144, group_limit = 4096;
  // Preflight is transactional: no publication or replacement on failure.
  void replace(const trafficgen_socket::TmaWakePolicy &policy, const ledger_t &ledger) {
    if (epoch_ == UINT32_MAX) throw std::runtime_error("TMA policy epoch exhausted");
    capacity("wake groups", policy.groups.size(), group_limit);
    std::vector<group_t> groups;
    std::vector<edge_t> edges;
    std::map<std::uint64_t,std::uint32_t> heads;
    auto known = known_;
    auto require_request = [&](const identity_t &id) -> const ledger_t::request_t & {
      const auto it = ledger.requests().find({id.launchId,id.requestUid});
      if (it == ledger.requests().end()) throw std::runtime_error("TMA policy references unknown request: launch=" +
        std::to_string(id.launchId) + " uid=" + std::to_string(id.requestUid));
      known.insert(id); return it->second;
    };
    for (const auto &id : policy.outstandingRequests) require_request(id);
    for (const auto &source : policy.groups) {
      group_t g; g.source = source; g.floor = source.readyCycle;
      for (const auto &id : source.requests) {
        const auto &r = require_request(id);
        const auto &s = ledger.services().at(r.physical);
        g.floor = std::max(g.floor,r.access.mCycleCount);
        if (s.returned) g.floor = std::max(g.floor,s.return_cycle);
        else g.services.insert(r.physical);
      }
      capacity("dependency edges",edges.size()+g.services.size(),edge_limit);
      for (auto service : g.services) {
        auto inserted = heads.emplace(service,edge_limit);
        edges.push_back({static_cast<std::uint32_t>(groups.size()),inserted.first->second});
        inserted.first->second = static_cast<std::uint32_t>(edges.size()-1);
      }
      groups.push_back(std::move(g));
    }
    groups_ = std::move(groups); edges_ = std::move(edges); heads_ = std::move(heads);
    known_ = std::move(known); ++epoch_; replacement_ = true; published_tokens_.clear();
    honor_deadline_ = !policy.groups.empty() || !policy.outstandingRequests.empty();
    group_high_water_ = std::max(group_high_water_,groups_.size());
    edge_high_water_ = std::max(edge_high_water_,edges_.size());
  }
  static bool nonempty(const trafficgen_socket::TmaWakePolicy &p) {
    return !p.groups.empty() || !p.outstandingRequests.empty();
  }
  const std::set<identity_t> &known() const { return known_; }
  const std::vector<group_t> &groups() const { return groups_; }
  std::uint32_t epoch() const { return epoch_; }
  std::size_t edge_count() const { return edges_.size(); }
  std::size_t group_high_water() const { return group_high_water_; }
  std::size_t edge_high_water() const { return edge_high_water_; }
  bool honor_deadline() const { return honor_deadline_; }
  bool pending() const { for (const auto &g : groups_) if (!g.reported) return true; return false; }
  // Ops 4/5/6/7/8: begin, group, edge, service head, atomic policy commit.
  // Heads for future upload windows remain in heads_ until assigned a token.
  std::vector<descriptor_t> descriptors(const ledger_t &ledger) const {
    std::vector<descriptor_t> result;
    auto emit = [&](unsigned op) -> descriptor_t & { result.emplace_back(); ledger_t::bits(result.back(),0,8,op); return result.back(); };
    if (replacement_) {
      auto &d = emit(4); ledger_t::bits(d,8,32,epoch_); ledger_t::bits(d,40,13,groups_.size());
      ledger_t::bits(d,53,19,edges_.size()); ledger_t::bits(d,72,1,honor_deadline_);
      ledger_t::bits(d,73,1,ledger.lrc_commands_active());
      for (std::size_t n=0;n<groups_.size();++n) {
        const auto &g=groups_[n]; auto &row=emit(5); ledger_t::bits(row,8,12,n);
        ledger_t::bits(row,20,32,g.services.size()); ledger_t::bits(row,52,64,g.floor);
        ledger_t::bits(row,116,1,g.source.conservative);
      }
      for (std::size_t n=0;n<edges_.size();++n) {
        auto &row=emit(6); ledger_t::bits(row,8,18,n); ledger_t::bits(row,26,12,edges_[n].group);
        ledger_t::bits(row,38,19,edges_[n].next);
      }
    }
    for (const auto &[service,head] : heads_) {
      const auto &s=ledger.services().at(service);
      if (!s.token || s.returned || published_tokens_.count(s.token)) continue;
      auto &row=emit(7); ledger_t::bits(row,8,64,s.token); ledger_t::bits(row,72,32,epoch_);
      ledger_t::bits(row,104,19,head);
    }
    if (replacement_) { auto &d=emit(8); ledger_t::bits(d,8,32,epoch_); }
    return result;
  }
  void committed(const ledger_t &ledger) {
    replacement_=false;
    for (const auto &[service,head] : heads_) { (void)head;
      const auto &s=ledger.services().at(service); if (s.token) published_tokens_.insert(s.token);
    }
  }
  void wake(std::uint64_t identity, std::uint64_t cycle, bool conservative, const ledger_t &ledger) {
    if ((identity >> 32) != epoch_ || static_cast<std::uint32_t>(identity) >= groups_.size())
      throw std::runtime_error("TMA stale or invalid hardware wake identity");
    auto &g=groups_[static_cast<std::uint32_t>(identity)];
    if (g.reported || g.source.conservative != conservative || cycle < g.floor)
      throw std::runtime_error("TMA duplicate, mistyped, or early hardware wake");
    for (const auto &id : g.source.requests) {
      const auto &r=ledger.requests().at({id.launchId,id.requestUid}); const auto &s=ledger.services().at(r.physical);
      if (!r.completed || !s.returned || std::max(s.return_cycle,r.access.mCycleCount)>cycle)
        throw std::runtime_error("TMA hardware wake before original completion");
    }
    g.reported=true;
  }
private:
  static void capacity(const char *name,std::size_t requested,std::size_t limit) {
    if (requested>limit) throw std::runtime_error(std::string("TMA ")+name+
      " capacity exceeded before publication: requested="+std::to_string(requested)+" occupied=0 limit="+std::to_string(limit));
  }
  std::uint32_t epoch_=0;
  bool replacement_=false, honor_deadline_=false;
  std::vector<group_t> groups_;
  std::vector<edge_t> edges_;
  std::map<std::uint64_t,std::uint32_t> heads_;
  std::set<std::uint64_t> published_tokens_;
  std::set<identity_t> known_;
  std::size_t group_high_water_=0,edge_high_water_=0;
};
#endif
