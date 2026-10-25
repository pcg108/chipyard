#!/usr/bin/env python3
"""Persistent serial validation -> 30 MHz implementation -> package controller.

Never accesses FPGA devices. Failed stages halt; resumption requires an explicit
--resume and unchanged source identity. Resource latches are never auto-cleared.
"""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
from common import ROOT,TARGET,QUINTUPLET,generated_dir,require,save,source_manifest,evidence

PKG=Path(__file__).resolve().parent

def stages(w,model,model_root):
    py=[sys.executable]; env=['bash',str(PKG/'run-env.sh')]
    def stage(name,command,result=None):return {'name':name,'command':list(map(str,command)),
                                              'result':str(result) if result else None}
    result=[stage('preflight',py+[PKG/'preflight.py','--work',w,'--model',model,'--model-root',model_root],w/'results/preflight.json'),
      stage('flow-tests',py+['-m','unittest','discover','-s',PKG/'tests','-v']),
      stage('focused-rtl',env+['sbt','chipyard/testOnly chipyard.example.L2PerfSpec chipyard.example.L2PerfAccumulatorSpec chipyard.example.L2PerfMSHRSpec chipyard.example.L2PerfArraySpec']),
      stage('bridge-collector',env+['bash',PKG/'collector-test.sh',w/'results/bridge-collector']),
      stage('processor-fix-regressions',env+[sys.executable,ROOT/'tests/rtl_fixes/run.py','--suite','all',
           '--work',w/'results/processor-fixes','--jobs','4','--guarded-child'],w/'results/processor-fixes/summary-all.json')]
    for name,script in [('protocol-v2','test-trafficgen-socket-v2.sh'),('lane-assignment','test-trafficgen-lane-assignment.sh'),
                        ('bundle-recovery','test-trafficgen-bundle-recovery.sh')]:
        result.append(stage(name,env+['env','GPU_MODEL_ROOT='+str(model_root),'CXX=/usr/bin/g++',
          'BOOST_ROOT=/home/prashanth/chipyard/.conda-env','bash',ROOT/'scripts'/script]))
    result += [stage('ampere-rtl',env+['sbt','chipyard/testOnly chipyard.example.TrafficGenLineOwnershipSpec chipyard.example.TrafficGenTargetCycleSpec chipyard.example.TrafficGenLaunchSlotsSpec chipyard.example.TrafficGenTLControlSpec']),
      stage('elaborate',env+['bash',PKG/'build-stage.sh','elaborate']),
      stage('elaboration-check',env+[sys.executable,PKG/'check-elaboration.py','--output',w/'results/elaboration.json'],w/'results/elaboration.json'),
      stage('metasim-build',env+['bash',PKG/'build-stage.sh','metasim']),
      stage('firmware-build',env+[sys.executable,PKG/'build-firmware.py','--output',w/'fixtures/firmware']),
      stage('combined-smoke',env+[sys.executable,PKG/'smoke.py','--chipyard',ROOT,'--work',w/'results/combined-smoke',
             '--gpu-model',model],w/'results/combined-smoke/result.json'),
      stage('rodinia',env+[sys.executable,PKG/'run-rodinia.py','--sim',generated_dir()/'VFireSim','--model',model,
             '--firmware',w/'fixtures/firmware/rodinia.elf','--registry',w/'fixtures/registry.json',
             '--config',w/'fixtures/hardware.config','--output',w/'results/rodinia'],w/'results/rodinia/run.json'),
      stage('set-interference',env+[sys.executable,PKG/'run-set-experiments.py','--model',model,
             '--work',w/'results/set-interference'],w/'results/set-interference/result.json'),
      stage('driver-build',env+['bash',PKG/'build-stage.sh','driver']),
      stage('bitstream',env+['bash',PKG/'build-stage.sh','bitstream']),
      stage('package',env+[sys.executable,PKG/'package.py','--work',w],w/'deliverables/hardware.json')]
    return result

def infer_build(w):
    implementations=sorted((w/'builds').rglob('impl_1/runme.log'),key=lambda p:p.stat().st_mtime)
    if implementations:
        text=implementations[-1].read_text(errors='replace')
        ops=re.findall(r'^(?:Command:\s*|#\s*)(opt_design|place_design|phys_opt_design|route_design|write_bitstream)\b',text,re.M)
        if ops:
            return {'opt_design':'optimization','place_design':'placement','route_design':'routing',
                    'write_bitstream':'bitstream_generation','phys_opt_design':'physical_optimization'}[ops[-1]]
    if list((w/'builds').rglob('synth_1/runme.log')):return 'synthesis'
    return 'project_and_ip_generation'

