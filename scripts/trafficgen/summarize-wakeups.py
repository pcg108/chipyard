#!/usr/bin/env python3
"""Summarize physical round-complete boundaries and actual scheduler exchanges.

Scheduling exit code 0 combines deadline, dependency wake, drain and completion
capacity exits. Reaching a deadline is an observation, NOT causal attribution.
Idle submission doorbells are not round-complete boundaries and are excluded.
"""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import statistics


def rows(path):
    with path.open(newline='') as stream:
        return [{k: int(v) for k, v in row.items()} for row in csv.DictReader(stream)]


def summarize(root):
    boundaries = rows(root / 'boundaries.csv')
    controls = rows(root / 'bridge/controls.csv')
    manifests = []
    for path in sorted((root / 'bridge').glob('round_*/manifest.txt')):
        values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
        manifests.append({k: int(v) for k, v in values.items() if k != 'files'})
    if not boundaries or not manifests:
        raise ValueError('missing boundary or round records')
    if len(controls) != len(manifests):
        raise ValueError('control and completed round totals differ (unfinished run?)')
    if [b['boundary'] for b in boundaries] != list(range(1, len(boundaries) + 1)):
        raise ValueError('missing/duplicate hardware boundary')
    by_round = {}
    for b in boundaries:
        if b['end_cycle'] < b['start_cycle'] or b['exit_reason'] not in range(4):
            raise ValueError('invalid boundary cycle/reason')
        by_round.setdefault(b['round'], []).append(b)
    for index, (control, manifest) in enumerate(zip(controls, manifests), 1):
        group = by_round[index]
        if manifest['round'] != index or group[0]['start_cycle'] != control['cycle']:
            raise ValueError('round start does not match control')
        if sum(b['scheduler_exchange'] for b in group) != 1 or not group[-1]['scheduler_exchange']:
            raise ValueError('round does not end with exactly one scheduler exchange')
        for left, right in zip(group, group[1:]):
            if left['end_cycle'] != right['start_cycle']:
                raise ValueError('discontinuous internal refill')
        for field, reference in [('issued_requests', 'issued_accesses_count'),
                                 ('completed_bundles', 'completed_bundle_ids_count')]:
            if sum(b[field] for b in group) != manifest[reference]:
                raise ValueError(f'boundary/round {field} mismatch')
        if group[-1]['end_cycle'] != manifest['current_cycle_after_issue']:
            raise ValueError('boundary/round end cycle mismatch')
    if len(by_round) != len(manifests):
        raise ValueError('unexpected boundary round')
    intervals = [b['end_cycle'] - b['start_cycle'] for b in boundaries]
    empty = sum(m['issued_accesses_count'] == m['completed_bundle_ids_count'] == 0 for m in manifests)
    deadline = [b for b in boundaries if b['start_cycle'] < b['effective_min_issue_cycle'] < (1 << 64) - 1]
    reached = [b for b in deadline if b['end_cycle'] >= b['effective_min_issue_cycle']]
    issued = sum(b['issued_requests'] for b in boundaries)
    completed = sum(b['completed_bundles'] for b in boundaries)
    labels = {0: 'scheduling_unspecified', 1: 'access_capacity', 2: 'bundle_table_full', 3: 'control'}
    histogram = Counter(b['exit_reason'] for b in boundaries)
    report = {
        'hardware_round_complete_boundaries': len(boundaries),
        'scheduler_exchanges': len(manifests),
        'internal_refill_boundaries': sum(not b['scheduler_exchange'] for b in boundaries),
        'exit_reason_counts': {labels[k]: histogram[k] for k in labels},
        'issued_requests': issued, 'completed_bundles': completed,
        'exchanges_per_completed_bundle': len(manifests) / completed if completed else None,
        'empty_feedback_exchanges': empty,
        'empty_feedback_percent': 100 * empty / len(manifests),
        'exchanges_with_no_new_accesses': sum(m['all_l2_trace_steps_count'] == 0 for m in manifests),
        'boundaries_with_future_finite_deadline': len(deadline),
        'boundaries_reaching_future_finite_deadline': len(reached),
        'empty_feedback_boundaries_reaching_future_finite_deadline': sum(
            b['issued_requests'] == b['completed_bundles'] == 0 for b in reached),
        'launch_bearing_controls': sum(c['new_launches'] > 0 for c in controls),
        'close_transitions': sum(c['end_of_launches'] and (i == 0 or not controls[i-1]['end_of_launches'])
                                 for i, c in enumerate(controls)),
        'target_cycles_in_memory_rounds': sum(intervals),
        'target_cycles_per_boundary': {'mean': statistics.mean(intervals), 'median': statistics.median(intervals),
                                       'min': min(intervals), 'max': max(intervals)},
        'final_session_cycle': manifests[-1]['current_cycle_after_issue'],
        'notes': [
            'Wakeup means a round-complete RTL-to-host boundary; CPU polling and idle doorbells are excluded.',
            'Scheduler exchanges exclude private access-capacity/bundle-table refills.',
            'Empty feedback means no issued requests and no completed bundles, not proof the round was unnecessary.',
            'Deadline reached is not an exclusive exit cause. Existing scheduling exit code combines several causes.',
            'Cycle intervals exclude idle gaps between rounds. Wall time includes host/logging overhead.']}
    (root / 'wakeups.json').write_text(json.dumps(report, indent=2) + '\n')
    with (root / 'rounds.csv').open('w', newline='') as stream:
        fields = ['round', 'start_cycle', 'end_cycle', 'elapsed_cycles', 'min_issue_cycle',
                  'scheduled_requests', 'issued_requests', 'completed_bundles', 'empty_feedback']
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for control, manifest in zip(controls, manifests):
            writer.writerow(dict(zip(fields, [manifest['round'], control['cycle'], manifest['current_cycle_after_issue'],
                manifest['current_cycle_after_issue'] - control['cycle'], manifest['min_issue_cycle'],
                manifest['all_l2_trace_steps_count'], manifest['issued_accesses_count'],
                manifest['completed_bundle_ids_count'],
                int(manifest['issued_accesses_count'] == manifest['completed_bundle_ids_count'] == 0)])))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.run), indent=2))


if __name__ == '__main__':
    main()
