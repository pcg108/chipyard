#!/usr/bin/env python3
"""Check independently collected target marker records and host interval math."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
from common import require, evidence, save

MASK=(1<<64)-1
DEMAND=('demand_get','demand_put','demand_acquire_block','demand_acquire_perm','demand_atomic','demand_other')
REQUESTERS=('rocket','saturn','gemmini_int8','trafficgen','other')

def check_interval(counters):
    banks=defaultdict(dict)
    for counter in counters:
        require(counter['name'] not in banks[counter['bank']],'Duplicate interval counter')
        banks[counter['bank']][counter['name']]=counter
    for bank,values in banks.items():
        required=set(DEMAND)|{'demand_completed','outstanding_requests','instrumentation_errors'}
        required|={r+'_'+kind for r in REQUESTERS for kind in ('accepted','completed')}
        require(required<=values.keys(),'Missing accounting counters in bank'+str(bank))
        delta=lambda name:int(values[name]['delta'])
        accepted=sum(delta(name) for name in DEMAND)
        completed=delta('demand_completed')
        outstanding=values['outstanding_requests']
        require(outstanding['gauge'],'Outstanding count must be an instantaneous gauge')
        require(accepted-completed==int(outstanding['end'])-int(outstanding['start']),
                'Accepted/completed/outstanding conservation failed in bank'+str(bank))
        require(sum(delta(r+'_accepted') for r in REQUESTERS)==accepted,
                'Requester accepted counts do not sum to demand counts in bank'+str(bank))
        require(sum(delta(r+'_completed') for r in REQUESTERS)==completed,
                'Requester completion counts do not sum to demand completions in bank'+str(bank))
        require(int(values['instrumentation_errors']['start'])==int(values['instrumentation_errors']['end'])==0,
                'Cache instrumentation reported an accounting error in bank'+str(bank))

def check(prefix, window_id):
    prefix=Path(prefix)
    raw=Path(str(prefix)+'.csv'); summary=Path(str(prefix)+'.summary.json')
    data=json.loads(summary.read_text())
    require(data['schema_version']==1 and data['complete'] and not data['errors'],
            'Incomplete/invalid marker collection')
    require(data['boundary_convention']=='[START,STOP)', 'Wrong boundary convention')
    records=defaultdict(list)
    with raw.open() as stream:
        for row in csv.DictReader(stream): records[int(row['sequence'])].append(row)
    require(len(records)==5, 'Expected START, two SNAPSHOTs, STOP and DRAIN_END')
    kinds=[]; cycles=[]; vectors=[]
    aliases={'1':'START','2':'SNAPSHOT','3':'STOP','4':'DRAIN_END'}
    for sequence,rows in sorted(records.items()):
        first=rows[0]
        require(int(first['window_id'])==window_id, 'Wrong target window identifier')
        require(all(int(row['errors'])==0 for row in rows), 'Target/bridge instrumentation errors')
        for field in ('schema_version','window_id','marker_sequence','marker_type','target_cycle'):
            require(len({r[field] for r in rows})==1, 'Torn snapshot metadata: '+field)
        kinds.append(aliases.get(first['marker_type'],first['marker_type'].upper()))
        cycles.append(int(first['target_cycle']))
        vec={(row['bank'],row['requester'],row['counter']):int(row['value']) for row in rows}
        require(len(vec)==len(rows), 'Duplicate counter identity')
        require(not vectors or vec.keys()==vectors[0].keys(), 'Snapshot counter set changed')
        errors=[row for row in rows if row['counter']=='instrumentation_errors']
        require(len(errors)==len({row['bank'] for row in rows}) and all(int(row['value'])==0 for row in errors),
                'Missing or nonzero cumulative instrumentation-error counters')
        vectors.append(vec)
    require(kinds==['START','SNAPSHOT','SNAPSHOT','STOP','DRAIN_END'], 'Unexpected marker sequence')
    require(all(a<b for a,b in zip(cycles,cycles[1:])), 'Marker target cycles are not monotonic')
    intervals=data['intervals']
    require(sum(i['kind']=='window' for i in intervals)==1, 'Expected exactly one whole window')
    require(sum(i['kind']=='drain' for i in intervals)==1, 'Drain was not recorded separately')
    require(sum(i['kind']=='snapshot' for i in intervals)>=2, 'Intermediate snapshots missing')
    for interval in intervals:
        require(int(interval['window_id'])==window_id, 'Interval identity mismatch')
        for counter in interval['counters']:
            if not counter['gauge']:
                require(int(counter['delta'])==((int(counter['end'])-int(counter['start']))&MASK),
                        'Incorrect unsigned 64-bit subtraction')
        check_interval(interval['counters'])
    window=next(i for i in intervals if i['kind']=='window')
    require(int(window['start_cycle'])==cycles[0] and int(window['end_cycle'])==cycles[3],
            'Whole-window boundaries changed by intermediate snapshots')
    return {'status':'passed','window_id':window_id,'markers':kinds,'target_cycles':cycles,
            'counters_per_snapshot':len(vectors[0]),'raw':evidence(raw),'summary':evidence(summary)}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--prefix',type=Path,required=True);p.add_argument('--window-id',type=int,required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    save(a.output,check(a.prefix,a.window_id))
