#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=${1:?output directory required}
mkdir -p "$work"
g++ -std=c++17 -Wall -Wextra -Werror \
 -I "$root/generators/firechip/bridgestubs/src/main/cc" \
 -I "$root/sims/firesim/sim/midas/src/main/cc" \
 "$root/generators/firechip/bridgestubs/src/main/cc/bridges/l2perf.cc" \
 "$root/generators/firechip/bridgestubs/src/main/cc/bridges/test/L2PerfDriverTest.cc" \
 -o "$work/driver-test"
"$work/driver-test" "$work/records"
