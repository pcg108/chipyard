# TrafficGen protocol-v2 sessions

The RTL TrafficGen bridge uses one scheduler socket connection for a session.
Target software can submit additional kernels while earlier launches still have
memory requests outstanding. A launch identifies an invocation; a registry ID
identifies its recorded dataset. Request and warp IDs are qualified by launch ID.
Bundle IDs are opaque and unique across the session. A replay group preserves the old grouping of scheduler-local bundle ordinals
within one launch and one introduction round. Both load and store groups must
complete before that launch can release its reserved SM resources to another
launch. Within its reservation, a launch retains the original early CTA reuse
approximation and dependency wake-up behavior.

At a paused boundary, the driver validates the completion count and reads only
the valid prefix of the completion BRAM, rounded to a 64-byte beat. An empty
report performs no DMA. This avoids transferring the full 256 KiB buffer for
every short scheduling round while preserving target timing.

The driver also retains the last successfully written request payload for each
lane. It skips a request DMA only when the complete packed bytes and length are
unchanged; the target only reads that BRAM region. Every upload still writes its
counts and control metadata and performs the normal commit/start handshake.
The payload cache belongs to the driver session and is not reset by a new launch.

## Preserving the single-kernel baseline

This implementation starts from Chipyard `d07370a293b15cbc347932a7745a36d6a6456585`
and GPU model `e345d5618078e77200697055d944d3ff9575c804`. Each launch has its own
legacy SM and scheduler contexts. The session translates their local bundle
ordinals into unique replay-group IDs only at the socket boundary; IDs and
completion broadcasts never cross launch contexts. Group membership, lane
assignment order, scheduling rotation, and the old nonempty-scheduler-call
`min_issue_cycle` calculation are preserved for one launch.

The engine returns dependency feedback at the original cooperative boundary:
ready requests and completions can continue after a wake becomes pending.
Submission changes interrupt promptly. Bundle-table recovery and completion
report capacity remain separate safety boundaries. Completion DMA prefix reads
and byte-identical request upload caching reduce host work without changing
replay timing.

An SM resource reservation belongs to a launch until its modeled work on that
SM is exhausted, its physical replay groups complete, and its compute horizon
passes. Other launches can use resources outside that reservation. This is
conservative sharing; it deliberately retains the baseline's early CTA reuse
inside one launch while preventing early resource reuse by a different launch.

The [compatibility validation workflow](compat/README.md) compares the old and
new systems using the same historical hello ELF, exact trace identities, and
per-request timing. Aggregate completion time alone is not its acceptance test.
The [completed results](compat/RESULTS.md) include ten passing exact comparisons
and the full disjoint Rodinia/ResNet/render quartet audit.

## Target MMIO ABI

All offsets are relative to `0x5000`. Use 32-bit accesses and an I/O fence before
ringing a submit or close doorbell. The C definitions and working example are
versioned in `scripts/trafficgen/workloads/trafficgen-hello/trafficgen.h` and
`hello.c`. Run `scripts/install-trafficgen-workload.sh` from the Chipyard root
to install them into `software/firemarshal/example-workloads/`. This keeps the
workload in this repository without changing the upstream FireMarshal submodule.

| Offset | Register | Behavior |
| --- | --- | --- |
| `0x04` | Legacy start | One closed launch selected by the driver option below |
| `0x10` | Global done | Successful session completion only |
| `0x38`, `0x3c` | Session cycle, low/high | Monotonic after the first round; retained at termination |
| `0x48` | ABI version | `3` |
| `0x4c` | Launch slots | `4` for RTL; `0` for the unsupported DPI launch ABI |
| `0x50` | Session status | Flags below |
| `0x54` | Close submissions | Write bit 0; sticky and idempotent |
| `0x58` | Session errors | Sticky, write one to clear |

Slot `i`, for `i=0..3`, starts at `0x100 + 0x20*i`:

| Slot offset | Register | Behavior |
| --- | --- | --- |
| `0x00`, `0x04` | Registry ID, low/high | Writable staging words |
| `0x08` | Submit | Write bit 0 to snapshot the registry ID and allocate a launch |
| `0x0c` | Status | State below |
| `0x10`, `0x14` | Launch ID, low/high | Assigned nonzero 64-bit invocation ID |
| `0x18` | Slot errors | Sticky, write one to clear |
| `0x1c` | Submit and close | Atomically submit this slot and close future submissions on success |

