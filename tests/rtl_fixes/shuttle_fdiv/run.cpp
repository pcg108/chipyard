// Bare-metal instruction frontend for the complete generated ShuttleCore.
// Actual decode, CSR, FP registers, divider and retirement logic execute here.
#include "VShuttle.h"
#include "verilated.h"
#include <cstdio>
#include <cstdint>
#include <vector>
#include <fstream>
#include <iterator>
static bool run(const char* file,bool reject,bool sqrt,unsigned bubbles) {
 std::ifstream f(file,std::ios::binary);std::vector<uint8_t> code((std::istreambuf_iterator<char>(f)),{});
 if(code.empty())return false;
 VShuttle d;
 #include "zero_inputs.h"
 d.io_dmem_req_ready=1;d.io_dmem_ordered=1;
 d.io_vector_ex_ready=1;d.io_vector_com_scalar_check_ready=1;
 uint64_t pc=0x80000000ULL;unsigned traps=0;
 for(unsigned cycle=0;cycle<20000;++cycle) {
  d.clock=0;d.reset=cycle<5;
  uint64_t offset=pc-0x80000000ULL;
  if(offset+4>code.size()){printf("FETCH_OUTSIDE pc=%lx\n",pc);return false;}
  uint32_t inst=code[offset]|uint32_t(code[offset+1])<<8|uint32_t(code[offset+2])<<16|uint32_t(code[offset+3])<<24;
  d.io_imem_resp_0_valid=cycle>=5&&(!bubbles||cycle%bubbles);
  d.io_imem_resp_0_bits_inst=inst;d.io_imem_resp_0_bits_raw_inst=inst;d.io_imem_resp_0_bits_pc=pc;
  d.io_imem_resp_0_bits_next_pc_valid=1;d.io_imem_resp_0_bits_next_pc_bits=pc+4;
  d.eval();
  if(d.dbg_exception){++traps;if(d.dbg_cause!=2||!reject){printf("BAD_TRAP cause=%lx\n",uint64_t(d.dbg_cause));return false;}}
  if(d.dbg_done) {
   uint64_t expected=reject?0x4022000000000000ULL:sqrt?0x4000000000000000ULL:0x400c000000000000ULL;
   bool ok=d.dbg_result==expected&&traps==unsigned(reject)&&d.dbg_trap_count==unsigned(reject);
   printf("SHUTTLE_CASE reject=%u sqrt=%u bubbles=%u expected=%lx actual=%lx traps=%u pass=%u\n",reject,sqrt,bubbles,expected,uint64_t(d.dbg_result),traps,ok);return ok;
  }
  bool advance=d.io_imem_resp_0_valid&&d.io_imem_resp_0_ready;
  bool redirect=d.io_imem_redirect_val;uint64_t target=d.io_imem_redirect_pc;
  d.clock=1;d.eval();if(redirect)pc=target&0xffffffffffULL;else if(advance)pc+=4;
 }
 printf("SHUTTLE_TIMEOUT reject=%u sqrt=%u bubbles=%u\n",reject,sqrt,bubbles);return false;
}
int main(int argc,char**argv) {
 Verilated::commandArgs(argc,argv);Verilated::randReset(0);if(argc!=5)return 2;
 unsigned total=0,failed=0;
 for(unsigned reject=0;reject<2;++reject)for(unsigned sqrt=0;sqrt<2;++sqrt)for(unsigned bubbles:{0u,3u,5u}) {
  ++total;if(!run(argv[1+reject*2+sqrt],reject,sqrt,bubbles))++failed;
 }
 printf("SHUTTLE_FDIV cases=%u passed=%u failed=%u\n",total,total-failed,failed);return failed?1:0;
}
