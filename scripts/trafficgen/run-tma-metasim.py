#!/usr/bin/env python3
"""Run an isolated, single-launch kernel-6632/6668 protocol-v7 reference or metasim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time

REPO = Path(__file__).resolve().parents[2]
PIN = '7b6abcafe312c21e87393181410eb2f2aea1d938'


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--model-source', type=Path, required=True)
    parser.add_argument('--model-build', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--reference', action='store_true')
    mode.add_argument('--simulator', type=Path)
    parser.add_argument('--dataset', type=Path, default=REPO.parent / 'gpu_model/hopper-gemma')
    parser.add_argument('--kernel', type=int, choices=(6632,6668), default=6632)
    parser.add_argument('--port', type=int, default=50052)
    parser.add_argument('--max-cycles', type=int, default=300000000)
    # The accepted smaller-target 6632 replay already took 9,284 seconds.
    # Match that run's explicit twelve-hour limit for the combined target.
    parser.add_argument('--timeout', type=int, default=43200)
    parser.add_argument('--design', default='FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig')
    parser.add_argument('--fpga', action='store_true', help='Use a freshly programmed physical U250 with --simulator DRIVER')
    parser.add_argument('--policy-decoder', type=Path, required=True,
                        help='official-v7 decoder for complete archived policy snapshots')
    args = parser.parse_args()
    if args.fpga and args.reference:
        parser.error('--fpga requires --simulator')
    if not 1 <= args.port <= 65535 or min(args.max_cycles, args.timeout) <= 0:
        parser.error('port must be 1..65535 and limits must be positive')
    root, source, build, data = (p.resolve() for p in
                                (args.output, args.model_source, args.model_build, args.dataset))
    observed_pin = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if observed_pin != PIN or subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'], text=True).strip():
        raise RuntimeError('GPU model source must be the unmodified requested commit')
    scheduler = build / 'gpu_model_socket'
    client = build / 'tma-reference-client' if args.reference else args.simulator.resolve()
    for path in (scheduler, client):
        if not path.is_file() or not os.access(path, os.X_OK):
            parser.error(f'not executable: {path}')
    # Probe ownership without connecting to another scheduler's one-shot accept.
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    root.mkdir(parents=True, exist_ok=False)
    (root / 'bridge').mkdir()
    trace = data / f'traces/gemma4-h100/kernel-{args.kernel}.traceg'
    kernel_dirs = list((data / 'l2_trace').glob(f'kernel_{args.kernel}_*'))
    if len(kernel_dirs) != 1:
        raise RuntimeError(f'expected one kernel {args.kernel} L2 directory; found {len(kernel_dirs)}')
    registry = {'kernels': [{'registryId': args.kernel, 'tracePath': str(trace), 'dataRoot': str(data)}]}
    (root / 'registry.json').write_text(json.dumps(registry, indent=2) + '\n')
    shutil.copy2(source / 'configs/mobile8_hopper.config', root / 'hardware.config')
    config = (root / 'hardware.config').read_text()
    scale=1.0 if args.kernel==6632 else 2.0
    config=re.sub(r'double issueLatencyScale = [0-9.]+',f'double issueLatencyScale = {scale}',config)
    (root / 'hardware.config').write_text(config)
    if f'double issueLatencyScale = {scale}' not in config or 'double memoryIssueStretchScale = 1.0' not in config:
        raise RuntimeError('pinned Hopper timing configuration does not match acceptance configuration')
    if 'bool enableMbarriers = true' not in config:
        raise RuntimeError('Phase 2 requires enableMbarriers=true')
    scheduler_cmd = [str(scheduler), '-c', str(root / 'hardware.config'), '--kernel-registry',
                     str(root / 'registry.json'), '--socket-port', str(args.port), '--round-log-dir',
                     str(root / 'scheduler'), '--plot-dir', str(root / 'plots'), '-t']
    commands = {'scheduler': scheduler_cmd}
    binaries = [scheduler, client, args.policy_decoder.resolve()]
    if args.reference:
        client_cmd = [str(client), str(args.port), str(args.kernel), str(data / 'l2_trace'),
                      str(root / 'bridge/lrc_events.csv')]
    else:
        hello = root / 'workload'
        hello.mkdir()
        for name in ('hello.c', 'trafficgen.h', 'mmio.h', 'Makefile'):
            shutil.copy2(REPO / 'scripts/trafficgen/workloads/trafficgen-hello' / name, hello / name)
        commands['workload_build'] = ['make', '-C', str(hello), 'TEST_CASE=single',
                                     f'KERNEL_REGISTRY_ID={args.kernel}',
                                     f'CC={REPO}/.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc',
                                     f'BARE_COMMON={REPO}/software/firemarshal/test/bare']
        with (root / 'workload-build.log').open('w') as log:
            subprocess.run(commands['workload_build'], stdout=log, stderr=subprocess.STDOUT, check=True)
        binaries.append(hello / 'hello')
        client_cmd = [str(client), '+permissive', '+fesvr-step-size=128',
                      f'+max-cycles={args.max_cycles}', '+blkdev-in-mem0=128', '+print-start=0',
                      '+print-end=-1', f'+trafficgen-socket-port={args.port}',
                      f'+trafficgen-round-log-dir={root}/bridge',
                      f'+trafficgen-boundary-log={root}/boundaries.csv', '+permissive-off', str(hello / 'hello')]
        runtime = []
        if args.design == 'FireSimQuadRocketDualGemminiSaturnTrafficGenConfig':
            runtime += ['+idle-counts=1', '+fesvr-wait-ticks=8']
            for channel in range(8):
                runtime += [f'+mm_{key}_{channel}={value}' for key, value in
                            [('readLatency', 30), ('writeLatency', 30), ('readMaxReqs', 10), ('writeMaxReqs', 10)]]
                runtime.append(f'+mm_useHardwareDefaultRuntimeSettings_{channel}')
        if args.fpga:
            runtime += ['+domain=0', '+bus=51', '+device=0', '+function=0', '+bar=0',
                        '+pci-vendor=10ee', '+pci-device=903f']
        client_cmd[client_cmd.index('+permissive-off'):client_cmd.index('+permissive-off')] = runtime
    commands['client'] = client_cmd
    relevant_sources = [p for base in ('generators/chipyard/src', 'generators/firechip')
                        for p in (REPO / base).rglob('*') if p.is_file() and
                        ('trafficgen' in str(p).lower() or 'LrcCompletionMonitor' in p.name or 'TmaWakeMonitor' in p.name) and
                        p.suffix in ('.scala', '.h', '.cc')]
    relevant_sources += [Path(__file__).resolve(), REPO / 'scripts/trafficgen/tma-reference-client.cc',
                         REPO / 'scripts/trafficgen/check-tma-session.py', REPO / 'scripts/trafficgen/plot-tma-session.py']
    dataset_files = [trace, data / 'README.txt'] + sorted(kernel_dirs[0].glob('shader_*/scheduler_*/*.txt'))
    dataset_files += list((data / f'metadata/kernel-{args.kernel}').glob('*.json'))
    if (data / 'import_manifest.json').exists():
        dataset_files.append(data / 'import_manifest.json')
    provenance = {
        'model_commit': PIN, 'protocol_version': 7, 'boost_version': '1.85.0',
        'mode': 'reference' if args.reference else 'firesim' if args.fpga else 'metasim',
        'design': args.design,
        'platform': 'BaseXilinxAlveoU250Config', 'replay_lanes': 16, 'outstanding_per_lane': 8,
        'registry_id': args.kernel, 'commands': commands, 'timeout_seconds': args.timeout,
        'binary_sha256': {str(p): sha(p) for p in binaries},
        'source_sha256': {str(p): sha(p) for p in relevant_sources},
        'model_source_sha256': {str(p.relative_to(source)): sha(p) for p in sorted(source.rglob('*')) if p.is_file() and '.git' not in p.relative_to(source).parts},
        'dataset_sha256': {str(p): sha(p) for p in dataset_files},
        'config_sha256': sha(root / 'hardware.config'), 'registry_sha256': sha(root / 'registry.json'),
        'build_provenance_note': 'Use the separate fresh-build manifest to establish binary source provenance.'}
    (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    children = []
    start = time.monotonic()
    try:
        with (root / 'scheduler.log').open('w') as sched_log, (root / 'client.log').open('w') as client_log:
            server = subprocess.Popen(scheduler_cmd, cwd=root, stdout=sched_log, stderr=subprocess.STDOUT)
            children.append(server)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('scheduler exited before listen; see scheduler.log')
                if subprocess.check_output(['ss', '-ltnH', f'sport = :{args.port}']).strip():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('scheduler listen timed out')
            process = subprocess.Popen(client_cmd, cwd=root, stdin=subprocess.DEVNULL,
                                       stdout=client_log, stderr=subprocess.STDOUT)
            children.append(process)
            result = process.wait(timeout=max(1, args.timeout - (time.monotonic() - start)))
            provenance['client_exit_code'] = result
            if result:
                raise RuntimeError(f'client exit {result}; see client.log')
            result = server.wait(timeout=max(1, args.timeout - (time.monotonic() - start)))
            provenance['scheduler_exit_code'] = result
            if result:
                raise RuntimeError(f'scheduler exit {result}; truncation is failure')
        text = (root / 'client.log').read_text()
        terminal = (r'^PASS LRC reference rounds=\d+ final_cycle=(\d+)\r?$' if args.reference else
                    r'^\[target\] PASS TrafficGen test=1 final_cycle=(\d+)\r?$')
        successes = re.findall(terminal, text, re.MULTILINE)
        if len(successes) != 1:
            raise RuntimeError('missing successful client/workload termination')
        provenance['terminal_final_cycle'] = int(successes[0])
        commands['decode_policies'] = [str(args.policy_decoder.resolve()), str(root/'bridge'),
                                       str(root/'policies.jsonl')]
        subprocess.run(commands['decode_policies'], check=True)
        commands['validation'] = ['python3', str(REPO / 'scripts/trafficgen/check-tma-session.py'),
                                  str(root), '--dataset', str(data), '--kernel', str(args.kernel),
                                  '--require-complete-policies']
        # Publish the observed process statuses before the independent checker
        # runs; a complete event log alone does not establish session success.
        (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        subprocess.run(commands['validation'], check=True)
        commands['plot'] = [str(REPO / '.conda-env/bin/python3'), str(REPO / 'scripts/trafficgen/plot-tma-session.py'), str(root)]
        subprocess.run(commands['plot'], check=True)
        provenance['result'] = 'PASS'
    except Exception as error:
        provenance['result'] = 'FAIL'
        provenance['error'] = str(error)
        raise
    finally:
        for child in reversed(children):
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
        provenance['wall_seconds'] = time.monotonic() - start
        (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


if __name__ == '__main__':
    main()
