.section .text.init,"ax"
.globl _start
_start:
  csrw mie, zero
  csrr a0, mhartid
  li t0, 4
  bgeu a0, t0, park
  .option push
  .option norelax
  la gp, __global_pointer$
  .option pop
  la sp, stacks
  addi t0, a0, 1
  slli t0, t0, 14
  add sp, sp, t0
  la t0, trap
  csrw mtvec, t0
  li t0, 0x1e600
  csrs mstatus, t0
  csrw fcsr, zero
  bnez a0, wait_init
  la t0, __bss_start
  la t1, __bss_end
clear_bss:
  bgeu t0, t1, init_done
  sd zero, 0(t0)
  addi t0, t0, 8
  j clear_bss
init_done:
  fence rw,rw
  la t0, initialized
  li t1, 1
  sd t1, 0(t0)
wait_init:
  la t0, initialized
  ld t1, 0(t0)
  beqz t1, wait_init
  fence rw,rw
  call smoke_main
park:
  wfi
  j park
.balign 4
trap:
  csrr t0, mcause
  csrr t1, mhartid
  slli t1, t1, 8
  or t0, t0, t1
  ori t0, t0, 0x400
  slli t0, t0, 1
  ori t0, t0, 1
  la t1, tohost
  sd t0, 0(t1)
  j park
.section .data
.balign 8
initialized: .dword 0
.section .bss
.balign 128
stacks: .skip 65536
