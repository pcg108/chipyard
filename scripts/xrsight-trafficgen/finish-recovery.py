#!/usr/bin/env python3
"""Emit and package a validated routing recovery without programming the FPGA.

This does not start or repeat routing and refuses a resource-stop latch.
Source env.sh before invoking so the driver-packaging dependencies are available.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--recovery', type=Path, required=True)
a = p.parse_args()
pkg = Path(__file__).resolve().parent
root = pkg.parents[1]
recovery = a.recovery.resolve(strict=True)
work = recovery.parent
result = json.loads((recovery / 'recovery.json').read_text())
if result.get('state') != 'validated_candidate':
    raise ValueError('Recovery has not passed final implementation checks')
if (work / 'STOPPED_NO_AUTORESTART.json').exists():
    raise ValueError('Resource-stop latch is set')
xrsight = Path('/home/prashanth/illixr_example_zephyr')
revision = subprocess.check_output(['git', '-C', str(xrsight), 'rev-parse', 'HEAD'], text=True).strip()
if revision != '5d3c912ed912c08a9e646279b15b4b48b680f879':
    raise ValueError('XRSight report-checker revision changed')
sys.path.insert(0, str(xrsight / 'scripts'))
from check_firesim_platform import parse_final_timing, parse_final_route, parse_postroute_bus_skew, evidence

project = recovery / 'vivado_proj'
reports = project / 'reports'
run = project / 'firesim.runs/impl_1'
checks = {
    'timing': parse_final_timing(reports / 'final_timing_summary.rpt'),
    'routing': parse_final_route(reports / 'final_route_status.rpt'),
    'bus_skew': parse_postroute_bus_skew(run / 'overall_fpga_top_bus_skew_postroute_physopted.rpt'),
}
for key, value in checks.items():
    if value != result[key]:
        raise ValueError(f'{key} report changed after recovery validation')
checkpoint = run / 'overall_fpga_top_postroute_physopt.dcp'
checkpoint_evidence = evidence(checkpoint)
quintuplet = 'xilinx_alveo_u250-firesim-FireSim-FireSimQuadRocketDualGemminiSaturnTrafficGenConfig-BaseXilinxAlveoU250Config'
cl = recovery / ('cl_' + quintuplet)
original_cl = work / 'builds/platforms/xilinx_alveo_u250' / cl.name
output = work / 'deliverables'
if cl.exists() or output.exists():
    raise ValueError('An emission or packaging attempt already exists; inspect it before continuing')
cl.mkdir()
(cl / 'vivado_proj').symlink_to(project, target_is_directory=True)
(cl / 'driver').symlink_to(original_cl / 'driver', target_is_directory=True)
status = json.loads((work / 'pipeline-status.json').read_text())
status.update(state='running', stage='recovered_bitstream_emission',
              recovery=str(recovery / 'recovery.json'), checkpoint=checkpoint_evidence)
def save(**items):
    status.update(items, updated_at=datetime.now(timezone.utc).isoformat())
    (work / 'pipeline-status.json').write_text(json.dumps(status, indent=2) + '\n')
save()
try:
    command = [sys.executable, str(pkg / 'firesim_resource_guard.py'), '--scratch', str(work),
               '--run-dir', str(recovery / 'guard-emission'),
               '--latch', str(work / 'STOPPED_NO_AUTORESTART.json'), '--jobs', '4', '--',
               '/ecad/tools/xilinx/Vivado/2022.1/bin/vivado', '-mode', 'batch', '-nojournal',
               '-log', str(recovery / 'emission-vivado.log'),
               '-source', str(pkg / 'emit-recovered-bitstream.tcl'),
               '-tclargs', str(checkpoint), str(project)]
    with (recovery / 'emission-console.log').open('x') as log:
        subprocess.run(command, cwd=recovery, env=dict(os.environ, TMPDIR=str(work / 'tmp')),
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    stage = cl / 'xilinx_alveo_u250'
    stage.mkdir()
    for name in ('firesim.bit', 'firesim.mcs'):
        source = project / name
        if source.stat().st_size == 0:
            raise ValueError(f'Empty {name}')
        (stage / name).symlink_to(source)
    triplet = 'FireSim-FireSimQuadRocketDualGemminiSaturnTrafficGenConfig-BaseXilinxAlveoU250Config'
    makefrag = str(root / 'generators/firechip/chip/src/main/makefrag/firesim')
    manifest = json.loads((pkg / 'build-manifest.json').read_text())
    tags = {
        'firesim-buildquintuplet': quintuplet, 'firesim-deployquintuplet': quintuplet,
        'firesim-buildtriplet': triplet, 'firesim-deploytriplet': triplet,
        'firesim-commit': manifest['source_checkouts']['sims/firesim'],
        'firesim-buildmakefrag': makefrag, 'firesim-deploymakefrag': makefrag,
    }
    (stage / 'metadata').write_text(','.join(f'{k}:{v}' for k, v in tags.items()) + '\n')
    with tarfile.open(cl / 'firesim.tar.gz', 'w:gz', dereference=True) as archive:
        archive.add(stage, arcname=stage.name)
    save(stage='package_recovered_bitstream')
    subprocess.run([sys.executable, str(pkg / 'package-build.py'), '--build-output', str(cl),
                    '--smoke-result', str(work / 'validation/smoke-20261012T035130Z/result.json'),
                    '--output', str(output)], cwd=root, check=True)
    # Preserve the distinction between the synthesized RTL and recovery scripts.
    original = json.loads((pkg / 'source-record/bitstream-stop.json').read_text())
    source_path = output / 'source-manifest.json'
    source = json.loads(source_path.read_text())
    source['packaging_source_commit'] = source['build_source_commit']
    source['build_source_commit'] = original['build_source_commit']
    source['implementation_recovery'] = {
        'source_commit': result['source_commit'], 'change': result['change'],
        'checkpoint': checkpoint_evidence, 'result': evidence(recovery / 'recovery.json'),
    }
    source_path.write_text(json.dumps(source, indent=2) + '\n')
    for name, path in {
        'recovery.json': recovery / 'recovery.json',
        'route-guard.json': recovery / 'guard/guard-result.json',
        'emission-guard.json': recovery / 'guard-emission/guard-result.json',
    }.items():
        shutil.copy2(path, output / name)
    hardware_path = output / 'hardware.json'
    hardware = json.loads(hardware_path.read_text())
    hardware['implementation_recovery'] = source['implementation_recovery']
    hardware['source_manifest'] = evidence(source_path)
    hardware_path.write_text(json.dumps(hardware, indent=2) + '\n')
    save(state='complete', stage='complete', hardware=str(hardware_path), fpga_programmed=False)
except BaseException as exc:
    save(state='failed', error=repr(exc))
    raise
