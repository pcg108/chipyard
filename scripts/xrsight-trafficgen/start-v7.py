#!/usr/bin/env python3
"""Fresh component validation and elaboration; stop on the first failure."""
import datetime,json,os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
pkg=root/'scripts/xrsight-trafficgen'
work=Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
os.environ['XRSIGHT_TG_WORK']=str(work)
status={'state':'running','source':str(root),'model_commit':'7b6abcafe312c21e87393181410eb2f2aea1d938','stage':'starting','completed_stages':[]}
def save(**values):
 status.update(values,updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
 tmp=work/'pipeline-status.tmp';tmp.write_text(json.dumps(status,indent=2)+'\n');tmp.replace(work/'pipeline-status.json')
 print(json.dumps(status),flush=True)
try:
 for stage in ['model','cpp','rtl','elaborate','check-elaboration']:
  save(stage=stage)
  if stage in ['model','cpp','rtl']:
   command=['python3',str(pkg/'firesim_resource_guard.py'),'--scratch',str(work),'--run-dir',str(work/('guard-'+stage)),
    '--latch',str(work/'STOPPED_NO_AUTORESTART.json'),'--jobs','4','--','bash',str(pkg/'validate-v7.sh'),stage]
  elif stage=='elaborate':command=['bash',str(pkg/'build.sh'),'elaborate']
  else:command=['python3',str(pkg/'check-elaboration.py'),'--output',str(work/'validation/elaboration.json')]
  with (work/'logs'/f'{stage}.log').open('x') as log:
   result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
  if result.returncode:raise RuntimeError(f'{stage} exited {result.returncode}; see logs/{stage}.log')
  status['completed_stages'].append(stage)
 save(state='elaboration_passed',stage='awaiting_metasim_build')
except BaseException as e:
 save(state='failed',error=repr(e));raise
