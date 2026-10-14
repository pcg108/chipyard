#!/usr/bin/env python3
"""Plot measured and recorded physical traffic without assuming equal latency."""
import argparse
import csv
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    with (args.run / 'physical-comparison.csv').open() as source:
        rows = [{k: int(v) for k, v in r.items()} for r in csv.DictReader(source)]
    validation = json.loads((args.run / 'validation.json').read_text())
    mode = json.loads((args.run / 'provenance.json').read_text())['mode']
    replay = 'software reference' if mode == 'reference' else 'RTL metasim'
    require = validation['result'] == 'PASS'
    if not require:
        raise RuntimeError('plot requires successful event validation')
    fig, axes = plt.subplots(2, 2, figsize=(13, 8), constrained_layout=True)
    for write, kind, color in [(0, 'reads', 'tab:blue'), (1, 'writes', 'tab:orange')]:
        subset = [r for r in rows if r['is_write'] == write]
        for field, style, label in [('measured_issue', '-', replay), ('recorded_issue', '--', 'capture')]:
            ordered = np.sort([r[field] for r in subset])
            axes[0, 0].plot(ordered, np.arange(1, len(ordered) + 1), style, color=color, label=f'{kind}: {label}')
        measured = np.array([r['measured_return'] - r['measured_issue'] for r in subset])
        recorded = np.array([r['recorded_return'] - r['recorded_issue'] for r in subset])
        axes[0, 1].hist(measured, bins=80, histtype='step', color=color, label=f'{kind}: {replay}')
        axes[0, 1].hist(recorded, bins=80, histtype='step', linestyle='--', color=color, label=f'{kind}: capture')
        axes[1, 0].scatter([r['recorded_issue'] for r in subset], [r['measured_issue'] for r in subset],
                           s=1, alpha=.25, color=color, rasterized=True, label=kind)
    physical = np.sort([r['measured_issue'] for r in rows])
    capture = np.sort([r['recorded_issue'] for r in rows])
    bins = np.linspace(0, max(physical[-1], capture[-1]) + 1, 101)
    axes[1, 1].hist(physical, bins=bins, histtype='step', label=replay, color='tab:blue')
    axes[1, 1].hist(capture, bins=bins, histtype='step', label='capture', color='black', linestyle='--')
    for ax, title, xlabel, ylabel in [
        (axes[0, 0], 'Cumulative physical transactions', 'cycle', 'transactions'),
        (axes[0, 1], 'Physical response latency', 'cycles from issue to return', 'transactions'),
        (axes[1, 0], 'Per-service issue cycle', 'recorded capture cycle', f'{replay} cycle'),
        (axes[1, 1], 'Physical issue traffic', 'cycle', 'transactions per bin')]:
        ax.set(title=title, xlabel=xlabel, ylabel=ylabel)
        ax.legend(fontsize=8)
        ax.grid(alpha=.2)
    timing = 'Recorded service latency' if mode == 'reference' else 'Measured RTL memory latency'
    fig.suptitle(f'Gemma {validation["kernel"]} — {replay}: {len(rows):,} physical transactions; final cycle {validation["final_cycle"]:,}\n'
                 f'Matched service identities; {timing.lower()} determines replay timing')
    for extension in ('png', 'pdf'):
        fig.savefig(args.run / f'physical-traffic-comparison.{extension}', dpi=180)


if __name__ == '__main__':
    main()
