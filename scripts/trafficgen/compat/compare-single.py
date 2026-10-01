#!/usr/bin/env python3
"""Exact single-launch differential check of exported scheduler/bridge snapshots.

Opaque bundle IDs are compared by their member request sets and introduction
rounds. Candidate completions are projected to historical ordinals, preserving
the exact old wire order even where historical completion generation is absent.
One explicit candidate target-epoch offset applies to EVERY cycle. No fitted
per-round or per-request offset is permitted. Requires observed lane CSVs by
default; --scheduler-only is useful for a model test but is not RTL acceptance.
"""
import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path
import statistics

MAX_CYCLE = (1 << 64) - 1


def rows(path):
    with path.open(newline='') as stream:
        return [{k: int(v) for k, v in row.items()} for row in csv.DictReader(stream)]


def load(root, offset, scheduler_only):
    data = {name: rows(root / (name + '.csv')) for name in
            ('scheduled', 'issued', 'rounds', 'completed', 'blocked', 'reservations')}
    launches = {r['launch_id'] for r in data['scheduled']} | {r['launch_id'] for r in data['issued']}
    if len(launches) != 1:
        raise ValueError(f'{root}: expected exactly one nonempty launch, got {sorted(launches)}')
    for name, records in data.items():
        for r in records:
            r.pop('launch_id', None)
            r.pop('registry_id', None)
            for field in ('cycle', 'min_issue_cycle', 'end_cycle'):
                if field in r and r[field] != MAX_CYCLE:
                    r[field] -= offset
    for name in ('scheduled', 'issued'):
        ids = [r['request_uid'] for r in data[name]]
        if len(ids) != len(set(ids)):
            raise ValueError(f'{root}: duplicate {name} request UIDs')
    scheduled = {r['request_uid']: r for r in data['scheduled']}
    issued = {r['request_uid']: r for r in data['issued']}
    if set(scheduled) != set(issued):
        raise ValueError(f'{root}: incomplete run; scheduled/issued UID sets differ')
    for uid, r in issued.items():
        if any(r[k] != scheduled[uid][k] for k in ('address', 'is_write')):
            raise ValueError(f'{root}: issued identity mismatch for {uid}')
    # Membership is round-local: historic bundle ordinals can alias across
    # schedulers, but each original replay generation is a distinct cohort.
    groups = defaultdict(list)
    for r in data['scheduled']:
        groups[(r['round'], r['bundle_id'])].append(r['request_uid'])
    members = {key: tuple(sorted(uids)) for key, uids in groups.items()}
    lanes = {}
    for path in sorted(root.glob('round_*_lanes.csv')):
        for r in rows(path):
            if r['request_uid'] in lanes:
                raise ValueError(f'{root}: duplicate lane assignment')
            lanes[r['request_uid']] = (r['lane'], r['member_count'])
    if not scheduler_only and set(lanes) != set(scheduled):
        raise ValueError(f'{root}: missing/extra observed lane assignments')
    normalized = {}
    for name in ('scheduled', 'issued'):
        normalized[name] = []
        for row in data[name]:
            r = dict(row)
            if name == 'scheduled':
                r['members'] = members[(r['round'], r.pop('bundle_id'))]
            normalized[name].append(r)
    normalized.update({name: data[name] for name in ('rounds', 'blocked', 'reservations')})
    normalized['completed'] = data['completed']
    normalized['_cohorts'] = members
    normalized['_issued_rounds'] = {uid: r['round'] for uid, r in issued.items()}
    if not scheduler_only:
        normalized['lanes'] = [{'request_uid': uid, 'lane': lane, 'member_count': count}
                               for uid, (lane, count) in sorted(lanes.items())]
    return normalized


