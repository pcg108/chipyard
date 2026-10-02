// Execute the generated Rocket RTL, including its real IBuf and CSR pipeline.
// Drive Saturn's block_all input to exercise deferred interrupt acceptance.
// Include mixed-width streams and 32-bit instructions spanning fetch words.
#include "VRocket.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
static constexpr uint64_t base=0x80000000ULL, handler=0x80002000ULL;
static uint16_t halfword(uint64_t pc, unsigned mode) {
 uint64_t offset=pc-base;
 uint32_t word=0;
 switch(offset & ~3ULL) {
 case 0: word=0x400012b7;break;
 case 4: word=0x00129293;break;
 case 8: word=0x30529073;break;
 case 12:word=0x08000293;break;
 case 16:word=0x30429073;break;
 case 20:word=0x30046073;break;
 case 0x2000:word=0x34102573;break;
 case 0x2004:word=0x342025f3;break;
 case 0x2008:word=0x30200073;break;
 default:
  if(mode==1) return 0x0405;
  if(mode==2) { const uint16_t mixed[]={0x0405,0x0413,0x0014,0x0405};return mixed[((offset-24)/2)%4]; }
  if(mode==3) { if(offset==24)return 0x0405;return ((offset-26)&2)?0x0014:0x0413; }
  if(mode==4) { const uint16_t mixed[]={0x0413,0x0014,0x0405};return mixed[((offset-24)/2)%3]; }
  word=0x00140413;break;
 }
 return word >> ((offset & 2)*8);
}
static uint32_t instruction(uint64_t pc, unsigned mode) {
 const auto aligned=pc & ~3ULL;
 return uint32_t(halfword(aligned,mode)) | (uint32_t(halfword(aligned+2,mode)) << 16);
}
static bool run(unsigned trigger,unsigned delay,unsigned stream,unsigned bubbles,bool verbose) {
 VRocket d;
 #include "zero_inputs.h"
 d.io_dmem_req_ready=1;d.io_dmem_ordered=1;d.io_fpu_fcsr_rdy=1;d.io_vector_ex_ready=1;
 uint64_t fetch=base,last=0,expected=0,trap_pc=0;bool trapped=false,resumed=false;
 unsigned retirements=0;
 for(unsigned cycle=0;cycle<trigger+delay+200;cycle++) {
  d.clock=0;d.reset=cycle<5;
  d.io_imem_resp_valid=cycle>=5&&(!bubbles||cycle%bubbles!=0);
  d.io_imem_resp_bits_pc=fetch;d.io_imem_resp_bits_data=instruction(fetch,stream);
  d.io_interrupts_mtip=cycle>=trigger&&!trapped;
  d.io_vector_mem_block_all=cycle>=trigger&&cycle<trigger+delay;
  d.io_vector_trap_check_busy=d.io_vector_mem_block_all;
  d.eval();
  if(d.dbg_exception) {
   if(trapped||d.dbg_cause!=0x8000000000000007ULL||!last) {
    printf("unexpected trap cycle=%u cause=%lx pc=%lx\n",cycle,uint64_t(d.dbg_cause),uint64_t(d.dbg_trap_pc));return false;
   }
   // Architectural oracle: the stream is linear. Resume immediately after the
   // last committed instruction, irrespective of speculative fetch/IBuf PCs.
   expected=last+((halfword(last,stream)&3)==3?4:2);
   trap_pc=d.dbg_trap_pc;trapped=true;
   if(verbose||trap_pc!=expected)printf("IRQ trigger=%u delay=%u stream=%u bubbles=%u last=%lx expected=%lx actual=%lx\n",trigger,delay,stream,bubbles,last,expected,trap_pc);
  }
  if(d.dbg_retire) {
   const uint64_t pc=d.dbg_retire_pc;
   if(pc<handler||pc>=handler+12) {
    if(trapped) {resumed=true;if(pc!=expected)return false;break;}
    last=pc;retirements++;
   }
  }
  const bool advance=d.io_imem_resp_ready&&d.io_imem_resp_valid;
  const bool redirect=d.io_imem_req_valid;const uint64_t target=d.io_imem_req_bits_pc;
  d.clock=1;d.eval();
  if(redirect)fetch=target&0xffffffffffULL;
  else if(advance)fetch=(fetch&~3ULL)+4;
 }
 if(!trapped||!resumed) {printf("missing trap/resume trigger=%u delay=%u stream=%u bubbles=%u retired=%u\n",trigger,delay,stream,bubbles,retirements);printf("ibuf=%lx interrupt=%u\n",uint64_t(d.dbg_ibuf_pc),d.dbg_interrupt);return false;}
 return trap_pc==expected;
}
int main(int argc,char**argv) {
 Verilated::commandArgs(argc,argv);Verilated::randReset(0);
 unsigned total=0,failed=0;
 for(unsigned trigger=80;trigger<144;++trigger)
 for(unsigned delay: {0u,1u,2u,3u,4u,8u,16u,31u,64u})
 for(unsigned stream: {0u,1u,2u,3u,4u})
 for(unsigned bubbles: {0u,5u,7u}) {++total;if(!run(trigger,delay,stream,bubbles,false))++failed;}
 printf("IRQ_REGRESSION cases=%u passed=%u failed=%u\n",total,total-failed,failed);
 return failed?1:0;
}
