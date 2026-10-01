#!/usr/bin/env bash
# Exercise generated datasets against the actual scheduler implementation.
set -euo pipefail

if [[ $# -gt 1 || ${1:-256} == *[!0-9]* || ${1:-256} -lt 1 || ${1:-256} -gt 16384 ]]; then
  echo "usage: $0 [iterations-per-kernel (1..16384, default 256)]" >&2
  exit 2
fi
iterations=${1:-256}
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
gpu_root=${GPU_MODEL_ROOT:-$(dirname "$repo_root")/gpu_model}
gpu_build=${GPU_MODEL_BUILD:-$gpu_root/build-chipyard-boost}
gpu_root=$(realpath "$gpu_root")
gpu_build=$(realpath "$gpu_build")
cache=$gpu_build/CMakeCache.txt
core=$gpu_build/libgpu_model_core.a
if [[ ! -f "$cache" || ! -f "$core" ]]; then
  echo "Build gpu_model_core first in $gpu_build; see scripts/trafficgen/README.md." >&2
  exit 1
fi
# Use this build's compiler, headers and Boost library, keeping its ABI intact.
compiler=${CXX:-$(rg -m1 '^CMAKE_CXX_COMPILER:[^=]*=' "$cache" | cut -d= -f2-)}
boost_include=$(rg -m1 '^Boost_INCLUDE_DIR:PATH=' "$cache" | cut -d= -f2-)
boost_library=$(rg -m1 '^Boost_SERIALIZATION_LIBRARY_RELEASE:FILEPATH=' "$cache" | cut -d= -f2-)
if [[ ! -f "$boost_library" ]] || ! rg -q '^#define BOOST_VERSION 108500$' "$boost_include/boost/version.hpp"; then
  echo "The synthetic test requires the scheduler build using Chipyard Boost 1.85." >&2
  exit 1
fi
boost_library_dir=$(dirname "$boost_library")
read -r -a opencv_cflags <<< "$(pkg-config --cflags opencv4)"
temporary=$(mktemp -d "${TMPDIR:-/tmp}/trafficgen-synthetic.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT

"$compiler" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$gpu_root/src/include" -I"$gpu_root/third_party/addrdec" \
  -I"$gpu_root/third_party/accel_sim_trace_parser" -I"$boost_include" \
  "${opencv_cflags[@]}" \
  "$repo_root/scripts/trafficgen/test-synthetic-registry.cc" "$core" "$boost_library" \
  -lopencv_imgcodecs -lopencv_imgproc -lopencv_core -lz \
  "-Wl,-rpath,$boost_library_dir" -o "$temporary/test-synthetic-registry"
python3 "$repo_root/scripts/trafficgen/generate-synthetic-registry.py" \
  "$temporary/fixture" --iterations "$iterations"
"$temporary/test-synthetic-registry" "$temporary/fixture/registry.json" "$iterations"
