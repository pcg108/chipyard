# Saturn resumed-page dependency regression

From the Chipyard root:

```sh
python3 tests/rtl_fixes/run.py --suite saturn-memory --work /scratch/saturn-memory-new --jobs 4
```

The runner elaborates current Scala sources, compiles the actual generated
`VectorMemUnit` with Verilator assertions, and repeats the checks in an isolated
checkout with only the dependency-bound fix removed. Internal logic is not
replaced, and saved historical Verilog is not an input.

The 1,008 basic checks cover 1-, 2-, 4-, and 8-byte elements and seven page-split
positions. Pending vector stores must block overlapping scalar reads/writes;
pending vector loads must block overlapping scalar writes. Scalar reads may
coexist with vector loads. Bounds operate at cache-block granularity.

The 39,984 extended checks cover 2/3/4/8-field segmented operations, nonzero
segment starts, whole-register encodings for 2/4/8 registers, and vector
store-to-load dependencies. Resumed and ordinary slices occur in both age
orders. Withholding store data tests whether a younger load issues; unrelated
page controls must make progress, so stalling everything cannot pass.

These tests inspect dependency decisions. They do not exercise complete memory
transfers, load-to-store ordering, or the full SoC. See [the fix description](../FIXES.md)
and the top-level validation report for the tested source revisions and counts.
