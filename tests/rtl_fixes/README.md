# Five RTL correctness regressions

This branch pins three private component repositories containing five RTL fixes.
`fixes.json` records one commit per fix. Generated RTL and bulk logs stay outside
Git. The initial source publication preceded these regression runs.

From an authenticated Chipyard checkout with its dependencies and installed
Conda tools, run:

```sh
python3 tests/rtl_fixes/run.py --suite all --work /scratch/rtl-fixes-validation --jobs 4
```

Individual suites are `rocket-trap-pc`, `rocket-interrupt`, `rocket-cache`,
`saturn-memory`, and `shuttle-fdiv`. A work directory is not overwritten. A suite
runs corrected RTL, then a negative control made by reverting only that fix in
an isolated component checkout. Other initialized dependencies are read-only
symlinks into the main checkout. No patches are reversed in the main checkout.

The runner elaborates the committed Scala test configurations, compiles the
result with FIRRTL2 and Verilator, and records source, generated RTL, firmware,
and fixture hashes. Internal probes add output aliases only. Cache protocol
stimuli drive the actual generated cache and its real requestor arbiter; no
behavioral cache substitutes or edited functional Verilog are used.

Coverage:

- Rocket trap arbitration: 384 scalar interrupt/illegal-instruction/instruction-access-fault
  overlaps with external vector retirement or exception writeback, plus 64
  vector-only exception controls and 64 ECALL controls. Check saved PC, cause and first retirement
  after MRET. This is an interface arbitration test with external vector
  writeback stimuli, not a Saturn instruction-stream test.
- Rocket instruction buffer: 8,640 interrupt arrival/defer/fetch-bubble and
  compressed/straddled instruction cases, checking the architectural resume PC.
- Rocket cache: the original lost-store scenario, 26,880 overlap cases, 26,880
  subsequent invalidation cases, 26,880 deliberately missing-store controls,
  6,720 arbitration cases and 9,600 backpressure cases.
- Saturn memory dependencies: 1,008 basic and 39,984 extended checks against the
  real generated VectorMemUnit, including resumed-page slices and independent
  progress. These check dependency decisions, not full end-to-end memory DMA.
- Shuttle: bare-metal FDIV/FSQRT with FP disabled must trap and preserve a seeded
  destination. Legal divide/square-root controls must produce their exact
  expected values. Three frontend bubble patterns produce twelve cases.
  Negative cases record a forbidden FP writeback before its assertion edge;
  corrected cases must complete final destination readback. Assertions stay
  enabled. Instruction responses drive the real ShuttleCore frontend interface; the
  actual decoder, CSR logic, FP registers and divider run without an OS.

Tests run sequentially at low priority with at most four workers. The guard
stops on less than 48 GiB available RAM, any process reaching 64 GiB RSS, or a new
kernel OOM kill. Memory PSI only warns. A guard stop creates a persistent latch
and does not restart. Missing result markers, timeouts and interruptions never
pass. Runtime limits are bounded per compilation and fixture.

The top-level runner is the supported entry point for all five suites.

Completed results, exact source/RTL hashes and negative-control outcomes are
recorded in [VALIDATION.md](VALIDATION.md) and [validation-summary.json](validation-summary.json).
