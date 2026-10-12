#!/usr/bin/env python3
"""Compile the combined smoke test against this elaboration's Gemmini headers."""
import argparse
from pathlib import Path
import shutil
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
src=Path(__file__).resolve().parent
root=src.parents[2]
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
prefix=root/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-'
cc=str(prefix)+'gcc'
flags=['-march=rv64gcv','-mabi=lp64d','-mcmodel=medany','-O2','-ffreestanding',
       '-fno-common','-fno-builtin','-ffunction-sections','-fdata-sections',
       '-fno-tree-vectorize','-mno-relax','-Wall','-Werror=implicit-function-declaration']
objects=[]
def compile(source,name,extra=()):
    obj=out/(name+'.o')
    subprocess.run([cc,*flags,*extra,'-c',str(source),'-o',str(obj)],check=True)
    objects.append(obj)
    return obj
for kind,dim,opcode,header in [('fp32',4,3,'gemmini_params_illixr.h'),('int8',16,2,'gemmini_params_illixr_int8.h')]:
    inc=out/kind/'include';inc.mkdir(parents=True,exist_ok=True)
    shutil.copy2(root/header,inc/'gemmini_params.h')
    extra=['-I'+str(out/kind),'-I'+str(src/'vendor'),'-DPREFIX='+kind,
           '-DEXPECT_DIM='+str(dim),'-DEXPECT_OPCODE='+str(opcode)]
    if kind=='fp32': extra+=['-DFLOAT_ARRAY']
    obj=compile(src/'array.c',kind,extra)
    # The upstream header defines non-static helper functions. Only these three
    # fixture functions should be global when linking the two parameterizations.
    subprocess.run([str(prefix)+'objcopy',*[f'--keep-global-symbol={kind}_{f}' for f in ('prepare','step','check')],str(obj)],check=True)
compile(src/'start.S','start')
compile(src/'main.c','main',['-I'+str(root/'scripts/trafficgen/workloads/trafficgen-hello')])
subprocess.run([cc,*flags,'-nostdlib','-nostartfiles','-Wl,--gc-sections',
    '-T'+str(src/'link.ld'),*[str(o) for o in objects],'-lgcc','-o',str(out/'smoke.elf')],check=True)
print(out/'smoke.elf')
