#!/usr/bin/env python3
"""Test the real generated VectorMemUnit's cross-page scalar hazard checks."""
import argparse,hashlib,json,re,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--generated-sv',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--expect-failure',action='store_true');p.add_argument('--extended',action='store_true');a=p.parse_args();a.work.mkdir(parents=True,exist_ok=False)
s=a.generated_sv.read_text();start=s.index('module VectorMemUnit(');ports=s[start:s.index(');',start)]
inputs=re.findall(r'^\s*input\s+(?:\[([^]]+)\]\s+)?(\w+)',ports,re.M);zero=[]
for width,name in inputs:
 bits=int(width.split(':')[0])+1 if width else 1
 if bits<=64:zero.append('d.'+name+'=0;')
 else:zero.extend('d.'+name+'['+str(i)+']=0;' for i in range((bits+31)//32))
(a.work/'zero_inputs.h').write_text('\n'.join(zero)+'\n')
for name in ['AbstractClockGate.v','plusarg_reader.v']:shutil.copy2(a.generated_sv.parent/name,a.work/name)
driver='page_bounds_extended.cpp' if a.extended else 'page_bounds.cpp'
shutil.copy2(Path(__file__).with_name(driver),a.work/'test.cpp')
cmd=['nice','-n','10','/home/prashanth/chipyard/.conda-env/bin/verilator','--cc','--exe','--build','--assert','-j','4','--top-module','VectorMemUnit','--prefix','VMem','-Wno-fatal','-DPRINTF_COND=0','--Mdir',str(a.work/'build'),str(a.generated_sv),str(a.work/'AbstractClockGate.v'),str(a.work/'plusarg_reader.v'),str(a.work/'test.cpp')]
with (a.work/'build.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
with (a.work/'run.log').open('w') as log:r=subprocess.run([str(a.work/'build/VMem')],stdout=log,stderr=subprocess.STDOUT)
text=(a.work/'run.log').read_text();m=re.search(r'SATURN_PAGE_BOUNDS checks=(\d+) passed=(\d+) failed=(\d+)',text)
assert m,'Incomplete generated RTL regression';total,passed,failed=map(int,m.groups());assert total==(39984 if a.extended else 1008) and passed+failed==total
accepted=(r.returncode==1 and failed>0) if a.expect_failure else (r.returncode==0 and failed==0)
record={'passed':accepted,'negative_control':a.expect_failure,'extended':a.extended,'exit_code':r.returncode,'checks':total,'checks_passed':passed,'checks_failed':failed,'source':str(a.generated_sv),'source_sha256':hashlib.sha256(a.generated_sv.read_bytes()).hexdigest(),'driver_sha256':hashlib.sha256((a.work/'test.cpp').read_bytes()).hexdigest(),'command':cmd,'scope':('Actual generated Saturn VectorMemUnit, unmodified logic. Segmented and whole-register scalar hazards, plus vector store-to-load ordering and nonconflicting-page controls.' if a.extended else 'Actual generated Saturn VectorMemUnit, unmodified logic. Pending loads/stores split across page boundaries; assert conflicting scalar operations are blocked.')}
(a.work/'result.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record,indent=2));raise SystemExit(0 if accepted else 1)
