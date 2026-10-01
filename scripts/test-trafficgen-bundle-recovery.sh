#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
compiler="${CXX:-/home/prashanth/chipyard/.conda-env/bin/x86_64-conda-linux-gnu-c++}"
output=$(mktemp "${TMPDIR:-/tmp}/trafficgen-bundle-recovery-test.XXXXXX")
trap 'rm -f "$output"' EXIT

"$compiler" -std=c++20 -O2 -Wall -Wextra -Werror \
  -I"$repo_root/generators/firechip/bridgestubs/src/main/cc" \
  "$repo_root/generators/firechip/bridgestubs/src/main/cc/bridges/test/TrafficGenBundleRecoveryTest.cc" \
  -o "$output"
"$output" "$repo_root/generators/firechip/bridgestubs/src/main/cc/bridges/test/data/kernel2_round911_pending.csv"
