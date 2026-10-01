# Single-kernel compatibility verification

These tools compare a complete legacy hello → RTL → bridge → GPU-model run
against the new implementation. The reference commits are Chipyard
`d07370a293b15cbc347932a7745a36d6a6456585` and GPU model
`e345d5618078e77200697055d944d3ff9575c804`.

A successful process exit, equal request count, or similar PNG is insufficient.
Acceptance requires independent request-identity audits and exact differential
comparison of the observed schedules, issues, lanes, and feedback exchanges.

The [completed validation report](RESULTS.md) includes all ten exact
single-kernel comparisons, native multi-launch audits, the real quartet
timeline, and the limits of the timing claim.

## Frozen inputs and baseline provenance

The completed experiment's local evidence archive lives under the following
paths. These datasets, binaries, and generated reports are not shipped in Git;
the implementation and reproduction tools are now on `trafficgen-target-cycle`
in the normal Chipyard and GPU-model checkouts.

```text
/home/prashanth/chipyard/.worktrees/trafficgen-compat/
  chipyard/                         implementation and these scripts
  gpu_model/                        candidate model
  state/
    baseline-gpu/                   unmodified git archive of e345d561
    baseline-gpu-build/             freshly built legacy model
    baseline-gpu-provenance.json    source, compiler, dependency, binary hashes
    VFireSim-legacy-logs            historical simulator with log paths relocated
    VFireSim-legacy-logs.relocation.json
    frozen-configs/                 scale 1 and 1.5 configs and their hashes
    alias-fixture/                  frozen disjoint datasets with reused UIDs
    oracle-alias-scale*/            complete legacy synthetic runs
    oracle-real-ID-scale*/          complete legacy real-workload runs
    legacy-oracle-summary.json      synthetic reference summary
    real-oracle-summary.json        audited real references and scope limitations
/scratch/trafficgen-compat-20261001/
  build/rtl/VFireSim                candidate simulator
  candidate-alias-scale*/           candidate runs and comparison.json
  candidate-real-ID-scale*/         candidate runs and comparison.json
```

The historical simulator is
`/scratch/prashanth_firesim_stage1_20260914/sim_slot_0/VFireSim-debug`.
Its SHA256 is
`e8163d36a61e4b8bfca9733297bbf08e6832122a5f0380b03c5608e31e31ab09`,
matching `analysis/kernel26_stage1_20260914/metasim_binary.json` in the original
Chipyard checkout. That archive's `source_after.json` matches d07370a for the
traffic generator engine, interface, module, driver, target configuration, and
Put/Get tests. The archived build.sbt differs; this is evidence for the preserved
binary, not a claim of a newly reproduced historical Scala build.

The old driver clears a hard-coded supplemental log directory. Never run the
original simulator directly for this experiment. `relocate-legacy-logs.py`
produces a separate copy, replacing only two NUL-terminated log-path literals.
The current copy changes 103 bytes, verifies that every other byte is identical,
and records offsets, old/new strings, and both file hashes. Runtime code and
original logs remain unchanged. `run-legacy-oracle.py` requires and checks that
relocation provenance.

Both sides of each strict single-kernel comparison use the same historical ELF:

```text
/scratch/prashanth_firesim_stage1_20260914/sim_slot_0/trafficgen-hello0-hello
```

To recreate the reference model in a fresh location, export the exact commit
and build with the local dependencies (the existing `state/baseline-gpu` is
already frozen and must not be overwritten):

```bash
compat_fresh=/tmp/trafficgen-reference-new
mkdir -p "$compat_fresh/source"
git -C /home/prashanth/gpu_model archive e345d5618078e77200697055d944d3ff9575c804 \
  | tar -x -C "$compat_fresh/source"
env -u LD_LIBRARY_PATH /home/prashanth/chipyard/.conda-env/bin/cmake \
  -S "$compat_fresh/source" -B "$compat_fresh/build" \
  -DCMAKE_CXX_COMPILER=/usr/bin/c++ -DCMAKE_BUILD_TYPE=Release \
  -DBoost_NO_BOOST_CMAKE=ON \
  -DBOOST_INCLUDEDIR=/home/prashanth/chipyard/.conda-env/include \
  -DBOOST_LIBRARYDIR=/home/prashanth/gpu_model/build/boost-libs \
  -DOpenCV_DIR=/usr/lib/x86_64-linux-gnu/cmake/opencv4
/home/prashanth/chipyard/.conda-env/bin/cmake --build "$compat_fresh/build" \
  --target gpu_model_socket gpu_model -j2
```

To create another isolated simulator copy, use `relocate-legacy-logs.py` with
the preserved simulator, a fresh destination, a short fresh `--log-root` path,
and `--expected-sha256` set to the SHA256 above. Keep its generated sibling
`.relocation.json` file. This is log relocation only; it does not rebuild RTL.

