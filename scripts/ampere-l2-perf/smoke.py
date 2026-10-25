#!/usr/bin/env python3
"""Run quad-Rocket/Saturn + Ampere TrafficGen metasim smoke; no physical FPGA access."""
import argparse
import csv
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time

from common import ValidationFailure, digest, evidence, generated_dir, require, TARGET
PROFILES = {'INT8': {'int8_rounds': 8, 'fp32_rounds': 0}}


def backend_activity(summary):
    """Confirm real target work exercised every requested backend in the ROI.

    Classification may happen in a later window than acceptance; only compare
    the per-requester hit/miss sums with the corresponding global events.
    """
    windows=[interval for interval in summary['intervals'] if interval['kind']=='window']
    require(len(windows)==1,'Expected one combined-smoke measurement window')
    banks={};totals={}
    for counter in windows[0]['counters']:
        if counter['gauge']:continue
        bank=banks.setdefault(counter['bank'],{})
        require(counter['name'] not in bank,'Duplicate smoke counter identity')
        bank[counter['name']]=int(counter['delta'])
        totals[counter['name']]=totals.get(counter['name'],0)+int(counter['delta'])
    requesters=('rocket','saturn','gemmini_int8','trafficgen','other')
    for bank,counts in banks.items():
        for outcome in ('tag_hits','tag_misses'):
            names=[r+'_'+outcome for r in requesters]
            require(outcome in counts and all(name in counts for name in names),
                    f'Missing requester-classification counters for bank {bank}')
            classified=sum(counts[name] for name in names)
            require(classified==counts[outcome],
                    f'Bank {bank} {outcome}: requester sum {classified} != global {counts[outcome]}')
    activity={}
    for requester in requesters[:-1]:
        required=[requester+'_'+event for event in ('accepted','completed','tag_hits','tag_misses')]
        require(all(name in totals for name in required),f'Missing backend counters for {requester}')
        counts={event:totals[requester+'_'+event] for event in ('accepted','completed','tag_hits','tag_misses')}
        require(counts['accepted']>0 and counts['completed']>0 and counts['tag_hits']+counts['tag_misses']>0,
                f'Combined smoke did not observe accepted, completed and classified {requester} requests: {counts}')
        activity[requester]=counts
    return activity


def int8_main(source):
    """Keep the four-hart/launch exercise while removing every FP32 call."""
    for old, new in (
        ('void int8_prepare(int); void fp32_prepare(int);', 'void int8_prepare(int);'),
        ('void int8_step(int); void fp32_step(int);', 'void int8_step(int);'),
        ('int int8_check(void); int fp32_check(void);', 'int int8_check(void);'),
        ('int8_prepare(0); fp32_prepare(0);', 'int8_prepare(0);'),
        ('int8_prepare(r); fp32_prepare(r);', 'int8_prepare(r);'),
        ('      if(r&1) { fp32_step(step); int8_step(step); }\n'
         '      else { int8_step(step); fp32_step(step); }', '      int8_step(step);'),
        ('int8_check()+fp32_check()+vector_check(0)', 'int8_check()+vector_check(0)'),
        ('dual Gemmini', 'INT8 Gemmini'), ('eight dual-array matrix rounds', 'eight INT8 matrix rounds'),
    ):
        require(old in source, f'Historical smoke source changed: {old}')
        source = source.replace(old, new)
    require(not re.search(r'\bfp32_', source), 'INT8 firmware retained FP32 references')
    return source