Launch states are `FREE=0`, `QUEUED=1`, `SUBMITTED=2`, `ACCEPTED=3`,
`DISPATCHED=4`, `COMPLETE=5`, `REJECTED=6`, and `ABORTED=7`.
Queued through dispatched slots are occupied. Complete and rejected slots can
be submitted again while submission remains open. A submit to an occupied slot
sets slot-error bit 0; submitting after closure sets bit 1; unsupported DPI use
sets bit 2. Session-error bit 0 means the launch-ID allocator is exhausted and
bit 1 means unsupported DPI submission. Errors do not overwrite an active launch.
Changing a staged registry ID also does not change an active launch.

The bridge assigns `streamId = slot + 1`. Launch IDs increase across slot reuse;
the hardware allocator resets on target reset. A supported session starts with
fresh simulator/driver and scheduler processes. A runtime target-only reset does
not establish a new protocol session. Host statuses are tagged by launch ID and
published atomically, so an old completion cannot affect a reused slot.
`ACCEPTED` means the scheduler accepted the launch. `DISPATCHED` means all its
CTAs have been admitted; it does not indicate memory completion. Only `COMPLETE`
allows software to treat a kernel as finished.

Session-status bits are `CLOSED=0`, `CLOSE_SENT=1`, `IDLE=2`, `COMPLETE=3`,
`TRUNCATED=4`, and `ERROR=5`. Close prevents further target submissions and the
next control forwards every already-queued launch before closing server
submission. Continue polling until session `COMPLETE`, or fail on
`TRUNCATED`/`ERROR`. An idle session can accept a later launch while submission is
open; an empty memory schedule is also valid. Truncation/error aborts unfinished
slots and never asserts successful global done.

The exchange is initialization, then repeated **server status → client control
→ server schedule → client result**. Frames retain the big-endian 64-bit length
and carry the v2 magic/version/message tag in their Boost-serialized payload.
Use matching Boost serialization versions when rebuilding both endpoints.

## Trace mixes and fixtures

The hello application supports `TEST_CASE=single`, `pair` (default), `four`,
and `reuse`. Single mode atomically submits slot 0 and closes submissions, then waits for
both kernel and session completion. It uses Rodinia registry ID `1106` by
default; `KERNEL_REGISTRY_ID` selects another entry for single mode only.
This version needs an ABI-3 bitstream for atomic single submission. The socket
protocol remains version 2; use the compatibility scheduler built alongside it.
The ordinary submit and close registers retain their previous behavior.

For exact comparisons using the historical single-kernel hello ELF, pass
`+trafficgen-legacy-registry-id=1106` to the simulator (replace the registry ID
for another dataset). Its old `0x5004` start write is translated into one closed
v2 launch. A legacy start without this option fails explicitly. Rejection of
that implicit launch also fails the host session, because the historical hello
cannot inspect per-slot rejection. This preserves
the old application's active CPU instruction/MMIO/HTIF stream; using a different
hello polling loop can otherwise perturb the shared L2 and confound a timing
comparison. The four-slot application continues to select its registry IDs
through the target registers.

The ABI-3 `single` example uses atomic submission and polls only the engine
diagnostic and global-done registers while replay is active. It defers launch-ID
reads, per-slot/session checks, and `SUBMIT`/`PASS` output until completion.
Simulator limits bound a stalled run. This reduces CPU interference, but its
program image and initial cache contents still differ from the historical ELF;
use the unchanged historical ELF with the legacy driver option for strict
baseline comparisons.


First run `scripts/install-trafficgen-workload.sh` from the Chipyard root.
Then, from `software/firemarshal` in the configured FireSim environment:

```bash
TEST_CASE=single ./marshal -v build example-workloads/trafficgen-hello.yaml
./marshal install example-workloads/trafficgen-hello.yaml
# Or select the short ResNet entry:
TEST_CASE=single KERNEL_REGISTRY_ID=1003 ./marshal -v build example-workloads/trafficgen-hello.yaml
```

The multi-launch runner below still accepts only `pair`, `four`, `reuse`, and
`synthetic`; single mode is selected through the hello/FireMarshal build.

