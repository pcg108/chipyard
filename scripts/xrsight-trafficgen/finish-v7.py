#!/usr/bin/env python3
"""Validate a completed v7 build, cold-program U250, and compare kernel 6632.

Run with the Chipyard environment sourced, after continue-v7.py succeeds.
Every failed gate stops; this script performs no implementation recovery.
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
pkg = root / 'scripts/xrsight-trafficgen'
work = Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
target = 'FireSimQuadRocketDualGemminiSaturnTrafficGenConfig'
quintuplet = f'xilinx_alveo_u250-firesim-FireSim-{target}-BaseXilinxAlveoU250Config'
build = work / 'builds/platforms/xilinx_alveo_u250' / ('cl_' + quintuplet)
status = json.loads((work / 'pipeline-status.json').read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--resume-package', action='store_true',
                    help='Resume a packaging failure after the completed final DRC')
args = parser.parse_args()
fpga_lock = None
priority = work / 'FPGA_REQUESTED.json'
if args.resume_package:
    assert status['state'] == 'failed' and status['stage'] == 'package', status
    assert 'final-drc' in status['completed_stages']
    assert 'XRSIGHT_FINAL_DRC_ERRORS 0' in (work / 'logs/final-drc.log').read_text()
    assert json.loads((work / 'guard-final-drc/guard-result.json').read_text())['command_exit_code'] == 0
    status.pop('error', None)
else:
    assert status['state'] == 'bitstream_build_finished', status
assert not (work / 'STOPPED_NO_AUTORESTART.json').exists()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            digest.update(block)
    return digest.hexdigest()


def save(**values):
    status.update(values, updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    temp = work / 'pipeline-status.tmp'
    temp.write_text(json.dumps(status, indent=2) + '\n')
    temp.replace(work / 'pipeline-status.json')
    print(json.dumps(status), flush=True)


def run(stage, command, env=None):
    save(state='running', stage=stage)
    guarded = [sys.executable, str(pkg / 'firesim_resource_guard.py'), '--scratch', str(work),
               '--run-dir', str(work / ('guard-' + stage)), '--latch',
               str(work / 'STOPPED_NO_AUTORESTART.json'), '--jobs', '4', '--', *command]
    with (work / 'logs' / (stage + '.log')).open('x') as log:
        subprocess.run(guarded, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    status['completed_stages'].append(stage)


try:
    for mode in ('reference-6632', 'metasim-6632'):
        assert json.loads((work / mode / 'validation.json').read_text())['result'] == 'PASS'
        assert json.loads((work / mode / 'provenance.json').read_text())['result'] == 'PASS'
    project = build / 'vivado_proj'
    checkpoint = project / 'firesim.runs/impl_1/overall_fpga_top_postroute_physopt.dcp'
    assert checkpoint.is_file(), checkpoint
    if args.resume_package:
        assert checkpoint.stat().st_mtime < (project / 'reports/final_drc.rpt').stat().st_mtime
    else:
        run('final-drc', ['/ecad/tools/xilinx/Vivado/2022.1/bin/vivado', '-mode', 'batch',
                         '-nojournal', '-log', str(work / 'logs/final-drc-vivado.log'),
                         '-source', str(pkg / 'check-final-drc.tcl'), '-tclargs', str(checkpoint),
                         str(project / 'reports/final_drc.rpt')])
    output = work / 'deliverables'
    run('package', [sys.executable, str(pkg / 'package-build.py'), '--build-output', str(build),
                    '--smoke-result', str(work / 'validation/combined-smoke/result.json'),
                    '--validation-root', str(work), '--output', str(output)])
    hardware = json.loads((output / 'hardware.json').read_text())
    assert hardware['timing_closed']
    bit = Path(hardware['package']['bit']['path'])
    assert sha(bit) == hardware['package']['bit']['sha256']
    slot = work / 'physical-slot'
    slot.mkdir()
    for name, evidence in hardware['driver_files'].items():
        path = Path(evidence['path'])
        assert sha(path) == evidence['sha256']
        shutil.copy2(path, slot / name)
    priority.write_text(json.dumps({'pid': os.getpid(), 'purpose': 'Hopper kernel 6632 physical validation'}) + '\n')
    save(stage='waiting_for_fpga', fpga_coordination='Ampere sweeps yield between kernels')
    fpga_lock = Path('/tmp/prashanth-xrsight-u250.lock').open('a')
    fcntl.flock(fpga_lock, fcntl.LOCK_EX)
    # Check all XDMA devices because the installed reload helper unloads the
    # shared module. Never displace another process using a device.
    for device in Path('/dev').glob('xdma*'):
        owned = subprocess.run(['fuser', str(device)], capture_output=True, text=True)
        assert owned.returncode == 1, (device, owned.stdout, owned.stderr)
    commands = []
    if Path('/sys/module/xdma').exists():
        commands.append(['sudo', '-n', '/usr/local/bin/firesim-remove-xdma-module'])
    commands.extend([
        ['sudo', '-n', '/usr/local/bin/firesim-fpga-util.py', '--bitstream', str(bit),
         '--bdf', '51:00.0', '--fpga-db', '/opt/firesim-db.json',
         '--vivado-bin', '/ecad/tools/xilinx/Vivado/2022.1/bin/vivado',
         '--hw-server-bin', '/ecad/tools/xilinx/Vivado/2022.1/bin/hw_server'],
        ['sudo', '-n', '/usr/local/bin/firesim-load-xdma-module'],
        ['sudo', '-n', '/usr/local/bin/firesim-chmod-xdma-perm'],
        ['sudo', '-n', '/usr/local/bin/firesim-change-pcie-perms', '0000:51:00.0']])
    (work / 'programming-commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    for index, command in enumerate(commands):
        run(f'programming-{index}', command)
    assert os.access('/dev/xdma0_user', os.R_OK | os.W_OK)
    save(fpga_programmed=True, programmed_bitstream=hardware['package']['bit'])
    env = dict(os.environ, LD_LIBRARY_PATH=str(work / 'model-build/boost-libs') + ':' + str(slot))
    env.pop('LD_PRELOAD', None)
    run('firesim-6632', ['/usr/bin/python3', str(root / 'scripts/trafficgen/run-tma-metasim.py'),
        str(work / 'firesim-6632'), '--model-source', str(work / 'model-src'), '--model-build',
        str(work / 'model-build'), '--dataset', '/home/prashanth/gpu_model/hopper-gemma',
        '--kernel', '6632', '--port', '50072', '--design', target, '--policy-decoder',
        str(work / 'model-build/export-tma-policies'), '--fpga', '--simulator',
        str(slot / 'FireSim-xilinx_alveo_u250')], env=env)
    validation = json.loads((work / 'firesim-6632/validation.json').read_text())
    save(firesim_scheduling_rounds=validation['complete_policy_snapshots'],
         firesim_final_cycle=validation['final_cycle'])
    run('comparison', ['/usr/bin/python3', str(pkg / 'plot-hopper-comparison.py'), '--software',
        str(work / 'reference-6632'), '--firesim', str(work / 'firesim-6632'), '--output', str(work / 'PNGs')])
    hardware.update(fpga_programmed=True, kernel_6632_validation=validation,
                    comparison_png=str(work / 'PNGs/hopper-gemma-kernel-6632-comparison.png'))
    (output / 'hardware.json').write_text(json.dumps(hardware, indent=2) + '\n')
    save(state='complete', stage='complete', hardware=str(output / 'hardware.json'),
         comparison_png=hardware['comparison_png'])
except BaseException as error:
    save(state='failed', error=repr(error))
    raise
finally:
    if priority.exists() and json.loads(priority.read_text()).get('pid') == os.getpid():
        priority.unlink()
    if fpga_lock is not None:
        fpga_lock.close()
