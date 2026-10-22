# Ampere model/bridge host optimization

This branch starts at Chipyard `680bb8023cfa8f1cb0147b360d87f5f6d037f4fc`.
Its source tree is identical to the prior Ampere baseline
`1461d707dda94050e0ca185a9645a0b8daa74350`; the different commit ID comes from the
consolidated published history. It is the last Ampere-only commit before the
Hopper import in that history.

The target remains `FireSimQuadRocketDualGemminiSaturnTrafficGenConfig`: four
Rockets, Saturn on every hart, FP32 and INT8 Gemmini on hart 0, and sixteen
TrafficGen lanes. All processor/accelerator submodule pins, RTL, MMIO register
layout, DMA record formats and protocol-v2 serialized fields are unchanged.
Gemmini, inclusive-cache and FireSim URLs point to the published forks containing
the existing pinned commits.

The host driver reuses aligned upload/issued/completed buffers, retains lane
vectors, caches bundle ownership validation per upload, removes duplicate
annotation work, moves reservation maps, and enables TCP_NODELAY. Optional
`+trafficgen-log-mode=summary` suppresses full round snapshots and verbose
messages while preserving lifecycle/control CSVs and a compact `summary.json`.
Detailed logging remains the default. Both the driver and matching model enforce
a 16,384 distinct-launch session budget and reject stale launch identities.

Use GPU model branch `trafficgen-ampere-optimized` (base `0e8527e3383343eb567f2a9a3001460198abeb53`).
Its socket protocol stays at version 2; the driver update is a host optimization,
not a new wire protocol. An original Ampere driver remains compatible.

## Reproduce checks

Initialize the pinned submodules and the normal Chipyard environment/toolchain.
Use Boost 1.85.0 for both model and driver. Build the optimized model according to
its `docs/ampere-optimized.md`, then:

```sh
GPU_MODEL_ROOT=/path/to/gpu_model BOOST_ROOT=/path/to/boost-1.85 \
  bash scripts/test-trafficgen-socket-v2.sh
bash scripts/test-trafficgen-lane-assignment.sh
bash scripts/test-trafficgen-bundle-recovery.sh
```

Build the original target using the normal FireSim make targets (`verilog`,
`verilator`, or the platform driver); no implementation-flow changes are needed.
`scripts/xrsight-trafficgen/check-elaboration.py --output /path/to/elaboration.json`
verifies the emitted SoC and Gemmini software headers. The inherited combined
smoke runner exercises four harts, vectors, both Gemmini arrays, and four launches.

Build both launch fixtures from the pinned FireMarshal bare-metal runtime:

```sh
python3 scripts/trafficgen/ampere-optimized/build-firmware.py --output /path/to/firmware
```

Use `rodinia.elf` for the single launch and `repeat/repeat.elf` for eight launches.
Create a registry whose ID 1106 points to kernel 1 in
the shared capture; add ID 1107 for kernel 2 when using `rodinia-repeat.c`.

```sh
python3 scripts/trafficgen/ampere-optimized/run-metasim.py \
  --sim /path/to/VFireSim --model /path/to/gpu_model_socket \
  --firmware /path/to/firmware/rodinia.elf --config /path/to/gpu_model/configs/mobile8.config \
  --registry /path/to/registry.json --output /path/to/new-run --ids 1106
```

To exercise eight alternating launches, select `repeat/repeat.elf` and pass
`--ids 1106 1107 1106 1107 1106 1107 1106 1107`. Repeat with `--summary` and an
optional `--cache-plan` to check compact output and retired contexts.

Run one heavy build/validation at a time, four workers, at least 24 GiB available
RAM and 100 GiB free scratch. Stop on a process reaching 64 GiB RSS or a new OOM.
These tests use metasim only. They do not program or interrupt an FPGA workload.

The six-panel plot helper accepts the captured kernel directory, the standalone
prediction CSV, and the metasim scheduler's `issued_accesses.csv`. It checks exact
request identities before plotting and labels the RTL column as metasim.
