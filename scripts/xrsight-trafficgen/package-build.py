#!/usr/bin/env python3
"""Validate final U250 reports and package the matching driver without programming hardware."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile

p=argparse.ArgumentParser()
p.add_argument('--build-output',type=Path,required=True)
p.add_argument('--smoke-result',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--xrsight',type=Path,default=Path('/home/prashanth/illixr_example_zephyr'))
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
revision=subprocess.check_output(['git','-C',str(a.xrsight),'rev-parse','HEAD'],text=True).strip()
if revision!='5d3c912ed912c08a9e646279b15b4b48b680f879': raise ValueError('XRSight report-checker revision changed')
sys.path.insert(0,str(a.xrsight/'scripts'))
from check_firesim_platform import parse_final_timing,parse_final_route,parse_postroute_bus_skew,inspect_package,evidence,require
build=a.build_output.resolve();out=a.output.resolve()
quintuplet='xilinx_alveo_u250-firesim-FireSim-FireSimQuadRocketDualGemminiSaturnTrafficGenConfig-BaseXilinxAlveoU250Config'
reports=build/'vivado_proj/reports'
skew=list((build/'vivado_proj/firesim.runs/impl_1').glob('overall_fpga_top_bus_skew_postroute_physopted.rpt'))
require(len(skew)==1,'Missing final bus-skew report')
smoke=json.loads(a.smoke_result.read_text());require(smoke['status']=='passed','Combined smoke has not passed')
record={
    'timing':parse_final_timing(reports/'final_timing_summary.rpt'),
    'routing':parse_final_route(reports/'final_route_status.rpt'),
    'bus_skew':parse_postroute_bus_skew(skew[0]),
    'package':inspect_package(build/'firesim.tar.gz',quintuplet),
    'utilization':evidence(reports/'final_utilization.rpt'),
    'smoke':evidence(a.smoke_result),
    'source_commit':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
    'checker':evidence(a.xrsight/'scripts/check_firesim_platform.py'),
    'timing_closed':True,'fpga_programmed':False,
}
driver=build/'driver/FireSim-xilinx_alveo_u250'
with driver.open('rb') as f: elf=f.read(64)
require(elf[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',elf,18)[0]==62 and os.access(driver,os.X_OK),'Wrong driver executable')
out.mkdir(parents=True,exist_ok=False)
runtime=out/'quad-rocket-dual-gemmini-saturn-trafficgen.conf'
args=[]
for ch in range(8):
    args.extend(f'+mm_{key}_{ch}={value}' for key,value in [('readLatency',30),('writeLatency',30),('readMaxReqs',10),('writeMaxReqs',10)])
    args.append(f'+mm_useHardwareDefaultRuntimeSettings_{ch}')
args+=['+fesvr-step-size=10000','+idle-counts=1','+fesvr-wait-ticks=8']
runtime.write_text('\n'.join(args)+'\n')
sys.path.insert(0,str(root/'sims/firesim/deploy'))
from runtools.utils import get_local_shared_libraries
contents=[(driver,driver.name),(runtime,runtime.name)]
# FireSim's helper queries /usr/lib paths, but this host's libc package records
# /lib paths. Resolve both spellings so we do not bundle glibc beside a host loader.
glibc_files={Path(path).resolve() for path in subprocess.check_output(
    ['dpkg-query','-L','libc6:amd64'],text=True).splitlines() if '.so' in path}
contents += [(Path(path),name) for path,name in get_local_shared_libraries(str(driver))
             if Path(path).resolve() not in glibc_files]
require(len({name for _,name in contents})==len(contents),'Duplicate driver archive members')
with tarfile.open(out/'driver.tar.gz','w:gz',dereference=True) as archive:
    for path,name in contents: archive.add(path,arcname=name)
record['driver_files']={name:evidence(path) for path,name in contents}
for name in ['gemmini_params_illixr.h','gemmini_params_illixr_int8.h']:
    shutil.copy2(root/name,out/name)
source_manifest=json.loads((root/'scripts/xrsight-trafficgen/build-manifest.json').read_text())
source_manifest['build_source_commit']=record['source_commit']
source_manifest['validation'].update(combined_target_metasimulation='passed',bitstream='passed_final_timing_and_routing')
(out/'source-manifest.json').write_text(json.dumps(source_manifest,indent=2)+'\n')
shutil.copy2(root/'scripts/xrsight-trafficgen/source-record/elaboration.json',out/'elaboration.json')
shutil.copy2(a.smoke_result,out/'smoke-result.json')
shutil.copy2(build/'firesim.tar.gz',out/'firesim.tar.gz')
record['bitstream']=evidence(out/'firesim.tar.gz');record['driver_archive']=evidence(out/'driver.tar.gz')
(out/'hardware.json').write_text(json.dumps(record,indent=2)+'\n')
runtime_dest=root/'sims/firesim/sim/custom-runtime-configs'/runtime.name
runtime_dest.parent.mkdir(parents=True,exist_ok=True)
if runtime_dest.exists(): require(runtime_dest.read_bytes()==runtime.read_bytes(),'Conflicting runtime config')
else: shutil.copy2(runtime,runtime_dest)
alias='alveo_u250_quad_rocket_dual_gemmini_saturn_trafficgen_abi3'
(out/'config_hwdb.yaml').write_text(json.dumps({alias:{'bitstream_tar':(out/'firesim.tar.gz').as_uri(),'deploy_quintuplet_override':None,'custom_runtime_config':runtime.name}},indent=2)+'\n')
print(out/'hardware.json')
