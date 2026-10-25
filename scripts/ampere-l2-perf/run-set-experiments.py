#!/usr/bin/env python3
"""Four full-target runs: isolated/concurrent and dispersed/conflicting L2 sets."""
import argparse
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path
from common import ROOT,TARGET,generated_dir,require,save,evidence

def geometry():
    prefix='firechip.chip.FireSim.'+TARGET
    stage=ROOT/'sims/firesim-staging/generated-src'/prefix
    l2path=stage/(prefix+'.l2.json');targetpath=stage/(prefix+'.l2-perf-target.json')
    banks=json.loads(l2path.read_text())['banks'];target=json.loads(targetpath.read_text())
    require(len(banks)==16 and all(b['blockBytes']==128 and b['ways']==16 for b in banks),
            'Set experiment requires the requested16-bank,16-way,128-byte L2')
    sets=banks[0]['setBits'];tags=banks[0]['tagBits']
    require(all(b['setBits']==sets and b['tagBits']==tags for b in banks),'Banks use different address layouts')
    require(len(sets)==7 and sets==list(range(min(sets),max(sets)+1)),'Expected128 contiguous sets per bank')
    require(min(tags)>max(sets),'Tag and set bits overlap')
    tg=target['trafficgen']
    require(tg['remap_trace_addresses'] and tg['remap_size']==1<<32,'Unrecognized trace remap')
    return {'banks':banks,'set_bits':sets,'dispersed_stride':1<<min(sets),
            'colliding_stride':1<<min(tags),'remap_base':tg['remap_base'],
            'l2_manifest':evidence(l2path),'target_manifest':evidence(targetpath)}

def location(address, geo):
    physical=geo['remap_base']+(address&0xffffffff)
    matches=[index for index,bank in enumerate(geo['banks']) if
             any(physical & ~int(a['mask']) == int(a['base']) for a in bank['addresses'])]
    require(len(matches)==1,'Synthetic physical address lacks unique bank ownership')
    set_index=sum(((physical>>bit)&1)<<position for position,bit in enumerate(geo['set_bits']))
    return matches[0],set_index

def firmware(out, ids):
    source='''#include <stdint.h>
#include <stdio.h>
#include "trafficgen.h"
#include "l2_perf.h"
static uint32_t rd(unsigned o) { return *(volatile uint32_t *)(uintptr_t)(0x5000+o); }
static void wr(unsigned o,uint32_t v) { *(volatile uint32_t *)(uintptr_t)(0x5000+o)=v; l2perf_fence(); }
int main(void) {
  const unsigned ids[]={IDS};
  if(rd(TG_ABI_VERSION)!=3 || rd(TG_SLOT_COUNT)!=4) return 1;
  if(l2perf_start(663201)) return 2;
  for(unsigned s=0;s<sizeof(ids)/sizeof(ids[0]);++s) {
    wr(TG_SLOT(s)+TG_REGISTRY_LOW,ids[s]); wr(TG_SLOT(s)+TG_REGISTRY_HIGH,0);
    wr(TG_SLOT(s)+(s+1==sizeof(ids)/sizeof(ids[0])?TG_SUBMIT_AND_CLOSE:TG_SUBMIT),1);
  }
  if(l2perf_snapshot()) return 3;
  unsigned sampled=0;
  while(!(rd(TG_SESSION_STATUS)&TG_SESSION_COMPLETE)) {
    if(rd(TG_SESSION_ERRORS)) return 4;
    if(!sampled) { if(l2perf_snapshot()) return 5; sampled=1; }
  }
  if(!sampled && l2perf_snapshot()) return 6;
  if(l2perf_stop() || l2perf_drain_end()) return 7;
  printf("[target] PASS L2PERF markers window=663201 snapshots=2\\n");
  printf("[target] PASS TrafficGen controlled set experiment\\n");
  return 0;
}
'''.replace('IDS',','.join(map(str,ids)))
    out.mkdir(parents=True)
    c=out/'sets.c';c.write_text(source)
    bare=ROOT/'software/firemarshal/test/bare';hello=ROOT/'scripts/trafficgen/workloads/trafficgen-hello'
    cc=str(ROOT/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc')
    elf=out/'sets.elf'
    command=[cc,'-march=rv64gcv','-mabi=lp64d','-mcmodel=medany','-O2','-fno-common','-fno-builtin',
      '-I'+str(bare),'-I'+str(hello),'-I'+str(Path(__file__).parent),'-T',str(bare/'link.ld'),
      '-static','-nostdlib','-nostartfiles',str(c),str(bare/'crt.S'),str(bare/'syscalls.c'),'-lgcc','-o',str(elf)]
    subprocess.run(command,check=True)
    return elf

def fixture(out, mode, geo):
    script=ROOT/'scripts/trafficgen/generate-synthetic-registry.py'
    spec=importlib.util.spec_from_file_location('synthetic',script);module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module);module.generate(out,256)
    locations=set()
    for slot,ident in enumerate(module.REGISTRY_IDS):
        paths=list((out/str(ident)/'l2_trace').rglob('l1_to_l2_requests.txt'))
        require(len(paths)==1,'Unexpected fixture capture layout')
        rows=[]
        for ordinal,line in enumerate(paths[0].read_text().splitlines()):
            iteration,member=divmod(ordinal,2)
            line_index=slot*32+iteration%32
            # 128-byte line, 16 banks, 128 sets. Both fixtures use banks0/1;
            # dispersed spans128sets, conflict keeps set0 and varies tag.
            stride=geo[mode+'_stride']
            address=0x01000000+line_index*stride+member*128
            bank,set_index=location(address,geo);locations.add((bank,set_index))
            line=re.sub(r'\baddr=\S+',f'addr=0x{address:x}',line)
            line=re.sub(r'\bset_index=\S+',f'set_index={set_index}',line)
            line=re.sub(r'\btag=\S+',f'tag=0x{address:x}',line)
            rows.append(line+(' type=WRITE' if member else ' type=READ'))
        paths[0].write_text('\n'.join(rows)+'\n')
    require(len({bank for bank,_ in locations})==2,'Load/store fixtures must each occupy one bank')
    require(len({index for _,index in locations})==(1 if mode=='colliding' else 128),
            'Generated physical addresses do not match requested set layout')
    save(out/'address-layout.json',{'mode':mode,'locations':sorted(locations),
        'l2_manifest':geo['l2_manifest'],'target_manifest':geo['target_manifest']})
    return module.REGISTRY_IDS

