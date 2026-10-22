# Ampere host optimization validation

Validated on illixr, October 21–22, 2026. **Metasim only; the running Hopper FPGA workload was not accessed.** No bitstream was rebuilt or programmed.

## Source and hardware compatibility

Chipyard base: `680bb8023cfa8f1cb0147b360d87f5f6d037f4fc`, the last Ampere-only commit in the consolidated published history. Its tree (`97358afef0bcb0e6fa159e7e41a4f0200fb987c7`) is identical to historical `1461d707dda94050e0ca185a9645a0b8daa74350`. Model base: `0e8527e3383343eb567f2a9a3001460198abeb53`.

Tested production source commits: Chipyard `524d7c90d`, model `659e316fa`; subsequent commits add documentation/evidence only. Both new branches are named `trafficgen-ampere-optimized`.

The target remains `FireSimQuadRocketDualGemminiSaturnTrafficGenConfig`: four Rockets, four Saturn units, FP32 and INT8 Gemmini on hart 0, sixteen TrafficGen lanes, eight memory channels, and the original processor/cache/memory parameters. All submodule gitlinks, RTL, register layout, DMA format, and socket protocol-v2 serialized fields remain unchanged. Launch ABI is 3. No Hopper features or frequency changes were ported.

| Dependency | Preserved commit |
|---|---|
| Rocket | `2c0e4784c46f1a67ef39699c7d67aef65e3ea8c0` |
| Saturn | `9e04c8c6c70a4b4db5989f9a16a89679183a5d1d` |
| Shuttle | `e789aa207148ade9eaed79f12841102597f5a0a5` |
| Gemmini | `8d6d83b7a68f30ab971c8a4e78083f2bda1c9c92` |
| Inclusive cache | `9242577cfa92e7c8f411ffa724ce55cf87bb9063` |
| FireSim | `7dd0b16ec97c0d02efcb47aa431d9a949dbb026b` |
| FireMarshal | `21119e5ce922ff9302f0bc9d2d7349c77f7ac064` |

Gemmini, inclusive-cache, and FireSim URLs now point to the published forks containing their existing commits. All seven pins above were fetched in a new checkout from their declared remotes. Independent shallow source clones (without Git alternates) built the buffered model and driver protocol/session/sanitizer tests. The full SoC elaboration/metasim build used the isolated pinned checkout and the host's existing tool environment; a completely new toolchain installation was not tested.

Generated `FireSim-generated.const.h` is byte-identical to the original Ampere build (SHA256 `5cced55274e1a5ab0ab89abe065d5ab73fde7bb8bd14782c1626bacb0be6a6f6`). The implementation and synthesis XDCs also match. Generated Verilog is identical after substituting only absolute checkout root strings in source-location comments (normalized SHA256 `9a3935b590c4be0a7de76548736e58701226727cb3b4a80141faee926e4c2b99`).

## Functional validation

- Original model: 20/20 tests; optimized stream, buffered, and double-buffered builds: 28/28 each, with assertions enabled.
- Driver protocol/session tests, summary mode, lane assignment, bundle recovery, and 3,000 randomized scratch-buffer operations under ASAN/UBSAN passed.
- Fresh RTL/metasim build and emitted topology checks passed.
- Combined target smoke passed: all four harts, Saturn integer/floating-point vectors and tails, eight alternating FP32/INT8 matrix rounds, guarded outputs, and four concurrent TrafficGen launches.
- Shared Rodinia NN kernel 1 passed all four old/new pairings below. Names list the model first, bridge driver second. Every request UID/address/read-write identity matched the capture. 451 detailed artifacts per pairing—including round inputs/outputs, request issue cycles, lifecycle cycles, and controls—were byte-identical to baseline.

| Model–driver pairing | Reads | Writes | Scheduling rounds | Metasim wall seconds |
|---|---:|---:|---:|---:|
| baseline-baseline | 19,688 | 1,875 | 56 | 137.33 |
| optimized-baseline | 19,688 | 1,875 | 56 | 137.14 |
| baseline-optimized | 19,688 | 1,875 | 56 | 127.27 |
| optimized-optimized | 19,688 | 1,875 | 56 | 119.89 |

These metasim durations are single functional runs, not a driver-speed benchmark.

Eight sequential launches alternating Rodinia kernels 1 and 2 also passed in detailed and summary modes. Lifecycle/target cycles and controls matched exactly. Both modes reported 157,504 reads, 15,000 writes, and 315 scheduling rounds. All eight contexts retired; active contexts, pending requests, and pending bundles were zero at completion. All 288 captured input files retained their SHA256 hashes.

## Host runtime experiment

Three fresh-process repetitions per variant/mode, rotating variant order, on CPUs 4–7. Inputs were warmed by transcript validation. Each full serialized validation transcript was byte-identical across variants. The baseline build had benchmark harness/CMake additions only; its production model sources were unchanged. Feedback used fixed **37-cycle synthetic ordinary-memory completion**, so these measurements do not measure FPGA throughput or FireSim driver speed. Other work was running on the host.

| Feedback transport | Original median seconds | Optimized stream median seconds | Optimized double-buffered median seconds | Original / double-buffered |
|---|---:|---:|---:|---:|
| local | 2.1195 | 0.4152 | 0.4152 | 5.10× |
| tcp | 3.0227 | 0.4162 | 0.4162 | 7.26× |

The benchmark JSON uses inherited labels `before`, `nodelay`, and `buffered`; here `nodelay` means **all host optimizations with the stream reader**, and `buffered` means all optimizations with the double-buffered reader. It does not isolate TCP_NODELAY alone. Results from one small kernel do not establish speedups for large sweeps or cold trace loading. Detailed logging was used for functional metasim comparisons; summary mode was validated separately.

## Comparison plot

Six panels show loads/stores for captured ground truth, standalone software, and FireSim RTL **metasim**. Request identities agree exactly; timing predictions need not agree because standalone replay uses captured completion latencies and metasim uses RTL/cache/DRAM feedback. Each timeline is shifted by its first access across both reads and writes; the plot is not an absolute launch-latency comparison.

| Source | Requests | First-to-last access span (cycles) |
|---|---:|---:|
| Captured ground truth | 21,563 | 17,723 |
| Software traffic generator | 21,563 | 10,516 |
| FireSim RTL metasim | 21,563 | 9,917 |

## Reproduction and artifacts

Use the branch build guide, Boost 1.85.0, and `scripts/trafficgen/ampere-optimized/` in Chipyard. Model builds used GCC 10.5, CMake 3.22.1, and OpenCV 4.5.4; metasim used Verilator 5.022 and the pinned Chipyard Conda toolchain. Builds and validation were sequential, at most four workers, with 24 GiB minimum available host RAM, 64 GiB maximum RSS per process, a new-OOM stop condition, and 100 GiB minimum free scratch before jobs.

The local experiment is `/scratch/prashanth_ampere_optimized_20261021`. `results/` contains detailed evidence and the PNG/PDF comparison; `deliverables/` contains the host driver/model archives, runtime configuration, Gemmini headers, fixtures, source/tool/dependency manifests, resource telemetry summary, and SHA256 checksums. The existing Ampere bitstream is referenced, not copied or retested on hardware. Use the original Ampere protocol-v2/ABI-3 image; the active Hopper image is incompatible.
