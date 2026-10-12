#!/usr/bin/env python3
"""Supervise only this build, including tagged localhost SSH workers.

Hard limits remain 48 GiB available RAM, 64 GiB per worker, and no new kernel
OOM kills. Global memory PSI some/full avg10 of 1.0/0.1% produces warnings only,
including sustained pressure. A hard stop creates a persistent latch; this
module never restarts a command.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


def utc():
    return datetime.now(timezone.utc).isoformat()


def identity(pid):
    try:
        path = Path('/proc', str(pid))
        fields = (path / 'stat').read_text().rsplit(')', 1)[1].split()
        return {'parent': int(fields[1]), 'start': fields[19],
                'uid': path.stat().st_uid, 'state': fields[0],
                'rss_mib': int(fields[21]) * os.sysconf('SC_PAGE_SIZE') // 1048576}
    except (OSError, ValueError, IndexError):
        return None


def snapshot():
    marker = ('ILLIXR_BUILD_GUARD_TAG=' + os.environ['ILLIXR_BUILD_GUARD_TAG']).encode()
    processes = {}
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        pid = int(path.name)
        info = identity(pid)
        if info is None or info['uid'] != os.getuid():
            continue
        try:
            info['cwd'] = os.readlink(path / 'cwd')
        except OSError:
            info['cwd'] = ''
        try:
            info['tagged'] = marker in (path / 'environ').read_bytes().split(b'\0')
        except OSError:
            info['tagged'] = False
        processes[pid] = info
    return processes


def select(processes, manager_pid, scratch):
    excluded = {os.getpid()}
    ancestor = os.getpid()
    while ancestor in processes:
        ancestor = processes[ancestor]['parent']
        if ancestor in excluded:
            break
        excluded.add(ancestor)
    selected = {manager_pid} if manager_pid in processes else set()
    selected.update(pid for pid, info in processes.items()
                    if info.get('tagged'))
    selected.difference_update(excluded)
    while True:
        children = {pid for pid, info in processes.items()
                    if info['parent'] in selected and pid not in excluded}
        if children <= selected:
            return selected
        selected.update(children)


def sample():
    mem = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    pressure = {}
    for line in Path('/proc/pressure/memory').read_text().splitlines():
        fields = line.split()
        pressure[fields[0]] = dict(item.split('=') for item in fields[1:])
    vm = dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
    return {'available_mib': int(mem['MemAvailable'].split()[0]) // 1024,
            'some_avg10': float(pressure['some']['avg10']),
            'full_avg10': float(pressure['full']['avg10']),
            'oom_kill': int(vm['oom_kill'])}


def stop_reasons(memory, initial_oom, processes):
    reasons = []
    if memory['available_mib'] < 48 * 1024:
        reasons.append('available_memory_below_48_GiB')
    if memory['oom_kill'] > initial_oom:
        reasons.append('kernel_oom_kill_counter_increased')
    if any(info['rss_mib'] >= 64 * 1024 for info in processes.values()):
        reasons.append('process_RSS_at_least_64_GiB')
    return reasons


def warning_reasons(memory):
    # This is a host-wide diagnostic, not evidence that this build is near OOM.
    if memory['some_avg10'] >= 1 or memory['full_avg10'] >= .1:
        return ['global_memory_PSI']
    return []


def matching(pid, expected):
    actual = identity(pid)
    return (actual is not None and actual['state'] not in ('Z', 'X')
            and actual['start'] == expected['start'] and actual['uid'] == expected['uid'])


def cleanup(child, scratch, known):
    # Preserve identities even if a fresh process scan fails. An unreaped direct
    # child also pins its process-group ID, making group cleanup safe.
    owned = dict(known)
    try:
        current = snapshot()
        selected = select(current, child.pid if child.returncode is None else None, scratch)
        owned.update({pid: current[pid] for pid in selected})
    except BaseException:
        pass
    group = child.pid if child.returncode is None else None
    for sig in (signal.SIGTERM, signal.SIGKILL):
        if group is not None:
            try:
                os.killpg(group, sig)
            except ProcessLookupError:
                pass
        for pid, info in owned.items():
            if matching(pid, info):
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
        if sig == signal.SIGTERM:
            deadline = time.monotonic() + 1
            while time.monotonic() < deadline and any(matching(pid, info) for pid, info in owned.items()):
                time.sleep(.05)
    try:
        child.wait(timeout=10)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait(timeout=10)
    return [pid for pid, info in owned.items() if matching(pid, info)]


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def supervise(command, scratch, run_dir, latch):
    child = None
    owned = {}
    signals = []
    initial_oom = None
    start = time.monotonic()
    result = {'started_at': utc(), 'status': 'starting', 'command': command,
              'psi_action': 'warning_only', 'psi_warning_samples': 0}
    # Configuration mistakes are not resource-stop events. Check before opening
    # telemetry, installing handlers, or starting a child; preserve a previous
    # hard-stop record exactly, even when the caller also omitted its tag.
    if latch.exists():
        result.update(status='resource_stopped',
                      stop_reasons=['existing_no_autorestart_latch'])
        setup_code = 90
    elif not os.environ.get('ILLIXR_BUILD_GUARD_TAG', '').strip():
        result.update(status='setup_error', error='ILLIXR_BUILD_GUARD_TAG must be nonempty')
        setup_code = 2
    else:
        setup_code = None
    if setup_code is not None:
        result.update(finished_at=utc(), host_elapsed_seconds=time.monotonic() - start)
        write_json(run_dir / 'guard-result.json', result)
        if setup_code == 2:
            print(result['error'], file=sys.stderr)
        return setup_code
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda signum, frame: signals.append(signum))
    try:
        # Do not start an unmonitored process if records cannot be opened.
        with (run_dir / 'memory.jsonl').open('x', buffering=1) as log:
            first = sample()
            initial_oom = first['oom_kill']
            reasons = stop_reasons(first, initial_oom, {})
            if latch.exists():
                reasons.append('existing_no_autorestart_latch')
            warnings = warning_reasons(first)
            record = {'utc': utc(), **first, 'stage': 'preflight', 'manager_pid': None,
                      'owned_pids': [], 'max_rss_mib': 0, 'tree_rss_mib': 0,
                      'stop_reasons': reasons, 'warnings': warnings}
            log.write(json.dumps(record) + '\n')
            if warnings:
                result['psi_warning_samples'] += 1
                result['first_psi_warning'] = record
                result['last_psi_warning'] = record
            if reasons:
                result.update(status='resource_stopped', stop_reasons=reasons, memory=first)
                if 'existing_no_autorestart_latch' not in reasons:
                    write_json(latch, result)
                return 90
            child = subprocess.Popen(command, start_new_session=True)
            result['manager_pid'] = child.pid
            write_json(run_dir / 'guard.json', {'pid': os.getpid(), **identity(os.getpid())})
            while True:
                processes = snapshot()
                selected = select(processes, child.pid, scratch)
                owned.update({pid: processes[pid] for pid in selected})
                live = {pid: processes[pid] for pid in selected}
                # Localhost SSH children do not inherit the manager's niceness/affinity.
                # Apply limits only after this guard has authenticated their ownership.
                allowed = os.sched_getaffinity(0)
                for pid in live:
                    try:
                        if matching(pid, live[pid]):
                            os.setpriority(os.PRIO_PROCESS, pid, max(10, os.getpriority(os.PRIO_PROCESS, pid)))
                            current = os.sched_getaffinity(pid)
                            if len(current) > len(allowed):
                                os.sched_setaffinity(pid, allowed)
                    except ProcessLookupError:
                        pass
                memory = sample()
                reasons = stop_reasons(memory, initial_oom, live)
                if latch.exists():
                    reasons.append("shared_build_stop_latch")
                warnings = warning_reasons(memory)
                record = {'utc': utc(), **memory, 'manager_pid': child.pid,
                          'owned_pids': sorted(live),
                          'max_rss_mib': max((info['rss_mib'] for info in live.values()), default=0),
                          'tree_rss_mib': sum(info['rss_mib'] for info in live.values()),
                          'stop_reasons': reasons, 'warnings': warnings}
                log.write(json.dumps(record) + '\n')
                if warnings:
                    result['psi_warning_samples'] += 1
                    result.setdefault('first_psi_warning', record)
                    result['last_psi_warning'] = record
                if reasons:
                    result.update(status='resource_stopped', stop_reasons=reasons, memory=memory)
                    try:
                        write_json(latch, {**result, 'stopped_at': utc()})
                        write_json(run_dir / 'STOPPED_NO_AUTORESTART.json', result)
                    finally:
                        result['remaining_pids'] = cleanup(child, scratch, owned)
                    return 90
                if signals:
                    result.update(status='interrupted', signal=signals[0])
                    result['remaining_pids'] = cleanup(child, scratch, owned)
                    return 128 + signals[0]
                status = child.poll()
                if status is not None:
                    result['remaining_pids'] = cleanup(child, scratch, owned)
                    result.update(status='command_completed' if status == 0 else 'command_failed',
                                  command_exit_code=status)
                    return status if status >= 0 else 128 - status
                time.sleep(1)
    except BaseException as exc:
        result.update(status='guard_error', error=repr(exc))
        try:
            write_json(latch, {**result, 'stopped_at': utc()})
        finally:
            if child is not None:
                result['remaining_pids'] = cleanup(child, scratch, owned)
        raise
    finally:
        result.update(finished_at=utc(), host_elapsed_seconds=time.monotonic() - start)
        write_json(run_dir / 'guard-result.json', result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--latch', type=Path, required=True)
    parser.add_argument('--jobs', type=int, choices=range(1, 5), default=4)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command and args.command[0] == '--' else args.command
    if not command:
        parser.error('A command is required')
    args.run_dir.mkdir(parents=True, exist_ok=True)
    args.latch.parent.mkdir(parents=True, exist_ok=True)
    args.scratch.mkdir(parents=True, exist_ok=True)
    os.environ['ILLIXR_BUILD_GUARD_TAG'] = 'xrsight-' + uuid.uuid4().hex
    os.environ['MAKEFLAGS'] = '-j' + str(args.jobs)
    os.environ['OMP_NUM_THREADS'] = str(args.jobs)
    os.environ['JDK_JAVA_OPTIONS'] = '-Xss64M -XX:ActiveProcessorCount=' + str(args.jobs)
    os.environ['JAVA_TOOL_OPTIONS'] = '-Xmx16G'
    cpus = sorted(os.sched_getaffinity(0))[-args.jobs:]
    os.environ['ILLIXR_BUILD_CPUS'] = ','.join(map(str, cpus))
    os.sched_setaffinity(0, cpus)
    os.nice(max(0, 10 - os.getpriority(os.PRIO_PROCESS, 0)))
    return supervise(command, str(args.scratch.resolve()), args.run_dir, args.latch)


if __name__ == '__main__':
    sys.exit(main())
