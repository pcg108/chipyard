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
target='FireSimQuadRocketDualGemminiSaturnTrafficGenConfig'
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
    require(sum(m.startswith('Gemmini') for m in mods)==(2 if h==0 else 0),f'Wrong Gemmini placement on hart {h}')
headers={}
for name,expected in {
    'gemmini_params_illixr.h':'4e45a12fddba04fcc9eeac9fa554c7282ec55e14526d2efcd661ffabf8f371f9',
    'gemmini_params_illixr_int8.h':'b56e2e464f26c6e3b93f08b4c1fc41fc1f8a4ba44057ab751101c4bdc25c3901',
}.items():
    actual=digest(root/name);require(actual==expected,f'{name} differs from XRSight ABI');headers[name]=actual
annos=json.loads((stage/(prefix+'.anno.json')).read_text())
bridges=[x for x in annos if x.get('class')=='firesim.lib.bridgeutils.BridgeAnnotation']
counts=Counter(x['widgetClass'].split('.')[-1] for x in bridges)
require(counts['FASEDMemoryTimingModel']==8,f'Expected eight FASED channels: {counts}')
require(counts['TrafficGenBridgeModule']==1,f'Missing TrafficGen bridge: {counts}')
require(not any('TracerV' in b or 'NIC' in b for b in counts),'Unexpected NIC or TracerV')
tg=next(x['widgetConstructorKey'] for x in bridges if x['widgetClass'].endswith('.TrafficGenBridgeModule'))
require(tg['numLanes']==16 and tg['useRTL'],'Wrong TrafficGen lane count/backend')
require(all(x['widgetConstructorKey']['axi4Widths']['dataBits']==256 for x in bridges if x['widgetClass'].endswith('.FASEDMemoryTimingModel')),'Wrong memory bus width')
l2=json.loads((stage/(prefix+'.l2.json')).read_text())['banks']
require(len(l2)==16 and all(b['ways']==16 and b['blockBytes']==128 and len(b['setBits'])==7 for b in l2),'Wrong L2 organization')
summary=stage/(prefix+'.firesimRationalClockBridge.freq-summary')
clocks=summary.read_text()
require('Input Reference Frequency: 500.0 MHz' in clocks or 'Input Reference Frequency: 500 MHz' in clocks,'Wrong modeled reference frequency')
result={'target':target,'cpus':cpus,'memory_base':0x80000000,'memory_bytes':8<<30,
    'timebase_hz':500000,'tiles':tiles,'bridge_counts':dict(counts),'headers':headers,
    'dts_sha256':digest(dts),'firrtl_sha256':digest(fir),'timing_closed':False}
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
