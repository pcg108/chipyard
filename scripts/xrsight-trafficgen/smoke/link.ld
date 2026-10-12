OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { ram (rwx) : ORIGIN = 0x80000000, LENGTH = 256M }
SECTIONS {
  . = ORIGIN(ram);
  .text : { *(.text.init) *(.text .text.*) } > ram
  .rodata : { *(.rodata .rodata.*) } > ram
  . = ALIGN(128);
  .data : { *(.data .data.*) } > ram
  .sdata : { __global_pointer$ = . + 0x800; *(.sdata .sdata.*) } > ram
  .htif : ALIGN(128) { KEEP(*(.htif)) } > ram
  .bss (NOLOAD) : ALIGN(128) {
    __bss_start = .; *(.bss .bss.* .sbss .sbss.* COMMON)
    . = ALIGN(8); __bss_end = .;
  } > ram
  _end = .;
}
