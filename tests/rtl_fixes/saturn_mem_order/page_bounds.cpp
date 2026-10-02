#include "VMem.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
static unsigned checks=0,failures=0;
static void tick(VMem &d) {d.clock=0;d.eval();d.clock=1;d.eval();d.clock=0;d.eval();}
static void reset(VMem &d) {
#include "zero_inputs.h"
 d.reset=1;for(unsigned i=0;i<5;++i)tick(d);d.reset=0;tick(d);
}
static void check(VMem &d,uint64_t address,bool scalar_store,bool expected,unsigned size,unsigned base,unsigned start,bool vector_store) {
 d.io_scalar_check_addr=address;d.io_scalar_check_store=scalar_store;d.eval();++checks;
 if(bool(d.io_scalar_check_conflict)!=expected) {
  ++failures;
  if(failures<=16)std::printf("PAGE_BOUND_MISMATCH elem_bytes=%u base=%u vstart=%u vector_store=%u scalar_store=%u addr=%llx conflict=%u expected=%u\n",size,base,start,unsigned(vector_store),unsigned(scalar_store),(unsigned long long)address,unsigned(d.io_scalar_check_conflict),unsigned(expected));
 }
}
int main(int argc,char **argv) {
 Verilated::commandArgs(argc,argv);VMem d;
 for(unsigned es=0;es<4;++es)for(unsigned consumed=1;consumed<8;++consumed)for(unsigned vs=0;vs<2;++vs)for(unsigned resumed=0;resumed<2;++resumed) {
  reset(d);const unsigned bytes=1u<<es,base=4096-consumed*bytes;
  const unsigned start=resumed?consumed:0,vl=resumed?8:consumed,page=0x80000+resumed;
  d.io_enq_bits_base_offset=base;d.io_enq_bits_page=page;d.io_enq_bits_vstart=start;d.io_enq_bits_vl=vl;
  d.io_enq_bits_elem_size=es;d.io_enq_bits_store=vs;d.io_enq_bits_vm=1;d.io_enq_valid=1;d.eval();
  if(!d.io_enq_ready){std::puts("PAGE_BOUND_SETUP_FAILED enq_not_ready");return 2;}
  tick(d);d.io_enq_valid=0;d.eval();
  if(!d.io_busy){std::puts("PAGE_BOUND_SETUP_FAILED no_pending_operation");return 2;}
  // No data or acknowledgments are supplied, so the operation remains pending.
  for(unsigned e=start;e<vl;++e)for(unsigned scalar_store=0;scalar_store<2;++scalar_store) {
   uint64_t address=(uint64_t(page)<<12)+((base+e*bytes)&4095);
   check(d,address,scalar_store,vs||scalar_store,bytes,base,start,vs);
  }
  check(d,uint64_t(page+4)<<12,true,false,bytes,base,start,vs);
 }
 std::printf("SATURN_PAGE_BOUNDS checks=%u passed=%u failed=%u\n",checks,checks-failures,failures);
 return failures?1:0;
}
