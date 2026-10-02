#!/usr/bin/env python3
"""Probe and test actual generated Rocket RTL; do not replace its pipeline logic."""
import argparse,hashlib,json,re,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--generated-sv',type=Path,required=True)
p.add_argument('--work',type=Path,required=True)
p.add_argument('--verilator',default='/home/prashanth/chipyard/.conda-env/bin/verilator')
p.add_argument('--expect-failure',action='store_true',help='Only for the preserved negative control')
a=p.parse_args();a.work.mkdir(parents=True,exist_ok=False)
source=a.generated_sv.read_text();start=source.index('module Rocket(');end=source.index('endmodule',start);module=source[start:end];ports=module[:module.index(');')]
inputs=re.findall(r'^\s*input\s+(?:\[([^]]+)\]\s+)?(\w+)',ports,re.M)
for width,name in inputs:
 if width:
  high,low=map(int,width.split(':'));assert high-low<64,(name,width)
(a.work/'zero_inputs.h').write_text('\n'.join('  d.'+name+' = 0;' for _,name in inputs)+'\n')
probes={'dbg_interrupt':('','csr_io_interrupt'),'dbg_ibuf_pc':('[39:0]','ibuf_io_pc'),'dbg_exception':('','csr_io_exception'),'dbg_trap_pc':('[39:0]','csr_io_pc'),'dbg_cause':('[63:0]','csr_io_cause'),'dbg_retire':('','wb_valid'),'dbg_retire_pc':('[39:0]','wb_reg_pc')}
ports_new=ports+',\n'+',\n'.join('  output '+width+' '+name for name,(width,_) in probes.items())+'\n'
module=ports_new+module[len(ports):]+'\n'+'\n'.join('assign '+name+' = '+signal+';' for name,(_,signal) in probes.items())+'\n'
(a.work/'probed.sv').write_text(source[:start]+module+source[end:])
for name in ('AbstractClockGate.v','plusarg_reader.v'):shutil.copy2(a.generated_sv.parent/name,a.work/name)
shutil.copy2(Path(__file__).with_name('interrupt_pc.cpp'),a.work/'test.cpp')
cmd=['nice','-n','10',a.verilator,'--cc','--exe','--build','-j','4','--top-module','Rocket','--prefix','VRocket','-Wno-fatal','-DPRINTF_COND=0','--Mdir',str(a.work/'build'),str(a.work/'probed.sv'),str(a.work/'AbstractClockGate.v'),str(a.work/'plusarg_reader.v'),str(a.work/'test.cpp')]
with (a.work/'build.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
with (a.work/'run.log').open('w') as log:run=subprocess.run([str(a.work/'build/VRocket')],stdout=log,stderr=subprocess.STDOUT)
s=(a.work/'run.log').read_text();match=re.search(r'IRQ_REGRESSION cases=(\d+) passed=(\d+) failed=(\d+)',s);assert match,'Incomplete RTL run'
total,passed,failed=map(int,match.groups());assert total==8640 and passed+failed==total
accepted=(run.returncode==1 and failed>0) if a.expect_failure else (run.returncode==0 and failed==0)
record=dict(passed=accepted,negative_control=a.expect_failure,generated_source=str(a.generated_sv),source_sha256=hashlib.sha256(a.generated_sv.read_bytes()).hexdigest(),driver_sha256=hashlib.sha256((a.work/'test.cpp').read_bytes()).hexdigest(),compile_command=cmd,exit_code=run.returncode,cases=total,checks_passed=passed,checks_failed=failed,scope='Generated Rocket RTL with observation-only probes; real CSR/IBuf pipeline; external Saturn block_all and trap_check_busy stimuli. Checks architectural trap PC and first retirement after MRET.')
(a.work/'result.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record,indent=2));raise SystemExit(0 if accepted else 1)