def validation_gate(state,planned):
    mandatory=[s['name'] for s in planned if s['name'] not in ('bitstream','package')]
    missing=[name for name in mandatory if state['stages'].get(name,{}).get('status')!='passed']
    require(not missing,'Missing validation gate stages: '+repr(missing))
    return {'status':'passed','required_stages':mandatory,
            'evidence':{name:state['stages'][name] for name in mandatory}}

def processor_fingerprint():
    paths=list((ROOT/'tests/rtl_fixes').rglob('*'))
    paths += [ROOT/'generators/chipyard/src/main/scala/config/RTLFixConfigs.scala']
    for component in ('rocket-chip','saturn','shuttle'):
        # Include the actual dirty component diff, not just its pinned commit.
        repo=ROOT/'generators'/component
        paths += list((repo/'src/main').rglob('*.scala'))
    from common import digest
    return hashlib.sha256(json.dumps({str(p.relative_to(ROOT)):digest(p) for p in paths
             if p.is_file() and '__pycache__' not in p.parts},sort_keys=True).encode()).hexdigest()

def run(args):
    w=args.work.resolve();w.mkdir(parents=True,exist_ok=True)
    for name in ('control','logs','results','tmp'): (w/name).mkdir(exist_ok=True)
    lock=(w/'control/controller.lock').open('a+')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    require(not (w/'STOPPED_NO_AUTORESTART.json').exists(),'Resource latch exists; resolve cause before explicit restart')
    snapshot=source_manifest(ROOT)
    fingerprint=hashlib.sha256(json.dumps(snapshot,sort_keys=True).encode()).hexdigest()
    processor_hash=processor_fingerprint()
    status_path=w/'control/status.json'
    if args.resume:
        state=json.loads(status_path.read_text())
        if state['source_fingerprint']!=fingerprint:
            require(args.refresh_source,'Source changed: use --refresh-source to archive and invalidate prior validation')
            require('bitstream' not in state['stages'],'Source changes after implementation require a fresh workspace')
            stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
            archive=w/'results/archive'/stamp;archive.mkdir(parents=True)
            old=dict(state['stages'])
            preserve=state.get('processor_fingerprint')==processor_hash and old.get('processor-fix-regressions',{}).get('status')=='passed'
            for result in (w/'results').iterdir():
                if result.name=='archive' or (preserve and result.name=='processor-fixes'):continue
                result.rename(archive/result.name)
            save(archive/'previous-controller-status.json',state)
            state['stages']={'processor-fix-regressions':old['processor-fix-regressions']} if preserve else {}
            state.update(source=snapshot,source_fingerprint=fingerprint,processor_fingerprint=processor_hash)
    else:
        require(not status_path.exists(),'Status already exists; use explicit --resume or a fresh workspace')
        state={'status':'starting','source_fingerprint':fingerprint,'processor_fingerprint':processor_hash,'source':snapshot,'stages':{},
               'started_at':datetime.now(timezone.utc).isoformat(),'fpga_programmed':False}
    planned=stages(w,args.model.resolve(),args.model_root.resolve());save(w/'control/stages.json',planned)
    started=time.monotonic()
    child=None
    def interrupted(signum, frame):
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=30)
        raise KeyboardInterrupt('Controller interrupted by signal '+str(signum))
    for sig in (signal.SIGTERM,signal.SIGINT):signal.signal(sig,interrupted)
    def oom_count():return int(dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())['oom_kill'])
    state.setdefault('initial_oom_kill',oom_count())
    def update(**values):
        state.update(values,updated_at=datetime.now(timezone.utc).isoformat(),
                     controller_pid=os.getpid(),controller_elapsed_seconds=time.monotonic()-started)
        save(status_path,state)
    # Historical failure evidence remains in stage/archive records. The live
    # top-level fields describe this explicit restart, not the previous attempt.
    for stale in ('error','action','failure_classification','exit_code','guard','diagnostics'):
        state.pop(stale,None)
    update(status='running')
    os.environ['L2_PERF_WORK']=str(w)
    try:
        for item in planned:
            name=item['name']
            if state['stages'].get(name,{}).get('status')=='passed':continue
            if name in state['stages']:
                # A failed validation may have created its output before failing.
                # Preserve that attempt, then give the explicit resume a fresh
                # output directory. Build checkpoints are never removed here.
                outputs={'processor-fix-regressions':w/'results/processor-fixes',
                  'combined-smoke':w/'results/combined-smoke','rodinia':w/'results/rodinia',
                  'set-interference':w/'results/set-interference','package':w/'deliverables'}
                old_output=outputs.get(name)
                if old_output and old_output.exists():
                    archive=w/'results/archive'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
                    archive.mkdir(parents=True,exist_ok=True)
                    old_output.rename(archive/old_output.name)
                    save(archive/(name+'-previous-stage.json'),state['stages'][name])
            if oom_count()>state['initial_oom_kill']:
                save(w/'STOPPED_NO_AUTORESTART.json',{'status':'resource_stopped','reason':'new_OOM_between_stages'})
                update(status='resource_stopped',failure_classification='host_resource')
                return 90
            require(shutil.disk_usage(w).free>=100*2**30,'Less than100GiB scratch before '+name)
            if name=='bitstream':
                current=source_manifest(ROOT)
                current_fingerprint=hashlib.sha256(json.dumps(current,sort_keys=True).encode()).hexdigest()
                require(current_fingerprint==state['source_fingerprint'],
                        'Source changed during validation; fresh FPGA implementation was not started')
                from common import digest
                smoke=json.loads((w/'results/combined-smoke/result.json').read_text())
                require(digest(generated_dir()/'FireSim-generated.const.h')==smoke['driver_header']['sha256'],
                        'Generated bridge register ABI changed after validated metasim')
                save(w/'results/pre-bitstream-source-manifest.json',current)
                save(w/'results/validation-gate.json',validation_gate(state,planned))
            stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
            guard=w/'control'/('guard-'+name+'-'+stamp);log=w/'logs'/(name+'-'+stamp+'.log')
            command=[sys.executable,str(PKG/'resource_guard.py'),'--scratch',str(w),'--run-dir',str(guard),
                     '--latch',str(w/'STOPPED_NO_AUTORESTART.json'),'--jobs','4','--',*item['command']]
            record={'status':'running','command':item['command'],'log':str(log),'guard':str(guard),
                    'started_at':datetime.now(timezone.utc).isoformat()}
            state['stages'][name]=record;update(stage=name,log=str(log))
            with log.open('x') as output:
                child=subprocess.Popen(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
                while child.poll() is None:
                    memory=guard/'memory.jsonl';current={}
                    if memory.exists():
                        with memory.open('rb') as stream:
                            stream.seek(max(0,memory.stat().st_size-8192));lines=stream.read().splitlines()
                        try:current=json.loads(lines[-1])
                        except (ValueError,IndexError):pass
                    update(stage=name,build_stage=infer_build(w) if name=='bitstream' else None,memory=current)
                    time.sleep(5)
                code=child.wait()
            guard_result=json.loads((guard/'guard-result.json').read_text())
            record.update(guard_result=guard_result,log_evidence=evidence(log),exit_code=code)
            samples=[json.loads(line) for line in (guard/'memory.jsonl').read_text().splitlines()]
            record.update(peak_process_rss_mib=max((s['max_rss_mib'] for s in samples),default=0),
                          peak_process_tree_rss_mib=max((s['tree_rss_mib'] for s in samples),default=0),
                          minimum_available_mib=min((s['available_mib'] for s in samples),default=0))
            if code:
                record['status']='resource_stopped' if guard_result.get('status')=='resource_stopped' else 'failed'
                update(status=record['status'],failure_classification='host_resource' if code==90 else name,
                       action='Preserve evidence; no automatic retry, hardware change, or timing recovery')
                return code
            if name in ('focused-rtl','ampere-rtl'):
                text=log.read_text(errors='replace')
                totals=re.findall(r'Total number of tests run:\s*(\d+)',text)
                require(totals and sum(map(int,totals))>0 and 'All tests passed.' in text,
                        'No verified passing Scala tests in '+str(log))
                required_suites=['L2PerfSpec','L2PerfAccumulatorSpec','L2PerfMSHRSpec','L2PerfArraySpec'] if name=='focused-rtl' else [
                    'TrafficGenLineOwnershipSpec','TrafficGenTargetCycleSpec','TrafficGenLaunchSlotsSpec','TrafficGenTLControlSpec']
                require(all(re.search(r'\b'+suite+r':',text) for suite in required_suites),
                        'A required RTL suite was not executed: '+str(log))
            if item['result']:
                outcome=json.loads(Path(item['result']).read_text())
                require(outcome.get('status')=='passed','Stage did not produce passing result: '+name)
                record['result']=evidence(item['result'])
            record['status']='passed';update()
            if args.stop_after==name:update(status='stopped_at_requested_stage');return 0
        update(status='complete',stage='complete',deliverables=str(w/'deliverables'))
        return 0
    except BaseException as error:
        update(status='failed',error=repr(error),action='Stopped; no automatic retry')
        raise

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,required=True);p.add_argument('--resume',action='store_true')
    p.add_argument('--refresh-source',action='store_true',help='Archive stale pre-build validation after an authorized source fix')
    p.add_argument('--stop-after')
    p.add_argument('--model',type=Path,default=Path('/scratch/prashanth_ampere_optimized_20261021/build/model/gpu_model_socket'))
    p.add_argument('--model-root',type=Path,default=Path('/scratch/prashanth_ampere_optimized_20261021/gpu-model'))
    raise SystemExit(run(p.parse_args()))
