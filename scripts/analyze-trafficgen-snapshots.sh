#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_file="$script_dir/trafficgen-snapshot-analyzer.cc"
binary="${TMPDIR:-/tmp}/trafficgen-snapshot-analyzer-${UID}"
compiler="${CXX:-/home/prashanth/chipyard/.conda-env/bin/x86_64-conda-linux-gnu-c++}"

wire_header="$script_dir/../generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen_socket_protocol.h"
if [[ ! -x "$binary" || "$source_file" -nt "$binary" || "$wire_header" -nt "$binary" ]]; then
  "$compiler" -std=c++20 -O2 "$source_file" -o "$binary" -lboost_serialization
fi

exec "$binary" "$@"
