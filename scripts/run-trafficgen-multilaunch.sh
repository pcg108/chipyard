#!/usr/bin/env bash
# Build/run one RTL v2 session, keeping generated artifacts and traces isolated.
set -eo pipefail
if [[ $# -ne 2 || ! $1 =~ ^(pair|four|reuse|synthetic)$ ]]; then
  echo "usage: $0 {pair|four|reuse|synthetic} OUTPUT_DIR" >&2
  echo "Optional: TRAFFICGEN_BUILD_ROOT, GPU_MODEL_ROOT, GPU_MODEL_BUILD, TRAFFICGEN_SKIP_BUILD=1" >&2
  exit 2
fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
case_name=$1
mkdir -p "$2"
run_root=$(realpath "$2")
if [[ -e "$run_root/scheduler.log" || -e "$run_root/metasim.log" ]]; then
  echo "Use a fresh output directory; refusing to overwrite previous run logs." >&2
  exit 2
fi
gpu_root=${GPU_MODEL_ROOT:-$(dirname "$repo")/gpu_model}
gpu_build=${GPU_MODEL_BUILD:-$gpu_root/build-chipyard-boost}
# Resolve caller-relative overrides before sourcing FireSim from another cwd.
gpu_root=$(realpath "$gpu_root")
gpu_build=$(realpath "$gpu_build")
if [[ ! -x "$gpu_build/gpu_model_socket" ]]; then
  echo "Missing scheduler executable: $gpu_build/gpu_model_socket" >&2
  exit 1
fi
build_root=${TRAFFICGEN_BUILD_ROOT:-$run_root/build}
mkdir -p "$build_root"
build_root=$(realpath "$build_root")
# sourceme-manager initializes the existing Chipyard toolchain without SSH changes.
cd "$repo/sims/firesim"
source sourceme-manager.sh --skip-ssh-setup
set -euo pipefail
export JAVA_TOOL_OPTIONS="-Xmx24G -Xss8M -Djava.io.tmpdir=$build_root/java_tmp"
mkdir -p "$build_root/java_tmp"
make_args=(PLATFORM=xilinx_alveo_u250 TARGET_PROJECT=firesim
  "TARGET_PROJECT_MAKEFRAG=$repo/generators/firechip/chip/src/main/makefrag/firesim"
  DESIGN=FireSim TARGET_CONFIG=FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig
  PLATFORM_CONFIG=BaseXilinxAlveoU250Config "GENERATED_DIR=$build_root" "OUTPUT_DIR=$build_root/driver")
if [[ ${TRAFFICGEN_SKIP_BUILD:-0} != 1 ]]; then
  make -C "$repo/sims/firesim/sim" verilator "${make_args[@]}" > "$run_root/build.log" 2>&1
fi
if [[ ! -x "$build_root/VFireSim" ]]; then
  echo "Missing metasim executable: $build_root/VFireSim" >&2
  exit 1
fi
workload_case=$case_name
scheduler_config=$gpu_root/configs/mobile8.config
if [[ $case_name == synthetic ]]; then
  workload_case=four
  python3 "$repo/scripts/trafficgen/generate-synthetic-registry.py" "$run_root/synthetic"
  registry=$run_root/synthetic/registry.json
  scheduler_config=$run_root/synthetic/hardware.config
else
  registry=$run_root/registry.json
  python3 "$repo/scripts/trafficgen/make-registry.py" "$registry" --case "$case_name" --gpu-model "$gpu_root"
fi
make -C "$repo/software/firemarshal/example-workloads/trafficgen-hello" TEST_CASE="$workload_case" \
  CC="$repo/.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc" > "$run_root/workload-build.log" 2>&1
cp "$repo/software/firemarshal/example-workloads/trafficgen-hello/hello" "$run_root/hello"
if [[ -n $(ss -ltnH 'sport = :50051') ]]; then
  echo "Port 50051 is already listening; leave that scheduler alone and use this runner later." >&2
  exit 1
fi
scheduler_pid=
cleanup() {
  if [[ -n "$scheduler_pid" ]] && kill -0 "$scheduler_pid" 2>/dev/null; then
    kill -TERM "$scheduler_pid" 2>/dev/null || true
    wait "$scheduler_pid" || true
  fi
}
trap cleanup EXIT
(
  cd "$run_root"
  exec "$gpu_build/gpu_model_socket" -c "$scheduler_config" --kernel-registry "$registry" \
    --round-log-dir "$run_root/scheduler"
) > "$run_root/scheduler.log" 2>&1 &
scheduler_pid=$!
ready=0
for ((attempt=0; attempt<300; ++attempt)); do
  if ! kill -0 "$scheduler_pid" 2>/dev/null; then
    cat "$run_root/scheduler.log" >&2
    wait "$scheduler_pid"
    exit 1
  fi
  if [[ -n $(ss -ltnH 'sport = :50051') ]]; then ready=1; break; fi
  sleep 0.1
done
if [[ $ready != 1 ]]; then echo "Scheduler listen timed out" >&2; exit 1; fi
(
  cd "$run_root"
  "$build_root/VFireSim" +permissive +fesvr-step-size=128 +max-cycles=300000000 \
    +blkdev-in-mem0=128 +print-start=0 +print-end=-1 \
    "+trafficgen-round-log-dir=$run_root/bridge" +permissive-off "$run_root/hello" </dev/null
) > "$run_root/metasim.log" 2>&1
# A successful target must have closed and drained its scheduler session. Do
# not hang indefinitely here if a workload exits without finishing that exchange.
for ((attempt=0; attempt<100; ++attempt)); do
  if ! kill -0 "$scheduler_pid" 2>/dev/null; then break; fi
  sleep 0.1
done
if kill -0 "$scheduler_pid" 2>/dev/null; then
  echo "Scheduler did not exit within 10 seconds after metasim; inspect $run_root/scheduler.log" >&2
  exit 1
fi
wait "$scheduler_pid"
scheduler_pid=
"$repo/scripts/analyze-trafficgen-snapshots.sh" "$case_name" "$run_root/bridge" "$run_root/analysis"
python3 "$repo/scripts/trafficgen/check-session.py" "$run_root" --case "$case_name"