def int8_firmware(root, work):
    old = root / 'scripts/xrsight-trafficgen/smoke'
    out = work / 'firmware'; inc = out / 'int8/include'; inc.mkdir(parents=True)
    import shutil
    shutil.copy2(root / 'gemmini_params_illixr_int8.h', inc / 'gemmini_params.h')
    source = int8_main((old / 'main.c').read_text())
    source = source.replace('#include \"trafficgen.h\"', '#include \"trafficgen.h\"\n#include \"l2_perf.h\"')
    source = source.replace('  int8_prepare(0);', '  if(l2perf_start(663202)) finish(41);\n  int8_prepare(0);', 1)
    source = source.replace('  cycles[1]=cycle();', '  if(l2perf_snapshot()) finish(42);\n  cycles[1]=cycle();')
    source = source.replace('  cycles[2]=cycle();', '  if(l2perf_snapshot() || l2perf_stop()) finish(43);\n  cycles[2]=cycle();')
    source = source.replace('  say(\"COMBINED_SMOKE PASS:', '  if(l2perf_drain_end()) finish(44);\n  say(\"COMBINED_SMOKE PASS:')
    (out / 'main.c').write_text(source)
    shutil.copy2(Path(__file__).with_name('l2_perf.h'), out / 'l2_perf.h')
    prefix = str(root / '.conda-env/riscv-tools/bin/riscv64-unknown-elf-')
    flags = ['-march=rv64gcv', '-mabi=lp64d', '-mcmodel=medany', '-O2', '-ffreestanding',
             '-fno-common', '-fno-builtin', '-ffunction-sections', '-fdata-sections',
             '-fno-tree-vectorize', '-mno-relax', '-Wall', '-Werror=implicit-function-declaration']
    with (work / 'firmware-build.log').open('w') as log:
        def run(args):
            subprocess.run(list(map(str, args)), check=True, stdout=log, stderr=subprocess.STDOUT)
        array = out / 'int8.o'
        run([prefix + 'gcc', *flags, '-I' + str(out / 'int8'), '-I' + str(old / 'vendor'),
             '-DPREFIX=int8', '-DEXPECT_DIM=16', '-DEXPECT_OPCODE=2', '-c', old / 'array.c', '-o', array])
        run([prefix + 'objcopy', *[f'--keep-global-symbol=int8_{f}' for f in ('prepare', 'step', 'check')], array])
        objects = [array]
        for source, name in ((old / 'start.S', 'start'), (out / 'main.c', 'main')):
            obj = out / (name + '.o'); objects.append(obj)
            run([prefix + 'gcc', *flags, '-I' + str(root / 'scripts/trafficgen/workloads/trafficgen-hello'),
                 '-c', source, '-o', obj])
        elf = out / 'smoke.elf'
        run([prefix + 'gcc', *flags, '-nostdlib', '-nostartfiles', '-Wl,--gc-sections',
             '-T' + str(old / 'link.ld'), *objects, '-lgcc', '-o', elf])
        symbols = subprocess.check_output([prefix + 'nm', str(elf)], text=True)
        require(not re.search(r'\bfp32_', symbols), 'INT8 ELF contains FP32 routines')
    return elf


