#include <stdint.h>
#include "trafficgen.h"

volatile uint64_t tohost __attribute__((section(".htif"), aligned(128)));
volatile uint64_t fromhost __attribute__((section(".htif"), aligned(128)));
static volatile unsigned stop, ready[4], done[4], failures[4];
static volatile unsigned rounds[4];
static volatile uint64_t cycles[4];
static uint64_t vin[4][40] __attribute__((aligned(128)));
static uint64_t vout[4][40] __attribute__((aligned(128)));
static double fin[4][40] __attribute__((aligned(128)));
static double fout[4][40] __attribute__((aligned(128)));
void int8_prepare(int); void fp32_prepare(int);
void int8_step(int); void fp32_step(int);
int int8_check(void); int fp32_check(void);

static inline void fence(void) { asm volatile("fence iorw,iorw" ::: "memory"); }
static uint64_t cycle(void) { uint64_t c; asm volatile("rdcycle %0":"=r"(c)); return c; }
static uint32_t rd(unsigned o) { return *(volatile uint32_t *)(uintptr_t)(0x5000+o); }
static void wr(unsigned o, uint32_t v) { *(volatile uint32_t *)(uintptr_t)(0x5000+o)=v; fence(); }
static void finish(unsigned code) {
  fence(); tohost=((uint64_t)code<<1)|1;
  for (;;) asm volatile("wfi");
}
static void say(const char *s) {
  uint64_t n=0; while(s[n]) ++n;
  volatile uint64_t args[8] __attribute__((aligned(128)))={64,1,(uint64_t)s,n};
  fence(); tohost=(uint64_t)args;
  while(!fromhost) {}
  fromhost=0; fence();
}
static unsigned vector_check(unsigned h) {
  unsigned errors=0;
  for(unsigned i=0;i<40;i++) { vin[h][i]=h*100+i; vout[h][i]=0; fin[h][i]=(double)i*.5; fout[h][i]=0; }
  for(unsigned offset=0;offset<37;) {
    unsigned long vl, n=37-offset;
    asm volatile("vsetvli %0,%1,e64,m1,ta,ma\n"
      "vle64.v v8,(%2)\n vadd.vi v8,v8,7\n vse64.v v8,(%3)"
      : "=&r"(vl):"r"(n),"r"(&vin[h][offset]),"r"(&vout[h][offset]):"v8","memory");
    asm volatile("vle64.v v8,(%0)\n vfadd.vv v8,v8,v8\n vse64.v v8,(%1)"
      ::"r"(&fin[h][offset]),"r"(&fout[h][offset]):"v8","memory");
    offset+=vl;
  }
  fence();
  for(unsigned i=0;i<40;i++) {
    errors+=vout[h][i] != (i<37?vin[h][i]+7:0);
    errors+=fout[h][i] != (i<37?(double)i:0);
  }
  return errors;
}
void smoke_main(unsigned long h) {
  failures[h]=vector_check(h); ready[h]=1; fence();
  if(h) {
    while(!stop) { failures[h]+=vector_check(h); rounds[h]++; fence(); }
    done[h]=1; fence(); return;
  }
  uint64_t start=cycle();
  while(!ready[1]||!ready[2]||!ready[3]) if(cycle()-start>1000000) finish(10);
  say("COMBINED_SMOKE: four harts passed startup; testing Saturn, dual Gemmini and TrafficGen\n");
  if(rd(TG_ABI_VERSION)!=3 || rd(TG_SLOT_COUNT)!=4) finish(11);
  int8_prepare(0); fp32_prepare(0);
  const unsigned ids[4]={1106,1003,1044,1001};
  for(unsigned s=0;s<4;s++) {
    wr(TG_SLOT(s)+TG_REGISTRY_LOW,ids[s]); wr(TG_SLOT(s)+TG_REGISTRY_HIGH,0);
    wr(TG_SLOT(s)+(s==3?TG_SUBMIT_AND_CLOSE:TG_SUBMIT),1);
  }
  start=cycle();
  while(rd(TG_SLOT(0)+TG_STATUS)!=TG_DISPATCHED) {
    if(rd(TG_SESSION_ERRORS) || rd(TG_SLOT(0)+TG_STATUS)>=TG_COMPLETE) finish(19);
    if(cycle()-start>1000000) finish(20);
  }
  cycles[0]=cycle();
  unsigned err=0, overlap=0;
  for(int r=0;r<8;r++) {
    if(r) { int8_prepare(r); fp32_prepare(r); }
    for(int step=0;step<9;step++) {
      for(unsigned s=0;s<4;s++) overlap += rd(TG_SLOT(s)+TG_STATUS)==TG_DISPATCHED;
      if(r&1) { fp32_step(step); int8_step(step); }
      else { int8_step(step); fp32_step(step); }
    }
    fence(); err+=int8_check()+fp32_check()+vector_check(0);
  }
  cycles[1]=cycle();
  start=cycle();
  while(!(rd(TG_SESSION_STATUS)&TG_SESSION_COMPLETE)) {
    if(rd(TG_SESSION_ERRORS) || (rd(TG_SESSION_STATUS)&(TG_SESSION_ERROR|TG_SESSION_TRUNCATED))) finish(12);
    if(cycle()-start>10000000) finish(13);
  }
  cycles[2]=cycle();
  if(rd(TG_SESSION_ERRORS)) finish(14);
  for(unsigned s=0;s<4;s++) if(rd(TG_SLOT(s)+TG_STATUS)!=TG_COMPLETE || rd(TG_SLOT(s)+TG_SLOT_ERRORS)) finish(15);
  stop=1; fence(); start=cycle();
  while(!done[1]||!done[2]||!done[3]) if(cycle()-start>1000000) finish(16);
  for(unsigned i=0;i<4;i++) err+=failures[i];
  if(!rounds[1]||!rounds[2]||!rounds[3]||!overlap) finish(17);
  if(err) finish(18);
  say("COMBINED_SMOKE PASS: four Saturn harts, eight dual-array matrix rounds, four completed TrafficGen launches, intact output guards\n");
  finish(0);
}
