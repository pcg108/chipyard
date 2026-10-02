#include "VMem.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
#include <initializer_list>
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
static bool enqueue(VMem &d,unsigned base,unsigned start,unsigned vl,unsigned page,
                    unsigned es,bool store,unsigned nf=0,unsigned segstart=0,bool whole=false) {
 d.io_enq_bits_base_offset=base;d.io_enq_bits_page=page;
 d.io_enq_bits_vstart=start;d.io_enq_bits_vl=vl;d.io_enq_bits_elem_size=es;
 d.io_enq_bits_store=store;d.io_enq_bits_vm=1;d.io_enq_bits_nf=nf;
 d.io_enq_bits_segstart=segstart;d.io_enq_bits_segend=whole?0:nf;
 d.io_enq_bits_whole_reg=whole;d.io_enq_valid=1;d.eval();
 if(!d.io_enq_ready)return false;
 tick(d);d.io_enq_valid=0;d.eval();return bool(d.io_busy);
}
int main(int argc,char **argv) {
 Verilated::commandArgs(argc,argv);VMem d;
 const unsigned fields_list[]={2,3,4,8};
 for(unsigned es=0;es<4;++es)for(unsigned fields:fields_list)
 for(unsigned start=1;start<8;++start)for(unsigned segment=0;segment<fields;++segment)
 for(unsigned store=0;store<2;++store) {
  reset(d);const unsigned bytes=1u<<es,base=4096-(start*fields+segment)*bytes;
  if(!enqueue(d,base,start,8,0x80001,es,store,fields-1,segment))return 2;
  // Only active elements on the translated page. Holes are conservatively covered.
  for(unsigned e=start;e<8;++e)for(unsigned field=(e==start?segment:0);field<fields;++field)
  for(unsigned ss=0;ss<2;++ss) {
   uint64_t address=0x80001000ull+((base+(e*fields+field)*bytes)&4095);
   check(d,address,ss,store||ss,bytes,base,start,store);
  }
  check(d,0x80005000ull,true,false,bytes,base,start,store);
 }
 std::printf("SATURN_SEGMENT_CHECKPOINT checks=%u failed=%u\n",checks,failures);
 for(unsigned es=0;es<4;++es)for(unsigned nf:{1u,3u,7u})
 for(unsigned start=1;start<8;++start)for(unsigned store=0;store<2;++store) {
  reset(d);const unsigned bytes=1u<<es,base=4096-start*bytes;
  if(!enqueue(d,base,start,8,0x80001,es,store,nf,0,true))return 2;
  for(unsigned e=start;e<8;++e)for(unsigned ss=0;ss<2;++ss)
   check(d,0x80001000ull+((base+e*bytes)&4095),ss,store||ss,bytes,base,start,store);
  check(d,0x80005000ull,true,false,bytes,base,start,store);
 }
 std::printf("SATURN_WHOLE_REGISTER_CHECKPOINT checks=%u failed=%u\n",checks,failures);
 // Check vector store-to-load ordering in both argument orientations of overlaps.
 // A normal slice accesses offset zero, which overlaps a resumed cross-page slice.
 // Withheld store data keeps the older store pending. No responses are fabricated.
 for(unsigned es=0;es<4;++es)for(unsigned start=1;start<8;++start)
 for(unsigned resumed_store=0;resumed_store<2;++resumed_store)
 for(unsigned other_page=0;other_page<2;++other_page) {
  reset(d);const unsigned bytes=1u<<es,base=4096-start*bytes;
  d.io_vu_lresp_ready=1;
  if(!enqueue(d,resumed_store?base:0,resumed_store?start:0,resumed_store?8:1,0x80001,es,true))return 2;
  if(!enqueue(d,resumed_store?0:base,resumed_store?0:start,resumed_store?1:8,0x80001+other_page,es,false))return 2;
  bool observed=false;
  for(unsigned cycle=0;cycle<24;++cycle){d.eval();observed|=bool(d.io_dmem_load_req_valid);tick(d);}
  ++checks;
  if(observed!=bool(other_page)) {
   ++failures;if(failures<=16)std::printf("VECTOR_ORDER_MISMATCH bytes=%u start=%u resumed_store=%u other_page=%u request=%u\n",bytes,start,resumed_store,other_page,unsigned(observed));
  }
 }
 std::printf("SATURN_PAGE_BOUNDS checks=%u passed=%u failed=%u\n",checks,checks-failures,failures);
 return failures?1:0;
}
