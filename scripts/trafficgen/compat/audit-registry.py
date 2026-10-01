#!/usr/bin/env python3
"""Independent request-set audit against the selected real registry datasets."""
import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path


def rows(path):
    with path.open(newline='') as stream:
        return [{k: int(v) for k, v in row.items()} for row in csv.DictReader(stream)]


def expectations(registry, registry_id):
    entry = next(r for r in json.loads(registry.read_text())['kernels'] if r['registryId'] == registry_id)
    data = (registry.parent / entry['dataRoot']).resolve()
    trace = (registry.parent / entry['tracePath']).resolve()
    kernel_id = trace.stem.split('-')[1]
    folders = list((data / 'l2_trace').glob(f'kernel_{kernel_id}_*'))
    if len(folders) != 1:
        raise ValueError('kernel folder missing or ambiguous')
    result = {}
    for path in sorted(folders[0].rglob('l1_to_l2_requests.txt')):
        sm, scheduler = (int(p.split('_')[1]) for p in path.parts[-3:-1])
        with path.open() as stream:
            for line in stream:
                fields = dict(t.split('=', 1) for t in line.split() if '=' in t)
                if not fields:
                    continue
                uid = int(fields['request_uid'], 0)
                if uid in result:
                    raise ValueError('duplicate recorded UID inside registry dataset')
                result[uid] = {'address': int(fields['addr'], 0), 'is_write': int(fields['type'].upper() == 'WRITE'),
                               'sm_id': sm, 'scheduler_id': scheduler, 'warp_id': int(fields['dynamic_warp'], 0)}
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('export', type=Path)
    p.add_argument('--registry', type=Path, required=True)
    p.add_argument('--legacy-registry', type=int)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    try:
        scheduled, issued = rows(args.export / 'scheduled.csv'), rows(args.export / 'issued.csv')
        by_launch, seen, issued_seen = defaultdict(list), {}, set()
        for r in scheduled:
            key = r['launch_id'], r['request_uid']
            if key in seen:
                raise ValueError('duplicate scheduled launch/UID')
            seen[key] = r
            by_launch[r['launch_id']].append(r)
        if not by_launch:
            raise ValueError('empty scheduled access set')
        summaries = {}
        for launch, group in by_launch.items():
            ids = {args.legacy_registry if args.legacy_registry is not None else r['registry_id'] for r in group}
            if len(ids) != 1:
                raise ValueError('launch changed dataset identity')
            registry_id = next(iter(ids))
            expected = expectations(args.registry.resolve(), registry_id)
            if set(expected) != {r['request_uid'] for r in group}:
                raise ValueError(f'incomplete/mismatched request set for launch {launch}')
            for r in group:
                if any(r[k] != v for k, v in expected[r['request_uid']].items()):
                    raise ValueError(f'wrong request fields for launch {launch}, UID {r["request_uid"]}')
            summaries[launch] = {'registry_id': registry_id, 'requests': len(group)}
        for r in issued:
            key = r['launch_id'], r['request_uid']
            if key in issued_seen or key not in seen:
                raise ValueError('duplicate or unknown issued launch/UID')
            issued_seen.add(key)
            if any(r[k] != seen[key][k] for k in ('address', 'is_write')) or r['cycle'] < seen[key]['cycle']:
                raise ValueError('wrong issued identity or issue before scheduled cycle')
        if issued_seen != set(seen):
            raise ValueError('scheduled requests remain unissued')
        cohorts = defaultdict(set)
        for r in scheduled:
            cohorts[(r['round'], r['bundle_id'])].add((r['launch_id'], r['sm_id'], r['scheduler_id']))
        result = {'result': 'PASS_REQUEST_IDENTITIES', 'launches': summaries,
                  'scheduled': len(scheduled), 'issued': len(issued),
                  'loads': sum(not r['is_write'] for r in issued), 'stores': sum(r['is_write'] for r in issued),
                  'cohorts': len(cohorts), 'cohort_scheduler_fanin_histogram': dict(Counter(map(len, cohorts.values()))),
                  'first_issue_cycle': min(r['cycle'] for r in issued), 'last_issue_cycle': max(r['cycle'] for r in issued),
                  'note': 'Request identity/issue audit only; not individual response or differential timing validation.'}
    except (ValueError, OSError, KeyError, StopIteration) as error:
        result = {'result': 'FAIL', 'error': str(error)}
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    raise SystemExit(result['result'] == 'FAIL')


if __name__ == '__main__':
    main()