def firmware(root, work, design):
    if design == 'INT8':
        return int8_firmware(root, work)
    old = root / 'scripts/xrsight-trafficgen/smoke'
    output = work / 'firmware'
    if design == 'B':
        with (work / 'firmware-build.log').open('w') as log:
            subprocess.run([sys.executable, str(old / 'build.py'), '--output', str(output)],
                           check=True, stdout=log, stderr=subprocess.STDOUT)
        return output / 'smoke.elf'
    output.mkdir()
    source = (old / 'main.c').read_text()
    # Preserve the exact four-hart Saturn, guards and four-launch checks while
    # replacing only the removed accelerator exercise with Saturn work.
    for line in ('void int8_prepare(int); void fp32_prepare(int);',
                 'void int8_step(int); void fp32_step(int);',
                 'int int8_check(void); int fp32_check(void);',
                 '  int8_prepare(0); fp32_prepare(0);',
                 '    if(r) { int8_prepare(r); fp32_prepare(r); }'):
        require(line in source, f'Historical smoke source changed: {line}')
        source = source.replace(line, '')
    source = source.replace('      if(r&1) { fp32_step(step); int8_step(step); }\n'
                            '      else { int8_step(step); fp32_step(step); }',
                            '      err += vector_check(0);')
    source = source.replace('err+=int8_check()+fp32_check()+vector_check(0)', 'err+=vector_check(0)')
    source = source.replace('Saturn, dual Gemmini and TrafficGen', 'Saturn and TrafficGen')
    source = source.replace('eight dual-array matrix rounds', 'eight Saturn exercise rounds')
    require(not re.search(r'\b(?:fp32|int8)_', source), 'No-Gemmini firmware retained accelerator references')
    main = output / 'main.c'
    main.write_text(source)
    cc = str(root / '.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc')
    flags = ['-march=rv64gcv', '-mabi=lp64d', '-mcmodel=medany', '-O2', '-ffreestanding',
             '-fno-common', '-fno-builtin', '-ffunction-sections', '-fdata-sections',
             '-fno-tree-vectorize', '-mno-relax', '-Wall', '-Werror=implicit-function-declaration']
    with (work / 'firmware-build.log').open('w') as log:
        subprocess.run([cc, *flags, '-nostdlib', '-nostartfiles', '-Wl,--gc-sections',
                        '-I' + str(root / 'scripts/trafficgen/workloads/trafficgen-hello'),
                        '-T' + str(old / 'link.ld'), str(old / 'start.S'), str(main), '-lgcc',
                        '-o', str(output / 'smoke.elf')], check=True, stdout=log, stderr=subprocess.STDOUT)
    return output / 'smoke.elf'


