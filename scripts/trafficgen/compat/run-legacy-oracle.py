#!/usr/bin/env python3
"""Bounded run of the old single-kernel protocol with an isolated log-only copy.

Requires relocate-legacy-logs.py provenance beside the simulator. Uses no VCD;
the actual old hello, RTL, bridge driver, and exact-commit socket model run.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--simulator', type=Path, required=True)
    p.add_argument('--scheduler', type=Path, required=True)
    p.add_argument('--hello', type=Path, required=True)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument('--fixture', type=Path)
    source.add_argument('--kernel-registry', type=Path)
    p.add_argument('--registry-id', type=int)
    p.add_argument('--config', type=Path)
    p.add_argument('--scale', choices=('1', '1.5'), default='1')
    p.add_argument('--max-rounds', type=int, default=2000)
    p.add_argument('--max-cycles', type=int, default=100000)
    p.add_argument('--timeout', type=int, default=180)
    p.add_argument('--port', type=int, default=50051)
    p.add_argument('--port-shim', type=Path,
                   help='optional test-only LD_PRELOAD loopback redirect; required for a nondefault port')
    args = p.parse_args()
    simulator, scheduler = args.simulator.resolve(), args.scheduler.resolve()
    fixture = args.fixture.resolve() if args.fixture else None
    selected = None
    if args.kernel_registry:
        if args.registry_id is None or args.config is None:
            p.error('--kernel-registry requires --registry-id and --config')
        registry_path = args.kernel_registry.resolve()
        selected = next((r for r in json.loads(registry_path.read_text())['kernels']
                         if r['registryId'] == args.registry_id), None)
        if selected is None:
            p.error('registry ID not found')
        dataset = (registry_path.parent / selected['dataRoot']).resolve()
        trace = (registry_path.parent / selected['tracePath']).resolve()
        if not dataset.is_dir() or not trace.is_file():
            p.error('dataset or trace is missing')
    relocation = json.loads(simulator.with_suffix('.relocation.json').read_text())
    if sha(simulator) != relocation['relocated_sha256'] or not relocation['all_other_bytes_identical']:
        p.error('simulator differs from verified log-only relocation')
    # Port isolation alone cannot protect the old driver's fixed log clearing.
    # Keep this descriptor alive throughout main(), including child cleanup.
    log_guard = (Path(relocation['log_root']) / '.compat-run.lock').open('w')
    try:
        fcntl.flock(log_guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        p.error('another reference is using this simulator supplemental log root')
    if min(args.max_rounds, args.max_cycles, args.timeout) <= 0:
        p.error('limits must be positive')
    if not 1 <= args.port <= 65535:
        p.error('port must be 1..65535')
    if (args.port != 50051) != (args.port_shim is not None):
        p.error('a nondefault port and --port-shim must be supplied together')
    child_env = os.environ.copy()
    redirection = None
    if args.port_shim:
        if child_env.get('LD_PRELOAD'):
            p.error('refuse to combine the port shim with an existing LD_PRELOAD')
        shim = args.port_shim.resolve(strict=True)
        child_env.update(LD_PRELOAD=str(shim), TG_COMPAT_SOCKET_PORT=str(args.port))
        redirection = {'shim': str(shim), 'sha256': sha(shim),
                       'original_endpoint': '127.0.0.1:50051',
                       'redirected_endpoint': f'127.0.0.1:{args.port}',
                       'environment': {'LD_PRELOAD': str(shim), 'TG_COMPAT_SOCKET_PORT': str(args.port)}}
    # Bind only to detect a collision; never connect to another user's server.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', args.port))
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    shutil.copy2(args.hello, root / 'hello')
    config = args.config.resolve() if args.config else fixture / f'hardware-scale-{args.scale}.config'
    shutil.copy2(config, root / 'hardware.config')
    if selected:
        kernels_list = root / 'kernelslist.g'
        kernels_list.write_text(str(trace) + '\n')
    else:
        dataset, kernels_list = fixture / '1106', fixture / '1106/traces/compat/kernelslist.g'
    scheduler_command = [str(scheduler), '-c', str(root / 'hardware.config'),
                         '-a', str(dataset), '-k', str(kernels_list),
                         '--round-log-dir', str(root / 'scheduler'), '-r', str(args.max_rounds)]
    simulator_command = [str(simulator), '+permissive', '+fesvr-step-size=128',
                         f'+max-cycles={args.max_cycles}', '+blkdev-in-mem0=128',
                         '+print-start=0', '+print-end=0',
                         f'+trafficgen-round-log-dir={root}/bridge', '+permissive-off', str(root / 'hello')]
    record = {'gpu_commit': 'e345d5618078e77200697055d944d3ff9575c804',
              'hardware_commit': 'd07370a293b15cbc347932a7745a36d6a6456585',
              'simulator_log_relocation': relocation, 'scheduler_command': scheduler_command,
              'simulator_command': simulator_command, 'fixture': str(fixture) if fixture else None,
              'registry_id': args.registry_id, 'registry_entry': selected,
              'scale': args.scale, 'timeout_seconds': args.timeout,
              'port': args.port, 'test_transport_redirection': redirection,
              'hashes': {str(path): sha(path) for path in (scheduler, root / 'hello', root / 'hardware.config')}}
    if selected:
        kernel_id = trace.stem.split('-')[1]
        inputs = [trace, kernels_list]
        for kind in ('warp_assignments', 'l2_trace'):
            for folder in (dataset / kind).glob(f'kernel_{kernel_id}_*'):
                inputs.extend(sorted(folder.rglob('*.txt')))
        record['input_sha256'] = {str(path): sha(path) for path in inputs}
    children = []
    started = time.monotonic()
    try:
        with (root / 'scheduler.log').open('w') as sl, (root / 'simulator.log').open('w') as vl:
            server = subprocess.Popen(scheduler_command, cwd=root, stdout=sl,
                                      stderr=subprocess.STDOUT, env=child_env)
            children.append(server)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('scheduler exited before listening')
                if subprocess.check_output(['ss', '-ltnH', f'sport = :{args.port}']).strip():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('scheduler listen timeout')
            sim = subprocess.Popen(simulator_command, cwd=root, stdin=subprocess.DEVNULL,
                                   stdout=vl, stderr=subprocess.STDOUT, env=child_env)
            children.append(sim)
            record['simulator_exit'] = sim.wait(timeout=max(1, args.timeout - (time.monotonic() - started)))
            record['scheduler_exit'] = server.wait(timeout=10)
            if record['simulator_exit'] or record['scheduler_exit']:
                raise RuntimeError('nonzero simulator/scheduler exit')
        record['result'] = 'COMPLETED_REQUIRES_DIFFERENTIAL_AUDIT'
    except Exception as error:
        record.update(result='FAIL', error=str(error))
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
        record['wall_seconds'] = time.monotonic() - started
        (root / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
