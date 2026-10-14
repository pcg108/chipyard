#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
gpu_root=${GPU_MODEL_ROOT:-"$(dirname -- "$repo_root")/gpu_model"}
boost_root=${BOOST_ROOT:-"$repo_root/.conda-env"}
compiler=${CXX:-c++}
build=$(mktemp -d "${TMPDIR:-/tmp}/trafficgen-v6-tests.XXXXXX")
trap 'rm -rf "$build"' EXIT
bridges="$repo_root/generators/firechip/bridgestubs/src/main/cc"
midas="$repo_root/sims/firesim/sim/midas/src/main/cc"
common=(-std=c++17 -O1 -g -Wall -Wextra -Werror -pthread -I"$bridges" -I"$midas" -I"$gpu_root/src/include" -I"$boost_root/include")
link=(-L"$boost_root/lib" -Wl,-rpath,"$boost_root/lib" -lboost_serialization)
if ! rg -q 'kSocketProtocolVersion = 6;' "$gpu_root/src/include/socket_protocol.h"; then
  echo 'Set GPU_MODEL_ROOT to gpu_model 01ca4420d030af39c1245ac89815e20d32563c32 (protocol v6).' >&2
  exit 1
fi
"$compiler" "${common[@]}" "$bridges/bridges/test/TrafficGenSocketProtocolTest.cc" "${link[@]}" -o "$build/protocol"
"$build/protocol"
"$compiler" "${common[@]}" "$bridges/bridges/test/TrafficGenLrcLedgerTest.cc" "${link[@]}" -o "$build/ledger"
"$build/ledger"
gpu_build=${GPU_MODEL_BUILD:-"$gpu_root/build-chipyard-boost"}
read -r -a opencv_libs <<< "$(pkg-config --libs opencv4)"
"$compiler" "${common[@]}" -I"$gpu_root/tests" -I"$gpu_root/third_party/accel_sim_trace_parser" \
  "$bridges/bridges/test/TrafficGenLrcDifferentialTest.cc" "$gpu_build/libgpu_model_core.a" \
  "${link[@]}" "${opencv_libs[@]}" -lz -o "$build/differential"
"$build/differential"
"$compiler" "${common[@]}" "$bridges/bridges/test/TrafficGenDriverSessionTest.cc" \
  "$bridges/bridges/trafficgen.cc" "$gpu_root/src/socket_transport.cpp" "${link[@]}" -o "$build/session"
timeout 45s "$build/session"
