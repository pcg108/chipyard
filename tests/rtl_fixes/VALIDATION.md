# RTL fix validation

All five fixes passed focused Verilator acceptance from `/home/prashanth/chipyard`. The source and test content validated is commit `35c73ecce4d518bc69e9f9bfd79fef6748452ace`. A subsequent validation-record commit changes documentation/results only.

Initial parent: `17fb85be67c46789aa55453bebc50f93438a6c0a`. Initial source publication `5e719833d88c87828fd94f0790383f24171201d8` preceded every test. Components retain their upstream history and remotes; no upstream PR or force-push was used.

| Fix | Corrected coverage | Negative control (fix removed) | Simulation host seconds, corrected / negative |
|---|---|---|---|
| Rocket scalar trap PC | 512/512 | 384 stale-PC failures; 128 controls passed | 0.366 / 0.366 |
| Rocket instruction buffer | 8,640/8,640 | 3,840 failures / 8,640 cases | 4.594 / 4.846 |
| Rocket cache metadata | 70,081/70,081 + 26,880 oracle controls | 1 minimal, 11 overlap, 22 invalidation lost stores | 79.030 / 43.277 |
| Saturn resumed-page bounds | 1,008/1,008 + 39,984/39,984 | 336 basic + 29,120 extended failures | 0.081 / 0.080 |
| Shuttle divide/square-root retirement | 12/12 | 6 forbidden FP writebacks; legal controls passed | 0.115 / 0.064 |

Negative-control failures are the expected defect reproductions. All fixtures reached their completion markers with the expected exit status; none of these accepted runs timed out. Simulation runtimes exclude Scala elaboration and Verilator compilation. Compilation timings, commands and detailed counts are recorded in the JSON results.

## Published sources

| Repository | Validated source revision |
|---|---|
| [pcg108/chipyard](https://github.com/pcg108/chipyard/tree/fix/rocket-saturn-shuttle-rtl) | `35c73ecce4d518bc69e9f9bfd79fef6748452ace` |
| [pcg108/rocket-chip](https://github.com/pcg108/rocket-chip/tree/fix/rocket-saturn-shuttle-rtl) | `2c0e4784c46f1a67ef39699c7d67aef65e3ea8c0` |
| [pcg108/saturn-vectors](https://github.com/pcg108/saturn-vectors/tree/fix/rocket-saturn-shuttle-rtl) | `9e04c8c6c70a4b4db5989f9a16a89679183a5d1d` |
| [pcg108/shuttle](https://github.com/pcg108/shuttle/tree/fix/rocket-saturn-shuttle-rtl) | `e789aa207148ade9eaed79f12841102597f5a0a5` |

The three component repositories are private. [fixes.json](fixes.json) records the five individual fix commits. An independent authenticated clone fetched the parent and used `git submodule update --init` to retrieve each exact component commit, without local object alternates.

## Reproduction

From the initialized Chipyard checkout with its installed toolchain:

```sh
python3 tests/rtl_fixes/run.py --suite all --work /scratch/rtl-fixes-reproduction --jobs 4
```

Each suite can also be selected individually:
- `--suite rocket-trap-pc`
- `--suite rocket-interrupt`
- `--suite rocket-cache`
- `--suite saturn-memory`
- `--suite shuttle-fdiv`

The runner creates isolated negative source checkouts; it never reverses patches in the main tree. Fresh Scala elaboration supplies the generated modules. Lowering preserves test interfaces, and module extraction copies whole dependency modules unchanged. Probes only observe signals. The cache wrapper connects the actual cache and arbiter. No generated functional logic is patched.

The Rocket/Saturn test configuration has four Rocket cores, four L1 MSHRs, four cache ways, REFV256D128 and FP64. The Shuttle/Saturn configuration has one dual-issue Shuttle core with REFV256D128 and FP64. These focused tests require no Gemmini, Zephyr or ILLIXR replay.

All tests ran sequentially, at low priority with at most four workers, under the 48 GiB reserve / 64 GiB per-process RSS / new-OOM guard. No resource-limit stop occurred. Memory PSI was warning-only. Existing FPGA builds were not stopped or changed.

## Evidence and hashes

Bulk generated sources, firmware, executables, logs, source patches, guard telemetry and publication records are under `/scratch/prashanth_chipyard_rtl_fixes_20261002T224801Z`.

[validation-summary.json](validation-summary.json) contains the per-fixture source/RTL and driver hashes, runtime, exit codes, firmware hashes, compiler identities and the preserved dirty-file fingerprints. Detailed command records are in the referenced `result.json` files. The four executed Shuttle instruction images are byte-identical between corrected and negative RTL. Unstripped ELF hashes differ because GCC embeds temporary object filenames in the symbol table; both ELF hashes and identical instruction-image hashes are retained. On the negative Shuttle, the fixture records six forbidden FP writebacks to an idle destination with changed proposed data and terminates each failing case before its assertion edge. Assertions remain enabled; no architectural overwrite after that edge is claimed. Corrected cases execute through final register readback.

| Suite | Corrected generated RTL SHA-256 | Negative generated RTL SHA-256 |
|---|---|---|
| `rocket-trap-pc` | `f827d3497884ef944990d8b737834c69ca7b764de4ef1307d8894d877bb11e7f` | `8a91526ea3cbed32c9b54baf7f7358265b5c5fe0ab6f2729465f4b635504a427` |
| `rocket-interrupt` | `f827d3497884ef944990d8b737834c69ca7b764de4ef1307d8894d877bb11e7f` | `903ba4a0bf65d6d936262fcd7b49c491da30322b313fd5f8f54da2128e15c37d` |
| `rocket-cache` | `f827d3497884ef944990d8b737834c69ca7b764de4ef1307d8894d877bb11e7f` | `d0994bdf6e4aff7b0852445c79f7a2f69fa2d0116f7e5b490f77ad9356e49520` |
| `saturn-memory` | `f827d3497884ef944990d8b737834c69ca7b764de4ef1307d8894d877bb11e7f` | `730d7384385cc67633351f146c30ea98a5b057f7f4beadc1dbe119b77d1047aa` |
| `shuttle-fdiv` | `28770f64b3b2b37e01350bddf4a889db25e4696b7c12baa24ccc91644bd4fb9e` | `2bca0f7352c8b9a228956b194798eca75a40dced244ddee09145544fc5ea1d4c` |

## Scope and preserved diagnostics

These are focused module/core regressions, not a full-system or FPGA acceptance claim. The instruction frontend and coherent manager supply external stimuli. Saturn checks dependency decisions and independent progress, not complete memory transfers. Shuttle executes actual bare-metal instructions through the generated core and its real FP divider.

Early setup/harness failures are preserved in scratch: SBT target isolation, FIRRTL format/annotation integration, unused whole-SoC monitor parsing, ECALL observation/CSR sampling, speculative fetch beyond the initial Shuttle image, and uncorrected Shuttle CSR/scoreboard assertions. They were corrected in the build/test harness; no additional functional RTL patch was needed. The final cache rerun explicitly drives legal Probe opcode/mask fields. All 23 originally recorded unrelated dirty-file hashes still match. The parent commit excludes those unrelated changes.
