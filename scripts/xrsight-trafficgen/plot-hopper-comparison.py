#!/usr/bin/env python3
"""Compare validated physical-service traffic on the original capture cycle axes."""
import argparse,csv,json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

p=argparse.ArgumentParser()
p.add_argument('--software',type=Path,required=True)
p.add_argument('--firesim',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
runs=[]
for directory,mode in [(a.software,'reference'),(a.firesim,'firesim')]:
    validation=json.loads((directory/'validation.json').read_text())
    provenance=json.loads((directory/'provenance.json').read_text())
    assert validation['result']=='PASS' and validation['original_completed']==504000
    assert provenance['mode']==mode and provenance['model_commit']=='7b6abcafe312c21e87393181410eb2f2aea1d938'
    with (directory/'physical-comparison.csv').open() as f:
        rows=[{k:int(v) for k,v in row.items()} for row in csv.DictReader(f)]
    runs.append((rows,validation))
# Each run's physical service IDs may differ; match the recorded service identity.
maps=[{r['recorded_service_id']:r for r in rows} for rows,_ in runs]
assert all(len(m)==len(rows) for m,(rows,_) in zip(maps,runs))
assert maps[0].keys()==maps[1].keys()
for key in maps[0]:
    assert all(maps[0][key][f]==maps[1][key][f] for f in ['is_write','recorded_issue','recorded_return'])
series=[('Ground truth',runs[0][0],'recorded_issue','recorded_return'),
        ('Software traffic generator',runs[0][0],'measured_issue','measured_return'),
        ('FireSim FPGA',runs[1][0],'measured_issue','measured_return')]
fig,axes=plt.subplots(2,3,figsize=(17,9),constrained_layout=True)
for col,(label,rows,issue,ret) in enumerate(series):
    for write,color,kind in [(0,'tab:blue','reads'),(1,'tab:orange','writes')]:
        subset=[r for r in rows if r['is_write']==write]
        issued=np.sort([r[issue] for r in subset]);finished=np.sort([r[ret] for r in subset])
        axes[0,col].plot(issued,np.arange(1,len(issued)+1),color=color,label=kind+' issued')
        axes[0,col].plot(finished,np.arange(1,len(finished)+1),'--',color=color,label=kind+' returned')
        axes[1,col].hist([r[ret]-r[issue] for r in subset],bins=80,histtype='step',color=color,label=kind)
    axes[0,col].set(title=label,xlabel='Target / capture cycle',ylabel='Cumulative physical transactions')
    axes[1,col].set(xlabel='Issue-to-return latency (cycles)',ylabel='Physical transactions')
    for row in range(2):axes[row,col].grid(alpha=.2);axes[row,col].legend(fontsize=8)
fig.suptitle('Hopper Gemma kernel 6632 — TMA + mbarriers + LRC\n'
             '504,000 original requests; matched recorded service identities; no cycle rescaling')
a.output.mkdir(parents=True,exist_ok=True)
fig.savefig(a.output/'hopper-gemma-kernel-6632-comparison.png',dpi=180)
summary={label:{'scheduling_rounds':v['complete_policy_snapshots'],'final_cycle':v['final_cycle'],
               'original_completed':v['original_completed'],'physical_total':v['physical_total']}
         for label,(_,v) in zip(['software','firesim'],runs)}
(a.output/'hopper-gemma-kernel-6632-metrics.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
