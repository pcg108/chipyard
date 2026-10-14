#!/usr/bin/env python3
"""Assemble completed Phase-2 evidence; reject missing or unsuccessful runs."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def physical(path):
    # Writes have no recorded LRC service ID. Match them by original identity,
    # never by the driver-assigned physical ID or an assumed issue ordering.
    write_uids = {}
    with (path.parent / 'bridge/lrc_events.csv').open() as stream:
        for row in csv.DictReader(stream):
            if row['event'] == 'physical_issue' and int(row['is_write']):
                write_uids[int(row['physical_id'])] = int(row['request_uid'])
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    result = {(int(r['is_write']), write_uids[int(r['physical_id'])] if int(r['is_write'])
               else int(r['recorded_service_id'])): r for r in rows}
    require(len(result) == len(rows), f'duplicate recorded physical identity: {path}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--phase1', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    evidence = {}
    for name in ('reference-6632-02', 'reference-6668-02', 'metasim-6632-02',
                 'metasim-6668-02', 'tma-fixture-03'):
        path = root / name / 'validation.json'
        result = json.loads(path.read_text())
        require(result['result'] == 'PASS', f'unsuccessful validation: {path}')
        if name.startswith(('reference-', 'metasim-')):
            require(result.get('official_policy_decode_matches_logs') is True,
                    f'complete policy archives were not verified: {name}')
        evidence[name] = result
    ordinary = json.loads((root / 'ordinary-regression-02/comparison.json').read_text())
    require(ordinary['result'] == 'PASS', 'ordinary timing regression failed')
    require(ordinary['candidate_cycle_offset'] == 0, 'ordinary regression has a cycle offset')
    ordinary_run = json.loads((root / 'ordinary-regression-02/run-scale1/provenance.json').read_text())
    require(ordinary_run['simulator_exit'] == ordinary_run['scheduler_exit'] == 0,
            'ordinary regression did not terminate successfully')
    evidence['ordinary-regression-02'] = ordinary
    audit = json.loads((root / 'elaboration-audit.json').read_text())
    require(audit['status'] == 'PASS', 'elaboration audit failed')
    evidence['elaboration'] = audit['status']
    model = json.loads((root / 'source-provenance.json').read_text())
    require(model['commit'] == '7b6abcafe312c21e87393181410eb2f2aea1d938', 'wrong model pin')
    for name, digest in model['files'].items():
        require(sha(root / 'model-src' / name) == digest, f'model snapshot modified: {name}')
    evidence['model_snapshot_unmodified'] = True
    build = json.loads((root / 'metasim-build-05-driver/build-manifest.json').read_text())
    require(build['result'] == 'PASS', 'final simulator build failed')
    binary = root / 'metasim-build-05-driver/VFireSim'
    require(sha(binary) == build['binary_sha256'], 'final simulator binary changed')
    for path, digest in build['source_sha256'].items():
        if '/src/main/' in path:
            require(sha(Path(path)) == digest, f'production source differs from tested binary: {path}')
    evidence['simulator_sha256'] = build['binary_sha256']
    require('100% tests passed, 0 tests failed out of 31' in
            (root / 'model-tests-runtime-fixed.log').read_text(), 'model test suite incomplete')
    require('TrafficGen driver session tests passed:' in (root / 'cpp-tests-07.log').read_text(),
            'final C++ test suite incomplete')
    for log, count in (('rtl-tests-04.log', 55), ('rtl-tests-05.log', 7), ('rtl-tests-06.log', 8)):
        data = (root / log).read_text()
        require(f'Tests: succeeded {count}, failed 0' in data, f'RTL suite failed: {log}')
    # The initial RTL invocation passed the other suites; its three environment
    # failures were rerun successfully in rtl-tests-04 with Verilator on PATH.
    require('Tests: succeeded 73, failed 3' in (root / 'rtl-tests-03.log').read_text(),
            'unexpected initial RTL suite result')
    old = physical(args.phase1 / 'metasim-02/physical-comparison.csv')
    new = physical(root / 'metasim-6668-02/physical-comparison.csv')
    require(old.keys() == new.keys(), 'LRC physical identities changed from Phase 1')
    deltas = {field: [int(new[k][field]) - int(old[k][field]) for k in old]
              for field in ('measured_issue', 'measured_return')}
    evidence['phase1_lrc_comparison'] = {
        'physical_identities': len(old),
        'cycles': {field: {'min_delta': min(values), 'max_delta': max(values),
                           'changed': sum(value != 0 for value in values)}
                   for field, values in deltas.items()}}
    for name in ('reference-6632-02', 'reference-6668-02', 'metasim-6632-02', 'metasim-6668-02'):
        run = root / name
        with (run / 'scheduler/launches.csv').open() as stream:
            require(len(list(csv.DictReader(stream))) == 3, f'launch lifecycle incomplete: {name}')
        rounds = list((run / 'bridge').glob('round_[0-9]*'))
        if not rounds:
            # Software reference writes per-round scheduler snapshots directly.
            rounds = list((run / 'scheduler').glob('round_[0-9]*'))
        evidence[name]['snapshot_rounds'] = len(rounds)
    lines = ['# Phase 2 acceptance: PASS', '',
             'Protocol v7 against unmodified GPU-model commit '
             '`7b6abcafe312c21e87393181410eb2f2aea1d938`, using Boost 1.85. '
             'Both complete metasim workloads terminated successfully and drained all tracked state.', '',
             '`FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig`: '
             '16 replay lanes, eight outstanding backend slots per lane, and existing address remapping. '
             'Issue scale is 1.0 for 6632 and 2.0 for 6668; memory issue stretch is 1.0 and mbarriers are enabled.', '',
             '| Kernel | Execution | Original completions | Physical transactions | Public rounds | Final replay cycle |',
             '|---|---|---:|---:|---:|---:|']
    for kernel in (6632, 6668):
        for mode in ('reference', 'metasim'):
            name = f'{mode}-{kernel}-02'
            r = evidence[name]
            lines.append(f'| {kernel} | {mode} | {r["original_completed"]:,} | '
                         f'{r["physical_total"]:,} | {r["snapshot_rounds"]:,} | {r["final_cycle"]:,} |')
    lines += ['', 'Kernel 6632 contains 491,520 original TMA reads, 192 ordinary reads, '
              'and 12,288 TMA writes. Its physical totals are 490,413 reads and 12,288 writes. '
              'Kernel 6668 retains 98,304 original reads and 32,768 writes, serviced by '
              '69,611 physical reads and 32,768 writes.', '',
              'Both metasim audits check exact identities, addresses, contributor membership, '
              'exactly-once completion, bundle readiness, wake readiness and stop cycles, '
              'complete policy archives decoded with official types, and zero pending state.', '',
              'The ordinary regression exactly matches the historical timing, lanes, reservations, '
              'and boundaries with zero cycle offset. The complete model suite (31 tests), '
              '91 distinct RTL tests, final C++ suite, lane assignment, bundle recovery, '
              'and TMA-without-LRC metasim fixture pass. See `component-validation.json` '
              'for the selected logs and superseded environment/timeout trials.', '',
              '## Timing and storage', '']
    for kernel in (6632, 6668):
        r = evidence[f'metasim-{kernel}-02']
        lines.append(f'- Kernel {kernel}: metasim final cycle {r["final_cycle"]:,}; '
                     f'recorded capture {r["recorded_capture_final_cycle"]:,}; '
                     f'difference {r["final_cycle_difference"]:+,} cycles. '
                     'The RTL memory system supplies the measured latency.')
    cycle_comparison = evidence['phase1_lrc_comparison']['cycles']
    lines.append(f'- Phase 1 comparison: {cycle_comparison["measured_issue"]["changed"]:,} '
                 f'changed physical issue cycles and {cycle_comparison["measured_return"]["changed"]:,} '
                 'changed return cycles across all 102,379 kernel-6668 physical identities.')
    lines += ['- Four new packed TMA stores total 2,046,976 raw bytes (1.952 MiB). '
              'Existing LRC buffers and the 435-bit request format are unchanged. '
              'FPGA packing and synthesis are outside this validation.', '',
              '| Metasim kernel | TMA groups HWM / 4096 | Edges HWM / 262144 | Deferred wakes HWM / 4096 |',
              '|---|---:|---:|---:|']
    for kernel in (6632, 6668):
        s = evidence[f'metasim-{kernel}-02']['final_driver_state']
        lines.append(f'| {kernel} | {s["tma_group_high_water"]:,} | '
                     f'{s["tma_edge_high_water"]:,} | {s["tma_timer_high_water"]:,} |')
    lines += ['', '## Evidence', '',
              f'- Final simulator: `metasim-build-05-driver/VFireSim`, SHA-256 `{build["binary_sha256"]}`.',
              '- Fresh RTL build: `metasim-build-03`; final driver relink uses its immutable RTL objects. '
              'Build manifests preserve source hashes, commands, and before/after object hashes.',
              '- `acceptance.json` contains the combined results; `evidence-sha256.json` indexes evidence hashes.',
              '- Each final run directory contains `provenance.json`, configuration and registry, '
              'dataset hashes, event logs, complete policy archives, decoded `policies.jsonl`, '
              'boundary/wake logs, validation results, physical comparison CSV, PNG/PDF plots, '
              'and the GPU model\'s own ground-truth/model plots.',
              '- `final-trafficgen-source.tar.gz` and its hash inventory preserve the implementation and validation tools.',
              '- Interface, storage inventory, and worked completion example: '
              '[TMA.md](/home/prashanth/chipyard/scripts/trafficgen/TMA.md).',
              '- Earlier runs and Phase 1 evidence are preserved; final acceptance uses the `-02` full runs.', '']
    lines += ['## Plots', '']
    for kernel in (6632, 6668):
        for mode, label in (('reference', 'software model'), ('metasim', 'RTL metasim')):
            run = root / f'{mode}-{kernel}-02'
            plot = run / 'physical-traffic-comparison.png'
            require(plot.is_file(), f'missing validated physical-traffic plot: {plot}')
            require(plot.with_suffix('.pdf').is_file(), f'missing plot PDF: {plot}')
            lines.append(f'- [{kernel}: {label} versus recorded capture]({plot})')
            native_plots = sorted((run / 'output').glob('*.png'))
            require(native_plots, f'missing native GPU-model plot: {run}')
            for native in native_plots:
                lines.append(f'- [{kernel}: native GPU-model ground-truth overlay, {label}]({native})')
    lines.append('')
    (root / 'acceptance.json').write_text(json.dumps({'result': 'PASS', 'evidence': evidence}, indent=2) + '\n')
    (root / 'RESULTS.md').write_text('\n'.join(lines))
    paths = [p for p in root.rglob('*') if p.is_file() and p.name in
             ('validation.json', 'provenance.json', 'build-manifest.json', 'hardware.config',
              'registry.json', 'commands.json', 'commands.sh', 'source-provenance.json',
              'comparison.json', 'acceptance.json', 'elaboration-audit.json', 'RESULTS.md')]
    paths += sorted(root.glob('*.log'))
    paths += [p for p in (root/'component-validation.json', root/'final-trafficgen-source.tar.gz',
                          root/'final-trafficgen-source-sha256.json', root/'policy-decoder-build-command.json',
                          root/'policy-decoder-build-hashes.json', root/'replay-progress-02.jsonl') if p.is_file()]
    (root / 'evidence-sha256.json').write_text(json.dumps(
        {str(p.relative_to(root)): sha(p) for p in sorted(set(paths))}, indent=2) + '\n')
    print(json.dumps({'result': 'PASS', 'acceptance': str(root / 'acceptance.json'),
                      'phase1_lrc_comparison': evidence['phase1_lrc_comparison']}, indent=2))


if __name__ == '__main__':
    main()