def metrics(prefix):
    data=json.loads(Path(str(prefix)+'.summary.json').read_text())
    window=next(i for i in data['intervals'] if i['kind']=='window')
    totals={}
    for counter in window['counters']:
        if not counter['gauge']:
            totals[counter['name']]=totals.get(counter['name'],0)+int(counter['delta'])
    return totals

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,required=True);p.add_argument('--model',type=Path,required=True)
    a=p.parse_args();a.work.mkdir(parents=True,exist_ok=False);runs={};geo=geometry()
    for concurrency in ('isolated','concurrent'):
        for layout in ('dispersed','colliding'):
            name=concurrency+'-'+layout;out=a.work/name;out.mkdir()
            ids=list(fixture(out/'fixture',layout,geo))
            if concurrency=='isolated':ids=ids[:1]
            elf=firmware(out/'firmware',ids)
            subprocess.run([sys.executable,str(Path(__file__).with_name('run-rodinia.py')),
              '--sim',str(generated_dir()/'VFireSim'),'--model',str(a.model),'--firmware',str(elf),
              '--registry',str(out/'fixture/registry.json'),'--config',str(out/'fixture/hardware.config'),
              '--unordered-ids','--ids',*map(str,ids),'--output',str(out/'run')],check=True)
            runs[name]={'metrics':metrics(out/'run/l2perf'),'result':evidence(out/'run/run.json')}
    for concurrency in ('isolated','concurrent'):
        good=runs[concurrency+'-dispersed']['metrics'];bad=runs[concurrency+'-colliding']['metrics']
        require(good['trafficgen_accepted']==bad['trafficgen_accepted']>0,'Request workload changed')
        require(good['trafficgen_completed']==good['trafficgen_accepted'],'Dispersed completion accounting')
        require(bad['trafficgen_completed']==bad['trafficgen_accepted'],'Colliding completion accounting')
        require(bad['trafficgen_tag_misses']>good['trafficgen_tag_misses'],
                'Set conflicts did not increase TrafficGen misses')
        require(good['trafficgen_tag_hits']>bad['trafficgen_tag_hits'],
                'Dispersed locality did not increase TrafficGen hits')
    concurrent=runs['concurrent-colliding']['metrics']
    require(concurrent['same_set_other_line_secondary_accepted']>0 or
            concurrent['same_set_other_line_secondary_blocked_cycles']>0,
            'Concurrent collision run did not exercise set serialization')
    save(a.work/'result.json',{'status':'passed','runs':runs,'address_geometry':
        {'line_bytes':128,'banks':16,'sets_per_bank':128,'working_lines_per_bank_per_cta':32}})

if __name__=='__main__':main()
