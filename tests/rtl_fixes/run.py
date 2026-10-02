#!/usr/bin/env python3
"""Generate RTL from Chipyard source and run bounded, guarded regressions."""
import argparse,json,os,subprocess,sys,time
from pathlib import Path
from common import ROOT,TOOLS,elaborate,isolated_source,write,sha
import suites
NAMES=['rocket-trap-pc','rocket-interrupt','rocket-cache','saturn-memory','shuttle-fdiv']
def execute(a):
 w=a.work.resolve();w.mkdir(parents=True,exist_ok=True)
 fixes=json.loads((Path(__file__).parent/'fixes.json').read_text())
 results=[]
 for name in NAMES if a.suite=='all' else [a.suite]:
  index=NAMES.index(name);fix=fixes[index]
  config='RTLFixShuttleConfig' if name=='shuttle-fdiv' else 'RTLFixRocketConfig'
  for negative in (False,True):
   out=w/name/('negative' if negative else 'corrected');out.mkdir(parents=True,exist_ok=False)
   print(f'START {name} negative={negative}',flush=True)
   start=time.monotonic()
   try:
    source=isolated_source(out,fix['repo'],fix['commit']) if negative else ROOT
    elab=out/'elaboration' if negative else w/'elaborations'/config
    sv=elaborate(source,config,elab,a.jobs)
    dest=out/'test'
    if name=='rocket-trap-pc':result=suites.rocket(sv,dest,a.jobs,negative,trap=True)
    elif name=='rocket-interrupt':result=suites.rocket(sv,dest,a.jobs,negative)
    elif name=='rocket-cache':result=suites.cache(sv,dest,a.jobs,negative)
    elif name=='saturn-memory':result=suites.saturn(sv,dest,a.jobs,negative)
    else:result=suites.shuttle(sv,dest,a.jobs,negative)
    record={'suite':name,'negative_control':negative,'status':'passed','seconds':time.monotonic()-start,'result':result}
   except BaseException as exc:
    record={'suite':name,'negative_control':negative,'status':'incomplete' if isinstance(exc,(KeyboardInterrupt,subprocess.TimeoutExpired)) else 'failed','seconds':time.monotonic()-start,'error':repr(exc)}
    write(out/'status.json',record);raise
   write(out/'status.json',record);results.append(record)
   print(f'PASS {name} negative={negative} seconds={record["seconds"]:.3f}',flush=True)
 write(w/('summary-'+a.suite+'.json'),{'status':'passed','parent_commit':subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),'results':results})
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--suite',choices=['all']+NAMES,default='all');p.add_argument('--work',type=Path,required=True);p.add_argument('--jobs',type=int,default=4);p.add_argument('--guarded-child',action='store_true',help=argparse.SUPPRESS);a=p.parse_args()
 if not 1<=a.jobs<=4:p.error('--jobs must be between 1 and 4')
 a.work=a.work.resolve()
 if a.guarded_child:execute(a);return 0
 a.work.mkdir(parents=True,exist_ok=True)
 (a.work/('guard-'+a.suite)).mkdir(exist_ok=False)
 env=os.environ.copy();env['RTL_FIX_CACHE']=str(a.work/'cache');env['ILLIXR_BUILD_GUARD_TAG']='rtl-fixes-'+str(os.getpid())
 cmd=[TOOLS/'python',Path(__file__).parent/'resource_guard.py','--scratch',a.work,'--run-dir',a.work/('guard-'+a.suite),'--latch',a.work/'STOPPED_NO_AUTORESTART.json','--','nice','-n','10',TOOLS/'python',Path(__file__).resolve(),'--suite',a.suite,'--work',a.work,'--jobs',str(a.jobs),'--guarded-child']
 return subprocess.call(list(map(str,cmd)),env=env)
if __name__=='__main__':sys.exit(main())