The candidate runner's `--legacy-registry-id` option passes the driver's
`+trafficgen-legacy-registry-id` bootstrap argument, selecting the registry
entry while retaining that ELF and its CPU activity. Native ABI-v3 single, pair,
four, and slot-reuse hellos are separate functional tests; do not substitute one
into a strict historical timing comparison.

The frozen working mobile8 config uses `issueLatencyScale = 1`; the e345 config
uses `1.5`. Their only byte difference is this value. Run both independently;
never retune one side to match the other.

## Required matrix

| Input | Registry | Requests per launch | Scales |
| --- | --- | ---: | --- |
| Two-SM/two-scheduler alias fixture | 1106 in fixture registry | 128 | 1, 1.5 |
| Rodinia nn smaller, kernel 1 | 1106 in real registry | 21,563 | 1, 1.5 |
| ResNet smaller, kernel 1 | 1003 | 2,746 | 1, 1.5 |
| ResNet smaller, kernel 26 | 1021 | 100,352 | 1, 1.5 |
| ResNet accelsim2, kernel 10 | 1054 | 444,416 | 1, 1.5 |

Kernel names and recorded IDs are not dataset identities. In particular, real
registry 1054 is the accelsim2 kernel 10, not smaller-collection kernel 10.
Read generated summaries and each candidate's `comparison.json` for current
results; this matrix is the requirement, not a list of asserted passes.

## Run and decode

Commands below run from the Chipyard repository root and use the retained local
evidence archive. Choose fresh output directories;
the runners and exporter refuse to overwrite existing results. All runs have
explicit cycle, round, and wall-clock limits. A timeout, truncation, missing
request, or incomplete drain is not a passing comparison.

```bash
compat_state=/home/prashanth/chipyard/.worktrees/trafficgen-compat/state
compat_hello=/scratch/prashanth_firesim_stage1_20260914/sim_slot_0/trafficgen-hello0-hello
compat_scripts=scripts/trafficgen/compat
compat_registry=../gpu_model/configs/kernel_registry.json

python3 "$compat_scripts/run-legacy-oracle.py" "$compat_state/example-old-1106" \
  --simulator "$compat_state/VFireSim-legacy-logs" \
  --scheduler "$compat_state/baseline-gpu-build/gpu_model_socket" \
  --hello "$compat_hello" --kernel-registry "$compat_registry" --registry-id 1106 \
  --config "$compat_state/frozen-configs/mobile8.working-20261001.config" \
  --scale 1 --max-rounds 100000 --max-cycles 1000000 --timeout 1200

python3 "$compat_scripts/run-candidate.py" "$compat_state/example-new-1106" \
  --simulator /scratch/trafficgen-compat-20261001/build/rtl/VFireSim \
  --scheduler ../gpu_model/build-chipyard-boost/gpu_model_socket --hello "$compat_hello" \
  --kernel-registry "$compat_registry" --legacy-registry-id 1106 --case single \
  --config "$compat_state/frozen-configs/mobile8.working-20261001.config" \
  --scale 1 --max-rounds 100000 --max-cycles 1000000 --timeout 1200 --port 50052
```

The old binaries use port 50051. Candidate runs can use another free port.
Use `mobile8.e345.config` and `--scale 1.5` for the second real-data scale. The
largest workload uses a 5,000,000-cycle bound, with 3600 seconds at scale 1 and
7200 seconds at scale 1.5. Increase an inadequate bound in a fresh rerun; do not
accept partial results.

For concurrent historical references, the optional test-only
`redirect-loopback-port.c` preload wrapper redirects only IPv4
`127.0.0.1:50051` bind/connect calls. It changes no protocol bytes, scheduling,
RTL, or timestamps. Build and exercise it with:

```bash
/usr/bin/cc -std=c11 -Wall -Wextra -Werror -O2 -fPIC -shared \
  "$compat_scripts/redirect-loopback-port.c" -ldl \
  -o "$compat_state/redirect-loopback-port.so"
python3 "$compat_scripts/test-port-shim.py" "$compat_state/redirect-loopback-port.so"
```

Pass `--port 50056 --port-shim "$compat_state/redirect-loopback-port.so"` to
`run-legacy-oracle.py`. Its provenance records the shim hash and exact preload
environment. Each concurrent reference needs its own log-relocated simulator
copy and supplemental log root; a distinct socket port alone does not isolate
the old driver's log clearing. The runner holds an exclusive lock on that
supplemental log root to reject accidental concurrent reuse. Validate redirected alias and real registry
1003 against their unredirected complete references at zero offset before
using this transport isolation for additional oracles. The current validation
directories are `state/oracle-redirect-alias-scale1` and
`state/oracle-redirect-real-1003-scale1`; inspect their comparison JSON results.

