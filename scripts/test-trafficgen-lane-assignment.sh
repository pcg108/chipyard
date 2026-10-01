#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
compiler="${CXX:-/home/prashanth/chipyard/.conda-env/bin/x86_64-conda-linux-gnu-c++}"
output="${TMPDIR:-/tmp}/trafficgen-lane-assignment-test-${UID}"

"$compiler" -std=c++20 -O2 -Wall -Wextra -Werror \
  -I"$repo_root/generators/firechip/bridgestubs/src/main/cc" \
  "$repo_root/generators/firechip/bridgestubs/src/main/cc/bridges/test/TrafficGenLaneAssignmentTest.cc" \
  -o "$output"
"$output"
