#!/usr/bin/env python3
"""Run an Ampere protocol-v2 session without accessing an FPGA.

Inputs are explicit so the same runner can exercise baseline and optimized peers.
Detailed mode checks the complete captured request identity set for every launch.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time


def rows(path):
    with path.open(newline='') as f:
        return list(csv.DictReader(f))


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def require(value, message):
    if not value:
        raise RuntimeError(message)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sim', 'model', 'firmware', 'registry', 'config', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--ids', type=int, nargs='+', default=[1106])
    p.add_argument('--summary', action='store_true')
    p.add_argument('--cache-plan', type=Path)
    p.add_argument('--timeout', type=int, default=3600)
    a = p.parse_args()
    for name in ('sim', 'model', 'firmware', 'registry', 'config', 'output'):
        setattr(a, name, getattr(a, name).resolve())
    out = a.output
    out.mkdir(parents=True, exist_ok=False)
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    model = [str(a.model), '-c', str(a.config), '--kernel-registry', str(a.registry),
             '--socket-port', str(port),
             '--summary-dir' if a.summary else '--round-log-dir', str(out / 'scheduler')]
    if a.cache_plan:
        model += ['--trace-cache-plan', str(a.cache_plan.resolve())]
    sim = [str(a.sim), '+permissive', '+fesvr-step-size=128', '+idle-counts=1',
           '+fesvr-wait-ticks=8', '+max-cycles=12000000',
           f'+trafficgen-socket-port={port}', f'+trafficgen-round-log-dir={out}/bridge',
           '+print-start=0', '+print-end=-1']
    if a.summary:
        sim.append('+trafficgen-log-mode=summary')
    for ch in range(8):
        sim += [f'+mm_{key}_{ch}={value}' for key, value in
                [('readLatency', 30), ('writeLatency', 30), ('readMaxReqs', 10), ('writeMaxReqs', 10)]]
        sim.append(f'+mm_useHardwareDefaultRuntimeSettings_{ch}')
    sim += ['+permissive-off', str(a.firmware)]
    record = {'status': 'running', 'execution': 'Verilator FireSim metasim; no FPGA',
              'commands': {'model': model, 'sim': sim}, 'summary_mode': a.summary,
              'binary_sha256': {key: sha(getattr(a, key)) for key in ('model', 'sim', 'firmware')}}
    (out / 'run.json').write_text(json.dumps(record, indent=2) + '\n')
    start = time.monotonic()
    server = None
    try:
        with (out / 'model.log').open('w') as slog, (out / 'metasim.log').open('w') as mlog:
            server = subprocess.Popen(model, cwd=out, stdout=slog, stderr=subprocess.STDOUT)
            # Do not probe with a connection: this server accepts exactly one client.
            deadline = time.monotonic() + 30
            while 'waiting for a client' not in (out / 'model.log').read_text(errors='replace'):
                require(server.poll() is None, 'Model exited before simulator startup')
                require(time.monotonic() < deadline, 'Model did not become ready')
                time.sleep(.05)
            result = subprocess.run(sim, cwd=out, stdin=subprocess.DEVNULL, stdout=mlog,
                                    stderr=subprocess.STDOUT, timeout=a.timeout)
            record['sim_exit'] = result.returncode
            require(result.returncode == 0, 'Metasim failed; see metasim.log')
            record['model_exit'] = server.wait(timeout=30)
            require(server.returncode == 0, 'Model failed; see model.log')
        text = (out / 'metasim.log').read_text(errors='replace')
        require('[target] PASS TrafficGen' in text, 'Missing target firmware PASS marker')
        life = rows(out / 'scheduler/launches.csv')
        completed = [r for r in life if r['event'] == 'completed']
        require([int(r['registry_id']) for r in completed] == a.ids, 'Completed launch sequence differs')
        require(len({r['launch_id'] for r in completed}) == len(a.ids), 'Reused launch ID')
        record['launches'] = completed
        if a.summary:
            model_summary = json.loads((out / 'scheduler/summary.json').read_text())
            driver_summary = json.loads((out / 'bridge/summary.json').read_text())
            require(model_summary['status'] == driver_summary['status'] == 'complete', 'Incomplete summary')
            for key in ('original_reads', 'original_writes', 'completed_launches'):
                require(model_summary[key] == driver_summary[key], 'Model/driver count mismatch: ' + key)
            require(not model_summary['pending_groups'] and not model_summary['pending_requests'], 'Model pending work')
            require(not driver_summary['pending_bundles'] and not driver_summary['pending_requests'], 'Driver pending work')
            record.update(model_summary=model_summary, driver_summary=driver_summary)
        else:
            manifest = json.loads(a.registry.read_text())['kernels']
            expected = {}
            inputs = {}
            for ident in set(a.ids):
                entry = next(e for e in manifest if e['registryId'] == ident)
                root = (a.registry.parent / entry['dataRoot']).resolve()
                trace = (a.registry.parent / entry['tracePath']).resolve()
                kernel_id = int(re.search(r'kernel-(\d+)', trace.name)[1])
                directories = list((root / 'l2_trace').glob(f'kernel_{kernel_id}_*'))
                require(len(directories) == 1, 'Ambiguous captured kernel directory')
                points = {}
                for name in ('l1_to_l2_requests.txt', 'l1_bypass_to_l2.txt'):
                    for path in directories[0].rglob(name):
                        inputs[str(path)] = sha(path)
                        for line in path.read_text().splitlines():
                            if not line.strip() or line.startswith('#'):
                                continue
                            f = dict(item.split('=', 1) for item in line.split() if '=' in item)
                            uid = int(f['request_uid'], 0)
                            require(uid not in points, 'Duplicate capture request UID')
                            points[uid] = (int(f['addr'], 0), int(f['type'] == 'WRITE'))
                require(points, 'Empty captured request set')
                expected[ident] = points
            issued = rows(out / 'scheduler/issued_accesses.csv')
            for launch in completed:
                subset = [r for r in issued if r['launch_id'] == launch['launch_id']]
                actual = {int(r['request_uid']): (int(r['address']), int(r['is_write'])) for r in subset}
                require(len(actual) == len(subset), 'Duplicate issued request UID')
                require(actual == expected[int(launch['registry_id'])], 'Issued request identity mismatch')
            require(len(issued) == sum(len(expected[i]) for i in a.ids), 'Unexpected launch requests')
            controls = rows(out / 'bridge/controls.csv')
            record.update(requests=len(issued), reads=sum(r['is_write'] == '0' for r in issued),
                          writes=sum(r['is_write'] == '1' for r in issued), rounds=len(controls),
                          issued_sha256=sha(out / 'scheduler/issued_accesses.csv'),
                          lifecycle_sha256=sha(out / 'scheduler/launches.csv'), input_sha256=inputs)
        record['status'] = 'passed'
    except BaseException as e:
        record.update(status='failed', error=repr(e))
        raise
    finally:
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        record['wall_seconds'] = time.monotonic() - start
        (out / 'run.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({k: v for k, v in record.items() if k not in ('input_sha256', 'commands')}, indent=2))


if __name__ == '__main__':
    main()
