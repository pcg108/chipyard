# Defects and source changes

The component histories and upstream licenses are retained. Each fix is a
separate commit, listed in `fixes.json`; all three branches use the name
`fix/rocket-saturn-shuttle-rtl`.

## Rocket scalar trap PC

The vector writeback interface can still describe an older vector instruction
when the scalar pipeline takes an exception or interrupt. Rocket already gives
the scalar exception cause priority, but the unconditional vector assignment
to `csr.io.pc` could pair that cause with the older vector PC. Returning from the
trap could execute the wrong instruction. `RocketCore.scala` now leaves the
scalar PC selected while `wb_reg_xcpt` is asserted. Vector-only exceptions keep
the vector PC.

Run `--suite rocket-trap-pc`. Its negative checkout removes only this priority
fix; it retains the separate instruction-buffer correction below.

## Rocket deferred-interrupt instruction retention

A pending interrupt can be deferred by the vector backend. A complete instruction
must remain in the instruction buffer until it can actually issue; consuming it
while interrupt acceptance is deferred can skip it. `RocketCore.scala` now
blocks the buffer's ready signal for a complete valid instruction under a
pending interrupt. The valid condition matters: an incomplete instruction
straddling fetch words must still be allowed to assemble, avoiding deadlock.

Run `--suite rocket-interrupt` for all 8,640 timing/stream combinations.

## Rocket nonblocking-cache lost store

A cache miss handler can be waiting to write updated dirty coherence metadata.
Accepting a conflicting probe during `s_meta_write_req` exposes stale clean
metadata to the probe handler. It can invalidate the line without returning the
newly written store data. `NBDcache.scala` extends the existing same-index probe
interlock through that pending metadata-write state. Existing metadata hazard
protection remains in effect; nonconflicting indices remain eligible.

Run `--suite rocket-cache`. The tests use the generated four-MSHR cache and real
cache arbiter. A coherent-manager fixture issues legal external probes and
grant traffic, then verifies the stored value and neighboring data. Deliberately
omitting the store verifies the readback oracle independently. The original
standalone fixture is preserved as `cache/minimal.cpp`; old saved or modified
Verilog is not an input to the runner.

## Saturn resumed-page dependency bounds

A split memory operation can resume partway through its elements or segments.
Bounds based only on the original base offset describe the wrong page slice.
`mem/Mem.scala` computes the page-relative start using `vstart`, segment count,
`segstart`, and element width. Both block containment and overlap checks use
that resumed offset, preserving required ordering without blocking unrelated
memory work.

Run `--suite saturn-memory` for the 1,008 basic and 39,984 extended checks.

## Shuttle rejected divide/square-root

A valid commit-stage instruction is not necessarily one that retires. With FP
disabled, FDIV or FSQRT must trap. Launching the divider merely because the
commit-stage instruction is valid can produce a later, untracked write to its
FP destination even though the instruction was rejected. `exu/Core.scala` now
gates launch with `com_retire(0)`, which excludes trapping, killed and replayed
instructions.

Run `--suite shuttle-fdiv`. The bare-metal program seeds the destination with
9.0, disables FP, executes the rejected instruction, reenables FP in its trap
handler, and waits long enough to expose a stray completion. It also checks
legal 7.0/2.0 and sqrt(4.0) results. No interrupt masking workaround or OS is
part of this fix.
