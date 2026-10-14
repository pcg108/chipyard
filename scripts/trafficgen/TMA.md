# Protocol v7 TMA/mbarrier bridge

The source reference is the unmodified GPU model at
`7b6abcafe312c21e87393181410eb2f2aea1d938`, linked with Chipyard Boost 1.85.
The socket framing version is 7, `L2Access` archive version is 2, and result
archive version is 4. The target launch MMIO ABI remains 3. Physical upload
requests remain 435 bits in a 512-bit beat; no tensor descriptor or barrier
counter enters the memory backend.

The GPU scheduler expands TMA addresses and owns mbarrier arrivals, expected
bytes, completion credits, phases, parity, and consumer release. The driver
retains original `(launchId, requestUid)` identities and translates each complete
wake-policy snapshot into distinct physical-service dependencies and a cycle
floor. The synthesizable monitor determines readiness and stops target time;
the host reconstructs and validates feedback after a boundary.

## Compilation and lifetime

`trafficgen_tma_policy` maintains request history independently of the current
policy. Known TMA identities are the union of all seen outstanding requests and
group members. Their transport bundles still produce public completions, but
cannot trigger an ordinary warp wake.

For each group, the compiler deduplicates physical services and computes:

```
remaining = number of distinct member services that have not returned
floor = max(group.readyCycle, all member admission cycles,
            all already-known member physical-return cycles)
ready at C = remaining == 0 && C >= floor
```

When an unreturned service returns, the remaining count decreases once for
each dependent group. Completion of that service already guarantees that its
return cycle has arrived. This establishes equivalence to checking every
original completion, including future admissions and Reuse, without retaining
full original records in hardware.

Groups retain independent slots even if their members overlap. Logical edges
are allocated for the entire snapshot before execution. Service heads are
published as tokens become available during physical upload windows. Private
refills preserve the group counters, policy epoch, and deferred heap. An empty
public policy replaces previous interests. Policy replacement happens only
while paused and after previous reports have been consumed.

## Metadata ABI

All records are 512-bit little-endian bitfields; unspecified bits are zero.
Existing LRC operations 1, 2, and 3 are unchanged. New operations use the same
65,536-beat staging window at bridge-relative address `0x05000000`.
A DMA write only changes staging RAM. `lrc_commit_metadata` installs its valid
prefix while paused; the target cannot execute between chunks. A final TMA
commit publishes the new policy epoch.

| Opcode | Fields, inclusive bit positions |
|---|---|
| 4: begin policy | opcode 7:0; epoch 39:8; group count 52:40; edge count 71:53; honor drain deadline 72; LRC batch semantics 73 |
| 5: group | opcode 7:0; slot 19:8; remaining services 51:20; earliest cycle 115:52; conservative kind 116 |
| 6: edge | opcode 7:0; index 25:8; group slot 37:26; next edge 56:38 |
| 7: service head | opcode 7:0; full physical token 71:8; epoch 103:72; first edge 122:104 |
| 8: commit policy | opcode 7:0; epoch 39:8 |

The null edge index is 262,144. Group and edge rows are installed consecutively
from index zero; links point to an earlier edge. Commit checks the declared
row counts. Token low bits select a service slot; all 64 bits qualify its
generation. A head is usable only when both epoch and token match. No group
fan-out is limited to four.

## Reports and target time

Reports reuse the 131,072-beat buffer at bridge-relative `0x05800000`.
Every report contains kind at 7:0, identity at 71:8, and cycle at 135:72.

| Kind | Identity | Cycle |
|---|---|---|
| 1 | physical token | accepted final memory response |
| 2 | public bundle ID | completion of all original obligations |
| 3 | `(policyEpoch << 32) \| groupSlot` | phase readiness evaluation |
| 4 | `(policyEpoch << 32) \| groupSlot` | conservative transfer readiness evaluation |

Wake reports never become public bundle completions. Both monitors consume the
same accepted return batch and must finish before the target token can commit.
The report reservation covers every live public bundle, every live wake group,
and all returns on the token. Report pressure invokes a private drain with no
scheduler exchange. Deferred groups remain pending even after memory drains.
New policies are rechecked at the held cycle: an already-ready group reports
that evaluation cycle, never a retrospective boundary.

Boundary flag bits are ordinary wake (0), deadline (1), issue boundary (2),
full drain (3), private refill (4), phase wake (5), and conservative wake (6).
Flags may coexist; wake has scheduler-return priority. Drain-only rounds honor
the deadline if the public snapshot includes wake groups or outstanding TMA
requests. This rule remains fixed through private operations.

TMA without LRC activates bridge-controlled execution but retains the pinned
model's legacy issue-boundary convention: after processing its last issue at C,
it returns at C+1 without retiring C+1. The bridge parks that next raw token
before accounting it. LRC batch mode instead uses the inclusive issue/admission
boundary at C. Ordinary sessions with neither feature retain the existing path.

## Storage inventory

