#!/usr/bin/env python3
"""Continue only after the fresh combined target has passed elaboration."""
import datetime,json,os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2];pkg=root/'scripts/xrsight-trafficgen'
work=Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
target='FireSimQuadRocketDualGemminiSaturnTrafficGenConfig'
generated=root/'sims/firesim/sim/generated-src/xilinx_alveo_u250'/('xilinx_alveo_u250-firesim-FireSim-'+target+'-BaseXilinxAlveoU250Config')
status=json.loads((work/'pipeline-status.json').read_text())
assert status['state']=='elaboration_passed',status
assert not (work/'STOPPED_NO_AUTORESTART.json').exists()
os.environ.update(XRSIGHT_TG_WORK=str(work),LD_LIBRARY_PATH=str(work/'model-build/boost-libs'),PATH='/usr/bin:/bin:'+os.environ['PATH'])
def save(**values):
 status.update(values,updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
 tmp=work/'pipeline-status.tmp';tmp.write_text(json.dumps(status,indent=2)+'\n');tmp.replace(work/'pipeline-status.json')
 print(json.dumps(status),flush=True)
def run(stage,command,guard=True):
 save(state='running',stage=stage)
 if guard:command=['/usr/bin/python3',str(pkg/'firesim_resource_guard.py'),'--scratch',str(work),'--run-dir',str(work/('guard-'+stage)),
  '--latch',str(work/'STOPPED_NO_AUTORESTART.json'),'--jobs','4','--',*command]
 with (work/'logs'/f'{stage}.log').open('x') as log:
  result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
 if result.returncode:raise RuntimeError(f'{stage} exited {result.returncode}; see logs/{stage}.log')
 status['completed_stages'].append(stage)
def kernel_command(out,mode):
 return ['/usr/bin/python3',str(root/'scripts/trafficgen/run-tma-metasim.py'),str(work/out),
  '--model-source',str(work/'model-src'),'--model-build',str(work/'model-build'),
  '--dataset','/home/prashanth/gpu_model/hopper-gemma','--kernel','6632','--port','50072',
  '--design',target,'--policy-decoder',str(work/'model-build/export-tma-policies'),*mode]
try:
 run('metasim-build',['bash',str(pkg/'build.sh'),'metasim'],guard=False)
 run('audit-v7',['/usr/bin/python3',str(root/'scripts/trafficgen/audit-tma-elaboration.py'),str(generated),
  '--command-file',str(work/'metasim-command.sh'),'--build-log',str(work/'logs/metasim-build.log'),
  '--output',str(work/'validation/tma-elaboration.json')])
 run('combined-smoke',['/usr/bin/python3',str(pkg/'run-smoke.py'),'--work',str(work/'validation/combined-smoke'),
  '--gpu-model',str(work/'model-build/gpu_model_socket')])
 run('software-6632',kernel_command('reference-6632',['--reference']))
 v=json.loads((work/'reference-6632/validation.json').read_text())
 save(software_scheduling_rounds=v['complete_policy_snapshots'],software_final_cycle=v['final_cycle'])
 run('metasim-6632',kernel_command('metasim-6632',['--simulator',str(generated/'VFireSim')]))
 v=json.loads((work/'metasim-6632/validation.json').read_text())
 save(metasim_scheduling_rounds=v['complete_policy_snapshots'],metasim_final_cycle=v['final_cycle'])
 run('bitstream',['bash',str(pkg/'build.sh'),'bitstream'],guard=False)
 save(state='bitstream_build_finished',stage='awaiting_report_review_and_physical_test')
except BaseException as e:
 save(state='failed',error=repr(e));raise
