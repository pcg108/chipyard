.option norvc
.option norelax
.section .text
.globl _start
_start:
 li t0,0x6000
 csrs mstatus,t0
 la t0,trap
 csrw mtvec,t0
 li s2,0
 li t6,0
 // A preceding divide leaves recognizable FP pipeline operands.
 li t0,0x3ff0000000000000
 fmv.d.x fs0,t0
 fmv.d.x fs1,zero
 fdiv.d ft0,fs0,fs1
 frflags t0
#if SQRT
 li t0,0x4010000000000000
#else
 li t0,0x401c000000000000
#endif
 fmv.d.x fs0,t0
 li t0,0x4000000000000000
 fmv.d.x fs1,t0
 li t0,0x4022000000000000
 fmv.d.x fa0,t0
 frflags t0
#if REJECT
 li t0,0x6000
 csrc mstatus,t0
#endif
.globl tested_instruction
tested_instruction:
#if SQRT
 fsqrt.d fa0,fs0
#else
 fdiv.d fa0,fs0,fs1
#endif
 frflags t0
 fmv.x.d a0,fa0
 li t6,1
finished: j finished
.balign 4
trap:
 csrr s3,mcause
 csrr s4,mepc
 addi s2,s2,1
 li t0,0x6000
 csrs mstatus,t0
 // Permit any wrongly launched operation to finish before checking fa0.
 .rept 256
 nop
 .endr
 csrr t0,mepc
 addi t0,t0,4
 csrw mepc,t0
 mret
