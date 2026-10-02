// Interface-level trap arbitration test against the complete generated Rocket.
// The external vector backend presents completion/exception writeback at the
// scalar exception boundary. No internal state or generated logic is modified.
#include "VRocket.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
static constexpr uint64_t base=0x80000000ULL,handler=base+0x2000;
static uint32_t insn(uint64_t pc,unsigned mode,uint64_t fault) {
 switch(pc-base) {
 case 0:return 0x400012b7;case 4:return 0x00129293;
 case 8:return 0x30529073;case 12:return 0x08000293;
 case 16:return 0x30429073;case 20:return 0x30046073;
 case 0x2000:return 0x341022f3; // csrr t0,mepc
 case 0x2004:return (mode==1||mode==2||mode==4)?0x00428293:0x00028293;
 case 0x2008:return 0x34129073;case 0x200c:return 0x30200073;
 default:if(pc==fault&&mode==1)return 0xffffffff;
         if(pc==fault&&mode==4)return 0x00000073;
         return 0x00140413;
 }
}
static bool run(unsigned timing,unsigned mode,unsigned wbkind) {
 VRocket d;
 #include "zero_inputs.h"
 d.io_dmem_req_ready=1;d.io_dmem_ordered=1;d.io_fpu_fcsr_rdy=1;d.io_vector_ex_ready=1;
 const uint64_t fault=base+0x100+timing*4,stale=base+0x40;
 uint64_t fetch=base,last=0,saved=0,resume=0,expected_cause=0;bool trapped=false,returned=false,verified=false;unsigned overlaps=0;
 for(unsigned cycle=0;cycle<900;++cycle) {
  d.clock=0;d.reset=cycle<5;
  d.io_imem_resp_valid=cycle>=5;d.io_imem_resp_bits_pc=fetch;d.io_imem_resp_bits_data=insn(fetch,mode,fault);
  d.io_imem_resp_bits_xcpt_ae_inst=mode==2&&fetch==fault&&!trapped;
  d.io_interrupts_mtip=mode==0&&cycle>=80+timing&&!trapped;
  d.io_vector_wb_retire=0;d.io_vector_wb_xcpt=0;d.io_vector_wb_pc=stale;d.io_vector_wb_cause=13;d.io_vector_wb_inst=0x02000057;
  d.eval();
  if(!trapped&&d.dbg_scalar_exception) {
   ++overlaps;
   d.io_vector_wb_retire=wbkind==0;d.io_vector_wb_xcpt=wbkind==1;
   d.eval();
  } else if(!trapped&&mode==3&&cycle==80+timing) {
   d.io_vector_wb_xcpt=1;d.eval();
  }
  bool trap_now=d.dbg_exception||(mode==4&&d.dbg_system_redirect&&d.dbg_wb_inst==0x73);
  if(trap_now) {
   if(trapped)return false;
   const uint64_t expected=mode==0?last+4:mode==3?stale:fault;
   const uint64_t cause=mode==0?0x8000000000000007ULL:mode==1?2:mode==2?1:mode==4?11:13;
   expected_cause=cause;saved=d.dbg_trap_pc;resume=expected+((mode==1||mode==2||mode==4)?4:0);trapped=true;
   if(saved!=expected||(mode!=4&&d.dbg_cause!=cause)) {
    printf("TRAP_MISMATCH timing=%u mode=%u wbkind=%u expected=%lx actual=%lx cause=%lx\n",timing,mode,wbkind,expected,saved,uint64_t(d.dbg_cause));return false;
   }
  }
  if(trapped&&!trap_now&&!verified){if(d.dbg_saved_pc!=saved||d.dbg_saved_cause!=expected_cause){printf("SAVED_CSR_MISMATCH mode=%u\n",mode);return false;}verified=true;}
  if(d.dbg_retire&&!trap_now) {
   uint64_t pc=d.dbg_retire_pc;
   if(pc<handler||pc>=handler+16) {
    if(trapped){returned=pc==resume;break;}
    last=pc;
   }
  }
  bool advance=d.io_imem_resp_ready&&d.io_imem_resp_valid;
  bool redirect=d.io_imem_req_valid;uint64_t target=d.io_imem_req_bits_pc;
  d.clock=1;d.eval();
  if(redirect)fetch=target&0xffffffffffULL;else if(advance)fetch=(fetch&~3ULL)+4;
 }
 if(!trapped||!returned||(mode<3&&overlaps!=1)) {
  printf("TRAP_INCOMPLETE timing=%u mode=%u wbkind=%u trap=%u return=%u overlaps=%u saved=%lx\n",timing,mode,wbkind,trapped,returned,overlaps,saved);return false;
 }
 return true;
}
int main(int argc,char**argv) {
 Verilated::commandArgs(argc,argv);Verilated::randReset(0);unsigned total=0,failed=0;
 for(unsigned t=0;t<64;++t) {
  for(unsigned mode=0;mode<3;++mode)for(unsigned k=0;k<2;++k){++total;if(!run(t,mode,k))++failed;}
  ++total;if(!run(t,3,1))++failed;
  ++total;if(!run(t,4,0))++failed;
 }
 printf("TRAP_PC cases=%u passed=%u failed=%u\n",total,total-failed,failed);return failed?1:0;
}
