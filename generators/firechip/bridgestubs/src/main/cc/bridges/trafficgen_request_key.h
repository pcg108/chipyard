#ifndef TRAFFICGEN_REQUEST_KEY_H
#define TRAFFICGEN_REQUEST_KEY_H
#include <cstdint>
#include <functional>
#include <string>
#include <tuple>

struct trafficgen_request_key_t {
  std::uint64_t launch_id = 0;
  std::uint64_t request_uid = 0;
  bool operator==(const trafficgen_request_key_t &other) const {
    return launch_id == other.launch_id && request_uid == other.request_uid;
  }
  bool operator<(const trafficgen_request_key_t &other) const {
    return std::tie(launch_id, request_uid) < std::tie(other.launch_id, other.request_uid);
  }
};
struct trafficgen_request_key_hash {
  std::size_t operator()(const trafficgen_request_key_t &key) const {
    const auto first = std::hash<std::uint64_t>{}(key.launch_id);
    return first ^ (std::hash<std::uint64_t>{}(key.request_uid) +
                    0x9e3779b9 + (first << 6) + (first >> 2));
  }
};
inline std::string trafficgen_request_description(const trafficgen_request_key_t &key) {
  return "launch_id=" + std::to_string(key.launch_id) +
         " request_uid=" + std::to_string(key.request_uid);
}
#endif
