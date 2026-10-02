# Rocket vector interrupt return-PC regression

Run `run_rtl.py --generated-sv /absolute/FireSim-generated.sv --work /new/scratch/output`.
Use `--expect-failure` only on the preserved hardware's generated RTL.

This compiles the actual generated Rocket module and its CSR/IBuf dependencies
with Verilator. Added outputs only observe existing signals; core logic is not
replaced. The fixture drives Saturn's real `block_all` and `trap_check_busy`
interface inputs to defer interrupt acceptance. It executes CSR setup, a linear
instruction stream, an interrupt handler, and MRET. The expected return address
comes from the last committed instruction, independently of speculative fetch
or instruction-buffer pointers.

The 8,640 cases cover 64 interrupt arrival cycles, nine stall durations
(including zero), five instruction layouts, and three instruction-fetch bubble
patterns. Layouts include aligned 32-bit, compressed 16-bit, and three mixed or
straddled streams. Every instruction fetch supplies its aligned 32-bit word;
the return-PC oracle decodes the instruction length at the last committed PC.
Each case checks trap delivery, the saved trap PC, and the first retired
instruction after MRET. The mixed-width cases reproduce a deadlock in the first
candidate that the earlier 432 aligned/compressed cases missed: IBuf must still
accept a fetch half when its output instruction is incomplete, even while an
interrupt is pending. This isolates the Rocket frontend protocol; it does not simulate
Saturn's vector datapath or replace the FPGA copy/pipeline acceptance tests.

The initial comparison used a directly edited generated ready expression while
Scala elaboration ran. Final acceptance must rerun this test on the fresh,
unmodified generated RTL from the candidate Scala source.
