import hashlib, json, math, re
from pathlib import Path

def require(condition, message):
    if not condition:
        raise ValueError(message)

def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def evidence(path):
    return {"path": str(Path(path).resolve()), "sha256": sha256(path)}

def parse_final_timing(path, requested_mhz):
    require(path.name == "final_timing_summary.rpt", "Only final_timing_summary.rpt is accepted")
    text = path.read_text()
    require(re.search(r"Command\s*:\s*report_timing_summary\b.*final_timing_summary\.rpt", text),
            "Missing final timing-report command provenance")
    require("post_synth" not in text.split("Timing Summary Report", 1)[0], "Synthesis timing is not final timing")
    require("Design Timing Summary" in text and "Clock Summary" in text, "Missing design timing summary")
    table = text.split("Design Timing Summary", 1)[1].split("Clock Summary", 1)[0]
    headings = re.search(r"WNS\(ns\).*TPWS Total Endpoints", table)
    require(headings is not None and all(x in headings[0] for x in
            ("TNS(ns)", "WHS(ns)", "THS(ns)", "WPWS(ns)", "TPWS(ns)")),
            "Timing report lacks setup/hold/pulse-width metrics")
    numeric = []
    for line in table[headings.end():].splitlines():
        values = line.split()
        if len(values) == 12:
            try:
                numeric.append([float(value) for value in values])
            except ValueError:
                continue
    require(len(numeric) == 1 and all(math.isfinite(v) for v in numeric[0]),
            "Expected exactly one finite design timing row")
    values = numeric[0]
    names = ("wns_ns", "tns_ns", "setup_failing_endpoints", "setup_total_endpoints",
             "whs_ns", "ths_ns", "hold_failing_endpoints", "hold_total_endpoints",
             "wpws_ns", "tpws_ns", "pulse_failing_endpoints", "pulse_total_endpoints")
    metrics = dict(zip(names, values))
    for index in (0, 4, 8):
        require(values[index] >= 0, f"Negative {names[index]}: {values[index]}")
        require(values[index + 1] == 0 and values[index + 2] == 0,
                f"Timing violations in {names[index]} domain")
        require(values[index + 3] > 0 and values[index + 3].is_integer(),
                f"No valid endpoint coverage for {names[index]}")
    require("All user specified timing constraints are met." in table,
            "Final timing report does not affirm timing closure")
    for check in ("no_clock", "unconstrained_internal_endpoints", "loops", "latch_loops"):
        counts = re.findall(rf"checking {check} \((\d+)\)", text)
        require(counts and all(int(count) == 0 for count in counts),
                f"Timing check {check} missing or nonzero")
    clock_table = text.split("Clock Summary", 1)[1].split("Intra Clock Table", 1)[0]
    host_clock = re.findall(r"^\s*host_clock\s+\{[^}]+\}\s+([\d.]+)\s+([\d.]+)", clock_table, re.M)
    require(len(host_clock) == 1 and abs(float(host_clock[0][1]) - requested_mhz) < .01,
            f"Routed host clock does not match the {requested_mhz} MHz physical request")
    return {"report": evidence(path), "metrics": metrics,
            "physical_clock": {"requested_mhz": requested_mhz, "reported_period_ns": float(host_clock[0][0]),
                               "reported_mhz": float(host_clock[0][1])}}

def parse_postroute_bus_skew(path):
    require(path.name == "overall_fpga_top_bus_skew_postroute_physopted.rpt",
            "Expected final post-route physical-optimization bus-skew report")
    text = path.read_text()
    require(re.search(r"Command\s*:\s*report_bus_skew\b.*overall_fpga_top_bus_skew_postroute_physopted\.rpt", text),
            "Missing bus-skew report command provenance")
    marker = "1. Bus Skew Report Summary\n--------------------------\n"
    require(text.count(marker) == 1, "Missing or ambiguous bus-skew summary")
    summary = text.split(marker, 1)[1].split("2. Bus Skew Report Per Constraint", 1)[0]
    ids = [int(x) for x in re.findall(r"^\s*(\d+)\s+\d+\s+\[get_cells", summary, re.M)]
    rows = re.findall(r"^\s*(Fast|Slow)\s+(-?\d+\.\d+)\s+(-?\d+\.\d+)\s+(-?\d+\.\d+)\s*$", summary, re.M)
    require(ids and ids == list(range(1, len(ids) + 1)) and len(rows) == len(ids),
            "Incomplete bus-skew constraint coverage")
    slacks = [float(row[3]) for row in rows]
    require(all(math.isfinite(x) and x >= 0 for x in slacks), "Bus-skew timing violation")
    return {"report": evidence(path), "constraints": len(rows),
            "minimum_slack_ns": min(slacks), "violations": 0}

