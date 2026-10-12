/* Compile separately with each generated ABI: never mix accelerator typedefs. */
#include "gemmini.h"
#include <stdint.h>
#ifndef PREFIX
#error PREFIX required
#endif
#define CAT_(a,b) a##b
#define CAT(a,b) CAT_(a,b)
#define FN(n) CAT(PREFIX,n)
_Static_assert(XCUSTOM_ACC == EXPECT_OPCODE, "opcode mismatch");
_Static_assert(DIM == EXPECT_DIM, "array geometry mismatch");
static elem_t a[DIM][DIM] __attribute__((aligned(64)));
static elem_t b[DIM][DIM] __attribute__((aligned(64)));
static struct { uint64_t before[8]; elem_t c[DIM][DIM]; uint64_t after[8]; } output __attribute__((aligned(64)));
static elem_t expected[DIM][DIM];
void FN(_prepare)(int round) {
 for(int q=0;q<8;q++)output.before[q]=output.after[q]=0xabcdef0192837465ULL;
 for(int i=0;i<DIM;i++)for(int j=0;j<DIM;j++) {
#ifdef FLOAT_ARRAY
  a[i][j]=((i*3+j+round)%7-3)*.25f;
  b[i][j]=((i+j*2+round)%5-2)*.5f;
#else
  a[i][j]=(i*3+j+round)%5-2;
  b[i][j]=(i+j*2+round)%3-1;
#endif
  output.c[i][j]=(elem_t)99;
 }
 for(int i=0;i<DIM;i++)for(int j=0;j<DIM;j++) {
  acc_t sum=0;for(int k=0;k<DIM;k++)sum+=(acc_t)a[i][k]*b[k][j];
  expected[i][j]=(elem_t)sum;
 }
 asm volatile("fence rw,rw" ::: "memory");
}
/* Identical local addresses deliberately check that each array has private state. */
void FN(_step)(int stage) {
 switch(stage) {
 case 0:gemmini_flush(0);break;
 case 1:gemmini_config_ld(DIM*sizeof(elem_t));break;
 case 2:gemmini_config_ex(WS,0,0);break;
 case 3:gemmini_config_st(DIM*sizeof(elem_t));break;
 case 4:gemmini_mvin(a,0);break;
 case 5:gemmini_mvin(b,DIM);break;
 case 6:gemmini_preload(DIM,1u<<(ADDR_LEN-1));break;
 case 7:gemmini_compute_preloaded(0,GARBAGE_ADDR);break;
 case 8:gemmini_mvout(output.c,1u<<(ADDR_LEN-1));break;
 }
}
int FN(_check)(void) {
 asm volatile("fence rw,rw" ::: "memory");
 int errors=0;
 for(int q=0;q<8;q++)errors+=output.before[q]!=0xabcdef0192837465ULL || output.after[q]!=0xabcdef0192837465ULL;
 for(int i=0;i<DIM;i++)for(int j=0;j<DIM;j++)errors+=output.c[i][j]!=expected[i][j];
 return errors;
}
