#!/usr/bin/env python3
"""Validate final routed evidence and package this instrumented image and driver."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile
from common import ROOT,TARGET,QUINTUPLET,digest,evidence,git,require,save,source_manifest,submodule_records
from report_checker import check_project

def libraries(driver):
    # Use the established FireSim library resolver and omit the system libc.
    sys.path.insert(0,str(ROOT/'sims/firesim/deploy'))
    from runtools.utils import get_local_shared_libraries
    libc={Path(p).resolve() for p in subprocess.check_output(
        ['dpkg-query','-L','libc6:amd64'],text=True).splitlines() if '.so' in p}
    return [(Path(p),n) for p,n in get_local_shared_libraries(str(driver)) if Path(p).resolve() not in libc]

def archive_source(out,work):
    """Ship committed history and all uncommitted source without relying on alternates."""
    baseline=json.loads((work/'control/baseline-manifest.json').read_text())
    original={entry['path']:entry['commit'] for entry in baseline['submodules']}
    modules=submodule_records(ROOT)
    repos=[('',ROOT,baseline['chipyard'])]+[(r['path'],ROOT/r['path'],original.get(r['path']))
          for r in modules if r['initialized']]
    records=[];bundle_dir=out/'source-bundles';bundle_dir.mkdir()
    with tarfile.open(out/'source-changes.tar.gz','w:gz',dereference=False) as archive:
        for rel,repo,initial in repos:
            head=git(repo,'rev-parse','HEAD')
            status=git(repo,'status','--porcelain=v1','--untracked-files=all')
            tracked=subprocess.check_output(['git','-C',str(repo),'diff','--name-only','-z','HEAD']).split(b'\0')
            untracked=subprocess.check_output(['git','-C',str(repo),'ls-files','--others','--exclude-standard','-z']).split(b'\0')
            files=sorted({p.decode() for p in tracked+untracked if p})
            item={'path':rel or '.','baseline_commit':initial,'actual_head':head,'git_status':status,'files':{}}
            for name in files:
                path=repo/name
                if not path.is_file() and not path.is_symlink():continue
                # A submodule directory is represented by its own record.
                arcname=str(Path('chipyard')/rel/name)
                archive.add(path,arcname=arcname,recursive=False)
                item['files'][name]={'symlink':os.readlink(path)} if path.is_symlink() else evidence(path)
            if rel=='' or head!=initial:
                bundle=bundle_dir/((rel.replace('/','__') or 'chipyard')+'.bundle')
                # Full HEAD history is self-contained even for a --shared clone.
                subprocess.run(['git','-C',str(repo),'bundle','create',str(bundle),'HEAD'],check=True)
                subprocess.run(['git','-C',str(repo),'bundle','verify',str(bundle)],check=True)
                item['bundle']=evidence(bundle)
            records.append(item)
    save(out/'source-repositories.json',records)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True)
    a=p.parse_args();w=a.work.resolve();out=w/'deliverables'
    gate=w/'results/validation-gate.json'
    require(json.loads(gate.read_text())['status']=='passed','Full validation gate has not passed')
    projects=[p.parent for p in (w/'builds').rglob('firesim.xpr') if (p.parent/'firesim.bit').is_file()]
    require(len(projects)==1,'Need exactly one completed fresh implementation project')
    project=projects[0];checks=check_project(project,30)
    synth=json.loads((project/'reports/post_synth_resource_gate.json').read_text())
    require(synth['status']=='passed','Synthesis resource gate did not pass')
    driver_candidates=[ROOT/'sims/firesim/sim/output/xilinx_alveo_u250'/QUINTUPLET/'FireSim-xilinx_alveo_u250',
                       ROOT/'sims/firesim/sim/generated-src/xilinx_alveo_u250'/QUINTUPLET/'FireSim-xilinx_alveo_u250']
    drivers=[p for p in driver_candidates if p.is_file()]
    if not drivers:
        drivers=[p for p in (ROOT/'sims/firesim/sim').rglob('FireSim-xilinx_alveo_u250') if QUINTUPLET in str(p)]
    require(len(drivers)==1,'Missing/ambiguous matching instrumented driver')
    driver=drivers[0];head=driver.read_bytes()[:64]
    require(head[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',head,18)[0]==62 and os.access(driver,os.X_OK),
            'Driver is not an executable x86-64 ELF')
    require(not out.exists() or not any(out.iterdir()),'Deliverables already contain files')
    out.mkdir(exist_ok=True);stage=out/'xilinx_alveo_u250';stage.mkdir()
    for name in ('firesim.bit','firesim.mcs'):
        require((project/name).stat().st_size>0,'Missing '+name)
        shutil.copy2(project/name,out/name);shutil.copy2(project/name,stage/name)
    triplet='FireSim-'+TARGET+'-BaseXilinxAlveoU250Config'
    tags={'firesim-buildquintuplet':QUINTUPLET,'firesim-deployquintuplet':QUINTUPLET,
      'firesim-buildtriplet':triplet,'firesim-deploytriplet':triplet,
      'firesim-commit':git(ROOT/'sims/firesim','rev-parse','HEAD'),
      'firesim-buildmakefrag':str(ROOT/'generators/firechip/chip/src/main/makefrag/firesim'),
      'firesim-deploymakefrag':str(ROOT/'generators/firechip/chip/src/main/makefrag/firesim')}
    (stage/'metadata').write_text(','.join(f'{k}:{v}' for k,v in tags.items())+'\n')
    with tarfile.open(out/'firesim.tar.gz','w:gz') as archive:archive.add(stage,arcname=stage.name)
    runtime=out/'quad-rocket-int8-gemmini-saturn-trafficgen-l2perf.conf'
    args=[]
    for channel in range(8):
        args.extend(f'+mm_{k}_{channel}={v}' for k,v in [('readLatency',30),('writeLatency',30),('readMaxReqs',10),('writeMaxReqs',10)])
        args.append(f'+mm_useHardwareDefaultRuntimeSettings_{channel}')
    args+=['+fesvr-step-size=10000','+idle-counts=1','+fesvr-wait-ticks=8','+l2perf-output0=l2perf0']
    runtime.write_text('\n'.join(args)+'\n')
    contents=[(driver,driver.name),(runtime,runtime.name),*libraries(driver)]
    require(len({name for _,name in contents})==len(contents),'Duplicate driver archive members')
    with tarfile.open(out/'driver.tar.gz','w:gz',dereference=True) as archive:
        for path,name in contents:archive.add(path,arcname=name)
    for name in ('l2_perf.h','measurement-example.c'):shutil.copy2(Path(__file__).with_name(name),out/name)
    shutil.copy2(ROOT/'gemmini_params_illixr_int8.h',out/'gemmini_params_illixr_int8.h')
    prefix='firechip.chip.FireSim.'+TARGET
    emitted=ROOT/'sims/firesim-staging/generated-src'/prefix
    shutil.copy2(emitted/(prefix+'.l2-perf-source-map.json'),out/'l2-perf-source-map.json')
    shutil.copy2(emitted/(prefix+'.l2-perf-target.json'),out/'l2-perf-target.json')
    shutil.copy2(w/'results/rodinia/l2perf.schema.json',out/'l2perf.schema.json')
    shutil.copytree(project/'reports',out/'reports')
    shutil.copy2(project/'firesim.runs/impl_1/overall_fpga_top_bus_skew_postroute_physopted.rpt',
                 out/'reports/overall_fpga_top_bus_skew_postroute_physopted.rpt')
    shutil.copy2(gate,out/'validation-gate.json')
    for name in ('combined-smoke','rodinia','set-interference'):
        # Preserve actual counter output and result manifests without copying simulator binaries.
        dest=out/'validation'/name;dest.mkdir(parents=True)
        for f in (w/'results'/name).rglob('*'):
            if f.is_file() and f.suffix in ('.json','.csv','.log'):
                target=dest/f.relative_to(w/'results'/name);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target)
    save(out/'source-manifest.json',source_manifest(ROOT))
    preflight=json.loads((w/'results/preflight.json').read_text());save(out/'tool-manifest.json',preflight['tools'])
    save(out/'final-reports.json',checks)
    status=json.loads((w/'control/status.json').read_text());save(out/'controller-status.json',status)
    validation_logs=out/'validation/logs';validation_logs.mkdir(parents=True,exist_ok=True)
    for name,entry in status['stages'].items():
        if name!='package' and entry.get('status')=='passed' and 'log' in entry:
            shutil.copy2(entry['log'],validation_logs/(name+'.log'))
    shutil.copy2(w/'results/processor-fixes/summary-all.json',out/'validation/processor-fixes-summary.json')
    telemetry=out/'resource-records';telemetry.mkdir()
    for name,entry in status['stages'].items():
        if 'guard' not in entry:continue
        dest=telemetry/name;dest.mkdir()
        for file in ('guard-result.json','memory.jsonl'):
            shutil.copy2(Path(entry['guard'])/file,dest/file)
    patches=out/'source-patches';patches.mkdir()
    (patches/'chipyard.patch').write_bytes(subprocess.check_output(['git','-C',str(ROOT),'diff','--binary','HEAD']))
    for entry in submodule_records(ROOT):
        rel=entry['path']
        if not (ROOT/rel/'.git').exists():continue
        patch=subprocess.check_output(['git','-C',str(ROOT/rel),'diff','--binary','HEAD'])
        if patch:(patches/(rel.replace('/','__')+'.patch')).write_bytes(patch)
    archive_source(out,w)
    alias='alveo_u250_quad_rocket_int8_gemmini_saturn_trafficgen_l2perf_30mhz'
    save(out/'config_hwdb.yaml',{alias:{'bitstream_tar':(out/'firesim.tar.gz').as_uri(),
      'driver_tar':(out/'driver.tar.gz').as_uri(),'deploy_quintuplet_override':None,'custom_runtime_config':runtime.name}})
    record={'status':'passed','target':TARGET,'requested_fpga_mhz':30,'modeled_target_mhz':500,
      'fpga_programmed':False,'checks':checks,'synthesis_resources':synth,'driver':evidence(driver),
      'driver_files':{name:evidence(path) for path,name in contents},'validation_gate':evidence(gate),
      'bitstream':evidence(out/'firesim.bit'),'mcs':evidence(out/'firesim.mcs'),'implementation_project':str(project)}
    save(out/'hardware.json',record)
    (out/'README.txt').write_text('Ampere L2 performance-counter U250 image, physical30MHz / modeled500MHz.\n'
      'Four Rockets, four Saturns, one INT8Gemmini on hart0/custom2,16TrafficGen lanes.\n'
      'No FPGA was programmed by this experiment. See hardware.json and final-reports.json for acceptance evidence.\n\n'
      'The source-manifest records actual checked-out commits, including manually cloned submodules.\n'
      'source-bundles contains self-contained HEAD histories for Chipyard and components changed since the baseline.\n'
      'To recover a bundled repository: git init DEST; git -C DEST fetch /absolute/PATH.bundle HEAD;\n'
      'git -C DEST checkout --detach FETCH_HEAD. Initialize unchanged dependencies from their recorded remotes/pins.\n'
      'source-patches captures remaining tracked differences and source-changes.tar.gz includes actual changed/new files.\n'
      'source-repositories.json records exact per-repository status and file hashes.\n\n'
      'Install the included runtime config in the chosen FireSim checkout before selecting config_hwdb.yaml.\n'
      'Use l2_perf.h from exactly one target coordinator; counter records are host-visible via +l2perf-output0=PREFIX.\n'
      'START/STOP define[START,STOP); intermediate SNAPSHOT records do not reset or stop the window.\n'
      'DRAIN_END records the separate tail. Plugins sharing a backend remain aggregated.\n')
    (out/'SHA256SUMS').write_text(''.join(f'{digest(f)}  {f.relative_to(out)}\n' for f in sorted(out.rglob('*')) if f.is_file()))
    print(out/'hardware.json')

if __name__=='__main__':main()
