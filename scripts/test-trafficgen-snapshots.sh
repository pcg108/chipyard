#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${CXX:-$repo_root/.conda-env/bin/x86_64-conda-linux-gnu-c++}
temporary=$(mktemp -d "${TMPDIR:-/tmp}/trafficgen-snapshots.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
"$compiler" -std=c++20 -O2 -Wall -Wextra -Werror \
  -I"$repo_root/generators/firechip/bridgestubs/src/main/cc" \
  "$repo_root/scripts/trafficgen/test-snapshots.cc" -lboost_serialization \
  -o "$temporary/producer"
"$temporary/producer" "$temporary/fixtures"
for kind in valid legacy unknown duplicate early early_then_valid; do
  TMPDIR="$temporary" CXX="$compiler" "$repo_root/scripts/analyze-trafficgen-snapshots.sh" \
    "$kind" "$temporary/fixtures/$kind" "$temporary/analysis/$kind"
done
# A comparison against itself must also preserve all four launch-qualified rows.
TMPDIR="$temporary" CXX="$compiler" "$repo_root/scripts/analyze-trafficgen-snapshots.sh" \
  valid "$temporary/fixtures/valid" "$temporary/comparison" "same=$temporary/fixtures/valid"

python3 - "$repo_root" "$temporary" <<'PY'
import csv
import io
import json
from pathlib import Path
import subprocess
import sys

repo, root = map(Path, sys.argv[1:])
high = 0x8000000000000042

def metrics(kind):
    return json.loads((root / "analysis" / kind / "metrics.json").read_text())["primary"]

def rows(kind, filename="per_uid.csv"):
    with (root / "analysis" / kind / filename).open(newline="") as source:
        return list(csv.DictReader(source))

valid = metrics("valid")
for key in ("scheduled_rows", "scheduled_unique", "issued_rows", "joined_count"):
    assert valid[key] == 4, (key, valid[key])
for key in ("missing_issued", "issued_without_schedule", "duplicate_scheduled_uids",
            "duplicate_issued_uids", "invalid_bundle_completions", "unfinished_bundles", "decreasing_cycles"):
    assert valid[key] == 0, (key, valid[key])
assert valid["completed_bundles"] == 2 and valid["final_cycle"] == 42
ownership = valid["lane_ownership"]
assert ownership["assignment_rows"] == 4 and ownership["unique_bundle_keys"] == 2
for key in ("missing_assignments", "duplicate_assignment_uids", "split_bundle_keys", "unstable_member_count_keys"):
    assert ownership[key] == 0, (key, ownership[key])
points = {(int(row["launch_id"]), int(row["request_uid"])): row for row in rows("valid")}
assert set(points) == {(launch, uid) for launch in (2, high) for uid in (77, 78)}
for launch, registry, bundle, lane in ((2, 1106, 501, 0), (high, 1044, 502, 1)):
    for uid in (77, 78):
        point = points[launch, uid]
        assert int(point["registry_id"]) == registry
        assert int(point["bundle_id"]) == bundle and int(point["lane"]) == lane
        assert int(point["member_count"]) == 2
assert points[2, 78]["primary_issued"] == "22"  # Issued in the next snapshot round.
assert rows("valid", "lane_load.csv") == [
    {"lane": "0", "access_count": "2", "bundle_count": "1"},
    {"lane": "1", "access_count": "2", "bundle_count": "1"},
]
comparison = json.loads((root / "comparison/metrics.json").read_text())["comparisons"][0]
assert comparison["common_uids"] == 4
assert all(comparison[key] == 0 for key in ("changed_scheduled_cycle", "changed_issued_cycle", "changed_drift"))

old = metrics("legacy")
assert old["scheduled_unique"] == old["joined_count"] == 2 and old["final_cycle"] == 40
assert old["duplicate_scheduled_uids"] == old["duplicate_issued_uids"] == 0
assert old["lane_ownership"]["missing_assignments"] == 0
assert {(int(r["launch_id"]), int(r["registry_id"]), int(r["request_uid"]))
        for r in rows("legacy")} == {(0, 0, 77), (0, 0, 78)}
for kind in ("unknown", "duplicate", "early", "early_then_valid"):
    bad = metrics(kind)
    assert bad["invalid_bundle_completions"] == 1, (kind, bad)
    assert bad["completed_bundles"] == (1 if kind == "early" else 2), (kind, bad)
    assert bad["unfinished_bundles"] == (1 if kind == "early" else 0), (kind, bad)

old_csv = root / "old.csv"
old_csv.write_text("request_uid,address,is_write\n78,12288,0\n77,12416,0\n")
new_csv = root / "new.csv"
new_csv.write_text("registry_id,request_uid,launch_id,address,is_write\n"
                   f"1044,77,{high},8192,1\n1106,77,2,4096,0\n")
normalizer = repo / "scripts/trafficgen/normalize-csv.py"
def normalize(*paths, stats=False):
    command = [sys.executable, str(normalizer), *map(str, paths)]
    if stats:
        command.append("--stats")
    return subprocess.check_output(command, text=True)
assert normalize(old_csv, new_csv, stats=True).strip() == "4 4 0"
assert normalize(new_csv, new_csv, stats=True).strip() == "4 2 2"
normalized = list(csv.reader(io.StringIO(normalize(old_csv, new_csv))))
assert normalized == [
    ["0", "77", "12416", "0", ""], ["0", "78", "12288", "0", ""],
    ["2", "77", "4096", "0", "1106"], [str(high), "77", "8192", "1", "1044"],
], normalized
print("PASS: v2 launch-qualified snapshots, high-bit IDs, v0 archives, seven-field lane CSVs, "
      "bundle completion errors and CSV normalization")
PY