Rows below describe packed raw RAM payloads, excluding registers, inferred FPGA
packing, and the existing target backend/cache state. No existing LRC or staging
buffer is resized.

| Store | Rows | Bits/row | Raw bytes |
|---|---:|---:|---:|
| LRC services | 65,536 | 325 | 2,662,400 |
| LRC public bundles | 65,536 | 212 | 1,736,704 |
| LRC deferred heap | 65,536 | 144 | 1,179,648 |
| Shared metadata staging | 65,536 | 512 | 4,194,304 |
| Shared reports | 131,072 | 512 | 8,388,608 |
| **New TMA service heads** | **65,536** | **115** | **942,080** |
| **New TMA dependency edges** | **262,144** | **31** | **1,015,808** |
| **New TMA wake groups** | **4,096** | **98** | **50,176** |
| **New TMA deferred heap** | **4,096** | **76** | **38,912** |

The four new stores total **2,046,976 bytes (1.952 MiB)**. All nine completion,
metadata, and report stores total 20,208,640 bytes. Existing bridge access-upload
banks retain 32,768 packed 435-bit rows (1,781,760 bytes), and issued-writeback
banks retain 32,768 packed 321-bit rows (1,314,816 bytes). Their DMA windows
use 512-bit beats; the RAM payloads omit beat padding.
The legacy public-bundle return RAM retains 32,768 64-bit IDs (256 KiB).
The 16 lanes each retain three two-entry, 435-bit prefetch FIFOs (5,220 bytes
total, typically registers). The 16 lanes retain eight backend outstanding slots
per lane. Capacity and high-water registers are host instrumentation, not target
software MMIO changes.

## Worked example

A physical read S serves original requests A and B. A is admitted at cycle 10,
B at cycle 40, and S returns at cycle 25. A belongs to public bundle 101 and B
to bundle 102. A TMA phase group contains both and has `readyCycle=50`.

| Cycle | Event |
|---:|---|
| 25 | Physical return S; original A completes; public bundle 101 completes. Group dependency count becomes zero. |
| 40 | Original B completes at its admission floor; public bundle 102 completes. No second memory transaction or physical return occurs. |
| 50 | The phase group's compiled floor arrives and produces one kind-3 wake report. |

The scheduler receives both public bundle completions and both original
completions. Neither TMA transport bundle forces a scheduler wake at 25 or 40.
The scheduler applies the original completions to its transfer/byte accounting
and releases its consumer when the phase is ready.

## Reproduction

Use a separate snapshot/build of the pinned model and run:

```sh
GPU_MODEL_ROOT=/scratch/trafficgen-tma-6632-20261013/model-src \
GPU_MODEL_BUILD=/scratch/trafficgen-tma-6632-20261013/model-build \
TMPDIR=/scratch/trafficgen-tma-6632-20261013 scripts/test-trafficgen-socket-v7.sh
scripts/trafficgen/build-tma-reference.sh MODEL_SOURCE MODEL_BUILD
scripts/trafficgen/run-tma-metasim.py FRESH_REFERENCE --model-source MODEL_SOURCE \
  --model-build MODEL_BUILD --reference --kernel 6632 --policy-decoder POLICY_DECODER
scripts/trafficgen/build-tma-metasim.sh FRESH_BUILD
scripts/trafficgen/run-tma-metasim.py FRESH_RUN --model-source MODEL_SOURCE \
  --model-build MODEL_BUILD --simulator FRESH_BUILD/VFireSim --kernel 6632 \
  --policy-decoder POLICY_DECODER
```

Repeat with `--kernel 6668` for the scale-2.0 LRC regression. Kernel 6632 uses
scale 1.0; both use memory issue stretch 1.0 and the pinned mbarrier configuration.
Each run requires successful process/session termination and an independent
identity, membership, completion, and traffic audit. Timeout or truncation fails.
Phase 1 evidence remains in `analysis/lrc_6668_20261013`.

Each public exchange saves the full Boost-serialized `TmaWakePolicy` in
`tma_policy_ROUND.bin`, its group rows in `tma_policy_ROUND.csv`, and the separate
outstanding-request set in `tma_outstanding_ROUND.csv`. Empty policies are saved
as well. Build `export-tma-policies.cc` against the pinned model's official headers
and Boost 1.85; pass that executable as `POLICY_DECODER`. The runner decodes the
archives into `policies.jsonl` and independently compares every group field,
member, and outstanding identity with the readable snapshots before acceptance.
The exact decoder build command is retained in the run campaign's evidence.

For this validation campaign, `summarize-tma-validation.py RUN_ROOT --phase1
PHASE1_ROOT` assembles the completed run results, verifies the pinned snapshot
and tested production source hashes, and compares the 6668 physical issue and
return cycles against Phase 1. It refuses to mark acceptance while a full run
is missing or unsuccessful. Individual build/run manifests retain exact commands,
binary/configuration/dataset hashes, and process exit status; the summary writes
`acceptance.json` and an evidence hash index.
