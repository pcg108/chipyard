#!/usr/bin/env python3
"""Bounded ABI-v3 hello/Verilator/socket run in a fresh artifact directory.

Build the desired single/pair/four/reuse hello separately and pass its exact
path. Completeness/correctness needs snapshot audit after successful exits.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import time


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 << 20), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--simulator', type=Path, required=True)
    p.add_argument('--scheduler', type=Path, required=True)
    p.add_argument('--hello', type=Path, required=True)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument('--fixture', type=Path)
    source.add_argument('--kernel-registry', type=Path)
    p.add_argument('--config', type=Path)
    p.add_argument('--scale', choices=('1', '1.5'), default='1')
    p.add_argument('--case', choices=('single', 'pair', 'four', 'reuse'), required=True)
    p.add_argument('--legacy-registry-id', type=int,
                   help='run the unchanged historical hello using the compatibility bootstrap')
    p.add_argument('--max-rounds', type=int, default=2000)
    p.add_argument('--max-cycles', type=int, default=100000)
    p.add_argument('--timeout', type=int, default=600)
    p.add_argument('--port', type=int, default=50051)
    args = p.parse_args()
    simulator, scheduler = args.simulator.resolve(), args.scheduler.resolve()
    fixture = args.fixture.resolve() if args.fixture else None
    if args.kernel_registry and args.config is None:
        p.error('--kernel-registry requires --config')
    registry = args.kernel_registry.resolve() if args.kernel_registry else fixture / 'registry.json'
    config = args.config.resolve() if args.config else fixture / f'hardware-scale-{args.scale}.config'
    if min(args.max_rounds, args.max_cycles, args.timeout) <= 0:
        p.error('limits must be positive')
    if not 1 <= args.port <= 65535:
        p.error('port must be 1..65535')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', args.port))
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    shutil.copy2(args.hello, root / 'hello')
    shutil.copy2(config, root / 'hardware.config')
    server_command = [str(scheduler), '-c', str(root / 'hardware.config'),
                      '--kernel-registry', str(registry),
                      '--socket-port', str(args.port),
                      '--round-log-dir', str(root / 'scheduler'), '-r', str(args.max_rounds)]
    sim_command = [str(simulator), '+permissive', '+fesvr-step-size=128',
                   f'+max-cycles={args.max_cycles}', '+blkdev-in-mem0=128', '+print-start=0', '+print-end=0',
                   f'+trafficgen-round-log-dir={root}/bridge',
                   f'+trafficgen-boundary-log={root}/boundaries.csv',
                   f'+trafficgen-socket-port={args.port}']
    if args.legacy_registry_id is not None:
        if args.case != 'single' or args.legacy_registry_id <= 0:
            p.error('--legacy-registry-id requires single case and positive ID')
        sim_command.append(f'+trafficgen-legacy-registry-id={args.legacy_registry_id}')
    sim_command += ['+permissive-off', str(root / 'hello')]
    record = {'case': args.case, 'fixture': str(fixture) if fixture else None, 'scale': args.scale,
              'registry': str(registry), 'registry_sha256': sha(registry), 'port': args.port,
              'timeout_seconds': args.timeout, 'legacy_registry_id': args.legacy_registry_id,
              'scheduler_command': server_command,
              'simulator_command': sim_command,
              'hashes': {str(path): sha(path) for path in (simulator, scheduler, root / 'hello', root / 'hardware.config')}}
    children, started = [], time.monotonic()
    try:
        with (root / 'scheduler.log').open('w') as sl, (root / 'simulator.log').open('w') as vl:
            server = subprocess.Popen(server_command, cwd=root, stdout=sl, stderr=subprocess.STDOUT)
            children.append(server)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('scheduler exited before listening')
                if subprocess.check_output(['ss', '-ltnH', f'sport = :{args.port}']).strip():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('scheduler listen timeout')
            sim = subprocess.Popen(sim_command, cwd=root, stdin=subprocess.DEVNULL,
                                   stdout=vl, stderr=subprocess.STDOUT)
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