| Case | Registry IDs | Purpose |
| --- | --- | --- |
| `pair` | Rodinia `1106`, short ResNet `1003` | Submit ResNet while Rodinia is active |
| `four` | `1106`, ResNet `1003` and `1044`, render `1001` | Four slots and disjoint kernels from Rodinia, ResNet, and render |
| `reuse` | Initial pair, then `1003` in slot 0 | An open-session idle gap and a new launch ID on reuse |
| `synthetic` | `1106`, `1003`, `1044`, `1001` in generated data roots | Independent one-CTA kernels that can reside together |

`make-registry.py` selects installed entries from the scheduler registry and
checks every pair for intersecting 64-byte lines in both recorded addresses and
the target mapping, `0x100000000 + (address & 0xffffffff)`. The chosen real
quartet is disjoint under both checks. The generated `.disjoint.json` records
that result and shared recorded request UIDs; the initial pair intentionally
exercises repeated UIDs. ResNet `1003` is store-only. Actual CTA overlap still
depends on each real kernel's resource requirements.

The real quartet contains 573,685 recorded requests, including 524,288 in
render. This full case can take several hours in metasim; the pair and synthetic
cases provide shorter checks while it runs.

The synthetic generator uses the same recorded kernel name/ID, dynamic warp ID,
and request UIDs in four distinct data roots. Its address ranges stay disjoint
after remapping. Each dataset has one 32-thread CTA with repeated load,
dependent arithmetic, and store instructions; the default is 256 groups.
The final instruction is a non-wake store. `hardware.config` permits four CTAs
on one SM. The standalone scheduler test deliberately withholds completions
until all four CTAs are resident, then verifies exactly-once completion and
retirement. It does not replace an RTL metasim run.

```bash
python3 scripts/trafficgen/make-registry.py /tmp/trafficgen-four.json --case four
python3 scripts/trafficgen/generate-synthetic-registry.py /tmp/trafficgen-synthetic
scripts/test-trafficgen-synthetic.sh       # 1024 loads and 1024 stores
scripts/test-trafficgen-synthetic.sh 8     # Shorter scheduler fixture test
```

## Rebuild and run

### Bounded wake-up baseline

`run-wakeup-baseline.py` runs the full Verilator hello → TileLink → RTL engine →
bridge driver → socket scheduler path with four disjoint synthetic datasets.
By default, each one-warp CTA executes 64 load/dependent-compute/store groups:
512 requests and 512 bundles across the session. The same recorded UIDs occur
in different launches. Validation requires four overlapping resident CTAs,
complete request/bundle accounting, and successful target/session completion.

Supply a Verilator simulator built with the current driver, and use a fresh
output directory. The test builds hello in that directory, leaving the installed
FireMarshal workload alone. It refuses to use an occupied scheduler port.

```bash
python3 scripts/trafficgen/run-wakeup-baseline.py \
  /scratch/trafficgen-wakeup-baseline-20260930/run-next \
  --simulator /scratch/trafficgen-wakeup-baseline-20260930/VFireSim \
  --scheduler /home/prashanth/gpu_model/build/gpu_model_socket
```

Bounds are 2,000 scheduler rounds, 100,000 target cycles (including boot), and
300 wall seconds for simulation and scheduler drain. Any timeout, truncation,
or incomplete workload is a failure, not a passing performance measurement.
Override with `--max-rounds`, `--max-cycles`, `--timeout`, or `--iterations`.

The optional driver argument `+trafficgen-boundary-log=/absolute/path.csv`
records every physical round-complete boundary using already-read values; it
does not add target MMIO reads or change exit decisions. `boundaries.csv`
contains start/end cycles, requested/effective deadlines, exit reason, fresh
issue/completion counts, and whether that boundary reaches the socket scheduler
or performs a private refill. Idle launch/close doorbells and CPU status polling
are excluded from the physical round-complete count.

`wakeups.json` summarizes the counts and cycle intervals. `rounds.csv` contains
one row per scheduler exchange. `provenance.json` captures commands, bounds,
binary/source hashes and wall time; full snapshots and validation remain in
the run directory. Re-summarize with:

```bash
python3 scripts/trafficgen/summarize-wakeups.py /path/to/completed/run
```