def parse_final_route(path):
    require(path.name == "final_route_status.rpt", "Only final_route_status.rpt is accepted")
    text = path.read_text()
    require("Design Route Status" in text, "Missing route status header")
    counts = {}
    for label, value in re.findall(r"# of (.+?)\.*\s*:\s*(\d+)\s*:", text):
        key = label.rstrip(". ").strip()
        require(key not in counts, f"Duplicate routing metric {key}")
        counts[key] = int(value)
    for key in ("logical nets", "routable nets", "fully routed nets", "nets with routing errors"):
        require(key in counts, f"Missing route metric {key}")
    require(counts["routable nets"] > 0 and counts["fully routed nets"] == counts["routable nets"],
            "Design routing is incomplete")
    require(counts["logical nets"] >= counts["routable nets"], "Inconsistent route counts")
    for key, count in counts.items():
        if any(part in key for part in ("routing errors", "unrouted", "partially routed")):
            require(count == 0, f"Route failure: {key}={count}")
    return {"report": evidence(path), "counts": counts}

def check_project(project, requested_mhz):
    reports = Path(project) / 'reports'
    result = {
        'timing': parse_final_timing(reports / 'final_timing_summary.rpt', requested_mhz),
        'routing': parse_final_route(reports / 'final_route_status.rpt'),
        'bus_skew': parse_postroute_bus_skew(Path(project) / 'firesim.runs/impl_1/overall_fpga_top_bus_skew_postroute_physopted.rpt'),
    }
    drc = reports / 'final_drc.rpt'
    text = drc.read_text()
    require('report_drc' in text and 'REPORT SUMMARY' in text, 'Missing final DRC provenance/summary')
    summary = text.split('REPORT SUMMARY', 2)[-1].split('2. REPORT DETAILS', 1)[0]
    found = re.search(r'Violations found:\s*(\d+)', summary)
    require(found is not None, 'Missing DRC violation count')
    rows = re.findall(r'^\|\s*(\S+)\s*\|\s*([^|]+?)\s*\|[^|]+\|\s*(\d+)\s*\|', summary, re.M)
    require(sum(int(row[2]) for row in rows) == int(found[1]), 'Incomplete DRC summary')
    critical = [row for row in rows if row[1].strip() in ('Critical Warning', 'Error') and int(row[2])]
    require(not critical, 'Critical DRC violations: ' + repr(critical))
    result['drc'] = {'report': evidence(drc), 'critical_or_error_violations': 0, 'other_violations': int(found[1])}
    path = reports / 'final_check_timing.rpt'
    text = path.read_text()
    checks = {}
    for name, count in re.findall(r'checking (\w+) \((\d+)\)', text):
        require(name not in checks or checks[name] == int(count), 'Inconsistent timing check: ' + name)
        checks[name] = int(count)
    required = {'no_clock', 'constant_clock', 'pulse_width_clock', 'unconstrained_internal_endpoints', 'no_input_delay', 'no_output_delay', 'multiple_clock', 'generated_clocks', 'loops', 'partial_input_delay', 'partial_output_delay', 'latch_loops'}
    require(required <= checks.keys(), 'Missing timing checks')
    expected_ports = {'no_input_delay': {'pcie_perstn', 'resetn'}, 'no_output_delay': {'ddr4_sdram_c0_reset_n'}}
    for name, count in checks.items():
        if name not in expected_ports:
            require(count == 0, f'Unexpected timing check {name}={count}')
        elif count:
            # These three reset pins are the existing stock U250 platform exceptions.
            section = text.rsplit('checking ' + name + ' (', 1)[1].split('\n\n\n', 1)[0]
            ports = re.search(r'\(HIGH\)\s*\n\s*\n(.*?)\n\s*\n', section, re.S)
            require(ports is not None, 'Cannot identify unconstrained platform ports')
            names = set(ports[1].split())
            require(len(names) == count and names <= expected_ports[name], 'Unexpected unconstrained ports: ' + repr(names))
    result['check_timing'] = {'report': evidence(path), 'counts': checks, 'existing_reset_port_exceptions': {k: sorted(v) for k,v in expected_ports.items()}}
    return result
