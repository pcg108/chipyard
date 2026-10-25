# Ampere L2 performance counters: validation and 30 MHz build

This directory drives the isolated quad-Rocket, four-Saturn, INT8-Gemmini,
16-lane Ampere TrafficGen target. It never programs or queries the FPGA.

Run `controller.py --work /scratch/prashanth_ampere_l2_perf_20261025` from the
isolated checkout. The controller runs each heavy validation/build job under
one guard, with four workers, 24 GiB available-memory floor, 64 GiB per-process
RSS ceiling, and 100 GiB free scratch before each stage. A new OOM or resource
stop is latched. A failed stage stops the sequence; it is never a fit result or
automatic retry. `--stop-after STAGE` supports a scheduled handoff. Explicit
`--resume` accepts previously passed stages only for the same source identity.
After an authorized instrumentation fix, `--resume --refresh-source` archives
and invalidates earlier validation. Completed processor regressions are retained
only when their own processor-source and test-source fingerprint still matches.
Changing source after FPGA implementation starts requires a fresh workspace.

The stages include independent cache/bridge RTL tests, the real C++ collector,
inherited processor regressions, protocol/lane/recovery checks, elaboration,
full target metasim, INT8 and per-hart vector smoke, Rodinia NN1 with real MMIO
markers, and four isolated/concurrent set-placement experiments. Only after
all pass does the controller build the matching driver and start a fresh
30 MHz NORETIMING U250 implementation. A synthesis gate records utilization
before placement and rejects hard resource overflow. Timing failure stops
without automatic implementation recovery or hardware edits.

Live state is `control/status.json`, exact commands are `control/stages.json`,
stage logs are in `logs`, and each guard records `memory.jsonl` and its result.
Final packaging checks setup, hold, pulse width, complete routing, route errors,
bus skew, DRC, the requested clock, and allowed platform reset-pin exceptions.

`l2_perf.h` documents the single-coordinator target interface. START and STOP
define `[START,STOP)`, SNAPSHOT leaves the active window/counters intact, and
DRAIN_END records the separate tail. Markers are acknowledged in target MMIO;
64-bit counter vectors are collected on the host. Backend classes combine all
plugins sharing that backend. Shared stalls and victim ownership are not
assigned to plugins. `measurement-example.c` shows integration points.

The existing model binary is accepted only with its pinned source revision and
the hash of its earlier successful integration run. No active experiment,
hardware database or runtime configuration is changed.