def run(args):
    root, work = args.chipyard.resolve(), args.work.resolve()
    generated = generated_dir(root, args.target)
    simulator = generated / 'VFireSim'
    model = args.gpu_model
    if model is None:
        raise FileNotFoundError('Supply --gpu-model or TRAFFICGEN_GPU_MODEL (protocol-v2 Ampere scheduler)')
    model = model.resolve()
    for path in (simulator, model):
        if not path.is_file() or not os.access(path, os.X_OK):
            raise FileNotFoundError(f'Missing executable: {path}')
    prefix = 'firechip.chip.FireSim.' + args.target
    annos = json.loads((root / 'sims/firesim-staging/generated-src' / prefix / (prefix + '.anno.json')).read_text())
    parameters = [v['widgetConstructorKey'] for v in annos if v.get('widgetClass', '').endswith('.TrafficGenBridgeModule')]
    require(len(parameters) == 1, 'Missing/ambiguous TrafficGen bridge')
    lanes = parameters[0]['numLanes']
    elf = firmware(root, work, args.design)
    subprocess.run([sys.executable, str(root / 'scripts/trafficgen/generate-synthetic-registry.py'),
                    str(work / 'fixture'), '--iterations', '256'], check=True)
    header = (generated / 'FireSim-generated.const.h').read_text()
    reset = re.search(r'new reset_pulse_t\(.*?\bargs\s*,\s*(\d+)\s*,\s*(\d+)\s*\)\);', header, re.S)
    require(reset is not None and 128 * 8 > int(reset[1]), 'Metasim HTIF startup interval does not cover reset')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = ':'.join(filter(None, [env.get('TRAFFICGEN_BOOST_LIBS'),
        str(root / '.conda-env/lib'), env.get('LD_LIBRARY_PATH')]))
    scheduler_command = [str(model), '-c', str(work / 'fixture/hardware.config'), '--kernel-registry',
                         str(work / 'fixture/registry.json'), '--socket-port', str(port),
                         '--round-log-dir', str(work / 'scheduler')]
    command = [str(simulator), '+permissive', '+fesvr-step-size=128', '+max-cycles=12000000',
               '+idle-counts=1', '+fesvr-wait-ticks=8', f'+trafficgen-socket-port={port}',
               f'+trafficgen-round-log-dir={work}/bridge', f'+l2perf-output0={work}/l2perf', '+print-start=0', '+print-end=-1']
    for channel in range(8):
        command.extend(f'+mm_{key}_{channel}={value}' for key, value in
                       [('readLatency', 30), ('writeLatency', 30), ('readMaxReqs', 10), ('writeMaxReqs', 10)])
        command.append(f'+mm_useHardwareDefaultRuntimeSettings_{channel}')
    command.extend(['+permissive-off', str(elf)])
    (work / 'commands.json').write_text(json.dumps({'scheduler': scheduler_command, 'metasim': command}, indent=2) + '\n')
    scheduler = None
    with (work / 'scheduler.log').open('w') as slog, (work / 'metasim.log').open('w') as mlog:
        try:
            scheduler = subprocess.Popen(scheduler_command, cwd=work, env=env, stdout=slog, stderr=subprocess.STDOUT)
            # Do not connect to probe a server that accepts exactly one client.
            deadline = time.monotonic() + 30
            while 'waiting for a client' not in (work/'scheduler.log').read_text(errors='replace'):
                if scheduler.poll() is not None:
                    raise RuntimeError('Scheduler exited before simulator startup; inspect scheduler.log')
                if time.monotonic() >= deadline:
                    raise RuntimeError('Scheduler did not become ready within 30 seconds; inspect scheduler.log')
                time.sleep(.05)
            result = subprocess.run(command, cwd=work, env=env, stdin=subprocess.DEVNULL,
                                    stdout=mlog, stderr=subprocess.STDOUT, timeout=args.timeout)
            require(result.returncode == 0, f'Metasimulator exited {result.returncode}; inspect metasim.log')
            scheduler.wait(timeout=30)
            require(scheduler.returncode == 0, f'Scheduler exited {scheduler.returncode}')
        finally:
            if scheduler is not None and scheduler.poll() is None:
                scheduler.terminate()
                try:
                    scheduler.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    scheduler.kill()
                    scheduler.wait()
    text = (work / 'metasim.log').read_text(errors='replace')
    require('COMBINED_SMOKE PASS:' in text, 'Missing four-hart firmware PASS marker')
    with (work / 'scheduler/launches.csv').open() as stream:
        completed = [row for row in csv.DictReader(stream) if row['event'] == 'completed']
    require(len(completed) == 4 and {int(r['registry_id']) for r in completed} == {1106, 1003, 1044, 1001},
            'Four-launch completion check failed')
    subprocess.run([sys.executable, str(Path(__file__).with_name('check-snapshots.py')),
                    '--prefix', str(work/'l2perf'), '--window-id', '663202',
                    '--output', str(work/'l2perf-check.json')], check=True)
    activity=backend_activity(json.loads((work/'l2perf.summary.json').read_text()))
    return {'status': 'passed', 'design': args.design, 'target': args.target, 'lanes': lanes,
            'harts': 4, 'saturn_harts': 4, 'dual_array_rounds': 8 if args.design == 'B' else 0,
            'int8_array_rounds': PROFILES[args.design]['int8_rounds'],
            'fp32_array_rounds': PROFILES[args.design]['fp32_rounds'],
            'backend_activity': activity,
            'completed_launches': completed, 'rtl': evidence(generated / 'FireSim-generated.sv'),
            'driver_header': evidence(generated / 'FireSim-generated.const.h'),
            'metasimulator': evidence(simulator), 'scheduler': evidence(model), 'firmware': evidence(elf)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chipyard', type=Path, required=True)
    parser.add_argument('--target', default=TARGET)
    parser.add_argument('--design', choices=tuple(PROFILES), default='INT8')
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--gpu-model', type=Path, default=os.environ.get('TRAFFICGEN_GPU_MODEL'))
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=False)
    try:
        result, code = run(args), 0
    except ValidationFailure as error:
        result, code = {'status': 'failed', 'classification': 'functional_validation', 'error': str(error)}, 2
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result, code = {'status': 'blocked', 'classification': 'infrastructure_or_timeout', 'error': repr(error)}, 90
    (args.work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return code

if __name__ == '__main__':
    raise SystemExit(main())
