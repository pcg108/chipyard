#include "bridges/trafficgen_host_scratch.h"
#include <cassert>
#include <iostream>
#include <vector>
int main() {
  constexpr std::size_t limit = 131072;
  TrafficGenHostScratch a, b;
  assert(a.get(0, limit) == nullptr && a.capacity() == 0);
  unsigned growth = 0;
  std::uint64_t seed = 17;
  for (unsigned i = 0; i < 3000; ++i) {
    seed = seed * 6364136223846793005ULL + 1;
    const auto n = i == 0 ? 1 : (seed % (limit + 1));
    const auto old = a.capacity();
    auto *p = a.get(n, limit);
    assert(a.capacity() >= n && a.capacity() <= limit);
    if (p)
      assert(reinterpret_cast<std::uintptr_t>(p) % 4096 == 0);
    growth += a.capacity() != old;
    if (n) {
      std::memset(p, 0xa5, n);
      auto *q = a.get(n / 2, limit);
      assert(q == p);
      for (std::size_t k = 0; k < n; ++k)
        assert(p[k] == 0xa5);
    }
  }
  assert(growth < 20);
  auto *pa = a.get(limit, limit);
  auto *pb = b.get(limit, limit);
  assert(pa != pb);
  std::memset(pa, 0x22, limit);
  std::memset(pb, 0x33, limit);
  assert(pa[0] == 0x22 && pb[0] == 0x33);
  bool rejected = false;
  try {
    a.get(limit + 1, limit);
  } catch (const std::runtime_error &) {
    rejected = true;
  }
  assert(rejected);
  assert(a.get(limit, limit) == pa && pa[0] == 0x22);
  std::cout << "scratch ownership, alignment, reuse, boundaries and failure preservation "
               "passed; growth="
            << growth << " requests=3000\n";
}
