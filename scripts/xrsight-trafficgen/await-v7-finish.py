#!/usr/bin/env python3
"""Hand off a successful supervised build to validation and the FPGA test."""
import json
import os
from pathlib import Path
import sys
import time

root = Path(__file__).resolve().parents[2]
work = Path('/scratch/prashanth_xrsight_trafficgen_v7_20261013')
while True:
    status = json.loads((work / 'pipeline-status.json').read_text())
    if status['state'] == 'failed' or (work / 'STOPPED_NO_AUTORESTART.json').exists():
        print('Build stopped; no recovery or FPGA programming attempted.', flush=True)
        sys.exit(1)
    if status['state'] == 'bitstream_build_finished':
        os.execv('/bin/bash', ['bash', '-c',
            'set -e; source "$1/env.sh"; exec "$1/.conda-env/bin/python3" "$1/scripts/xrsight-trafficgen/finish-v7.py"',
            'finish-v7', str(root)])
    # A dead build supervisor is not authorization to restart it.
    manager = int((work / 'continuation.pid').read_text())
    try:
        os.kill(manager, 0)
    except ProcessLookupError:
        print('Build supervisor exited without a successful build; inspect status and logs.', flush=True)
        sys.exit(1)
    time.sleep(30)
