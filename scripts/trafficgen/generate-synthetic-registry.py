#!/usr/bin/env python3
"""Generate four disjoint, concurrently resident protocol-v2 test datasets.

The recorded names, kernel/warp IDs and request UIDs intentionally collide.
Load dependencies keep each one-warp CTA alive through successive launches;
the last instruction is a non-wake store whose completion must retire its CTA.
"""

import argparse
import json
from pathlib import Path


REGISTRY_IDS = (1106, 1003, 1044, 1001)


def generate(root: Path, iterations: int) -> Path:
    if not 1 <= iterations <= 16384:
        raise ValueError("iterations must be between 1 and 16384")
    root.mkdir(parents=True, exist_ok=True)
    entries = []
    for slot, registry_id in enumerate(REGISTRY_IDS):
        data = root / str(registry_id)
        trace_path = data / "traces/kernel-1.traceg"
        assignment_path = data / "warp_assignments/kernel_1_test/shader_0.txt"
        requests_path = data / "l2_trace/kernel_1_test/shader_0/scheduler_0/l1_to_l2_requests.txt"
        for path in (trace_path, assignment_path, requests_path):
            path.parent.mkdir(parents=True, exist_ok=True)
        assignment_path.write_text(
            "CTA 0 (ctaid=0) assigned to shader core 0:\n"
            "  Warp 0 (warp_in_cta=0 dynamic_warp_id=0) -> Scheduler 0\n"
        )
        trace = [
            "-kernel name = test", "-kernel id = 1", "-grid dim = (1,1,1)",
            "-block dim = (32,1,1)", "-shmem = 0", "-nregs = 4",
            "-accelsim tracer version = 3", "#trace format", "#BEGIN_TB",
            "thread block = 0,0,0", "warp = 0", f"insts = {3 * iterations}",
        ]
        requests = []
        base = (slot + 1) * 0x01000000
        for iteration in range(iterations):
            ordinal = 3 * iteration
            trace.extend((
                "0000 ffffffff 1 R1 LDG 0 0",
                "0010 ffffffff 1 R2 IADD3 1 R1 0",
                "0020 ffffffff 0 STG 1 R2 0",
            ))
            for member, (pc, inst_ordinal) in enumerate(((0, ordinal), (0x20, ordinal + 2))):
                address = base + iteration * 256 + member * 128
                requests.append(
                    f"request_uid={100 + 2 * iteration + member} PC=0x{pc:x} dynamic_warp=0 "
                    f"inst_ordinal={inst_ordinal} addr=0x{address:x} subpartition=0 "
                    f"set_index=0 tag=0x{address:x} sector_mask=0x1"
                )
        trace.append("#END_TB")
        trace_path.write_text("\n".join(trace) + "\n")
        requests_path.write_text("\n".join(requests) + "\n")
        entries.append({
            "registryId": registry_id,
            "tracePath": str(trace_path.relative_to(root)),
            "dataRoot": str(data.relative_to(root)),
        })
    manifest = root / "registry.json"
    manifest.write_text(json.dumps({"kernels": entries}, indent=2) + "\n")
    # Match tests/live_fixture.h: four CTAs fit together on the same SM.
    (root / "hardware.config").write_text(
        "unsigned numSMs = 1\nunsigned numSubcorePerSM = 1\n"
        "unsigned maxThreadsPerSM = 128\nunsigned maxCTAPerSM = 4\n"
        "unsigned registerPerSM = 4096\n"
        "std::vector<unsigned> sharedMemSizeOption = {64}\n"
        "double issueLatencyScale = 1\n"
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory for generated traces and registry.json")
    parser.add_argument("--iterations", type=int, default=256, help="load/dependency/store groups per CTA (default: 256)")
    args = parser.parse_args()
    if not 1 <= args.iterations <= 16384:
        parser.error("--iterations must be between 1 and 16384")
    print(generate(args.output.resolve(), args.iterations))


if __name__ == "__main__":
    main()
