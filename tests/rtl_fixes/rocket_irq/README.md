# Rocket trap and interrupt regressions

From the Chipyard root:

```sh
python3 tests/rtl_fixes/run.py --suite rocket-interrupt --work /scratch/rocket-interrupt-new --jobs 4
python3 tests/rtl_fixes/run.py --suite rocket-trap-pc --work /scratch/rocket-trap-pc-new --jobs 4
```

Each suite elaborates Scala and runs corrected and isolated negative-control
versions. Generated core logic is unchanged; added outputs only observe signals.

`interrupt_pc.cpp` drives the vector backend's `block_all` and `trap_check_busy`
inputs to defer interrupt acceptance. Its 8,640 cases cover 64 arrival cycles,
nine defer durations, five aligned/compressed/straddled instruction layouts, and
three fetch-bubble patterns. The architectural return oracle is the last
committed PC plus that instruction's decoded length. Every case checks trap
arrival and the first retirement after MRET. Incomplete split instructions must
still assemble while an interrupt is pending, or these cases detect deadlock.

`trap_pc.cpp` tests 384 scalar interrupt/illegal-instruction/instruction-access-
fault overlaps with stale vector retirement or exception writeback. Another 64
vector-only exceptions and 64 ECALL controls check the unaffected paths. It
checks PC/cause selection, the actual saved CSR values, and return instruction.
ECALL controls observe the CSR-generated trap path; they do not inject vector
writeback. This is an interface arbitration test, not a vector program.

Both fixtures execute the real Rocket decode, CSR and instruction-buffer logic.
The external instruction frontend and vector interface are test stimuli. No OS,
full SoC, or FPGA behavior is claimed by these focused tests.
