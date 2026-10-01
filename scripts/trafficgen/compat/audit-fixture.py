#!/usr/bin/env python3
"""Audit decoded requests against independently generated fixture identities.

Does not prove individual memory responses; the RTL/bridge completion records
and RTL unit tests supply that separate evidence. No timing-fit acceptance.
"""
import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path


def rows(path):
    with path.open(newline='') as stream:
        return [{k: int(v) for k, v in row.items()} for row in csv.DictReader(stream)]


def audit(export, fixture, legacy_registry=None):
    expected = {(r['registry_id'], r['request_uid']): r
                for r in json.loads((fixture / 'expected.json').read_text())}
    scheduled, issued = rows(export / 'scheduled.csv'), rows(export / 'issued.csv')
    launches, scheduled_by_key, issued_by_key = {}, {}, {}
    for r in scheduled:
        registry = legacy_registry if legacy_registry is not None else r['registry_id']
        launch = r['launch_id']
        if launch in launches and launches[launch] != registry:
            raise ValueError('launch changed registry identity')
        launches[launch] = registry
        key = (launch, r['request_uid'])
        if key in scheduled_by_key:
            raise ValueError('duplicate scheduled request')
        scheduled_by_key[key] = r
        original = expected.get((registry, r['request_uid']))
        if original is None or any(r[k] != original[k] for k in
                                   ('address', 'is_write', 'sm_id', 'scheduler_id', 'warp_id')):
            raise ValueError(f'unknown/mismatched scheduled identity: {key}')
    for r in issued:
        key = (r['launch_id'], r['request_uid'])
        if key in issued_by_key:
            raise ValueError('duplicate issued request')
        issued_by_key[key] = r
        original = scheduled_by_key.get(key)
        if original is None or any(r[k] != original[k] for k in ('address', 'is_write')):
            raise ValueError(f'unknown/mismatched issued identity: {key}')
        if r['cycle'] < original['cycle']:
            raise ValueError('request issued before scheduled time')
    if not launches or set(scheduled_by_key) != set(issued_by_key):
        raise ValueError('empty or incomplete scheduled/issued sets')
    for launch, registry in launches.items():
        actual = {uid for l, uid in scheduled_by_key if l == launch}
        want = {uid for reg, uid in expected if reg == registry}
        if actual != want:
            raise ValueError(f'fixture request set differs for launch {launch}')
    groups = defaultdict(list)
    for r in scheduled:
        groups[(r['round'], r['bundle_id'])].append(r)
    fanin = Counter(len({(r['launch_id'], r['sm_id'], r['scheduler_id']) for r in group})
                    for group in groups.values())
    return {'result': 'PASS_REQUEST_IDENTITIES', 'launches': launches,
            'scheduled': len(scheduled), 'issued': len(issued),
            'load_count': sum(not r['is_write'] for r in issued),
            'store_count': sum(r['is_write'] for r in issued),
            'cohorts': len(groups), 'cohort_scheduler_fanin_histogram': dict(fanin),
            'first_issue_cycle': min(r['cycle'] for r in issued),
            'last_issue_cycle': max(r['cycle'] for r in issued),
            'note': 'Identity/issue audit only; not individual response or differential timing validation.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--legacy-registry', type=int)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.export, args.fixture, args.legacy_registry)
    except (ValueError, OSError, KeyError) as error:
        result = {'result': 'FAIL', 'error': str(error)}
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    raise SystemExit(result['result'] == 'FAIL')


if __name__ == '__main__':
    main()
