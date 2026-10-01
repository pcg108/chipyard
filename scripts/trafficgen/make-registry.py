#!/usr/bin/env python3
"""Select installed datasets and check cache-line disjointness for the RTL tests."""
import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--gpu-model", type=Path,
                        default=Path(__file__).resolve().parents[3] / "gpu_model")
    parser.add_argument("--case", choices=("pair", "four", "reuse"), default="pair")
    args = parser.parse_args()
    source = args.gpu_model.resolve() / "configs/kernel_registry.json"
    registry = {row["registryId"]: row for row in json.loads(source.read_text())["kernels"]}
    ids = [1106, 1003, 1044, 1001] if args.case == "four" else [1106, 1003]
    selected, lines, physical, uids, report = [], {}, {}, {}, {}
    for registry_id in ids:
        entry = registry[registry_id].copy()
        for key in ("tracePath", "dataRoot"):
            entry[key] = str((source.parent / entry[key]).resolve(strict=True))
        selected.append(entry)
        header_lines = []
        with Path(entry["tracePath"]).open() as trace:
            for line in trace:
                if line.startswith("#"):
                    break
                header_lines.append(line)
        header = "".join(header_lines)
        match = re.search(r"^-kernel id\s*=\s*(\d+)\s*$", header, re.M)
        if not match:
            raise ValueError(f"missing kernel id: {entry['tracePath']}")
        folders = list((Path(entry["dataRoot"]) / "l2_trace").glob(f"kernel_{match[1]}_*"))
        if len(folders) != 1:
            raise ValueError(f"expected one L2 trace folder for registry {registry_id}: {folders}")
        addresses, request_uids, requests = set(), set(), 0
        files = sorted(folders[0].rglob("l1_to_l2_requests.txt"))
        if not files:
            raise ValueError(f"missing request traces: {folders[0]}")
        for path in files:
            with path.open() as stream:
                for line in stream:
                    address = re.search(r"\baddr=(0x[\da-fA-F]+)", line)
                    if address:
                        addresses.add(int(address[1], 16))
                        requests += 1
                    uid = re.search(r"\b(?:uid|request_uid)=(\d+)", line)
                    if uid:
                        request_uids.add(int(uid[1]))
        lines[registry_id] = {addr >> 6 for addr in addresses}
        physical[registry_id] = {(addr & 0xffffffff) >> 6 for addr in addresses}
        uids[registry_id] = request_uids
        report[str(registry_id)] = {"recorded_requests": requests, "cache_lines": len(lines[registry_id])}
    pairs = []
    for i, left in enumerate(ids):
        for right in ids[i + 1:]:
            overlap = lines[left] & lines[right]
            remapped_overlap = physical[left] & physical[right]
            if overlap or remapped_overlap:
                raise ValueError(f"datasets {left}/{right} overlap: raw={len(overlap)} remapped={len(remapped_overlap)}")
            pairs.append({"registries": [left, right], "raw_overlap": 0, "remapped_overlap": 0,
                          "shared_recorded_uids": len(uids[left] & uids[right])})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"kernels": selected}, indent=2) + "\n")
    report_path = args.output.with_suffix(".disjoint.json")
    report_path.write_text(json.dumps({"datasets": report, "pairs": pairs,
                                      "physical_address": "0x100000000 + (trace_address & 0xffffffff)"}, indent=2) + "\n")
    print(f"wrote {args.output}; disjointness report: {report_path}")


if __name__ == "__main__":
    main()
