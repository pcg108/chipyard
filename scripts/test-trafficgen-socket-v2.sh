#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
gpu_root=${GPU_MODEL_ROOT:-/home/prashanth/gpu_model}
boost_root=${BOOST_ROOT:-"$repo_root/.conda-env"}
compiler=${CXX:-c++}
build=$(mktemp -d "${TMPDIR:-/tmp}/trafficgen-v2-tests.XXXXXX")
trap 'rm -rf "$build"' EXIT
bridges="$repo_root/generators/firechip/bridgestubs/src/main/cc"
midas="$repo_root/sims/firesim/sim/midas/src/main/cc"
common=(-std=c++17 -O1 -g -Wall -Wextra -Werror -pthread -I"$bridges" -I"$midas" -I"$gpu_root/src/include" -I"$boost_root/include")
link=(-L"$boost_root/lib" -Wl,-rpath,"$boost_root/lib" -lboost_serialization)
"$compiler" "${common[@]}" "$bridges/bridges/test/TrafficGenSocketProtocolTest.cc" "${link[@]}" -o "$build/protocol"
"$build/protocol"
"$compiler" "${common[@]}" "$bridges/bridges/test/TrafficGenDriverSessionTest.cc" \
  "$bridges/bridges/trafficgen.cc" "$gpu_root/src/socket_transport.cpp" "${link[@]}" -o "$build/session"
timeout 45s "$build/session"
