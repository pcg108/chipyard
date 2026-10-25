#!/usr/bin/env bash
# Invoked only underneath controller.py's single resource guard.
set -eo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=${L2_PERF_WORK:-$(dirname -- "$repo")}
target=FireSimQuadRocketInt8GemminiSaturnTrafficGenL2PerfConfig
make_args=(-C "$repo/sims/firesim/sim" PLATFORM=xilinx_alveo_u250 TARGET_PROJECT=firesim
 TARGET_PROJECT_MAKEFRAG="$repo/generators/firechip/chip/src/main/makefrag/firesim"
 DESIGN=FireSim TARGET_CONFIG="$target" PLATFORM_CONFIG=BaseXilinxAlveoU250Config
 "VERILATOR_MAKEFLAGS=-j4 VM_PARALLEL_BUILDS=1")
case "${1:-}" in
 elaborate) exec make "${make_args[@]}" verilog ;;
 metasim) exec make "${make_args[@]}" 'EXTRA_VERILATOR_FLAGS=-Wno-WIDTHEXPAND -Wno-UNSIGNED' verilator ;;
 driver) exec make "${make_args[@]}" xilinx_alveo_u250 ;;
 bitstream) exec python3 "$repo/scripts/xrsight-trafficgen/firesim_manager.py" --chipyard "$repo" -- \
   buildbitstream -b "$work/control/config_build.yaml" -r "$work/control/config_build_recipes.yaml" \
   -a "$work/control/config_hwdb.yaml" ;;
 *) echo 'Expected elaborate, metasim, driver, or bitstream' >&2; exit 2 ;;
esac
