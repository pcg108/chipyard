#!/usr/bin/env python3
"""Normalize old and launch-qualified TrafficGen CSVs without merging launches."""
import argparse
import csv
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("files", nargs="+")
parser.add_argument("--stats", action="store_true")
args = parser.parse_args()
rows = []
columns = []
for path in args.files:
    with open(path, newline="") as source:
        reader = csv.DictReader(source)
        for field in reader.fieldnames or []:
            if field not in columns:
                columns.append(field)
        for row in reader:
            row.setdefault("launch_id", "0")
            if "request_uid" not in row:
                raise ValueError(f"missing request_uid column: {path}")
            rows.append(row)
rows.sort(key=lambda r: (int(r["launch_id"]), int(r["request_uid"])))
if args.stats:
    keys = {(r["launch_id"], r["request_uid"]) for r in rows}
    print(len(rows), len(keys), len(rows) - len(keys))
else:
    columns = ["launch_id", "request_uid"] + [c for c in columns if c not in ("launch_id", "request_uid")]
    writer = csv.DictWriter(sys.stdout, columns, lineterminator="\n")
    # No header: comparisons historically consume data rows only.
    writer.writerows(rows)
