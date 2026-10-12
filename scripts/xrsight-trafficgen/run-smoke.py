#!/usr/bin/env python3
"""Run the combined target against an isolated synthetic scheduler session."""
import argparse
import csv
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

p=argparse.ArgumentParser()
p.add_argument('--work',type=Path,required=True)
p.add_argument('--gpu-model',type=Path,default=Path('/home/prashanth/gpu_model/build-chipyard-boost/gpu_model_socket'))
a=p.parse_args()
root=Path(__file__).resolve().parents[2];work=a.work.resolve()
work.mkdir(parents=True,exist_ok=False)
pkg=root/'scripts/xrsight-trafficgen'
subprocess.run([sys.executable,str(pkg/'smoke/build.py'),'--output',str(work/'firmware')],check=True,stdout=(work/'firmware-build.log').open('w'),stderr=subprocess.STDOUT)
subprocess.run([sys.executable,str(root/'scripts/trafficgen/generate-synthetic-registry.py'),str(work/'fixture'),'--iterations','256'],check=True)
sim=root/'sims/firesim/sim/generated-src/xilinx_alveo_u250/xilinx_alveo_u250-firesim-FireSim-FireSimQuadRocketDualGemminiSaturnTrafficGenConfig-BaseXilinxAlveoU250Config/VFireSim'
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
env=os.environ.copy()
env['LD_LIBRARY_PATH']=str(root/'.conda-env/lib')+':/home/prashanth/gpu_model/build-chipyard-boost/boost-libs:'+env.get('LD_LIBRARY_PATH','')
sched_cmd=[str(a.gpu_model),'-c',str(work/'fixture/hardware.config'),'--kernel-registry',str(work/'fixture/registry.json'),'--socket-port',str(port),'--round-log-dir',str(work/'scheduler')]
cmd=[str(sim),'+permissive','+fesvr-step-size=128','+max-cycles=12000000',
     '+idle-counts=1','+fesvr-wait-ticks=8',f'+trafficgen-socket-port={port}',
     f'+trafficgen-round-log-dir={work}/bridge','+print-start=0','+print-end=-1']
for ch in range(8):
    cmd.extend(f'+mm_{key}_{ch}={value}' for key,value in [('readLatency',30),('writeLatency',30),('readMaxReqs',10),('writeMaxReqs',10)])
    cmd.append(f'+mm_useHardwareDefaultRuntimeSettings_{ch}')
cmd.extend(['+permissive-off',str(work/'firmware/smoke.elf')])
(work/'commands.json').write_text(json.dumps({'scheduler':sched_cmd,'metasim':cmd},indent=2)+'\n')
with (work/'scheduler.log').open('w') as slog,(work/'metasim.log').open('w') as mlog:
    scheduler=subprocess.Popen(sched_cmd,cwd=work,env=env,stdout=slog,stderr=subprocess.STDOUT)
    try:
        # Do not consume the server's single client connection just to probe it.
        time.sleep(1)
        if scheduler.poll() is not None: raise RuntimeError('Scheduler exited before simulator startup')
        result=subprocess.run(cmd,cwd=work,env=env,stdin=subprocess.DEVNULL,stdout=mlog,stderr=subprocess.STDOUT,timeout=3600)
        if result.returncode: raise RuntimeError(f'Metasimulator exit {result.returncode}; see {work}/metasim.log')
        scheduler.wait(timeout=30)
        if scheduler.returncode: raise RuntimeError(f'Scheduler exit {scheduler.returncode}')
    finally:
        if scheduler.poll() is None:
            scheduler.terminate()
            try: scheduler.wait(timeout=10)
            except subprocess.TimeoutExpired: scheduler.kill();scheduler.wait()
text=(work/'metasim.log').read_text(errors='replace')
if 'COMBINED_SMOKE PASS:' not in text: raise RuntimeError('Missing firmware PASS marker')
with (work/'scheduler/launches.csv').open() as f: launches=list(csv.DictReader(f))
completed=[r for r in launches if r['event']=='completed']
if len(completed)!=4 or {int(r['registry_id']) for r in completed}!={1106,1003,1044,1001}:
    raise RuntimeError('Four-launch completion check failed')
(work/'result.json').write_text(json.dumps({'status':'passed','harts':4,'dual_array_rounds':8,'completed_launches':completed},indent=2)+'\n')
print(f'Combined smoke passed: {work}/result.json')
