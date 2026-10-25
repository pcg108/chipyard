#!/usr/bin/env python3
"""Fail closed on a missing/over-capacity post-synthesis U250 resource report."""
import argparse
import hashlib
import json
import re
from pathlib import Path

CAPACITY = {'LUT':1728000, 'FF':3456000, 'BRAM36':2688, 'URAM':1280, 'DSP':12288}

def parse(text):
    if 'xcu250' not in text or 'Synthesized' not in text:
        raise ValueError('Expected synthesized U250 utilization report')
    rows = [line for line in text.splitlines()
            if re.match(r'^\|\s*overall_fpga_top\s*\|\s*\(top\)\s*\|', line)]
    if len(rows) != 1:
        raise ValueError('Missing or ambiguous top-level synthesis resource row')
    columns = [x.strip() for x in rows[0].strip('|').split('|')][2:]
    values = [float(re.fullmatch(r'([\d.]+)(?:\([\d.]+%\))?', x)[1]) for x in columns]
    if len(values) != 9:
        raise ValueError('Unexpected utilization column layout')
    # Total LUT, logic LUT, LUTRAM, SRL, FF, RAMB36, RAMB18, URAM, DSP.
    used = dict(zip(('LUT','FF','BRAM36','URAM','DSP'),
                    (values[0], values[4], values[5]+values[6]/2, values[7], values[8])))
    return {key:{'used':value,'available':CAPACITY[key],
                 'percent':100*value/CAPACITY[key]} for key,value in used.items()}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('report',type=Path); p.add_argument('output',type=Path)
    a=p.parse_args()
    result={'status':'failed', 'report':str(a.report.resolve()),
            'report_sha256':hashlib.sha256(a.report.read_bytes()).hexdigest()}
    try:
        result['resources']=parse(a.report.read_text())
        over=[k for k,v in result['resources'].items() if v['used']>v['available']]
        result['high_pressure']=[k for k,v in result['resources'].items() if v['percent']>=90]
        if over: raise ValueError('Hard resource overflow: '+', '.join(over))
        result.update(status='passed',decision='Proceed to placement; aggregate resources fit')
    except Exception as error:
        result['error']=str(error)
    a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    return 0 if result['status']=='passed' else 1

if __name__=='__main__': raise SystemExit(main())
