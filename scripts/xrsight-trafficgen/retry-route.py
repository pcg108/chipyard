#!/usr/bin/env python3
"""Preview a bounded recovery; execution requires explicit restart approval.

This creates a routed candidate and validates it. Bitstream emission and packaging
follow only after those checks pass. The original resource-stop evidence is kept.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--work', type=Path, default=Path('/scratch/prashanth_xrsight_trafficgen_20261011'))
p.add_argument('--execute-after-approval', action='store_true',
               help='Start one retry only after explicit user approval of the resource-stop recovery')
a = p.parse_args()
pkg = Path(__file__).resolve().parent
root = pkg.parents[1]
work = a.work.resolve()
quintuplet = 'xilinx_alveo_u250-firesim-FireSim-FireSimQuadRocketDualGemminiSaturnTrafficGenConfig-BaseXilinxAlveoU250Config'
cl = work / 'builds/platforms/xilinx_alveo_u250' / ('cl_' + quintuplet)
checkpoint = cl / 'vivado_proj/firesim.runs/impl_1/overall_fpga_top_physopt.dcp'
expected_hash = '526010fdf50d1309e952b33bc509761feaa7c6b3cd26456b0f18b3853de5a0a9'
latch = work / 'STOPPED_NO_AUTORESTART.json'
prior = json.loads(latch.read_text())
if prior.get('stop_reasons') != ['process_RSS_at_least_64_GiB']:
    raise ValueError('Unexpected stop reason: review this stop separately')
if prior.get('stopped_at') != '2026-10-12T14:22:11.002138+00:00':
    raise ValueError('This recovery applies only to the reviewed original stop')
with checkpoint.open('rb') as stream:
    actual_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
if actual_hash != expected_hash:
    raise ValueError('Pre-routing checkpoint changed')
smoke = work / 'validation/smoke-20261012T035130Z/result.json'
if json.loads(smoke.read_text()).get('status') != 'passed':
    raise ValueError('Combined smoke result is not passing')
xrsight = Path('/home/prashanth/illixr_example_zephyr')
revision = subprocess.check_output(['git', '-C', str(xrsight), 'rev-parse', 'HEAD'], text=True).strip()
if revision != '5d3c912ed912c08a9e646279b15b4b48b680f879':
    raise ValueError('XRSight report-checker revision changed')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = work / ('route-retry-' + stamp)
command = [sys.executable, str(pkg / 'firesim_resource_guard.py'), '--scratch', str(work),
           '--run-dir', str(out / 'guard'), '--latch', str(latch), '--jobs', '4', '--',
           '/ecad/tools/xilinx/Vivado/2022.1/bin/vivado', '-mode', 'batch', '-nojournal',
           '-log', str(out / 'vivado.log'), '-source', str(pkg / 'retry-route.tcl'),
           '-tclargs', str(checkpoint), str(out)]
record = {
    'state': 'approval_required', 'checkpoint': str(checkpoint),
    'checkpoint_sha256': actual_hash, 'output': str(out),
    'change': {'route.optimizeLeafClkProgDlys': False},
    'hardware_and_clocks_changed': False,
    'limits': {'cpus': 4, 'max_process_rss_gib': 64, 'min_host_available_gib': 48},
    'restart_count': 1, 'automatic_retry': False,
    'final_timing_and_routing_checks_required': True, 'command': command,
}
print(json.dumps(record, indent=2), flush=True)
if not a.execute_after_approval:
    sys.exit(0)

out.mkdir(parents=True, exist_ok=False)
# This branch must only be entered after user approval. Keep the stop evidence;
# the new guarded attempt uses the original shared latch path for any new stop.
latch.rename(out / 'prior-resource-stop.json')
record.update(state='running', source_commit=subprocess.check_output(
    ['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip())
def save():
    (out / 'recovery.json').write_text(json.dumps(record, indent=2) + '\n')
save()
try:
    env = dict(os.environ, TMPDIR=str(work / 'tmp'))
    with (out / 'console.log').open('x') as log:
        subprocess.run(command, cwd=out, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    sys.path.insert(0, str(xrsight / 'scripts'))
    from check_firesim_platform import parse_final_timing, parse_final_route, parse_postroute_bus_skew
    reports = out / 'vivado_proj/reports'
    record['timing'] = parse_final_timing(reports / 'final_timing_summary.rpt')
    record['routing'] = parse_final_route(reports / 'final_route_status.rpt')
    record['bus_skew'] = parse_postroute_bus_skew(
        out / 'vivado_proj/firesim.runs/impl_1/overall_fpga_top_bus_skew_postroute_physopted.rpt')
    record.update(state='validated_candidate', bitstream_produced=False, fpga_programmed=False)
except BaseException as exc:
    record.update(state='failed', error=repr(exc))
    raise
finally:
    save()
