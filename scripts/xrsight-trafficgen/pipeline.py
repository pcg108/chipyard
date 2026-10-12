#!/usr/bin/env python3
"""Run ordered validation/build stages and leave an explicit machine-readable status."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

p=argparse.ArgumentParser()
p.add_argument('--work',type=Path,default=Path('/scratch/prashanth_xrsight_trafficgen_20261011'))
p.add_argument('--after-elaboration-guard',type=Path)
p.add_argument('--after-smoke-result',type=Path,
               help='Resume FPGA build after a passing smoke run of unchanged hardware')
a=p.parse_args()
work=a.work.resolve();root=Path(__file__).resolve().parents[2];pkg=Path(__file__).resolve().parent
status={'source':str(root),'source_commit':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),'state':'running','stage':'starting'}
def save(**items):
    status.update(items,updated_at=datetime.now(timezone.utc).isoformat())
    tmp=work/'pipeline-status.tmp';tmp.write_text(json.dumps(status,indent=2)+'\n');tmp.replace(work/'pipeline-status.json')
    print(json.dumps(status),flush=True)
def run(stage,command):
    save(stage=stage,command=command)
    subprocess.run(command,cwd=root,check=True)
try:
    if a.after_smoke_result:
        smoke_result=a.after_smoke_result.resolve()
        smoke_record=json.loads(smoke_result.read_text())
        if smoke_record.get('status')!='passed' or smoke_record.get('harts')!=4 or smoke_record.get('dual_array_rounds')!=8:
            raise RuntimeError('Cannot resume without a passing combined smoke result')
        smoke=smoke_result.parent
        save(stage='resume_after_smoke',smoke_result=str(smoke_result))
    elif a.after_elaboration_guard:
        save(stage='waiting_for_elaboration',guard=str(a.after_elaboration_guard))
        result=a.after_elaboration_guard/'guard-result.json'
        while not result.exists(): time.sleep(5)
        r=json.loads(result.read_text())
        if r.get('command_exit_code')!=0: raise RuntimeError(f'Elaboration did not pass: {r}')
    else:
        run('elaboration',['bash',str(pkg/'build.sh'),'elaborate'])
    run('check_elaboration',[sys.executable,str(pkg/'check-elaboration.py'),'--output',str(work/'validation/elaboration.json')])
    if not a.after_smoke_result:
        run('metasim_build',['bash',str(pkg/'build.sh'),'metasim'])
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        smoke=work/'validation'/('smoke-'+stamp)
        run('combined_smoke',[sys.executable,str(pkg/'firesim_resource_guard.py'),'--scratch',str(work),
            '--run-dir',str(work/('guard-smoke-'+stamp)),'--latch',str(work/'STOPPED_NO_AUTORESTART.json'),
            '--jobs','4','--',sys.executable,str(pkg/'run-smoke.py'),'--work',str(smoke)])
    save(smoke_result=str(smoke/'result.json'))
    run('bitstream',['bash',str(pkg/'build.sh'),'bitstream'])
    alias='alveo_u250_quad_rocket_dual_gemmini_saturn_trafficgen_abi3'
    packages=list((root/'sims/firesim/deploy/results-build').glob('*-'+alias+'/cl_*/firesim.tar.gz'))
    if len(packages)!=1: raise RuntimeError(f'Expected one newly built bitstream, found {packages}')
    run('package',[sys.executable,str(pkg/'package-build.py'),'--build-output',str(packages[0].parent),
        '--smoke-result',str(smoke/'result.json'),'--output',str(work/'deliverables')])
    save(state='complete',stage='complete',hardware=str(work/'deliverables/hardware.json'))
except BaseException as exc:
    save(state='failed',error=repr(exc))
    raise
