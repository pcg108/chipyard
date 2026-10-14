#!/usr/bin/env python3
"""Read fresh Golden Gate artifacts; fail closed on missing native v7 ABI/RTL.

Usage: python3 audit-tma-elaboration.py BUILD_DIRECTORY [--output REPORT.json]
Exit 0: complete/pass; 1: mismatch; 2: generation is not complete yet.
This never searches other build directories or modifies generated artifacts.
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("build", type=pathlib.Path)
parser.add_argument("--output", type=pathlib.Path)
parser.add_argument("--command-file", type=pathlib.Path)
parser.add_argument("--build-log", type=pathlib.Path)
parser.add_argument("--rtl-only", action="store_true", help="Audit generated FPGA RTL without requiring a Verilator build")
args = parser.parse_args()
build = args.build.resolve()
repo = pathlib.Path(__file__).resolve().parents[2]
checks = []
missing = []
artifacts = {}


def check(name, passed, evidence=None):
    checks.append({"name": name, "passed": bool(passed), "evidence": evidence})


def read(path):
    if not path.is_file():
        missing.append(str(path))
        return None
    data = path.read_bytes()
    artifacts[str(path)] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    return data.decode()


def module(text, name):
    match = re.search(r"(?ms)^module\s+" + re.escape(name) + r"(?:_\d+)?\b.*?^endmodule\b", text)
    check("SV module " + name, match is not None)
    return match.group() if match else ""


def memories(text):
    pattern = r"\b(?:reg|logic)\s+(?:\[(\d+)\s*:\s*0\]\s+)?(\w+)\s*\[\s*0\s*:\s*(\d+)\s*\]"
    return {name: {"width": int(high or 0) + 1, "depth": int(last) + 1}
            for high, name, last in re.findall(pattern, text)}


def memory_group(name, mems, prefix, depth, width=None, total_entries=None):
    rows = {key: val for key, val in mems.items()
            if key == prefix or key.startswith(prefix + "_")}
    ok = bool(rows) and all(val["depth"] == depth for val in rows.values())
    if width is not None:
        # Bundle memories can be emitted as a packed row or one array per field.
        ok = ok and sum(val["width"] for val in rows.values()) == width
    if total_entries is not None:
        ok = ok and sum(val["depth"] for val in rows.values()) == total_entries
    check(name, ok, rows)
    return rows


physical_names = {
    "trafficgen_physicalIssueValidMask", "trafficgen_physicalIssueAccesses",
    "trafficgen_physicalConsumeMask", "trafficgen_physicalCompletionValidMask",
    "trafficgen_physicalCompletionTokens", "trafficgen_physicalCompletionCycles",
}
annotations = sorted(build.glob("*.anno.json"))
if not annotations:
    missing.append(str(build / "*.anno.json"))
else:
    raw = read(annotations[0])
    bridges = [a for a in json.loads(raw) if a.get("class", "").endswith(".BridgeAnnotation")
               and a.get("target", "").endswith("|TrafficGenBridge")]
    check("one fresh TrafficGen bridge annotation", len(bridges) == 1)
    if len(bridges) == 1:
        pipes = [c for c in bridges[0]["bridgeChannels"] if c["class"].endswith(".PipeBridgeChannel")]
        physical = {c["name"]: c for c in pipes if c["name"].startswith("trafficgen_physical")}
        check("exact six native physical channels", set(physical) == physical_names, sorted(physical))
        check("native channels are target outputs with latency zero",
              all(c["latency"] == 0 and len(c["sources"]) == 1 and not c["sinks"]
                  for c in physical.values()), {n: c["latency"] for n, c in physical.items()})
        legacy = {c["name"]: c["latency"] for c in pipes if c["name"] not in physical_names}
        check("every legacy wire channel retains latency one", bool(legacy) and set(legacy.values()) == {1}, legacy)

target_firs = sorted(build.glob("firechip.chip.FireSim.*.fir"))
if not target_firs:
    missing.append(str(build / "firechip.chip.FireSim.*.fir"))
else:
    text = read(target_firs[0])
    check("target replay requests are 435 bits", bool(re.search(r"accessReadData\s*:\s*UInt<435>\[48\]", text)))
    check("16 native issued records are 321 bits each", "physicalIssueAccesses : UInt<5136>" in text)
    check("16 native return token/cycle fields are 64 bits each",
          "physicalCompletionTokens : UInt<1024>" in text and "physicalCompletionCycles : UInt<1024>" in text)
    check("three replay consume slots per lane", "physicalConsumeMask : UInt<48>" in text)
    del text

header = read(build / "FireSim-generated.const.h")
if header is not None:
    driver = (repo / "generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.h").read_text()
    struct = re.search(r"struct TRAFFICGENBRIDGEMODULE_struct\s*\{(.*?)\};", driver, re.S).group(1)
    expected = [name.strip() for declaration in re.findall(r"uint64_t\s+([^;]+);", struct) for name in declaration.split(",")]
    generated = re.findall(r"offsetof\(TRAFFICGENBRIDGEMODULE_struct,\s*(\w+)\)\s*==\s*(\d+)\s*\*", header)
    generated = [name for name, _ in sorted(generated, key=lambda item: int(item[1]))]
    check("generated MMIO field order exactly matches driver", generated == expected,
          {"expected": expected, "generated": generated})
    check("native MMIO tail includes TMA high water", generated[-1:] == ["tma_timer_high_water"])
    initializer = re.search(r"TRAFFICGENBRIDGEMODULE_struct\s*\{(.*?)\}", header, re.S)
    addresses = dict((name, int(addr)) for name, addr in
                     re.findall(r"\.(\w+)\s*=\s*(\d+)", initializer.group(1) if initializer else ""))
    check("every driver MMIO register has one aligned distinct generated address",
          set(addresses) == set(expected) and len(set(addresses.values())) == len(expected)
          and all(value % 4 == 0 for value in addresses.values()), addresses)

sv = read(build / "FireSim-generated.sv")
if sv is not None:
    monitor = module(sv, "LrcCompletionMonitor")
    heap = module(sv, "LrcTimerHeap")
    bridge = module(sv, "TrafficGenBridgeModule")
    module(sv, "LrcNativeFence")
    module(sv, "LrcReportCapacityGate")
    monitor_mems, heap_mems, bridge_mems = memories(monitor), memories(heap), memories(bridge)
    services = memory_group("65536 generation-tagged service rows", monitor_mems, "services", 65536, 325)
    bundles = memory_group("65536 generation-tagged logical bundle rows", monitor_mems, "bundles", 65536, 212)
    timers = memory_group("65536 admission timer heap entries", heap_mems, "mem", 65536, 144)
    check("each native table retains one packed BRAM word for its RAMStyleHint",
          set(services) == {"services"} and set(bundles) == {"bundles"} and set(timers) == {"mem"})
    memory_group("4096 atomic metadata staging records", bridge_mems, "lrcMetadata", 4096, 512)
    memory_group("131072 report records", bridge_mems, "lrcReports", 131072, 136)
    replay = memory_group("32768 privately refilled physical upload records", bridge_mems,
                          "accessStores", 2048, total_entries=32768)
    check("all physical upload banks preserve 435 bits", bool(replay) and all(r["width"] == 435 for r in replay.values()))
    issued = memory_group("32768 physical issue writeback records", bridge_mems,
                          "issuedAccessWritebackStores", 2048, total_entries=32768)
    check("all issue banks preserve 321 bits", bool(issued) and all(r["width"] == 321 for r in issued.values()))
    for signal in ("lrcParkedToken", "lrcOrdinaryParked", "lrcCheckpointActive", "lrcBoundaryFlags"):
        check("bridge retains " + signal, signal in bridge)
    for name, body in (("bridge", bridge), ("monitor", monitor)):
        count = len(re.findall(r"(?m)^\s*assert\s*\(", body))
        check(name + " has emitted immediate assertions", count > 0, count)
    check("monitor token-commit invariant is present", "LRC token consumed before completion accounting committed" in monitor)
    check("bridge physical issue accounting invariant is present", "LRC physical issue exceeded uploaded descriptors" in bridge)
    tma = module(sv,"TmaWakeMonitor")
    tma_heap = module(sv,"TmaTimerHeap")
    tma_mems = memories(tma)
    memory_group("65536 TMA service adjacency heads",tma_mems,"heads",65536,115)
    memory_group("262144 TMA dependency edges",tma_mems,"edges",262144,31)
    memory_group("4096 TMA wake groups",tma_mems,"groups",4096,98)
    memory_group("4096 TMA deferred heap entries",memories(tma_heap),"mem",4096,76)
    for name in ("heads","edges","groups"):
        ports = set(re.findall(r"wire\s+"+name+r"_(\w+)_mask;",tma))
        check("TMA "+name+" has one packed write port",len(ports)==1,sorted(ports))
    check("held-cycle TMA assertion is emitted","Target cycle advanced during TMA bookkeeping" in tma)
    check("atomic policy publication assertion is emitted","TMA token evaluated before atomic policy publication" in tma)
    check("legacy TMA raw-token boundary is retained","legacyUnaccountedBoundary" in bridge)
    source=(repo/"generators/firechip/goldengateimplementations/src/main/scala/TrafficGenBridge.scala").read_text()
    for name in ("heads","edges","groups","heap.mem"):
        check("TMA requests BLOCK RAM inference for "+name,
              "RAMStyleHint(tmaMonitor."+name+", RAMStyles.BLOCK)" in source)
    # Golden Gate's metasim XDC writer collects target annotations, not these
    # host-bridge hints. Verify inferable packed synchronous RAMs above; physical
    # FPGA mapping is deliberately outside this phase's acceptance criteria.
    del sv

if not args.rtl_only:
    command = read(args.command_file or build / "command.sh")
    if command is not None:
        check("build explicitly enables Verilator assertions", "EXTRA_VERILATOR_FLAGS=--assert" in command, command.strip())
    log = args.build_log or build / "build.log"
    if log.is_file():
        invocations = [line.strip() for line in log.read_text(errors="replace").splitlines()
                       if re.search(r"(?:^|/)verilator\s", line) and "-Mdir" in line]
        if invocations:
            check("actual Verilator invocation enables assertions", all("--assert" in line for line in invocations), invocations)
        else:
            missing.append("Verilator invocation in build.log (build still generating RTL)")

failed = [item["name"] for item in checks if not item["passed"]]
result = {"build": str(build), "rtl_only": args.rtl_only, "status": "FAIL" if failed else "INCOMPLETE" if missing else "PASS",
          "failed": failed, "missing": missing, "checks": checks, "artifacts": artifacts,
          "limits": "Static ABI and elaboration checks; functional timing is covered by component/channel/metasim tests. "
                    "Channel latency is verified from the fresh bridge annotation, not inferred from SV FIFO names."}
rendered = json.dumps(result, indent=2) + "\n"
if args.output:
    args.output.write_text(rendered)
print(rendered)
sys.exit(1 if failed else 2 if missing else 0)
