# Compatibility implementation: completed validation

Built from Chipyard `d07370a293b15cbc347932a7745a36d6a6456585` and GPU model
`e345d5618078e77200697055d944d3ff9575c804`. Validation ran in isolated
`chipyard` and `gpu_model` worktrees under `/home/prashanth/chipyard/.worktrees/trafficgen-compat`,
preserving the original checkouts during the experiment. The validated source
was subsequently ported to `trafficgen-target-cycle` in both repositories.
Absolute artifact links below refer to the retained local evidence archive;
generated traces and binaries are not included in the source repositories.

The [machine-readable evidence](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/final-validation.json) records
the comparison files, source/binary hashes, test results, and limitations.
The [workflow](README.md) explains how to reproduce the checks.

## What restores the single-kernel behavior

Each launch runs the legacy instruction scheduler in its own context. Local
bundle ordinals still alias across schedulers within that launch. Accesses with
the same launch and ordinal introduced in one round become a replay group with
a session-unique wire handle. Completion translates back to the local ordinal
and broadcasts only within its originating launch.

The implementation preserves same-launch CTA recycling, scheduler rotation,
lane-assignment order, the original nonempty-call `min_issue_cycle` sampling,
and the RTL engine's cooperative wake batching. Physical Put/Get and L2 logic
is unchanged from the Chipyard baseline. Host completion-prefix DMA reads and
unchanged-payload upload caching remain enabled.

Cross-launch resource sharing uses per-launch/per-SM reservations. An SM's
reservation is transferable only after modeled work, physical memory groups,
and the compute horizon drain. This retains the baseline's within-launch
approximations while preventing another launch from using resources too early.
It is intentionally more conservative than per-CTA resource sharing.

The target has four slots with registry IDs and launch-qualified status. ABI 3
adds atomic submit-and-close for a single launch; the socket remains protocol
v2. Pending queues, reservations, store completions, and launch identities
survive later submissions and slot reuse.

| Layer | Main source | Change |
| --- | --- | --- |
| Hello | [hello.c](../workloads/trafficgen-hello/hello.c) | Single, pair, four, and reuse modes; atomic single submit/close and a quiet active polling loop |
| TileLink and RTL engine | [TrafficGen.scala](../../../generators/chipyard/src/main/scala/example/TrafficGen.scala) | Four launch slots, tagged status, legacy start, atomic submission; original cooperative wake batching |
| Bridge module | [TrafficGenBridge.scala](../../../generators/firechip/goldengateimplementations/src/main/scala/TrafficGenBridge.scala) | Transfer launch controls and statuses at paused boundaries while preserving replay state |
| Host driver | [trafficgen.cc](../../../generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.cc) | Protocol v2, launch-qualified tracking, persistent queues, all-member load/store completion, local refills, and legacy bootstrap |
| Scheduler session | [socket_session.cpp](https://github.com/pcg108/gpu_model/blob/trafficgen-target-cycle/src/socket_session.cpp) | Per-launch legacy contexts, opaque replay-group handles, conservative SM reservations, exact feedback validation |
| Registry and plots | [kernel_registry.cpp](https://github.com/pcg108/gpu_model/blob/trafficgen-target-cycle/src/kernel_registry.cpp) and [socket_plots.cpp](https://github.com/pcg108/gpu_model/blob/trafficgen-target-cycle/src/socket_plots.cpp) | Dataset identity independent of recorded kernel names; combined/per-launch output |

## Exact single-kernel comparison

All ten cases pass: **1,138,410 request issues compared**, with **zero cycle
offset** and the same historical target ELF and configuration bytes in each
old/new pair. Both the working scale 1.0 and committed scale 1.5 are covered.
Each pass checks complete per-request schedules and adapter-FIFO issue cycles,
ordering, replay-group membership/introduction round, lane/member counts,
reservations, blocked warps, deadlines, round-return cycles, and observable
completion reports. The cycle columns below are equal in the old and new runs.

| Case | Issue scale | Requests | Groups | Rounds | Last issue | Final cycle | Exact comparison |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Aliasing fixture | 1 | 128 | 16 | 18 | 1,010 | 1,026 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-alias-scale1/comparison.json) |
| Aliasing fixture | 1.5 | 128 | 16 | 18 | 1,022 | 1,038 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-alias-scale1_5/comparison.json) |
| Rodinia 1106 | 1 | 21,563 | 472 | 29 | 7,839 | 7,848 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1106-scale1/comparison.json) |
| Rodinia 1106 | 1.5 | 21,563 | 555 | 45 | 10,259 | 10,268 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1106-scale1_5/comparison.json) |
| Short ResNet 1003 | 1 | 2,746 | 28 | 3 | 1,622 | 1,705 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1003-scale1/comparison.json) |
| Short ResNet 1003 | 1.5 | 2,746 | 28 | 3 | 2,128 | 2,211 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1003-scale1_5/comparison.json) |
| ResNet 1021 | 1 | 100,352 | 416 | 10 | 26,064 | 26,113 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1021-scale1/comparison.json) |
| ResNet 1021 | 1.5 | 100,352 | 416 | 10 | 36,305 | 36,361 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1021-scale1_5/comparison.json) |
| Large ResNet 1054 | 1 | 444,416 | 4,284 | 245 | 119,892 | 119,931 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1054-scale1/comparison.json) |
| Large ResNet 1054 | 1.5 | 444,416 | 4,437 | 400 | 152,439 | 152,449 | [PASS](/scratch/trafficgen-compat-20261001/candidate-final-real-1054-scale1_5/comparison.json) |

