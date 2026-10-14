#!/usr/bin/env python3
"""Independently validate kernel-6632/6668 original and physical event ledgers."""
import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path
import re
import statistics


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def fields(text):
    return dict(re.findall(r'(\w+)=([^\s]+)', text))


def number(value):
    return int(value, 0) if value.startswith('0x') else int(value)


def read_capture(dataset, kernel):
    directories = list((dataset / 'l2_trace').glob(f'kernel_{kernel}_*'))
    require(len(directories) == 1, 'expected exactly one kernel 6668 capture')
    sources, services, returns = {}, {}, {}
    for name, write, time_key in [('l1_bypass_to_l2.txt', 0, 'source_to_l2_cycle'),
                                   ('l1_to_l2_requests.txt', 1, 'l1_to_l2_cycle'),
                                   ('tma_to_l2_requests.txt', None, 'source_to_l2_cycle')]:
        for path in sorted(directories[0].glob('shader_*/scheduler_*/'+name)):
            for line in path.read_text().splitlines():
                if not line.strip():
                    continue
                f = fields(line)
                uid = number(f['request_uid'])
                require(uid not in sources, f'duplicate capture request {uid}')
                sources[uid] = {'address': number(f['addr']), 'is_write': int(f['type']=='WRITE'), 'source': f.get('source','ordinary'),
                                'cycle': number(f[time_key])}
    for path in sorted(directories[0].glob('shader_*/scheduler_*/lrc_to_l2_requests.txt')):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            header, members = line.split('contributors=', 1)
            f = fields(header)
            uid = number(f['service_uid'])
            require(uid not in services, f'duplicate capture service {uid}')
            members = json.loads(members)
            require(len(members) == int(f['contributor_count']), 'capture contributor count mismatch')
            services[uid] = {'address': number(f['addr']), 'cycle': number(f['lrc_to_l2_cycle']),
                             'members': {int(m['request_uid']) for m in members}}
    for path in sorted(directories[0].glob('shader_*/scheduler_*/l2_to_icnt_timing.txt')):
        for line in path.read_text().splitlines():
            if line.strip():
                f = fields(line)
                returns[number(f['request_uid'])] = number(f['end_cycle'])
    return sources, services, returns


def unique(rows, key, name):
    result = {}
    for row in rows:
        identity = key(row)
        require(identity not in result, f'duplicate {name}: {identity}')
        result[identity] = row
    return result


