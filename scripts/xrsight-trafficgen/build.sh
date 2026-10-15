#!/usr/bin/env bash
set -eo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=${XRSIGHT_TG_WORK:-"$root/.xrsight-build"}
mkdir -p "$work"
work=$(cd -- "$work" && pwd)
mkdir -p "$work/tmp" "$work/logs"
stage=${1:-elaborate}
source "$root/env.sh"
export FS_DIR="$root/sims/firesim"
export FIRESIM_ENV_SOURCED=1 FIRESIM_SOURCED=1
# The model tests prepend /usr/bin. Re-activating an already-active Conda
# environment can leave that ordering intact; restore the build environment.
export PATH="$root/.conda-env/bin:$work/espresso-build:$FS_DIR/deploy:$PATH"
export TMPDIR="$work/tmp"
export SBT_OPTS="-Dsbt.supershell=false -Dsbt.server.forcestart=true"
export COURSIER_CACHE=${COURSIER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/coursier/v1}
pkg="$root/scripts/xrsight-trafficgen"
target=FireSimQuadRocketDualGemminiSaturnTrafficGenConfig
make_args=(-C "$FS_DIR/sim" PLATFORM=xilinx_alveo_u250 TARGET_PROJECT=firesim
  TARGET_PROJECT_MAKEFRAG="$root/generators/firechip/chip/src/main/makefrag/firesim"
  DESIGN=FireSim TARGET_CONFIG="$target" PLATFORM_CONFIG=BaseXilinxAlveoU250Config
  "VERILATOR_MAKEFLAGS=-j4 VM_PARALLEL_BUILDS=1")
case "$stage" in
  elaborate) command=(make "${make_args[@]}" verilog) ;;
  # Stock Gemmini emits a narrow unsigned divisor; Saturn emits comparisons
  # that specialize to constants. Neither changes the intended arithmetic.
  metasim) command=(make "${make_args[@]}"
    "EXTRA_VERILATOR_FLAGS=--assert -Wno-WIDTHEXPAND -Wno-UNSIGNED" verilator) ;;
  driver) command=(make "${make_args[@]}" xilinx_alveo_u250) ;;
  bitstream)
    python3 "$pkg/configure-build.py" --work "$work"
    command=(python3 "$pkg/firesim_manager.py" --chipyard "$root" --
    buildbitstream -b "$work/config/config_build.yaml" -r "$work/config/config_build_recipes.yaml"
    -a "$work/config/config_hwdb.yaml") ;;
  *) echo "usage: $0 {elaborate|metasim|driver|bitstream}" >&2; exit 2 ;;
esac
attempt=$(date -u +%Y%m%dT%H%M%SZ)
printf '%q ' "${command[@]}" > "$work/$stage-command.sh"
printf '\n' >> "$work/$stage-command.sh"
python3 "$pkg/firesim_resource_guard.py" --scratch "$work" \
  --run-dir "$work/guard-$stage-$attempt" --latch "$work/STOPPED_NO_AUTORESTART.json" \
  --jobs 4 -- "${command[@]}" 2>&1 | tee "$work/logs/$stage-$attempt.log"