The historical ELF uses the old start register. The new driver option
`+trafficgen-legacy-registry-id=<id>` selects one closed launch for that
doorbell. It has no effect on native slot submissions. Missing configuration
or rejection of this implicit launch fails explicitly; the negative RTL test
is recorded in the aggregate evidence.

The native ABI-3 hello is a different CPU program and can change shared-L2
traffic even with a short polling loop. It is **not** claimed to reproduce the
old ELF's per-request timing. The diagnostic Rodinia run ended at cycle 9,916
versus 10,268 with the old ELF, with nonuniform per-request differences; see
[application comparison](/scratch/trafficgen-compat-20261001/candidate-native-single-1106-scale1_5/application-difference.json).
The unchanged ELF is the strict compatibility workload.

## Native session and residency validation

| Run | Requests | Completed groups | Scheduler rounds | Final cycle | Audit |
| --- | --- | --- | --- | --- | --- |
| candidate-abi3-single | 128 | 16 | 18 | 1,168 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-single/extended-validation.json) |
| candidate-abi3-pair | 256 | 32 | 36 | 13,962 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-pair/extended-validation.json) |
| candidate-abi3-four | 512 | 64 | 64 | 28,050 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-four/extended-validation.json) |
| candidate-abi3-reuse | 384 | 48 | 53 | 22,536 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-reuse/extended-validation.json) |
| candidate-abi3-stores-fast-submit | 1,024 | 128 | 5 | 834 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-stores-fast-submit/extended-validation.json) |
| candidate-abi3-tail-one-cta | 256 | 32 | 38 | 13,962 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-tail-one-cta/extended-validation.json) |
| candidate-abi3-long-tail-one-cta | 128 | 17 | 26 | 1,586 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-abi3-long-tail-one-cta/extended-validation.json) |
| candidate-real-pair | 24,309 | 509 | 41 | 13,962 | [PASS](/scratch/trafficgen-compat-20261001/candidate-real-pair/extended-validation.json) |
| candidate-real-four | 573,685 | 3,630 | 144 | 172,470 | [PASS](/scratch/trafficgen-compat-20261001/candidate-real-four/extended-validation.json) |
| candidate-final-abi3-four | 512 | 64 | 64 | 28,050 | [PASS](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-final-abi3-four/extended-validation.json) |

