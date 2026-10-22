// Host-only, per-driver scratch storage. No target protocol or timing state.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
class TrafficGenHostScratch {
  struct Free {
    void operator()(std::uint8_t *p) const { std::free(p); }
  };
  std::unique_ptr<std::uint8_t, Free> storage_;
  std::size_t capacity_ = 0;

public:
  static constexpr std::size_t alignment = 4096;
  std::size_t capacity() const { return capacity_; }
  std::uint8_t *get(std::size_t size, std::size_t bound) {
    if (size > bound)
      throw std::runtime_error("TrafficGen host scratch exceeds hardware window");
    if (size <= capacity_)
      return storage_.get();
    std::size_t next = capacity_ ? capacity_ : alignment;
    if (next > bound)
      next = bound;
    while (next < size) {
      if (next > bound / 2) {
        next = bound;
        break;
      }
      next *= 2;
    }
    void *p = nullptr;
    const int rc = posix_memalign(&p, alignment, next);
    if (rc)
      throw std::runtime_error("TrafficGen host scratch posix_memalign failed: " +
                               std::string(std::strerror(rc)));
    storage_.reset(static_cast<std::uint8_t *>(p));
    capacity_ = next;
    return storage_.get();
  }
};
