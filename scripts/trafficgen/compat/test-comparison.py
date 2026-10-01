#!/usr/bin/env python3
"""Contract tests: ID renaming/epoch shifts are allowed; timing/lane drift is not."""
import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare-single.py'))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


def write(path, header, records):
    with path.open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(header.split(','))
        writer.writerows(records)


def fixture(root, launch=0, bundle=3, shift=0):
    root.mkdir()
    write(root / 'scheduled.csv', 'round,order,launch_id,registry_id,request_uid,address,is_write,sm_id,scheduler_id,warp_id,bundle_id,wake_relevant,cycle,subpartition,mask',
          [(1, i, launch, 1001, 7 + i, 64 + i * 32, 0, i, 0, 0, bundle, 1, 10 + i + shift, i, 1) for i in range(2)])
    write(root / 'issued.csv', 'round,order,launch_id,request_uid,address,is_write,cycle',
          [(1, i, launch, 7 + i, 64 + i * 32, 0, 12 + i + shift) for i in range(2)])
    write(root / 'rounds.csv', 'round,min_issue_cycle,end_cycle,scheduled,issued,completed',
          [(1, 20 + shift, 25 + shift, 2, 2, 1)])
    write(root / 'completed.csv', 'round,order,bundle_id', [(1, 0, bundle)])
    write(root / 'blocked.csv', 'round,launch_id,sm_id,scheduler_id,warp_id', [(1, launch, 0, 0, 0)])
    write(root / 'reservations.csv', 'round,cycle,subpartition', [(1, 10 + shift, 0)])
    write(root / 'round_000001_lanes.csv', 'request_uid,bundle_generation,bundle_id,lane,member_count',
          [(7, 1, bundle, 2, 2), (8, 1, bundle, 2, 2)])


def edit_rows(path, change):
    with path.open(newline='') as stream:
        reader = csv.DictReader(stream)
        header, records = reader.fieldnames, list(reader)
    change(records)
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=header)
        writer.writeheader()
        writer.writerows(records)


def split_groups(root, second_bundle):
    edit_rows(root / 'scheduled.csv', lambda r: r[1].update(bundle_id=second_bundle))
    edit_rows(root / 'round_000001_lanes.csv', lambda r: (
        r[0].update(member_count=1), r[1].update(bundle_id=second_bundle, member_count=1, lane=3)))
    edit_rows(root / 'completed.csv', lambda r: r.append(dict(r[0], order=1, bundle_id=second_bundle)))
    edit_rows(root / 'rounds.csv', lambda r: r[0].update(completed=2))


class Comparison(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        fixture(self.root / 'old')
        fixture(self.root / 'new', launch=1, bundle=999, shift=30)

    def tearDown(self):
        self.temp.cleanup()

    def checks(self, offset=30):
        return comparison.compare(comparison.load(self.root / 'old', 0, False),
                                  comparison.load(self.root / 'new', offset, False))

    def test_explicit_epoch_and_opaque_id_rename(self):
        self.assertTrue(all(row['equal'] for row in self.checks().values()))

    def test_unaccounted_epoch_is_not_fitted_away(self):
        self.assertFalse(self.checks(0)['issued']['equal'])

    def test_issue_drift_is_detected(self):
        path = self.root / 'new/issued.csv'
        path.write_text(path.read_text().replace(',42\n', ',43\n'))
        self.assertFalse(self.checks()['issued']['equal'])

    def test_lane_change_is_detected(self):
        path = self.root / 'new/round_000001_lanes.csv'
        path.write_text(path.read_text().replace(',2,2\n', ',3,2\n'))
        self.assertFalse(self.checks()['lanes']['equal'])

    def test_incomplete_run_is_rejected(self):
        path = self.root / 'new/issued.csv'
        path.write_text('\n'.join(path.read_text().splitlines()[:2]) + '\n')
        with self.assertRaisesRegex(ValueError, 'incomplete run'):
            self.checks()

    def test_duplicate_uid_is_rejected(self):
        path = self.root / 'new/issued.csv'
        path.write_text(path.read_text() + path.read_text().splitlines()[1] + '\n')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.checks()

    def test_missing_lane_evidence_is_rejected(self):
        (self.root / 'new/round_000001_lanes.csv').unlink()
        with self.assertRaisesRegex(ValueError, 'lane assignments'):
            self.checks()

    def test_projection_does_not_hide_wrong_group_membership(self):
        split_groups(self.root / 'new', 1000)
        result = self.checks()
        self.assertFalse(result['scheduled']['equal'])
        self.assertFalse(result['completion_projection']['equal'])

    def test_projection_rejects_completion_before_issue(self):
        edit_rows(self.root / 'new/issued.csv', lambda r: r[1].update(round=2))
        self.assertFalse(self.checks()['completion_projection']['equal'])

    def test_projection_preserves_exact_completion_order(self):
        split_groups(self.root / 'old', 4)
        split_groups(self.root / 'new', 1000)
        self.assertTrue(all(r['equal'] for r in self.checks().values()))
        edit_rows(self.root / 'new/completed.csv', lambda r: (
            r[0].update(bundle_id=1000), r[1].update(bundle_id=999)))
        result = self.checks()
        self.assertTrue(result['completion_projection']['equal'])
        self.assertFalse(result['completed']['equal'])

    def test_projection_rejects_missing_report(self):
        edit_rows(self.root / 'new/completed.csv', lambda r: r.clear())
        self.assertFalse(self.checks()['completion_projection']['equal'])

    def test_old_ordinal_aliases_across_rounds_remain_observable(self):
        split_groups(self.root / 'old', 3)
        split_groups(self.root / 'new', 1000)
        for name in ('old', 'new'):
            root = self.root / name
            edit_rows(root / 'scheduled.csv', lambda r: r[1].update(round=2, order=0))
            edit_rows(root / 'issued.csv', lambda r: r[1].update(round=2, order=0))
            edit_rows(root / 'completed.csv', lambda r: [x.update(round=2) for x in r])
        result = self.checks()
        self.assertTrue(all(r['equal'] for r in result.values()))
        self.assertEqual(result['completion_projection']['mapped_cohorts'], 2)


if __name__ == '__main__':
    unittest.main()
