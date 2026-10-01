#!/usr/bin/env python3
"""Small, deterministic traces exercising legacy aliases across SMs/schedulers.

Four datasets deliberately reuse kernel/CTA/warp/request identities but have
disjoint recorded AND low-32-bit mapped addresses. Each contains one two-warp
CTA on each of two SMs; its warps run on different schedulers. Timing files also
permit the exact historic standalone gpu_model to read the fixture.
"""
import argparse
import hashlib
import json
from pathlib import Path

REGISTRY_IDS = (1106, 1003, 1044, 1001)


def generate(root, iterations=8, mode='mixed'):
    root.mkdir(parents=True, exist_ok=False)
    entries, expected = [], []
    for dataset, registry_id in enumerate(REGISTRY_IDS):
        data = root / str(registry_id)
        trace_dir = data / 'traces' / 'compat'
        trace_dir.mkdir(parents=True)
        trace_path = trace_dir / 'kernel-1.traceg'
        trace = ['-kernel name = compat_alias', '-kernel id = 1', '-grid dim = (2,1,1)',
                 '-block dim = (64,1,1)', '-shmem = 0', '-nregs = 4',
                 '-accelsim tracer version = 3', '#trace format']
        for sm in range(2):
            assignments = [f'CTA {sm} (ctaid={sm}) assigned to shader core {sm}:']
            trace += ['#BEGIN_TB', f'thread block = {sm},0,0']
            for warp in range(2):
                assignments.append(f'  Warp {warp} (warp_in_cta={warp} dynamic_warp_id={warp}) -> Scheduler {warp}')
                instructions, requests, timings = [], [], []
                for iteration in range(iterations):
                    for operation in (('load', 'store') if mode != 'stores' else ('store',)):
                        ordinal = len(instructions)
                        write = operation == 'store'
                        instructions.append('0020 ffffffff 0 STG 0 0' if write else '0000 ffffffff 1 R1 LDG 0 0')
                        pc = 0x20 if write else 0
                        for member in range(2):
                            uid = 100 + sm * 100000 + warp * 10000 + iteration * 4 + int(write) * 2 + member
                            address = (dataset + 1) * 0x01000000 + sm * 0x100000 + warp * 0x10000 + iteration * 256 + int(write) * 64 + member * 32
                            kind = 'WRITE' if write else 'READ'
                            requests.append(f'request_uid={uid} PC=0x{pc:x} dynamic_warp={warp} inst_ordinal={ordinal} addr=0x{address:x} subpartition={2 * sm + warp} set_index=0 tag=0x{address:x} sector_mask=0x1 l1_to_l2_cycle={10 * iteration + int(write)} type={kind}')
                            timings.append(f'request_uid={uid} elapsed_cycle={20 + sm * 7 + warp * 3 + member}')
                            expected.append({'registry_id': registry_id, 'request_uid': uid, 'address': address,
                                             'is_write': int(write), 'sm_id': sm, 'scheduler_id': warp,
                                             'warp_id': warp, 'inst_ordinal': ordinal})
                        if not write:
                            instructions.append('0010 ffffffff 1 R2 IADD3 1 R1 0')
                # CTA tails differ by warp, so the first completed warp cannot
                # by itself justify CTA retirement or resource reuse.
                if mode == 'compute-tail':
                    instructions += ['0030 ffffffff 1 R3 IADD3 1 R3 0'] * (8 + 8 * warp + 4 * sm)
                trace += [f'warp = {warp}', f'insts = {len(instructions)}', *instructions]
                scheduler = data / 'l2_trace/kernel_1_compat_alias' / f'shader_{sm}' / f'scheduler_{warp}'
                scheduler.mkdir(parents=True)
                (scheduler / 'l1_to_l2_requests.txt').write_text('\n'.join(requests) + '\n')
                (scheduler / 'l2_to_icnt_timing.txt').write_text('\n'.join(timings) + '\n')
            trace += ['#END_TB']
            assignment = data / 'warp_assignments/kernel_1_compat_alias' / f'shader_{sm}.txt'
            assignment.parent.mkdir(parents=True, exist_ok=True)
            assignment.write_text('\n'.join(assignments) + '\n')
        trace_path.write_text('\n'.join(trace) + '\n')
        (trace_dir / 'kernelslist.g').write_text('kernel-1.traceg\n')
        entries.append({'registryId': registry_id, 'tracePath': str(trace_path.relative_to(root)),
                        'dataRoot': str(data.relative_to(root))})
    config = ('unsigned numSMs = 2\nunsigned numSubcorePerSM = 2\n'
              'unsigned maxThreadsPerSM = 256\nunsigned maxCTAPerSM = 4\n'
              'unsigned registerPerSM = 8192\nstd::vector<unsigned> sharedMemSizeOption = {64}\n')
    # Run independent oracles for both scales; never retune a candidate to fit.
    for scale in (1, 1.5):
        (root / f'hardware-scale-{scale}.config').write_text(config + f'double issueLatencyScale = {scale}\n')
    (root / 'registry.json').write_text(json.dumps({'kernels': entries}, indent=2) + '\n')
    (root / 'expected.json').write_text(json.dumps(expected, indent=2) + '\n')
    manifest = {'mode': mode, 'iterations': iterations, 'sm_count': 2, 'schedulers_per_sm': 2,
                'ctas_per_launch': 2, 'warps_per_cta': 2, 'requests_per_launch': len(expected) // 4,
                'registries': list(REGISTRY_IDS), 'addresses_disjoint': True,
                'files_sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in sorted(root.rglob('*')) if p.is_file()}}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--iterations', type=int, default=8)
    parser.add_argument('--mode', choices=('mixed', 'stores', 'compute-tail'), default='mixed')
    args = parser.parse_args()
    if not 1 <= args.iterations <= 256:
        parser.error('iterations must be 1..256')
    print(json.dumps(generate(args.output.resolve(), args.iterations, args.mode), indent=2))


if __name__ == '__main__':
    main()
