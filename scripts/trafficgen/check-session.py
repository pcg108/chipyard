#!/usr/bin/env python3
"""Fail a metasim run unless launch, request, bundle and idle-session checks pass."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path


def rows(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--case", choices=("pair", "four", "reuse", "synthetic"), required=True)
    args = parser.parse_args()
    log = (args.run / "metasim.log").read_text(errors="replace")
    if (args.run / "uartlog").exists():
        log += (args.run / "uartlog").read_text(errors="replace")
    require("PASS TrafficGen" in log and "[target] ERROR" not in log, "target workload did not pass")
    lifecycle = rows(args.run / "scheduler/launches.csv")
    events = defaultdict(dict)
    registries = {}
    previous_lifecycle_cycle = 0
    for row in lifecycle:
        launch = int(row["launch_id"])
        registry = int(row["registry_id"])
        cycle = int(row["cycle"])
        require(launch > 0, "launch IDs must be nonzero")
        require(row["event"] in ("accepted", "dispatched", "completed"),
                f"unexpected lifecycle event: {row}")
        require(cycle >= previous_lifecycle_cycle, f"backward lifecycle cycle: {row}")
        previous_lifecycle_cycle = cycle
        require(registries.setdefault(launch, registry) == registry,
                f"launch registry changed: {row}")
        require(row["event"] not in events[launch], f"duplicate lifecycle event: {row}")
        events[launch][row["event"]] = cycle
    count = {"pair": 2, "four": 4, "reuse": 3, "synthetic": 4}[args.case]
    require(len(events) == count, f"expected {count} launches, got {len(events)}")
    for launch, event in events.items():
        require(all(name in event for name in ("accepted", "dispatched", "completed")),
                f"unfinished launch lifecycle {launch}")
        require(event["accepted"] <= event["dispatched"] <= event["completed"],
                f"backward launch cycle {launch}")
    ordered = sorted(events)
    expected_registries = ([1106, 1003, 1044, 1001] if args.case in ("four", "synthetic")
                           else [1106, 1003, 1003] if args.case == "reuse" else [1106, 1003])
    require([registries[launch] for launch in ordered] == expected_registries,
            f"wrong registry sequence: {registries}")
    require(events[ordered[1]]["accepted"] < events[ordered[0]]["completed"],
            "second kernel was not accepted while the first was executing")
    if args.case == "reuse":
        require(events[ordered[2]]["accepted"] > max(events[i]["completed"] for i in ordered[:2]),
                "slot-reuse test did not include an idle gap")
    if args.case == "synthetic":
        # Each synthetic kernel has one CTA. Dispatched means that CTA is
        # admitted; intersecting dispatched-to-completed intervals proves that
        # all four were resident before any completed.
        require(max(event["dispatched"] for event in events.values()) <
                min(event["completed"] for event in events.values()),
                "synthetic run did not show four simultaneously resident CTAs")
    issued = rows(args.run / "scheduler/issued_accesses.csv")
    keys = [(int(r["launch_id"]), int(r["request_uid"])) for r in issued]
    require(len(keys) == len(set(keys)), "duplicate issued launch/request key")
    by_launch = defaultdict(list)
    for row in issued:
        launch = int(row["launch_id"])
        require(launch in registries and int(row["registry_id"]) == registries[launch],
                f"issued access has unknown launch or wrong registry: {row}")
        by_launch[launch].append(int(row["cycle"]))
    require(set(by_launch) == set(events), "an expected launch issued no requests")
    for launch, cycles in by_launch.items():
        # Acceptance is logged when the next status is returned, so accesses
        # from that first round can precede its logged acceptance timestamp.
        require(max(cycles) <= events[launch]["completed"],
                f"issued access after launch completion: {launch}")
    metrics = json.loads((args.run / "analysis/metrics.json").read_text())["primary"]
    for key in ("missing_issued", "issued_without_schedule", "duplicate_scheduled_uids",
                "duplicate_issued_uids", "invalid_bundle_completions", "unfinished_bundles", "decreasing_cycles"):
        require(metrics[key] == 0, f"bridge integrity failure {key}={metrics[key]}")
    for key in ("missing_assignments", "duplicate_assignment_uids", "split_bundle_keys", "unstable_member_count_keys"):
        require(metrics["lane_ownership"][key] == 0, f"lane integrity failure {key}")
    require(metrics["issued_rows"] == len(issued), "scheduler and bridge issue totals differ")
    bridge_issued = rows(args.run / "analysis/per_uid.csv")
    bridge_points = {(int(r["launch_id"]), int(r["request_uid"])):
                     (int(r["registry_id"]), int(r["primary_issued"]), int(r["address"]), int(r["is_write"]))
                     for r in bridge_issued}
    require(len(bridge_points) == len(bridge_issued) and set(bridge_points) == set(keys),
            "scheduler and bridge request identity sets differ")
    for row in issued:
        key = int(row["launch_id"]), int(row["request_uid"])
        require(bridge_points.get(key) == (int(row["registry_id"]), int(row["cycle"]),
                                          int(row["address"]), int(row["is_write"])),
                f"scheduler/bridge issued record mismatch: {key}")
    controls = rows(args.run / "bridge/controls.csv")
    require(controls, "missing session control messages")
    previous_control_cycle = 0
    closed = False
    for row in controls:
        cycle = int(row["cycle"])
        require(cycle >= previous_control_cycle, f"backward control cycle: {row}")
        previous_control_cycle = cycle
        require(row["end_of_launches"] in ("0", "1"), f"invalid submission closure: {row}")
        if closed:
            require(row["end_of_launches"] == "1" and int(row["new_launches"]) == 0,
                    f"submissions reopened after close: {row}")
        closed = row["end_of_launches"] == "1"
    require(closed, "session never closed submissions")
    require(sum(int(row["new_launches"]) for row in controls) == count,
            "control launch count differs from completed launches")
    require(any(int(r["new_launches"]) and int(r["outstanding_bundles"]) for r in controls),
            "no new submission observed with earlier outstanding bundles")
    overlap = []
    for i, left in enumerate(ordered):
        for right in ordered[i+1:]:
            overlap.append({"launches": [left, right],
                            "issue_intervals_overlap": max(min(by_launch[left]), min(by_launch[right])) <=
                                                       min(max(by_launch[left]), max(by_launch[right])),
                            "later_issue_before_earlier_completion": min(by_launch[right]) < events[left]["completed"]})
    report = {"case": args.case, "launches": count, "issued_requests": len(keys),
              "completed_bundles": metrics["completed_bundles"], "final_cycle": metrics["final_cycle"],
              "overlap": overlap, "result": "PASS"}
    (args.run / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
