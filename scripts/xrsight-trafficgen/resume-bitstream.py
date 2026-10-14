#!/usr/bin/env python3
"""Resume the build after the verified Fabric interpreter-selection failure."""
import datetime
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
pkg = root / 'scripts/xrsight-trafficgen'
work = Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
status = json.loads((work / 'pipeline-status.json').read_text())
assert status['state'] == 'failed' and status['stage'] == 'bitstream', status
assert 'metasim-6632' in status['completed_stages']
assert "ModuleNotFoundError: No module named 'fabric'" in (work / 'logs/bitstream.log').read_text()
assert not (work / 'STOPPED_NO_AUTORESTART.json').exists()
with (work / 'pipeline-before-launcher-fix.json').open('x') as stream:
    json.dump(status, stream, indent=2)
status.pop('error', None)


def save(**values):
    status.update(values, updated_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    temp = work / 'pipeline-status.tmp'
    temp.write_text(json.dumps(status, indent=2) + '\n')
    temp.replace(work / 'pipeline-status.json')
    print(json.dumps(status), flush=True)


try:
    save(state='running', stage='bitstream',
         launcher_fix='Restore Conda bin precedence after system-compiler model tests',
         build_log=str(work / 'logs/bitstream-resume-01.log'))
    with (work / 'logs/bitstream-resume-01.log').open('x') as log:
        subprocess.run(['bash', str(pkg / 'build.sh'), 'bitstream'], cwd=root,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    status['completed_stages'].append('bitstream')
    save(state='bitstream_build_finished', stage='awaiting_report_review_and_physical_test')
    os.execv('/bin/bash', ['bash', '-c',
        'set -e; source "$1/env.sh"; exec "$1/.conda-env/bin/python3" "$1/scripts/xrsight-trafficgen/finish-v7.py"',
        'finish-v7', str(root)])
except BaseException as error:
    save(state='failed', error=repr(error))
    raise
