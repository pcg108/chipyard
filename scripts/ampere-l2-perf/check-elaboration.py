#!/usr/bin/env python3
"""Check emitted hardware rather than relying on config-fragment precedence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess

def require(ok, message):
    if not ok: raise ValueError(message)

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1<<20),b''): h.update(block)
    return h.hexdigest()

def read_dts(path):
    blob=subprocess.check_output(['dtc','-I','dts','-O','dtb',str(path)],stderr=subprocess.PIPE)
    magic,_,pos,strings=struct.unpack_from('>4I',blob)
    require(magic==0xd00dfeed,'Invalid DTB')
    nodes={};stack=[]
    while True:
        tag,=struct.unpack_from('>I',blob,pos);pos+=4
        if tag==1:
            end=blob.index(0,pos);stack.append(blob[pos:end].decode());pos=(end+4)&~3
            nodes['/'+'/'.join(stack[1:])]={}
        elif tag==2: stack.pop()
        elif tag==3:
            length,offset=struct.unpack_from('>2I',blob,pos);pos+=8
            key=blob[strings+offset:blob.index(0,strings+offset)].decode()
            nodes['/'+'/'.join(stack[1:])][key]=blob[pos:pos+length];pos=(pos+length+3)&~3
        elif tag==4: continue
        elif tag==9: return nodes
        else: raise ValueError('Invalid FDT token')

p=argparse.ArgumentParser()
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
# The instrumented branch must retain the exact Ampere engine and protocol.
ampere_sources=[
 'generators/chipyard/src/main/scala/example/TrafficGen.scala',
 'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.cc',
 'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen_socket_protocol.h',
 'generators/firechip/goldengateimplementations/src/main/scala/TrafficGenBridge.scala',
]
ampere_hashes={}
for source in ampere_sources:
    pinned=subprocess.check_output(['git','-C',str(root),'show','d0646221af1361919fa13b2a43584eeca8db4dd9:'+source])
    require((root/source).read_bytes()==pinned,'Ampere source changed unexpectedly: '+source)
    ampere_hashes[source]=digest(root/source)
protocol=(root/ampere_sources[2]).read_text()
require(re.search(r'kSocketProtocolVersion\s*=\s*2\s*;',protocol),'Socket protocol is not v2')
target='FireSimQuadRocketInt8GemminiSaturnTrafficGenL2PerfConfig'
prefix='firechip.chip.FireSim.'+target
stage=root/'sims/firesim-staging/generated-src'/prefix
dts=stage/(prefix+'.dts')
fir=stage/(prefix+'.fir')
nodes=read_dts(dts)
number=lambda b:int.from_bytes(b,'big')
cpus=sorted((number(v['reg']),v['riscv,isa'].rstrip(b'\0').decode()) for v in nodes.values() if v.get('device_type')==b'cpu\0')
require([h for h,_ in cpus]==list(range(4)),f'Unexpected CPUs: {cpus}')
require(all('v' in isa.split('_')[0] and 'd' in isa.split('_')[0] and 'zvl256b' in isa for _,isa in cpus),'Missing VLEN256/FP64 ISA')
require(number(nodes['/cpus']['timebase-frequency'])==500000,'Wrong timebase')
mem=[v for v in nodes.values() if v.get('device_type')==b'memory\0' and v.get('status')!=b'disabled\0']
require(len(mem)==1 and number(mem[0]['reg'][:8])==0x80000000 and number(mem[0]['reg'][8:])==8<<30,'Wrong memory map')
tiles={};module=None;traffic=[]
with fir.open() as f:
    for line in f:
        m=re.match(r'  module (\w+)\s*:',line)
        if m:
            module=m[1]
            if re.fullmatch(r'RocketTile(?:_\d+)?',module): tiles[module]=[]
        m=re.match(r'    inst (\w+) of (\w+)',line)
        if m:
            if module in tiles: tiles[module].append(m[2])
            if m[1].startswith('trafficgenmem'): traffic.append(m.groups())
require(len(tiles)==4,'Wrong Rocket tile count')
for h in range(4):
    mods=tiles['RocketTile'+('_'+str(h) if h else '')]
    require(sum('Saturn' in m and 'Unit' in m for m in mods)==1,f'Missing Saturn on hart {h}')
    require(sum(m.startswith('Gemmini') for m in mods)==(1 if h==0 else 0),f'Wrong Gemmini placement on hart {h}')
headers={}
for name,expected in {
    'gemmini_params_illixr_int8.h':'b56e2e464f26c6e3b93f08b4c1fc41fc1f8a4ba44057ab751101c4bdc25c3901',
}.items():
    actual=digest(root/name);require(actual==expected,f'{name} differs from XRSight ABI');headers[name]=actual
annos=json.loads((stage/(prefix+'.anno.json')).read_text())
bridges=[x for x in annos if x.get('class')=='firesim.lib.bridgeutils.BridgeAnnotation']
counts=Counter(x['widgetClass'].split('.')[-1] for x in bridges)
require(counts['FASEDMemoryTimingModel']==8,f'Expected eight FASED channels: {counts}')
require(counts['L2PerfBridgeModule']==1,f'Missing L2 performance bridge: {counts}')
require(counts['TrafficGenBridgeModule']==1,f'Missing TrafficGen bridge: {counts}')
require(not any('TracerV' in b or 'NIC' in b for b in counts),'Unexpected NIC or TracerV')
tg=next(x['widgetConstructorKey'] for x in bridges if x['widgetClass'].endswith('.TrafficGenBridgeModule'))
require(tg['numLanes']==16 and tg['useRTL'],'Wrong TrafficGen lane count/backend')
require(all(x['widgetConstructorKey']['axi4Widths']['dataBits']==256 for x in bridges if x['widgetClass'].endswith('.FASEDMemoryTimingModel')),'Wrong memory bus width')
l2=json.loads((stage/(prefix+'.l2.json')).read_text())['banks']
require(len(l2)==16 and all(b['ways']==16 and b['blockBytes']==128 and len(b['setBits'])==7 for b in l2),'Wrong L2 organization')
target_manifest=stage/(prefix+'.l2-perf-target.json')
actual=json.loads(target_manifest.read_text())
require(actual['schema_version']==1,'Wrong target manifest schema')
require([h['hart_id'] for h in actual['harts']]==list(range(4)),'Wrong actual hart IDs')
for hart in actual['harts']:
    require(hart['num_hpm_counters']==13 and hart['vlen']==256,'HPM/vector parameters changed')
    require('Saturn' in hart['vector_class'],'Expected Saturn on every hart')
    require(len(hart['roccs'])==(1 if hart['hart_id']==0 else 0),'Wrong actual RoCC placement')
gemmini=actual['harts'][0]['roccs'][0]
require(gemmini['opcodes']==[91],'INT8 Gemmini must use custom2')
g=gemmini['gemmini']
require(g['rows']==g['columns']==16 and g['input_bits']==8 and 'SInt' in g['input_type'] and g['accumulator_bits']==32,'Wrong INT8 Gemmini arithmetic')
require(g['scratchpad_bytes']==256*1024 and g['accumulator_bytes']==64*1024,'Gemmini memory capacity changed')
t=actual['trafficgen']
require(t['num_lanes']==16 and t['mem_outstanding']==8 and t['serialize_same_line'] and t['backend']=='rtl' and t['replay_entries']==32768,'Wrong actual TrafficGen parameters')
require('L2PutGet' in t['memory_backend'],'Wrong TrafficGen memory backend')
require(actual['bus']['system_beat_bytes']==actual['bus']['memory_beat_bytes']==32,'Wrong actual bus widths')
require(actual['bus']['system_frequency_hz']==actual['bus']['memory_frequency_hz']==500000000,'Wrong modeled clock parameters')
require(actual['memory_channels']==8 and actual['memory_bytes']==8<<30 and actual['l2_banks']==16,'Wrong actual memory topology')
source_map=stage/(prefix+'.l2-perf-source-map.json')
requesters=json.loads(source_map.read_text())
require(requesters['schema_version']==1 and len(requesters['banks'])==16,'Missing bank source-ID map')
for bank in requesters['banks']:
    classes={c['requester'] for c in bank['clients']}
    require({'rocket','saturn','gemmini_int8','trafficgen'}<=classes,'Missing requester class in elaborated source map')
    for client in bank['clients']:
        require(client['name'] and client['source_start']<client['source_end_exclusive'],'Invalid source-ID range')
    intervals=sorted((c['source_start'],c['source_end_exclusive']) for c in bank['clients'])
    require(all(a[1]<=b[0] for a,b in zip(intervals,intervals[1:])),'Overlapping source-ID ownership')
summary=stage/(prefix+'.firesimRationalClockBridge.freq-summary')
clocks=summary.read_text()
require('Input Reference Frequency: 500.0 MHz' in clocks or 'Input Reference Frequency: 500 MHz' in clocks,'Wrong modeled reference frequency')
result={'target':target,'cpus':cpus,'memory_base':0x80000000,'memory_bytes':8<<30,
    'timebase_hz':500000,'tiles':tiles,'bridge_counts':dict(counts),'headers':headers,
    'actual_target':actual,'target_manifest_sha256':digest(target_manifest),'ampere_sources':ampere_hashes,'socket_protocol':2,'launch_abi':3,'source_id_map':str(source_map),'source_id_map_sha256':digest(source_map),'dts_sha256':digest(dts),'firrtl_sha256':digest(fir),'timing_closed':False,'status':'passed'}
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
