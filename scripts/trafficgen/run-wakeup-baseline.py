#!/usr/bin/env python3
"""Bounded four-kernel RTL metasim baseline; requires a built Verilator simulator.

Uses the actual hello/TL/RTL/bridge/socket path. Changes no scheduling policy.
Each synthetic one-warp CTA has N load/dependent-compute/store groups.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

REPO = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--simulator', type=Path, required=True)
    parser.add_argument('--scheduler', type=Path, default=REPO.parent / 'gpu_model/build/gpu_model_socket')
    parser.add_argument('--iterations', type=int, default=64)
    parser.add_argument('--max-rounds', type=int, default=2000)
    parser.add_argument('--max-cycles', type=int, default=100000)
    parser.add_argument('--timeout', type=int, default=300, help='wall seconds for simulation and scheduler drain')
    parser.add_argument('--no-boundary-log', action='store_true', help='control run with instrumentation disabled')
    args = parser.parse_args()
    if not 16 <= args.iterations <= 256 or min(args.max_rounds, args.max_cycles, args.timeout) <= 0:
        parser.error('iterations must be 16..256; all limits must be positive')
    root = args.output.resolve()
    simulator, scheduler = args.simulator.resolve(), args.scheduler.resolve()
    for executable in (simulator, scheduler):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            parser.error(f'not executable: {executable}')
    # Do not connect to or disturb someone else's scheduler.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', 50051))
    root.mkdir(parents=True, exist_ok=False)
    subprocess.run(['python3', str(REPO / 'scripts/trafficgen/generate-synthetic-registry.py'),
                    str(root / 'synthetic'), '--iterations', str(args.iterations)], check=True)
    # Build hello entirely in this run directory, leaving the user's workload alone.
    hello = root / 'workload'
    hello.mkdir()
    source = REPO / 'software/firemarshal/example-workloads/trafficgen-hello'
    for name in ('hello.c', 'trafficgen.h', 'mmio.h', 'Makefile'):
        shutil.copy2(source / name, hello / name)
    with (root / 'workload-build.log').open('w') as log:
        subprocess.run(['make', '-C', str(hello), 'TEST_CASE=four',
                        f'CC={REPO}/.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc',
                        f'BARE_COMMON={REPO}/software/firemarshal/test/bare'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    shutil.copy2(hello / 'hello', root / 'hello')
    scheduler_cmd = [str(scheduler), '-c', str(root / 'synthetic/hardware.config'),
                     '--kernel-registry', str(root / 'synthetic/registry.json'),
                     '--round-log-dir', str(root / 'scheduler'), '-r', str(args.max_rounds)]
    simulator_cmd = [str(simulator), '+permissive', '+fesvr-step-size=128',
                     f'+max-cycles={args.max_cycles}', '+blkdev-in-mem0=128',
                     '+print-start=0', '+print-end=-1',
                     f'+trafficgen-round-log-dir={root}/bridge']
    if not args.no_boundary_log:
        simulator_cmd.append(f'+trafficgen-boundary-log={root}/boundaries.csv')
    simulator_cmd += ['+permissive-off', str(root / 'hello')]
    provenance = {
        'design': 'FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig',
        'platform': 'BaseXilinxAlveoU250Config', 'iterations_per_launch': args.iterations,
        'expected_requests': 8 * args.iterations, 'expected_bundles': 8 * args.iterations,
        'scheduler_command': scheduler_cmd, 'simulator_command': simulator_cmd,
        'timeout_seconds': args.timeout,
        'binary_sha256': {str(p): sha(p) for p in (simulator, scheduler, root / 'hello')},
        'source_sha256': {str(p): sha(p) for p in [
            REPO / 'generators/chipyard/src/main/scala/example/TrafficGen.scala',
            REPO / 'generators/firechip/goldengateimplementations/src/main/scala/TrafficGenBridge.scala',
            REPO / 'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.cc',
            REPO / 'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.h',
            REPO.parent / 'gpu_model/src/socket_session.cpp',
            REPO / 'scripts/trafficgen/generate-synthetic-registry.py']},
        'note': 'Source hashes identify the checkout, not proof of how the supplied binaries were built.'}
    (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    children = []
    started = time.monotonic()
    try:
        with (root / 'scheduler.log').open('w') as sched_log, (root / 'metasim.log').open('w') as sim_log:
            server = subprocess.Popen(scheduler_cmd, cwd=root, stdout=sched_log, stderr=subprocess.STDOUT)
            children.append(server)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('scheduler exited before listen; see scheduler.log')
                if subprocess.check_output(['ss', '-ltnH', 'sport = :50051']).strip():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('scheduler listen timed out')
            simulation = subprocess.Popen(simulator_cmd, cwd=root, stdin=subprocess.DEVNULL,
                                          stdout=sim_log, stderr=subprocess.STDOUT)
            children.append(simulation)
            sim_code = simulation.wait(timeout=max(1, args.timeout - (time.monotonic() - started)))
            if sim_code:
                raise RuntimeError(f'Verilator exit {sim_code}; see metasim.log')
            server_code = server.wait(timeout=min(10, max(1, args.timeout - (time.monotonic() - started))))
            if server_code:
                raise RuntimeError(f'scheduler exit {server_code}; truncation is not a passing baseline')
        provenance['simulation_wall_seconds'] = time.monotonic() - started
        subprocess.run([str(REPO / 'scripts/analyze-trafficgen-snapshots.sh'),
                        'synthetic', str(root / 'bridge'), str(root / 'analysis')], check=True,
                       stdout=(root / 'analyzer.log').open('w'), stderr=subprocess.STDOUT)
        subprocess.run(['python3', str(REPO / 'scripts/trafficgen/check-session.py'),
                        str(root), '--case', 'synthetic'], check=True)
        validation = json.loads((root / 'validation.json').read_text())
        if validation['issued_requests'] != 8 * args.iterations or validation['completed_bundles'] != 8 * args.iterations:
            raise RuntimeError('request/bundle count does not match bounded fixture')
        if not args.no_boundary_log:
            subprocess.run(['python3', str(REPO / 'scripts/trafficgen/summarize-wakeups.py'), str(root)], check=True)
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
        (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


if __name__ == '__main__':
    main()