The RTL's scheduling exit code combines deadline, dependency wake, drain, and
completion-capacity exits. The report therefore calls out *deadline reached*,
not an exclusive causal classification. Likewise, empty feedback means no
issued requests and no completed bundles; compute/control progress may still
make an empty round necessary. Cycle averages exclude open-session idle gaps.
Use `--no-boundary-log` for a control run and compare per-request issue cycles,
control/lifecycle CSVs, and round manifests to verify measurement neutrality.

The archived 2026-09-30 baseline passed with
344 physical boundaries and 344 scheduler exchanges for 512 completed bundles.
209 exchanges (60.8%) had empty feedback; 208 of those reached a future finite
deadline. The median boundary interval was 5 target cycles. There were 342
unspecified scheduling exits, 2 control exits, and no private refills. All four
CTAs overlapped from cycle 724 to 4,712, and all kernel work completed at 5,519.
The later final session cycle (28,009) includes target software/UART time before
closing submission. The original uninstrumented simulator produced identical
results across 344 rounds and 2,758 compared files. Full evidence is retained in
`/scratch/trafficgen-wakeup-baseline-20260930`.

### Build commands

The commands below use the regular Chipyard checkout and its FireSim submodule.
Install the versioned workload with `scripts/install-trafficgen-workload.sh`
before building hello or running the metasim runner. This operation updates
only workload sources, backs up differing installed sources, and preserves
existing build outputs. The historical validation used FireSim `fa08b6cae659f88d00efb1a2d8c71be85aed97f8`;
its archived build and evidence locations are recorded in
[the compatibility workflow](compat/README.md).

Run from the Chipyard repository root. `GPU_MODEL_ROOT` defaults to the sibling
`gpu_model`; `GPU_MODEL_BUILD` defaults to its `build-chipyard-boost` directory.
The synthetic test reads the existing build's CMake cache for compiler and Boost
paths, verifies Boost 1.85 headers, links its actual `gpu_model_core`, and removes
its temporary fixture on exit. It requires `rg`, `pkg-config`, and OpenCV
development libraries. `CXX` and `TMPDIR` may override compiler and temporary
directory.

For the local Linux environment, rebuild the scheduler against Chipyard's Boost
1.85 rather than a different system serialization library:

```bash
repo=$(pwd)
gpu=${GPU_MODEL_ROOT:-$(dirname "$repo")/gpu_model}
gpu_build=${GPU_MODEL_BUILD:-$gpu/build-chipyard-boost}
mkdir -p "$gpu_build/boost-libs"
ln -sfn "$repo/.conda-env/lib/libboost_serialization.so.1.85.0" "$gpu_build/boost-libs/libboost_serialization.so"
ln -sfn "$repo/.conda-env/lib/libboost_serialization.so.1.85.0" "$gpu_build/boost-libs/libboost_serialization.so.1.85.0"
cmake -S "$gpu" -B "$gpu_build" -DCMAKE_CXX_COMPILER=/usr/bin/c++ \
  -DBoost_NO_BOOST_CMAKE=ON -DBOOST_INCLUDEDIR="$repo/.conda-env/include" \
  -DBOOST_LIBRARYDIR="$gpu_build/boost-libs" \
  -DBoost_INCLUDE_DIR="$repo/.conda-env/include" \
  -DBoost_SERIALIZATION_LIBRARY_RELEASE="$gpu_build/boost-libs/libboost_serialization.so" \
  -DBoost_SERIALIZATION_LIBRARY_DEBUG="$gpu_build/boost-libs/libboost_serialization.so" \
  -DOpenCV_DIR=/usr/lib/x86_64-linux-gnu/cmake/opencv4
cmake --build "$gpu_build" --parallel 8
ctest --test-dir "$gpu_build" --output-on-failure
scripts/test-trafficgen-synthetic.sh
scripts/test-trafficgen-socket-v2.sh
scripts/test-trafficgen-snapshots.sh
scripts/test-trafficgen-lane-assignment.sh
scripts/test-trafficgen-bundle-recovery.sh
```

The metasim runner builds
`FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig`, rebuilds
the selected `hello.c` workload, starts the scheduler on port `50051`, and runs
the bridge snapshot and session checks. Use a fresh output directory for each
run. The root filesystem has little free space; put the large generated build
on `/scratch` by setting `TRAFFICGEN_BUILD_ROOT`:

