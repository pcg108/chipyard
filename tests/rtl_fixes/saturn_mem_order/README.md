# Saturn cross-page dependency regression

`run_rtl.py` compiles the actual generated `VectorMemUnit` with Verilator and
assertions enabled. No internal logic is replaced. It enqueues pending unit-stride
loads/stores in first-page and resumed-next-page forms, holds acknowledgments,
and checks scalar hazards at every active element plus an unrelated-page control.

The 1,008 checks cover 1-, 2-, 4-, and 8-byte elements and seven split positions.
A pending vector store must block overlapping scalar reads/writes; a pending
vector load must block overlapping scalar writes. Scalar reads may coexist with
pending vector loads. Bounds operate at cache-block granularity.

Use `--expect-failure` only for the preserved uncorrected hardware. A successful
negative-control runner means the hardware defect was reproduced; it does not
mean the hardware passed. The corrected generated source must pass without that
option. This regression alone does not validate the complete SoC or FPGA.

`--extended` adds 39,984 checks covering 2/3/4/8-field segmented operations
(including nonzero segment starts), whole-register operations with 2/4/8
register encodings, and vector store-to-load dependencies. The latter enqueues
a resumed slice and an ordinary slice in both age orders, withholding store
data and checking whether the younger load issues. An unrelated-page control
must issue, so permanently stalling all traffic does not pass. This test does
not yet exercise load-to-store dependencies or complete memory transfers.

The fix is in [saturn-cross-page-dependency.patch](../../patches/saturn-cross-page-dependency.patch).
It computes the active page's lower dependency offset from `base_offset`,
`vstart`, and `segstart`. Regenerate the hardware after applying the patch;
testing an existing generated file does not apply the fix to that file.

From the repository root, run both suites against each generated configuration:

```sh
python3 tests/saturn_mem_order/run_rtl.py \
  --generated-sv /absolute/path/to/FireSim-generated.sv \
  --work /scratch/saturn-basic-new
python3 tests/saturn_mem_order/run_rtl.py \
  --generated-sv /absolute/path/to/FireSim-generated.sv \
  --work /scratch/saturn-extended-new --extended
```

Each work directory must be new. The generated file's directory must contain
`AbstractClockGate.v` and `plusarg_reader.v`. The runner currently uses the
installed Verilator at `/home/prashanth/chipyard/.conda-env/bin/verilator` and
four build workers. It saves build/run logs and a `result.json` containing
source and test-driver hashes, counts, and exit status.

For the old generated hardware, use separate work directories and add
`--expect-failure`. Our preserved old design failed 336 basic checks and
29,120 extended checks. Both corrected single- and quad-core designs passed
all 40,992 checks per configuration.

The [FPGA results](../../docs/gemmini-openblas.md#corrected-quad-core-fpga-results-2026-09-30-utc)
also record unchanged-firmware reproductions and complete ILLIXR runs on the
corrected images. Those results and the full standalone RTL suites have
separate acceptance requirements; passing this module test alone is insufficient.
