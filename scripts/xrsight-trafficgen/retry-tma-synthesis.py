#!/usr/bin/env python3
"""Validate the user-supplied TMA fix, regenerate RTL, and retry the normal build."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
pkg = root / 'scripts/xrsight-trafficgen'
work = Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
target = 'FireSimQuadRocketDualGemminiSaturnTrafficGenConfig'
quintuplet = f'xilinx_alveo_u250-firesim-FireSim-{target}-BaseXilinxAlveoU250Config'
generated = root / 'sims/firesim/sim/generated-src/xilinx_alveo_u250' / quintuplet
build = work / 'builds/platforms/xilinx_alveo_u250' / ('cl_' + quintuplet)
attempt = work / 'retry-tma-assertion-fix'
state = json.loads((work / 'pipeline-status.json').read_text())
assert state['state'] == 'failed' and state['stage'] == 'bitstream', state
assert not (work / 'STOPPED_NO_AUTORESTART.json').exists()
assert 'Synth 8-6058' in (build / 'vivado_proj/firesim.runs/synth_1/runme.log').read_text()
attempt.mkdir()
(attempt / 'previous-pipeline-status.json').write_text(json.dumps(state, indent=2) + '\n')
shutil.copytree(build / 'vivado_proj/firesim.runs/synth_1', attempt / 'failed-synth_1')
shutil.copy2(build / 'design/FireSim-generated.sv', attempt / 'previous-FireSim-generated.sv')
shutil.copy2(work / 'provenance/build-source.json', attempt / 'previous-build-source.json')
shutil.copy2(work / 'validation/elaboration.json', attempt / 'previous-elaboration.json')
state.pop('error', None)
state['attempt_started_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
state['retry_reason'] = 'User fixed the TMA constant-false synthesis assertion and requested regeneration/retry'
state['prior_metasim_validation_scope'] = 'Passed before assertion-only fix; retained as prior evidence'
os.environ['XRSIGHT_TG_WORK'] = str(work)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def save(**values):
    state.update(values, updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    p = work / 'pipeline-status.tmp'
    p.write_text(json.dumps(state, indent=2) + '\n')
    p.replace(work / 'pipeline-status.json')
    print(json.dumps(state), flush=True)


def run(stage, command, guard=True):
    save(state='running', stage=stage, build_log=str(attempt / (stage + '.log')))
    (attempt / (stage + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
    if guard:
        command = ['/usr/bin/python3', str(pkg / 'firesim_resource_guard.py'), '--scratch', str(work),
                   '--run-dir', str(work / ('guard-retry-' + stage)), '--latch',
                   str(work / 'STOPPED_NO_AUTORESTART.json'), '--jobs', '4', '--', *command]
    with (attempt / (stage + '.log')).open('x') as log:
        subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    state['completed_stages'].append(stage)


try:
    source = root / 'generators/firechip/bridgeinterfaces/src/main/scala/TmaWakeMonitor.scala'
    expected_source = sha(source)
    assert 'def fail(code: Int): Unit = { error := code.U }' in source.read_text()
    run('tma-fix-tests', ['bash', '-c',
        'set -e; source "$1/env.sh"; export PATH="$1/.conda-env/bin:$PATH"; '
        'exec java -jar "$1/scripts/sbt-launch.jar" "$2"', 'tma-fix-tests', str(root),
        'chipyard/testOnly chipyard.example.TmaWakeMonitorSpec chipyard.example.TmaWakeMonitorAdversarialSpec chipyard.example.TmaWakeMonitorFailureSpec'])
    run('regenerate-rtl', ['bash', str(pkg / 'build.sh'), 'elaborate'], guard=False)
    run('check-regenerated-elaboration', ['/usr/bin/python3', str(pkg / 'check-elaboration.py'),
         '--output', str(work / 'validation/elaboration.json')])
    assert sha(source) == expected_source, 'TMA source changed while regenerating'
    emitted = generated / 'FireSim-generated.sv'
    sv = emitted.read_text()
    tma = re.search(r'(?ms)^module TmaWakeMonitor\b.*?^endmodule', sv).group(0)
    assert not re.search(r"assert\s*\(\s*1'h0\s*\)", tma), 'Rejected assertion remains in emitted TMA RTL'
    assert re.search(r'\bassert\s*\(', tma), 'Expected remaining runtime invariants disappeared'
    assert sha(emitted) != sha(attempt / 'previous-FireSim-generated.sv'), 'RTL was not regenerated'
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    record = {'result': 'PASS', 'chipyard_commit': revision, 'tma_source_sha256': expected_source,
              'previous_rtl_sha256': sha(attempt / 'previous-FireSim-generated.sv'),
              'regenerated_rtl_sha256': sha(emitted), 'constant_false_tma_assertions': 0,
              'remaining_tma_assertions': len(re.findall(r'\bassert\s*\(', tma)),
              'focused_tests_log': str(attempt / 'tma-fix-tests.log'),
              'prior_metasim_validation_scope': state['prior_metasim_validation_scope']}
    (work / 'validation/tma-synthesis-fix.json').write_text(json.dumps(record, indent=2) + '\n')
    provenance = json.loads((work / 'provenance/build-source.json').read_text())
    provenance['previous_chipyard_commit'] = provenance['chipyard_commit']
    provenance['chipyard_commit'] = revision
    provenance['copied_hardware_sources_unchanged'] = False
    provenance['user_tma_synthesis_fix'] = record
    (work / 'provenance/build-source.json').write_text(json.dumps(provenance, indent=2) + '\n')
    save(regenerated_rtl_sha256=record['regenerated_rtl_sha256'], source_commit=revision)
    run('bitstream', ['bash', str(pkg / 'build.sh'), 'bitstream'], guard=False)
    assert sha(build / 'design/FireSim-generated.sv') == record['regenerated_rtl_sha256'], 'Build RTL differs from validated regeneration'
    save(state='bitstream_build_finished', stage='awaiting_report_review_and_physical_test')
    os.execv('/bin/bash', ['bash', '-c',
        'set -e; source "$1/env.sh"; exec "$1/.conda-env/bin/python3" "$1/scripts/xrsight-trafficgen/finish-v7.py"',
        'finish-v7', str(root)])
except BaseException as error:
    save(state='failed', error=repr(error))
    raise
