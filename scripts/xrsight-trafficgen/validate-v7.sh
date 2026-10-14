#!/usr/bin/env bash
set -eo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=${XRSIGHT_TG_WORK:-/scratch/prashanth_xrsight_trafficgen_v7_20261013}
source "$root/env.sh"
export GPU_MODEL_ROOT="$work/model-src" GPU_MODEL_BUILD="$work/model-build"
export BOOST_ROOT="$root/.conda-env" TMPDIR="$work/tmp" CXX=/usr/bin/c++
export LD_LIBRARY_PATH="$work/model-build/boost-libs:${LD_LIBRARY_PATH:-}"
export PATH="$root/.conda-env/bin:$PATH"
if [[ "$1" == model || "$1" == cpp ]]; then
 export PATH="/usr/bin:/bin:$PATH"
fi
case "$1" in
 model)
  mkdir -p "$GPU_MODEL_BUILD/boost-libs"
  ln -sf "$BOOST_ROOT/lib/libboost_serialization.so.1.85.0" "$GPU_MODEL_BUILD/boost-libs/libboost_serialization.so"
  ln -sf "$BOOST_ROOT/lib/libboost_serialization.so.1.85.0" "$GPU_MODEL_BUILD/boost-libs/libboost_serialization.so.1.85.0"
  /usr/bin/cmake -S "$GPU_MODEL_ROOT" -B "$GPU_MODEL_BUILD" -DCMAKE_CXX_COMPILER=/usr/bin/c++ \
   -DCMAKE_BUILD_TYPE=Release -DBOOST_ROOT="$BOOST_ROOT" -DBOOST_LIBRARYDIR="$GPU_MODEL_BUILD/boost-libs" -DBoost_NO_BOOST_CMAKE=ON
  /usr/bin/cmake --build "$GPU_MODEL_BUILD" -j4
  /usr/bin/ctest --test-dir "$GPU_MODEL_BUILD" --output-on-failure
  bash "$root/scripts/trafficgen/build-tma-reference.sh" "$GPU_MODEL_ROOT" "$GPU_MODEL_BUILD"
  /usr/bin/c++ -std=c++17 -O2 -Wall -Wextra -Werror -I"$GPU_MODEL_ROOT/src/include" -I"$BOOST_ROOT/include" \
   "$root/scripts/trafficgen/export-tma-policies.cc" -L"$BOOST_ROOT/lib" -Wl,-rpath,"$BOOST_ROOT/lib" \
   -lboost_serialization -o "$GPU_MODEL_BUILD/export-tma-policies"
  ;;
 cpp)
  bash "$root/scripts/test-trafficgen-socket-v7.sh"
  bash "$root/scripts/test-trafficgen-lane-assignment.sh"
  bash "$root/scripts/test-trafficgen-bundle-recovery.sh"
  ;;
 rtl)
  cd "$root"
  java -jar scripts/sbt-launch.jar 'chipyard/testOnly chipyard.example.LrcCompletionMonitorSpec chipyard.example.LrcNativeFenceSpec chipyard.example.TrafficGenPutGetSpec chipyard.example.TrafficGenPutGetL2Spec chipyard.example.TrafficGenTLControlSpec chipyard.example.TrafficGenTargetCycleSpec chipyard.example.TmaWakeMonitorSpec chipyard.example.TmaWakeMonitorAdversarialSpec chipyard.example.TmaWakeMonitorFailureSpec'
  java -jar scripts/sbt-launch.jar 'firechip_bridgestubs/testOnly firechip.bridgestubs.TrafficGenChannelSpec'
  ;;
 *) exit 2 ;;
esac
