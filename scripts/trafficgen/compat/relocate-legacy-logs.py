#!/usr/bin/env python3
"""Copy the preserved oracle binary, relocating only hard-coded log literals.

The old bridge clears a fixed supplemental snapshot directory on startup. Never
run it against the user's old artifacts. This alters NUL-terminated log strings
only, verifies every other byte is unchanged, and records exact changed ranges.
"""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import shutil

OLD = '/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 << 20), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('destination', type=Path)
    p.add_argument('--log-root', type=Path, required=True)
    p.add_argument('--expected-sha256', required=True)
    args = p.parse_args()
    before = sha(args.source)
    if before != args.expected_sha256:
        p.error('source simulator hash does not match recorded oracle')
    new = str(args.log_root.resolve())
    if len(new.encode()) > len(OLD):
        p.error(f'log root must fit in {len(OLD)} bytes')
    if args.destination.exists() or args.log_root.exists():
        p.error('destination and log root must both be fresh')
    shutil.copy2(args.source, args.destination)
    changes = []
    with args.destination.open('r+b') as stream, mmap.mmap(stream.fileno(), 0) as mapped:
        for suffix in ('/bridge_socket_round_logs', ''):
            old_string, new_string = (OLD + suffix).encode(), (new + suffix).encode()
            start = 0
            count = 0
            while True:
                offset = mapped.find(old_string + b'\0', start)
                if offset < 0:
                    break
                replacement = new_string + b'\0' * (len(old_string) - len(new_string))
                mapped[offset:offset + len(old_string)] = replacement
                changes.append({'offset': offset, 'length': len(old_string),
                                'original': old_string.decode(), 'replacement': new_string.decode()})
                start, count = offset + len(old_string), count + 1
            if count == 0:
                raise RuntimeError(f'expected literal absent: {old_string!r}')
        mapped.flush()
    # Verify not a single byte outside the declared string ranges changed.
    allowed = {i for change in changes for i in range(change['offset'], change['offset'] + change['length'])}
    differences = 0
    with args.source.open('rb') as original, args.destination.open('rb') as updated:
        offset = 0
        while (a := original.read(1 << 20)):
            b = updated.read(len(a))
            if len(a) != len(b):
                raise RuntimeError('binary size changed')
            if a != b:
                for i, (x, y) in enumerate(zip(a, b)):
                    if x != y:
                        if offset + i not in allowed:
                            raise RuntimeError('unexpected byte change')
                        differences += 1
            offset += len(a)
        if updated.read(1):
            raise RuntimeError('binary size changed')
    args.log_root.mkdir()
    record = {'source': str(args.source.resolve()), 'destination': str(args.destination.resolve()),
              'original_sha256': before, 'relocated_sha256': sha(args.destination),
              'changes': changes, 'changed_bytes': differences,
              'all_other_bytes_identical': True, 'runtime_code_changed': False,
              'log_root': new}
    args.destination.with_suffix('.relocation.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