For the synthetic case, replace the real-registry/config arguments with
`--fixture "$compat_state/alias-fixture" --scale 1` (or `1.5`); the candidate
still requires `--legacy-registry-id 1106 --case single` and the same old ELF.
`generate-fixtures.py NEW_DIRECTORY` creates a new fixture. Never regenerate
the frozen fixture midway through a comparison. Its manifest records hashes.

Compile `export-snapshots.cc` separately against each snapshot producer's
headers and static core library. Do not decode old snapshots using new layouts.
For example, with `compat_model` and `compat_build` set to the matching model
source and build directories:

```bash
/usr/bin/c++ -std=c++17 -O2 -Wall -Wextra -Werror \
  -I "$compat_model/src/include" \
  -I "$compat_model/third_party/accel_sim_trace_parser" \
  -I "$compat_model/third_party/addrdec" \
  -I /home/prashanth/chipyard/.conda-env/include \
  -I /usr/include/opencv4 \
  "$compat_scripts/export-snapshots.cc" "$compat_build/libgpu_model_core.a" \
  -L /home/prashanth/gpu_model/build/boost-libs -lboost_serialization \
  -Wl,-rpath,/home/prashanth/gpu_model/build/boost-libs \
  -lopencv_imgcodecs -lopencv_imgproc -lopencv_core -lz -o "$compat_exporter"
```

Rebuild the candidate exporter after changes to serialization. Then:

```bash
"$compat_state/export-baseline" "$compat_state/example-old-1106/bridge" \
  "$compat_state/example-old-1106/export"
"$compat_state/export-candidate" "$compat_state/example-new-1106/bridge" \
  "$compat_state/example-new-1106/export"

python3 "$compat_scripts/audit-registry.py" "$compat_state/example-old-1106/export" \
  --registry "$compat_registry" --legacy-registry 1106 \
  --output "$compat_state/example-old-1106/identity-audit.json"
python3 "$compat_scripts/audit-registry.py" "$compat_state/example-new-1106/export" \
  --registry "$compat_registry" \
  --output "$compat_state/example-new-1106/identity-audit.json"

python3 "$compat_scripts/compare-single.py" "$compat_state/example-old-1106/export" \
  "$compat_state/example-new-1106/export" \
  --output "$compat_state/example-new-1106/comparison.json"
python3 "$compat_scripts/test-comparison.py"
```

Synthetic runs use `audit-fixture.py` with the frozen fixture's `expected.json`;
consult `--help` for its arguments. Request audits independently read the actual
recorded requests and verify launch-qualified UID, address, load/store type,
SM/scheduler/warp identity, full scheduling and issuing, and no issue before its
schedule. A request audit alone does not establish historical timing parity.

## Exact comparison contract and limits

The default comparison requires all observed lane CSVs. It checks complete
per-UID schedule and adapter-FIFO issue records, their cycles and ordering,
cohort membership and introduction round, lane/member counts, reservations,
blocked warps, min-issue deadlines, round-return cycles, and completion reports.
Opaque handle numbers may differ; their membership may not. The normal matrix
uses zero cycle offset. An explicitly justified fixed target-epoch offset can
be supplied, but the tool never fits an offset and applies it to every cycle.
`--scheduler-only` is a narrower model check, not RTL acceptance.

Legacy bundle ordinals intentionally alias across schedulers and can be reused
in later rounds. Historical completion records lack a generation. Consequently
the comparator cannot recover which of two outstanding legacy generations a
raw ordinal completed. It instead requires an exact cohort-membership and
introduction-round bijection, maps each candidate physical handle through that
bijection to its legacy ordinal, and checks the exact completion
`(round, order, ordinal)` sequence. It independently rejects candidate physical
handle reuse, unknown/duplicate/missing completions, and completion before all
members issued. A pass establishes the observable historical wire behavior;
it does not invent missing historical generation information.

Snapshots do not contain each individual memory-response timestamp. Separate
RTL tests must prove response-based all-member completion and store draining.
Native multi-kernel tests must also prove launch/registry isolation, overlapping
and sequential submissions, slot reuse, resource residency, compute tails, and
the session drain contract. Single-run wall times under concurrent builds and
simulations are observations, not controlled host-performance ratios.

The earliest frozen alias fixture omits optional reference `type=READ/WRITE`
labels. Its PNG accuracy summary therefore labels 64 stores incorrectly; the
actual instruction types and independent request audit are correct. Updated
fixture generation includes those labels. Preserve the frozen input and rely
on exact exported records for compatibility, not that PNG summary.
