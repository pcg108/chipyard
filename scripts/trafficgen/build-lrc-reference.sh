#!/usr/bin/env bash
# Link the single-launch oracle against an already-built, untouched v6 core.
set -euo pipefail
if [[ $# != 2 ]]; then echo "usage: $0 PINNED_MODEL_SOURCE PINNED_MODEL_BUILD" >&2; exit 2; fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
source_root=$(realpath "$1")
build_root=$(realpath "$2")
command=(/usr/bin/c++ -std=c++17 -O2
  "-I$source_root/src/include" "-I$source_root/third_party/addrdec"
  "-I$source_root/third_party/accel_sim_trace_parser" "-I$repo/.conda-env/include" -I/usr/include/opencv4
  "$repo/scripts/trafficgen/lrc-reference-client.cc" "$build_root/libgpu_model_core.a"
  "$repo/.conda-env/lib/libboost_serialization.so.1.85.0" "-Wl,-rpath,$repo/.conda-env/lib"
  -lopencv_imgcodecs -lopencv_imgproc -lopencv_core -lz -pthread -o "$build_root/lrc-reference-client")
printf '%q ' "${command[@]}" > "$build_root/lrc-reference-build-command.sh"
printf '\n' >> "$build_root/lrc-reference-build-command.sh"
"${command[@]}"
sha256sum "$repo/scripts/trafficgen/lrc-reference-client.cc" "$build_root/lrc-reference-client" \
  > "$build_root/lrc-reference-build-hashes.txt"