def validate(root, dataset, kernel, require_complete_policies=False):
    captured, captured_services, captured_returns = read_capture(dataset, kernel)
    total = 504000 if kernel==6632 else 131072
    capture_cycle = 363659 if kernel==6632 else 53034
    provenance = json.loads((root / 'provenance.json').read_text())
    require(provenance['mode'] in ('reference', 'metasim'), 'unknown validation mode')
    metasim = provenance['mode'] == 'metasim'
    for field in ('client_exit_code', 'scheduler_exit_code'):
        if metasim or field in provenance:
            require(provenance.get(field) == 0, f'missing or unsuccessful process termination: {field}')
    with (root / 'bridge/lrc_events.csv').open() as log:
        reader = csv.DictReader(log)
        metadata = ('launch_id', 'request_uid', 'physical_id', 'recorded_service_id',
                    'bundle_id', 'address', 'is_write', 'action', 'source_ready_cycle')
        require(set(('event', 'round', 'cycle') + metadata) <= set(reader.fieldnames or ()),
                'event log is missing required fields, including source_ready_cycle')
        rows = [{key: value if key in ('event', 'action') else number(value)
                 for key, value in row.items()} for row in reader]
    def same_metadata(row, expected, description):
        require(all(row[k] == expected[k] for k in metadata), f'{description} metadata mismatch')
    events = defaultdict(list)
    for row in rows:
        require(row['action'] in ('Issue', 'Attach', 'Reuse'), f'unknown LRC action {row["action"]}')
        require(row['is_write'] in (0, 1) and row['round'] > 0 and row['cycle'] >= 0,
                'invalid event boolean, round, or cycle')
        events[row['event']].append(row)
    allowed = {'scheduled', 'issued', 'source_ready', 'completed', 'reused', 'physical_issue',
               'physical_return', 'member', 'bundle_complete'}
    require(set(events) <= allowed, f'unknown event types: {set(events) - allowed}')
    identity = lambda r: (r['launch_id'], r['request_uid'])
    original = {name: unique(events[name], identity, name)
                for name in ('scheduled', 'issued', 'source_ready', 'completed', 'reused')}
    scheduled = original['scheduled']
    require(len(scheduled) == total, f'original count {len(scheduled)} != {total}')
    require(len({r['launch_id'] for r in scheduled.values()}) == 1, 'expected one launch')
    require({r['request_uid'] for r in scheduled.values()} == set(captured), 'original identities differ from capture')
    for name in ('issued', 'source_ready', 'completed'):
        require(set(original[name]) == set(scheduled), f'{name} does not cover each original exactly once')
    physical = {name: unique(events[name], lambda r: r['physical_id'], name)
                for name in ('physical_issue', 'physical_return')}
    issues, returns = physical['physical_issue'], physical['physical_return']
    require(set(issues) == set(returns), 'physical issue/return identity mismatch')
    counts = {'original_reads': sum(not r['is_write'] for r in scheduled.values()),
              'original_writes': sum(r['is_write'] for r in scheduled.values()),
              'original_completed': len(original['completed']),
              'physical_reads': sum(not r['is_write'] for r in issues.values()),
              'physical_writes': sum(r['is_write'] for r in issues.values()),
              'physical_total': len(issues)}
    expected = (dict(original_reads=491712, original_writes=12288, original_completed=504000,
                    physical_reads=490413, physical_writes=12288, physical_total=502701) if kernel==6632 else
                dict(original_reads=98304, original_writes=32768, original_completed=131072,
                    physical_reads=69611, physical_writes=32768, physical_total=102379))
    if kernel==6632:
        require(sum(r['source']=='tma_load' for r in captured.values())==491520,'TMA load capture count differs')
        require(sum(r['source']=='tma_store' for r in captured.values())==12288,'TMA store capture count differs')
    require(counts == expected, f'event totals differ: {counts}; expected {expected}')
    membership = defaultdict(set)
    for row in events['member']:
        pid, key = row['physical_id'], identity(row)
        require(pid in returns and key in scheduled, f'unknown physical membership {pid}, {key}')
        require(key not in membership[pid], f'duplicate physical membership {pid}, {key}')
        require(row['cycle'] == returns[pid]['cycle'], 'member timestamp differs from physical return')
        same_metadata(row, scheduled[key], f'physical member {pid}, {key}')
        require(row['round'] >= scheduled[key]['round'], f'membership precedes registration {pid}, {key}')
        membership[pid].add(key)
    expected_members = defaultdict(set)
    recorded_members = defaultdict(set)
    bundle_sources = defaultdict(list)
    expected_reused = set()
    for key, request in scheduled.items():
        uid, pid, admission = request['request_uid'], request['physical_id'], request['cycle']
        capture = captured[uid]
        require(all(request[k] == capture[k] for k in ('address', 'is_write')), f'capture metadata mismatch {key}')
        require(pid in issues, f'original refers to unissued physical service {pid}')
        require(all(request[k] == issues[pid][k]
                    for k in ('address', 'is_write', 'recorded_service_id', 'launch_id')),
                f'original/physical metadata mismatch {key}')
        for name in ('issued', 'source_ready', 'completed'):
            same_metadata(original[name][key], request, f'{name} original {key}')
            require(original[name][key]['round'] >= request['round'], f'{name} precedes registration {key}')
        require(original['issued'][key]['cycle'] == admission, f'original admission changed {key}')
        require(original['source_ready'][key]['cycle'] == request['source_ready_cycle'], f'source-ready cycle changed {key}')
        require(original['source_ready'][key]['cycle'] <= admission, f'source ready after admission {key}')
        completion = original['completed'][key]['cycle']
        require(completion == max(admission, returns[pid]['cycle']), f'wrong original completion cycle {key}')
        if request['action'] == 'Reuse':
            expected_reused.add(key)
            require(request['bundle_id'] == 0, f'reuse created a bundle obligation {key}')
            require(returns[pid]['round'] < request['round'], f'reuse before prior-round return {key}')
            require(completion == admission, f'reuse did not complete at admission {key}')
        else:
            require(request['round'] <= returns[pid]['round'], f'late attachment to already returned service {key}')
            expected_members[pid].add(key)
            require(request['bundle_id'] != 0, f'original has no bundle {key}')
            bundle_sources[request['bundle_id']].append(key)
        if not request['is_write']:
            recorded_members[request['recorded_service_id']].add(uid)
    require(set(original['reused']) == expected_reused, 'reuse event identities differ from scheduled reuse')
    for key, row in original['reused'].items():
        same_metadata(row, scheduled[key], f'reused original {key}')
        require(row['round'] >= scheduled[key]['round'], f'reuse precedes registration {key}')
        require(row['cycle'] == scheduled[key]['cycle'], f'reuse timestamp differs from admission {key}')
    require(dict(membership) == dict(expected_members), 'physical return membership differs from complete registered contributors')
    require(set(recorded_members) == set(captured_services), 'recorded physical read service identities differ')
    for service, expected_service in captured_services.items():
        require(recorded_members[service] == expected_service['members'], f'capture contributor membership mismatch {service}')
    read_service_ids = [r['recorded_service_id'] for r in issues.values() if not r['is_write']]
    require(len(set(read_service_ids)) == len(read_service_ids), 'recorded read service issued more than once')
    for pid, issue in issues.items():
        same_metadata(returns[pid], issue, f'physical return {pid}')
        require(returns[pid]['round'] >= issue['round'], f'physical return precedes issue round {pid}')
        require(returns[pid]['cycle'] >= issue['cycle'], f'physical return before issue {pid}')
        initiators = [key for key in expected_members[pid] if scheduled[key]['action'] == 'Issue']
        require(len(initiators) == 1, f'physical service {pid} does not have exactly one Issue original')
        same_metadata(issue, scheduled[initiators[0]], f'physical issue {pid}')
        require(issue['round'] >= scheduled[initiators[0]]['round'], f'physical issue precedes registration {pid}')
        require(issue['cycle'] >= scheduled[initiators[0]]['cycle'], f'physical service issued before its admission {pid}')
    bundles = unique(events['bundle_complete'], lambda r: r['bundle_id'], 'bundle completion')
    require(set(bundles) == set(bundle_sources), 'public bundles did not all complete exactly once')
    for bundle, sources in bundle_sources.items():
        expected_bundle = dict(scheduled[sources[0]])
        if metasim:
            expected_bundle.update(request_uid=0, physical_id=0)
        same_metadata(bundles[bundle], expected_bundle, f'bundle completion {bundle}')
        ready = max(original['completed'][key]['cycle'] for key in sources)
        require(bundles[bundle]['cycle'] == ready if metasim else bundles[bundle]['cycle'] >= ready,
                f'wrong bundle completion cycle {bundle}')
    with (root / 'scheduler/launches.csv').open() as log:
        lifecycle = list(csv.DictReader(log))
    require(Counter(r['event'] for r in lifecycle) == Counter(accepted=1, dispatched=1, completed=1),
            f'unsuccessful launch lifecycle {lifecycle}')
    launch = next(iter(scheduled))[0]
    require(all(int(r['launch_id']) == launch and int(r['registry_id']) == kernel for r in lifecycle),
            'launch lifecycle identity differs from the replayed registry and launch')
    require([r['event'] for r in lifecycle] == ['accepted', 'dispatched', 'completed'] and
            [int(r['cycle']) for r in lifecycle] == sorted(int(r['cycle']) for r in lifecycle),
            'launch lifecycle is out of order')
    completed_cycle = int(next(r['cycle'] for r in lifecycle if r['event'] == 'completed'))
    require(completed_cycle >= max(r['cycle'] for r in original['completed'].values()), 'launch completed prematurely')
    require(completed_cycle >= max(r['cycle'] for r in bundles.values()), 'launch completed before public bundles')
    terminal = (r'^\[target\] PASS TrafficGen test=1 final_cycle=(\d+)\r?$' if metasim else
                r'^PASS LRC reference rounds=\d+ final_cycle=(\d+)\r?$')
    successes = re.findall(terminal, (root / 'client.log').read_text(), re.MULTILINE)
    require(len(successes) == 1, 'missing or duplicate successful terminal line')
    # UART is printed after the host publishes terminal status. Its target
    # counter may include the final transport edge beyond the public fence.
    require(int(successes[0]) >= completed_cycle if metasim else int(successes[0]) == completed_cycle,
            'successful terminal cycle precedes or disagrees with launch completion')
    if metasim:
        submitted = re.findall(r'^\[target\] SUBMIT slot=0 registry=(\d+) launch=(\d+)\r?$',
                               (root / 'client.log').read_text(), re.MULTILINE)
        require(submitted == [(str(kernel), str(launch))], 'workload submission differs from launch lifecycle')
    delays = [issues[r['physical_id']]['cycle'] - r['cycle'] for r in scheduled.values() if r['action'] == 'Issue']
    latencies = [returns[pid]['cycle'] - row['cycle'] for pid, row in issues.items()]
    def stats(values):
        values = sorted(values)
        return {'min': values[0], 'median': statistics.median(values),
                'p95': values[min(len(values) - 1, int(len(values) * .95))], 'max': values[-1]}
    result = {'result': 'PASS', 'kernel': kernel, **counts, 'completed_bundles': len(bundles),
              'actions': dict(Counter(r['action'] for r in scheduled.values())),
              'exact_contributor_membership': True, 'exactly_once_original_and_physical_events': True,
              'no_premature_bundle_completion': True, 'final_cycle': completed_cycle,
              'recorded_capture_final_cycle': capture_cycle, 'final_cycle_difference': completed_cycle - capture_cycle,
              'physical_issue_delay_cycles': stats(delays), 'physical_latency_cycles': stats(latencies)}
    if require_complete_policies:
        policies=[json.loads(line) for line in (root/'policies.jsonl').read_text().splitlines()]
        require([p['round'] for p in policies]==list(range(1,len(policies)+1)),
                'complete policy archive sequence is not contiguous')
        csv_paths=list((root/'bridge').glob('tma_policy_*.csv'))
        require(len(policies)==len(csv_paths), 'missing complete policy archives')
        outstanding_count=0
        for policy in policies:
            r=policy['round']
            require((root/'bridge'/f'tma_policy_{r}.bin').is_file(), 'missing binary policy snapshot')
            with (root/'bridge'/f'tma_outstanding_{r}.csv').open() as stream:
                identities=[(int(x['launch_id']),int(x['request_uid'])) for x in csv.DictReader(stream)]
            require(identities==[tuple(x) for x in policy['outstandingRequests']],
                    'outstanding identities differ from official policy decode')
            require(len(set(identities))==len(identities), 'duplicate outstanding identity')
            for key in identities:
                require(key in scheduled and scheduled[key]['round']<=r,
                        'policy outstanding identity was not registered')
                require(captured[key[1]]['source'] in ('tma_load','tma_store'),
                        'non-TMA request marked outstanding in TMA policy')
            outstanding_count+=len(identities)
            with (root/'bridge'/f'tma_policy_{r}.csv').open() as stream:
                groups=defaultdict(list)
                for row in csv.DictReader(stream): groups[int(row['group'])].append(row)
            require(set(groups)==set(range(len(policy['groups']))), 'policy group slots differ')
            for slot,group in enumerate(policy['groups']):
                rows=groups[slot]
                for field in ('launch','cta','barrier','phase','transfer','conservative','ready_cycle'):
                    require(all(int(row[field])==group[field] for row in rows),
                            f'policy group {field} differs from official decode')
                members=[(int(row['member_launch']),int(row['member_uid'])) for row in rows if row['member_uid']]
                require(members==[tuple(x) for x in group['requests']],
                        'policy group membership differs from official decode')
        result['complete_policy_snapshots']=len(policies)
        result['official_policy_decode_matches_logs']=True
        result['outstanding_identity_observations']=outstanding_count
    if metasim:
        state = json.loads((root / 'bridge/lrc_state.json').read_text())
        for field in ('pending_requests', 'pending_services', 'pending_bundles',
                      'active_service_slots', 'active_bundle_slots', 'hardware_service_count',
                      'hardware_bundle_count', 'hardware_timer_count', 'hardware_report_count',
                      'tma_pending', 'tma_groups', 'tma_edges', 'tma_timers'):
            require(state[field] == 0, f'undrained driver state: {field}={state[field]}')
        result['final_driver_state'] = state
        for field, limit in (('service_high_water', 65536), ('bundle_high_water', 65536),
                             ('hardware_service_high_water', 65536), ('hardware_bundle_high_water', 65536),
                             ('hardware_timer_high_water', 65536), ('hardware_report_high_water', 131072),
                             ('tma_group_high_water', 4096), ('tma_edge_high_water', 262144), ('tma_timer_high_water', 4096)):
            require(0 <= state[field] <= limit, f'high-water mark exceeds capacity: {field}={state[field]}')
        require((root / 'boundaries.csv').is_file(), 'missing boundary diagnostics')
        with (root / 'bridge/lrc_boundaries.csv').open() as source:
            boundaries = list(csv.DictReader(source))
        require(boundaries, 'empty native boundary diagnostics')
        require([int(row['hardware_boundary']) for row in boundaries] == list(range(1, len(boundaries) + 1)),
                'native boundary sequence is not contiguous')
        require([int(row['cycle']) for row in boundaries] == sorted(int(row['cycle']) for row in boundaries),
                'native boundary cycle decreased')
        require(all(0 <= int(row['flags']) <= 127 for row in boundaries), 'unknown native boundary flags')
        result['native_boundary_flag_counts'] = dict(Counter(int(row['flags']) for row in boundaries))
    if metasim:
        wakes=[]
        wakepath=root/'bridge/tma_wakes.csv'
        if wakepath.exists():
            with wakepath.open() as f:
                wakes=[tuple(map(int,row)) for row in csv.reader(f)]
        reported={}
        for round_id,epoch,slot,cycle,kind in wakes:
            require((epoch,slot) not in reported,'duplicate wake group report')
            reported[epoch,slot]=(round_id,cycle,kind)
        with (root/'boundaries.csv').open() as f:
            hardware=list(csv.DictReader(f))
        rounds={}
        for b in hardware:
            r=int(b['round'])
            rounds.setdefault(r,[int(b['start_cycle']),int(b['end_cycle'])])[1]=int(b['end_cycle'])
        expected_reports=set()
        for path in (root/'bridge').glob('tma_policy_*.csv'):
            round_id=int(path.stem.split('_')[-1])
            require(round_id in rounds,'policy has no completed hardware round')
            start,end=rounds[round_id]
            with path.open() as f:
                groups=defaultdict(list)
                for row in csv.DictReader(f): groups[int(row['group'])].append(row)
            for slot,group in groups.items():
                epoch=int(group[0]['epoch'])
                ready=int(group[0]['ready_cycle'])
                for row in group:
                    if row['member_uid']:
                        key=(int(row['member_launch']),int(row['member_uid']))
                        require(key in original['completed'],'policy member never completed')
                        ready=max(ready,original['completed'][key]['cycle'])
                evaluation=max(start,ready)
                identity=(epoch,slot)
                if evaluation<=end:
                    expected_reports.add(identity)
                    require(identity in reported,'missing hardware wake for a ready policy group')
                    actual_round,at,kind=reported[identity]
                    require(actual_round==round_id and at==evaluation,'wake at wrong evaluation cycle or policy round')
                    require(at==end,'TMA wake failed to stop its public round')
                    require(kind==(4 if int(group[0]['conservative']) else 3),'wake report kind mismatch')
                    require(at>=int(group[0]['compiled_floor']),'wake before compiled floor')
                else:
                    require(identity not in reported,'wake before all members and floor were ready')
        require(set(reported)==expected_reports,'wake from unknown policy or premature group')
        result['phase_wakes']=sum(r[4]==3 for r in wakes)
        result['conservative_wakes']=sum(r[4]==4 for r in wakes)
        result['no_premature_or_duplicate_wakes']=True
        result['exact_wake_readiness_and_stop_cycles']=True
    (root / 'validation.json').write_text(json.dumps(result, indent=2) + '\n')
    with (root / 'physical-comparison.csv').open('w') as output:
        writer = csv.writer(output)
        writer.writerow(['physical_id', 'recorded_service_id', 'is_write', 'measured_issue',
                         'measured_return', 'recorded_issue', 'recorded_return'])
        for pid, row in issues.items():
            uid = row['request_uid']
            recorded_issue = captured[uid]['cycle'] if row['is_write'] else captured_services[row['recorded_service_id']]['cycle']
            writer.writerow([pid, row['recorded_service_id'], row['is_write'], row['cycle'],
                             returns[pid]['cycle'], recorded_issue, captured_returns[uid]])
    print(json.dumps(result, indent=2))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--kernel', type=int, choices=(6632,6668), default=6632)
    parser.add_argument('--require-complete-policies', action='store_true')
    args = parser.parse_args()
    try:
        validate(args.run, args.dataset, args.kernel, args.require_complete_policies)
    except Exception as error:
        (args.run / 'validation.json').write_text(json.dumps({'result': 'FAIL', 'error': str(error)}, indent=2) + '\n')
        raise


if __name__ == '__main__':
    main()
