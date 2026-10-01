// Session-wide accounting. Hardware completion includes stores and is separate
// from whether completing the bundle should wake the scheduler immediately.
#ifndef TRAFFICGEN_BUNDLE_TRACKING_H
#define TRAFFICGEN_BUNDLE_TRACKING_H
#include "trafficgen_request_key.h"
#include <map>
#include <set>
#include <stdexcept>
#include <utility>
#include <vector>

class trafficgen_bundle_tracker {
public:
  struct bundle_t {
    std::uint64_t launch_id = 0;
    std::vector<trafficgen_request_key_t> requests;
    std::size_t issued = 0;
  };
  struct request_t { std::uint64_t bundle_id = 0; bool issued = false; };

  template<class Access> void add_schedule(const std::vector<Access> &accesses) {
    std::map<std::uint64_t, bundle_t> fresh;
    std::set<trafficgen_request_key_t> keys;
    for (const auto &access : accesses) {
      const trafficgen_request_key_t key{access.launch_id, access.id};
      if (!key.launch_id || !access.m_bundle_id || bundles.count(access.m_bundle_id) ||
          requests.count(key) || !keys.insert(key).second)
        throw std::runtime_error("TrafficGen repeated or invalid scheduled identity: " +
                                 trafficgen_request_description(key));
      auto &bundle = fresh[access.m_bundle_id];
      if (bundle.launch_id && bundle.launch_id != key.launch_id)
        throw std::runtime_error("TrafficGen bundle spans launches");
      bundle.launch_id = key.launch_id;
      bundle.requests.push_back(key);
      if (bundle.requests.size() > 65535)
        throw std::runtime_error("TrafficGen bundle exceeds packed member count");
    }
    for (auto &[id, bundle] : fresh) {
      for (const auto &key : bundle.requests) requests.emplace(key, request_t{id, false});
      bundles.emplace(id, std::move(bundle));
    }
  }

  template<class Issued> void record_issued(const std::vector<Issued> &points) {
    std::set<trafficgen_request_key_t> fresh;
    for (const auto &point : points) {
      const trafficgen_request_key_t key{point.launch_id, point.request_uid};
      const auto it = requests.find(key);
      if (it == requests.end() || it->second.issued || !fresh.insert(key).second)
        throw std::runtime_error("TrafficGen unknown or repeated issue: " +
                                 trafficgen_request_description(key));
    }
    for (const auto &key : fresh) {
      auto &request = requests.at(key);
      request.issued = true;
      ++bundles.at(request.bundle_id).issued;
    }
  }

  void complete(const std::vector<std::uint64_t> &ids) {
    std::set<std::uint64_t> fresh;
    for (const auto id : ids) {
      const auto it = bundles.find(id);
      if (it == bundles.end() || !fresh.insert(id).second)
        throw std::runtime_error("TrafficGen unknown or repeated bundle completion=" + std::to_string(id));
      if (it->second.issued != it->second.requests.size())
        throw std::runtime_error("TrafficGen bundle completed before all members issued=" + std::to_string(id));
    }
    for (const auto id : fresh) {
      for (const auto &key : bundles.at(id).requests) requests.erase(key);
      bundles.erase(id);
    }
  }
  bool has_pending() const { return !bundles.empty(); }
  std::size_t size() const { return bundles.size(); }
  bool has_launch(std::uint64_t id) const {
    for (const auto &entry : bundles) if (entry.second.launch_id == id) return true;
    return false;
  }
  void clear() { bundles.clear(); requests.clear(); }
private:
  std::map<std::uint64_t, bundle_t> bundles;
  std::map<trafficgen_request_key_t, request_t> requests;
};
#endif