The real quartet uses Rodinia 1106, ResNet 1003 and 1044, and render 1001.
[All six dataset pairs](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/candidate-real-registry/registry.disjoint.json)
have no shared 64-byte lines before or after target address remapping. Recorded
UIDs do repeat, exercising launch-qualified identity. The quartet has actual
ResNet/render issue overlap. The real pair submits ResNet while Rodinia has
pending work, but its request issue intervals do not overlap because Rodinia
holds the needed reservation. Four simultaneous active contexts are separately
exercised by the disjoint synthetic fixture.

![Completed quartet issue spans and reported completion cycles](/home/prashanth/chipyard/.worktrees/trafficgen-compat/state/final-report-figures/real-quartet-timeline.png)

The spans run from each launch's first to last memory issue and may contain
gaps; they do not imply continuous utilization.

The quartet finished 573,685 requests and 3,630 groups across **151 physical
boundaries: 144 scheduler exchanges and 7 private refills**. Those private
refills issued 208,423 requests and completed 1,226 groups. Their feedback is
batched into the next socket exchange, so the scheduler CSV alone temporarily
under-reports hardware progress during the final drain. Final physical counts
and complete snapshots agree exactly.

Store-only validation completes all 128 non-wake groups and holds the second
launch until the first reservation drains. Slot reuse creates a new launch ID
after an open-session idle interval. The long compute-tail fixture checks
per-SM handoff and finite empty-schedule progress while another launch still
has pending memory requests.

The store and long-tail overlap tests use a dedicated prestaged, back-to-back
submission hello. The initial store pilot used the shipped pair hello, whose
acceptance/status work allowed the first tiny kernel to finish at cycle 425
before the second submission; that run failed its overlap precondition. Its
failure is retained in the aggregate evidence. The targeted test application
fixes that test precondition without changing the shipped pair/four/reuse
applications or weakening the memory/residency checks.

## Build and supporting checks

- Full Verilator/GoldenGate build passed. Final simulator:
  `/scratch/trafficgen-compat-20261001/build/rtl/VFireSim` (SHA-256 `cdd44a90cefe6c03978510b86abc93873b74f75cc8115390a564f93bfefde554`).
- All 12 final GPU CTests and all 8 current-source real-trace model-loop parity
  cases passed.
- Final RTL engine tests: 41 passed; slot and production TileLink control
  tests: 9 passed. Put/Get and L2 tests passed with unchanged memory logic.
- Driver/protocol, bundle recovery, lane assignment, snapshot, synthetic
  registry, comparator-contract, and test-only port-redirection checks passed.
- `git diff --check` passed in Chipyard, GPU model, and FireMarshal worktrees.

The quartet and earlier native fixtures used the preserved simulator
`VFireSim-before-legacy-rejection-guard`. The final host-only change rejects an
invalid implicit legacy launch; native paths and RTL are unchanged. Repeating
the native four-launch fixture on the final image produced **1,221 byte-identical
files**, including snapshots, controls, lifecycle, and boundary records.
Every strict single-kernel case above uses the final image.

The old reference binary has only its two fixed log-path strings relocated to
protect the original run directory. Tests on a second port use a validated
loopback bind/connect shim with unchanged payload and zero-offset alias/real
reference comparisons. Full per-run provenance records those changes.

## Limits and hardware use

Historical completion records omit a generation for reused ordinals. The
comparison checks exact cohort bijection and observable completion sequence;
it cannot recover an unrecorded historical generation. Snapshots also omit
individual memory-response timestamps; separate RTL tests check response-based
all-member completion and store drain. No wall-time speedup is inferred from
these concurrently executed simulations.

No FPGA bitstream was built or deployed. Native atomic submission needs the
matching ABI-3 bitstream. The [build recipe](../configs/config_build_recipes_trafficgen_multilaunch.yaml)
uses `FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig` and
`BaseXilinxAlveoU250Config`; its alias ends in
`m8_multilaunch_compat_abi3`. Follow the build instructions in
the [TrafficGen README](../README.md).
