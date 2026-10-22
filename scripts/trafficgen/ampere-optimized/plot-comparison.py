#!/usr/bin/env python3
"""Plot captured ground truth, standalone software, and RTL metasim (never FPGA)."""
import argparse, csv, json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter, MaxNLocator
import numpy as np

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--capture',type=Path,required=True,help='One kernel directory under l2_trace')
p.add_argument('--software',type=Path,required=True,help='Standalone per-request prediction CSV')
p.add_argument('--metasim',type=Path,required=True,help='Scheduler issued_accesses.csv from metasim')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
gt={}
for name in ('l1_to_l2_requests.txt','l1_bypass_to_l2.txt'):
 for path in a.capture.rglob(name):
  for line in path.read_text().splitlines():
   if not line.strip() or line.startswith('#'): continue
   f=dict(x.split('=',1) for x in line.split() if '=' in x)
   uid=int(f['request_uid'],0)
   if uid in gt: raise ValueError('Duplicate ground-truth UID')
   gt[uid]=(int(f.get('l1_to_l2_cycle',f.get('source_to_l2_cycle')),0),int(f['addr'],0),int(f['type']=='WRITE'))
def read(path,cycle):
 out={}
 with path.open() as f:
  for row in csv.DictReader(f):
   uid=int(row['request_uid'])
   if uid in out: raise ValueError('Duplicate prediction UID')
   out[uid]=(int(row[cycle]),int(row['address']),int(row['is_write']))
 return out
sw=read(a.software,'model_cycle');rtl=read(a.metasim,'cycle')
if not gt or gt.keys()!=sw.keys() or gt.keys()!=rtl.keys(): raise ValueError('Request sets differ')
for uid in gt:
 if gt[uid][1:]!=sw[uid][1:] or gt[uid][1:]!=rtl[uid][1:]: raise ValueError('Address/type mismatch')
ids=sorted(gt)
series={label:np.array([points[i] for i in ids],dtype=np.int64) for label,points in
 [('Captured ground truth',gt),('Software traffic generator',sw),('FireSim RTL metasim',rtl)]}
stats={}
for label,points in series.items():
 start=int(points[:,0].min());points[:,0]-=start
 stats[label]={'first_raw_cycle':start,'access_span_cycles':int(points[:,0].max()),'requests':len(points),
               'reads':int((points[:,2]==0).sum()),'writes':int((points[:,2]==1).sum())}
fig,axes=plt.subplots(2,3,figsize=(16,8),sharex=True,sharey='row')
limit=max(v['access_span_cycles'] for v in stats.values())*1.02
for col,((label,points),color) in enumerate(zip(series.items(),['#68737b','#8952b5','#087fb5'])):
 for row in range(2):
  ax=axes[row,col];mask=points[:,2]==row
  ax.scatter(points[mask,0],points[mask,1],s=.9,alpha=.4,color=color,rasterized=True,linewidths=0)
  ax.grid(alpha=.18);ax.set_axisbelow(True);ax.set_xlim(0,limit)
  ax.xaxis.set_major_locator(MaxNLocator(6));ax.xaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/1000:g}k'))
  ax.yaxis.set_major_locator(MaxNLocator(4));ax.yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'0x{int(v):X}'))
  ax.tick_params(labelsize=8)
  if row==0: ax.set_title(f'{label}\nAccess span: {stats[label]["access_span_cycles"]:,} cycles',fontsize=12,color=color)
  if col==0: ax.set_ylabel(('Loads' if row==0 else 'Stores')+' — address')
  if row==1: ax.set_xlabel('Cycles since first access')
fig.suptitle('Rodinia NN kernel 1 — Ampere protocol v2',fontsize=17,y=.98)
fig.text(.5,.025,f'{len(gt):,} identical request identities in every column · timelines aligned at first access\nSoftware uses recorded completion latencies; metasim uses RTL/cache/DRAM feedback. No FPGA run.',ha='center',fontsize=10)
fig.subplots_adjust(left=.115,right=.99,bottom=.14,top=.86,wspace=.12,hspace=.18)
a.output.mkdir(parents=True,exist_ok=True)
fig.savefig(a.output/'comparison.png',dpi=180);fig.savefig(a.output/'comparison.pdf');plt.close(fig)
(a.output/'comparison.json').write_text(json.dumps({'request_identity_check':'passed','alignment':'first access across loads and stores together','statistics':stats,'execution':'metasim, not FPGA'},indent=2)+'\n')