def compare(left, right):
    # Cohort membership is observable in scheduled snapshots and lane logs.
    # Historical completions omit generation, so projecting through an EXACT
    # membership/introduction-round bijection preserves all observable wire
    # behavior without pretending we recovered missing historical information.
    legacy_by_members = {(generation, members): ordinal
                         for (generation, ordinal), members in left['_cohorts'].items()}
    candidate_by_id, projection, errors = {}, {}, []
    for (generation, handle), members in right['_cohorts'].items():
        if handle in candidate_by_id:
            errors.append(f'candidate reuses physical cohort handle {handle}')
        candidate_by_id[handle] = (generation, members)
        ordinal = legacy_by_members.get((generation, members))
        if ordinal is None:
            errors.append(f'candidate cohort {handle} has no exact legacy membership/introduction-round match')
        else:
            projection[handle] = ordinal
    signatures = {(g, m) for (g, _), m in right['_cohorts'].items()}
    if signatures != set(legacy_by_members):
        errors.append('cohort membership/introduction-round mapping is not a bijection')
    retired, projected = set(), []
    for row in right['completed']:
        handle = row['bundle_id']
        group = candidate_by_id.get(handle)
        if group is None or handle in retired:
            errors.append(f'candidate completion of unknown/already completed handle {handle}')
        elif group[0] > row['round'] or any(right['_issued_rounds'][uid] > row['round'] for uid in group[1]):
            errors.append(f'candidate cohort {handle} completes before all members issue')
        retired.add(handle)
        projected.append(dict(row, bundle_id=projection.get(handle)))
    if retired != set(candidate_by_id):
        errors.append('candidate has missing or extra cohort completion reports')
    right = dict(right, completed=projected)
    report = {}
    for name in left:
        if name.startswith('_'):
            continue
        a, b = left[name], right[name]
        mismatch = [{'index': i, 'baseline': x, 'candidate': y}
                    for i, (x, y) in enumerate(zip(a, b)) if x != y]
        report[name] = {'equal': a == b, 'baseline_count': len(a), 'candidate_count': len(b),
                        'changed_common_rows': len(mismatch), 'examples': mismatch[:8]}
        if name in ('scheduled', 'issued'):
            by_uid_a, by_uid_b = ({r['request_uid']: r for r in records} for records in (a, b))
            common = set(by_uid_a) & set(by_uid_b)
            changed = Counter(k for uid in common for k in by_uid_a[uid]
                              if by_uid_a[uid][k] != by_uid_b[uid][k])
            delta = [by_uid_b[uid]['cycle'] - by_uid_a[uid]['cycle'] for uid in common]
            report[name]['per_uid'] = {
                'matched': len(common), 'missing': sorted(set(by_uid_a) - set(by_uid_b)),
                'extra': sorted(set(by_uid_b) - set(by_uid_a)), 'changed_fields': dict(changed),
                'cycle_delta': {'min': min(delta), 'median': statistics.median(delta),
                                'max': max(delta), 'nonzero': sum(d != 0 for d in delta)} if delta else None}
    report['completion_projection'] = {'equal': not errors, 'errors': errors[:20],
                                        'error_count': len(errors), 'mapped_cohorts': len(projection),
                                        'scope': 'exact legacy observable completion (round, order, ordinal)',
                                        'limitation': 'Historical completion generation is unobservable; candidate physical uniqueness/all-member-issue are checked independently.'}
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('baseline', type=Path)
    p.add_argument('candidate', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--candidate-cycle-offset', type=int, default=0)
    p.add_argument('--scheduler-only', action='store_true')
    args = p.parse_args()
    result = {'baseline': str(args.baseline.resolve()), 'candidate': str(args.candidate.resolve()),
              'candidate_cycle_offset': args.candidate_cycle_offset,
              'scope': 'observable-scheduler-compatibility' if args.scheduler_only else 'observable-scheduler-and-RTL-lane-compatibility'}
    try:
        result['checks'] = compare(load(args.baseline, 0, args.scheduler_only),
                                   load(args.candidate, args.candidate_cycle_offset, args.scheduler_only))
        result['result'] = 'PASS' if all(x['equal'] for x in result['checks'].values()) else 'FAIL'
    except (ValueError, OSError, KeyError) as error:
        result.update(result='FAIL', error=str(error))
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    raise SystemExit(result['result'] != 'PASS')


if __name__ == '__main__':
    main()
