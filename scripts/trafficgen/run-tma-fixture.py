#!/usr/bin/env python3
"""Execute the deterministic TMA-without-LRC socket fixture in a fresh metasim run."""
import argparse,json,os,shutil,subprocess,time
from pathlib import Path
from runpy import run_path
REPO=Path(__file__).resolve().parents[2]
sha=run_path(str(REPO/'scripts/trafficgen/run-tma-metasim.py'))['sha']
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('output',type=Path);p.add_argument('--simulator',type=Path,required=True)
p.add_argument('--peer',type=Path,required=True);p.add_argument('--port',type=int,default=50064)
a=p.parse_args(); root=a.output.resolve();root.mkdir(parents=True,exist_ok=False)
(root/'workload').mkdir();(root/'bridge').mkdir()
for name in ('hello.c','trafficgen.h','mmio.h','Makefile'):
    shutil.copy2(REPO/'scripts/trafficgen/workloads/trafficgen-hello'/name,root/'workload'/name)
build=['make','-C',str(root/'workload'),'TEST_CASE=single','KERNEL_REGISTRY_ID=6632',
       f'CC={REPO}/.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc',f'BARE_COMMON={REPO}/software/firemarshal/test/bare']
with (root/'workload-build.log').open('w') as out:subprocess.run(build,stdout=out,stderr=subprocess.STDOUT,check=True)
peer=[str(a.peer.resolve()),str(a.port)]
sim=[str(a.simulator.resolve()),'+permissive','+fesvr-step-size=128','+max-cycles=100000','+blkdev-in-mem0=128',
     '+print-start=0','+print-end=0',f'+trafficgen-socket-port={a.port}',f'+trafficgen-round-log-dir={root}/bridge',
     f'+trafficgen-boundary-log={root}/boundaries.csv','+permissive-off',str(root/'workload/hello')]
record={'commands':{'build':build,'peer':peer,'simulator':sim},'hashes':{str(x):sha(x) for x in
        (a.peer.resolve(),a.simulator.resolve(),root/'workload/hello',Path(__file__).resolve(),REPO/'scripts/trafficgen/tma-metasim-fixture.cc')}}
children=[];start=time.monotonic()
try:
    with (root/'peer.log').open('w') as pl,(root/'client.log').open('w') as cl:
        server=subprocess.Popen(peer,cwd=root,stdout=pl,stderr=subprocess.STDOUT);children.append(server)
        for _ in range(100):
            if server.poll() is not None:raise RuntimeError('peer exited before listen')
            if subprocess.check_output(['ss','-ltnH',f'sport = :{a.port}']).strip():break
            time.sleep(.1)
        else:raise RuntimeError('peer listen timed out')
        client=subprocess.Popen(sim,cwd=root,stdin=subprocess.DEVNULL,stdout=cl,stderr=subprocess.STDOUT);children.append(client)
        record['client_exit']=client.wait(timeout=600);record['peer_exit']=server.wait(timeout=10)
    if record['client_exit'] or record['peer_exit']:raise RuntimeError('fixture process failed')
    if 'PASS TMA metasim fixture' not in (root/'peer.log').read_text():raise RuntimeError('missing peer success')
    if '[target] PASS TrafficGen test=1' not in (root/'client.log').read_text():raise RuntimeError('missing target success')
    state=json.loads((root/'bridge/lrc_state.json').read_text())
    for key in ('pending_requests','pending_services','pending_bundles','hardware_service_count',
                'hardware_bundle_count','hardware_timer_count','hardware_report_count','tma_pending','tma_groups','tma_edges','tma_timers'):
        if state[key]:raise RuntimeError('undrained '+key)
    record['final_state']=state;record['result']='PASS'
except Exception as e:
    record.update(result='FAIL',error=str(e));raise
finally:
    for child in reversed(children):
        if child.poll() is None:
            child.terminate()
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:child.kill();child.wait()
    record['wall_seconds']=time.monotonic()-start
    (root/'validation.json').write_text(json.dumps(record,indent=2)+'\n')
