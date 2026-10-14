#!/usr/bin/env python3
"""Retry the normal build with the user-provided metadata/report storage changes.

Stop on any failed check. After a successful bitstream build, refresh metasim
validation before handing off to the existing packaging and physical test flow.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
pkg = root / 'scripts/xrsight-trafficgen'
work = Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
target = 'FireSimQuadRocketDualGemminiSaturnTrafficGenConfig'
quint = f'xilinx_alveo_u250-firesim-FireSim-{target}-BaseXilinxAlveoU250Config'
generated = root / 'sims/firesim/sim/generated-src/xilinx_alveo_u250' / quint
build = work / 'builds/platforms/xilinx_alveo_u250' / ('cl_' + quint)
attempt = work / 'retry-packed-storage'
state = json.loads((work / 'pipeline-status.json').read_text())
assert state['state'] == 'failed' and state['stage'] == 'bitstream', state
assert not (work / 'STOPPED_NO_AUTORESTART.json').exists()
assert 'DRC UTLZ-1' in (build / 'vivado_proj/firesim.runs/impl_1/runme.log').read_text()
attempt.mkdir()
(attempt / 'previous-pipeline-status.json').write_text(json.dumps(state, indent=2) + '\n')
for relative in ('provenance/build-source.json', 'validation/elaboration.json',
                 'validation/tma-elaboration.json'):
    shutil.copy2(work / relative, attempt / ('previous-' + Path(relative).name))
shutil.copy2(build / 'design/FireSim-generated.sv', attempt / 'previous-FireSim-generated.sv')
for run_name in ('synth_1', 'impl_1'):
    old_run = build / 'vivado_proj/firesim.runs' / run_name
    destination = attempt / ('previous-' + run_name)
    destination.mkdir()
    for path in old_run.iterdir():
        if path.is_file() and path.suffix in ('.log', '.rpt', '.tcl', '.rst'):
            shutil.copy2(path, destination / path.name)
state.pop('error', None)
state['attempt_started_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
state['retry_reason'] = 'User reduced metadata staging to 4096 entries and report storage to 136 bits'
state['prior_metasim_validation_scope'] = 'Pre-storage-change evidence; fresh metasim validation queued after successful bitstream build'
os.environ.update(XRSIGHT_TG_WORK=str(work),
                  LD_LIBRARY_PATH=str(work / 'model-build/boost-libs'),
                  PATH='/usr/bin:/bin:' + os.environ['PATH'])


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            digest.update(block)
    return digest.hexdigest()


def save(**values):
    state.update(values, updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    temp = work / 'pipeline-status.tmp'
    temp.write_text(json.dumps(state, indent=2) + '\n')
    temp.replace(work / 'pipeline-status.json')
    print(json.dumps(state), flush=True)


def run(stage, command, guard=True):
    save(state='running', stage=stage, build_log=str(attempt / (stage + '.log')))
    (attempt / (stage + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
    if guard:
        command = ['/usr/bin/python3', str(pkg / 'firesim_resource_guard.py'), '--scratch', str(work),
                   '--run-dir', str(work / ('guard-packed-' + stage)), '--latch',
                   str(work / 'STOPPED_NO_AUTORESTART.json'), '--jobs', '4', '--', *command]
    with (attempt / (stage + '.log')).open('x') as log:
        subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    state['completed_stages'].append('packed-storage/' + stage)


try:
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    paths = [
        'generators/firechip/goldengateimplementations/src/main/scala/TrafficGenBridge.scala',
        'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.cc',
        'generators/firechip/bridgestubs/src/main/cc/bridges/test/TrafficGenDriverSessionTest.cc',
        'scripts/trafficgen/audit-tma-elaboration.py',
    ]
    sources = {p: sha(root / p) for p in paths}
    assert 'val lrcMetadataEntries = 4096' in (root / paths[0]).read_text()
    assert 'val lrcReportPayloadBits = 136' in (root / paths[0]).read_text()
    assert 'kLrcMetadataEntries = 4096' in (root / paths[1]).read_text()
    model_revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=work / 'model-src', text=True).strip()
    assert model_revision == '7b6abcafe312c21e87393181410eb2f2aea1d938'
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=work / 'model-src', text=True).strip()
    run('cpp', ['bash', str(pkg / 'validate-v7.sh'), 'cpp'])
    run('regenerate-rtl', ['bash', str(pkg / 'build.sh'), 'elaborate'], guard=False)
    run('check-elaboration', ['/usr/bin/python3', str(pkg / 'check-elaboration.py'),
                             '--output', str(work / 'validation/elaboration.json')])
    run('audit-packed-rtl', ['/usr/bin/python3', str(root / 'scripts/trafficgen/audit-tma-elaboration.py'),
                            str(generated), '--rtl-only', '--output',
                            str(work / 'validation/packed-storage-elaboration.json')])
    assert {p: sha(root / p) for p in paths} == sources, 'Sources changed during regeneration'
    emitted = generated / 'FireSim-generated.sv'
    rtl_sha = sha(emitted)
    assert rtl_sha != sha(attempt / 'previous-FireSim-generated.sv'), 'RTL was not regenerated'
    record = {'result': 'PASS', 'chipyard_commit': revision, 'source_sha256': sources,
              'regenerated_rtl_sha256': rtl_sha, 'metadata_entries': 4096,
              'report_storage_bits': 136, 'report_dma_bits': 512,
              'cpp_log': str(attempt / 'cpp.log'),
              'elaboration_audit': str(work / 'validation/packed-storage-elaboration.json')}
    (work / 'validation/packed-storage.json').write_text(json.dumps(record, indent=2) + '\n')
    provenance = json.loads((work / 'provenance/build-source.json').read_text())
    provenance.update(previous_chipyard_commit=provenance['chipyard_commit'],
                      chipyard_commit=revision, user_packed_storage_change=record,
                      prior_metasim_validation_scope=state['prior_metasim_validation_scope'])
    (work / 'provenance/build-source.json').write_text(json.dumps(provenance, indent=2) + '\n')
    save(regenerated_rtl_sha256=rtl_sha, source_commit=revision)
    run('bitstream', ['bash', str(pkg / 'build.sh'), 'bitstream'], guard=False)
    assert sha(build / 'design/FireSim-generated.sv') == rtl_sha, 'Build RTL differs from validated RTL'
    save(bitstream_build_finished=True)

    # Refresh functional evidence for the changed storage and chunked driver.
    run('metasim-build', ['bash', str(pkg / 'build.sh'), 'metasim'], guard=False)
    assert sha(emitted) == rtl_sha, 'Metasim build changed the validated hardware'
    run('audit-v7', ['/usr/bin/python3', str(root / 'scripts/trafficgen/audit-tma-elaboration.py'),
                    str(generated), '--command-file', str(work / 'metasim-command.sh'),
                    '--build-log', str(attempt / 'metasim-build.log'), '--output',
                    str(work / 'validation/tma-elaboration.json')])
    (work / 'validation/combined-smoke').rename(attempt / 'previous-combined-smoke')
    run('combined-smoke', ['/usr/bin/python3', str(pkg / 'run-smoke.py'), '--work',
                          str(work / 'validation/combined-smoke'), '--gpu-model',
                          str(work / 'model-build/gpu_model_socket')])
    (work / 'metasim-6632').rename(attempt / 'previous-metasim-6632')
    run('metasim-6632', ['/usr/bin/python3', str(root / 'scripts/trafficgen/run-tma-metasim.py'),
                       str(work / 'metasim-6632'), '--model-source', str(work / 'model-src'),
                       '--model-build', str(work / 'model-build'), '--dataset',
                       '/home/prashanth/gpu_model/hopper-gemma', '--kernel', '6632',
                       '--port', '50072', '--design', target, '--policy-decoder',
                       str(work / 'model-build/export-tma-policies'), '--simulator',
                       str(generated / 'VFireSim')])
    validation = json.loads((work / 'metasim-6632/validation.json').read_text())
    provenance['metasim_validation_scope'] = 'Fresh packed-storage RTL and driver passed combined smoke and Hopper Gemma kernel 6632'
    (work / 'provenance/build-source.json').write_text(json.dumps(provenance, indent=2) + '\n')
    save(state='bitstream_build_finished', stage='awaiting_report_review_and_physical_test',
         metasim_scheduling_rounds=validation['complete_policy_snapshots'],
         metasim_final_cycle=validation['final_cycle'],
         metasim_validation_scope=provenance['metasim_validation_scope'])
    os.execv('/bin/bash', ['bash', '-c',
        'set -e; source "$1/env.sh"; exec "$1/.conda-env/bin/python3" "$1/scripts/xrsight-trafficgen/finish-v7.py"',
        'finish-v7', str(root)])
except BaseException as error:
    save(state='failed', error=repr(error))
    raise