```bash
export TRAFFICGEN_BUILD_ROOT=/scratch/prashanth/trafficgen-compat-abi3-build
scripts/run-trafficgen-multilaunch.sh pair /scratch/prashanth/trafficgen-compat-abi3-pair
export TRAFFICGEN_SKIP_BUILD=1
scripts/run-trafficgen-multilaunch.sh four /scratch/prashanth/trafficgen-compat-abi3-four
scripts/run-trafficgen-multilaunch.sh reuse /scratch/prashanth/trafficgen-compat-abi3-reuse
scripts/run-trafficgen-multilaunch.sh synthetic /scratch/prashanth/trafficgen-compat-abi3-synthetic
```

Use `TRAFFICGEN_SKIP_BUILD=1` only with a current, successfully built
`VFireSim` in that build directory. Avoid simultaneous sbt/metasim builds in the
shared checkout. The runner leaves an existing listener on port `50051` alone.
It creates isolated scheduler/bridge logs, normalized analysis, and
`validation.json`; the validation requires later submission with prior
outstanding bundles, launch completion, monotonic cycles, matching issued
records, and no lost/duplicate requests or bundle completions. It also records
measured issue-interval overlap. Recheck a saved run with:

```bash
python3 scripts/trafficgen/check-session.py /scratch/prashanth/trafficgen-compat-abi3-four --case four
```

Reviewable FireSim Manager templates live in `scripts/trafficgen/configs/`;
the Manager's local `deploy/config_*.yaml` files are git-ignored. The templates
include metasim runtime, FPGA runtime, build configuration (output on `/scratch`,
sharing disabled), and one matching build recipe. Their recipe/HWDB alias is
`alveo_u250_firesim_rocket_singlecore_trafficgen_rtl_no_nic_l2_putget_boundary_only_m8_multilaunch_compat_abi3`.
The distinct suffix prevents an existing older image from satisfying the FPGA
runtime configuration merely because its target configuration name matches.

To prepare local Manager copies, run this from the Chipyard root. The new
`*_abi3.yaml` filenames leave the existing prepared Manager files intact:

```bash
for config in config_runtime_trafficgen_multilaunch \
              config_runtime_trafficgen_multilaunch_fpga \
              config_build_trafficgen_multilaunch \
              config_build_recipes_trafficgen_multilaunch; do
  cp -n "scripts/trafficgen/configs/$config.yaml" "sims/firesim/deploy/${config}_abi3.yaml"
done
```

`cp -n` preserves any previously customized copies. Review the copied host and
scratch paths before using them. Source `sims/firesim/sourceme-manager.sh` from
its directory with `--skip-ssh-setup`, then select the files explicitly with
these Manager flags:

| Use | Flags, relative to `sims/firesim/deploy/` |
| --- | --- |
| Metasim runtime tasks | `-c config_runtime_trafficgen_multilaunch_abi3.yaml -r config_build_recipes_trafficgen_multilaunch_abi3.yaml` |
| ABI-3 image build | `-b config_build_trafficgen_multilaunch_abi3.yaml -r config_build_recipes_trafficgen_multilaunch_abi3.yaml` |
| FPGA runtime tasks after that build | `-c config_runtime_trafficgen_multilaunch_fpga_abi3.yaml -a config_hwdb_trafficgen_multilaunch_abi3.yaml` |

The local `firesim` CLI switches to `deploy/` before loading configuration.
Run/build-farm `base_recipe` and `bit_builder_recipe` paths resolve there;
`TARGET_PROJECT_MAKEFRAG` resolves relative to the build-recipe YAML. Keep the
copies directly in `deploy/` as shown so its `../../../generators/...` path
remains valid. The metasim runner above also starts the socket scheduler and
validates logs; Manager runtime configurations alone do not start that server.

The FPGA HWDB file is deliberately absent from the templates. After a future
build of this source finishes, copy its newly generated
`deploy/built-hwdb-entries/<the ABI-3 alias above>` entry to
`deploy/config_hwdb_trafficgen_multilaunch_abi3.yaml`, and verify `bitstream_tar`
names that new build's artifact. Renaming an old HWDB entry to the ABI-3 alias does
not update its image or MMIO ABI. No FPGA build or deployment is performed by
preparing these templates.

These commands validate simulation. They do not build or deploy an FPGA
bitstream, and DPI multi-launch execution remains unsupported.
