#!/usr/bin/env bash
set -eo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=${L2_PERF_WORK:-$(dirname -- "$repo")}
source /home/prashanth/miniforge3/etc/profile.d/conda.sh
source "$repo/env.sh"
export CY_DIR="$repo" FS_DIR="$repo/sims/firesim"
export FIRESIM_ENV_SOURCED=1 FIRESIM_SOURCED=1
export PATH="$repo/.conda-env/bin:/home/prashanth/chipyard/.vecadd-sim/espresso-build:/ecad/tools/xilinx/Vivado/2022.1/bin:$PATH"
export TMPDIR="$work/tmp"
export COURSIER_CACHE=/scratch/prashanth_chipyard_rtl_fixes_20261002T224801Z/validation/cache/coursier/v1
export SBT_OPTS="-Dsbt.supershell=false -Dsbt.server.forcestart=true"
export TRAFFICGEN_BOOST_LIBS=/scratch/prashanth_ampere_optimized_20261021/build/boost-libs
export LD_LIBRARY_PATH="$TRAFFICGEN_BOOST_LIBS:$repo/.conda-env/lib:${LD_LIBRARY_PATH:-}"
exec "$@"
