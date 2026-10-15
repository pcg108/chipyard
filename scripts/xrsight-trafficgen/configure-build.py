#!/usr/bin/env python3
"""Materialize the checked-in build recipes for this checkout and output directory."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    package = Path(__file__).resolve().parent
    root = package.parents[1]
    work = args.work.resolve()
    output = work / 'config'
    output.mkdir(parents=True, exist_ok=True)

    def expand(value):
        if isinstance(value, str):
            return value.replace('@CHIPYARD@', str(root)).replace('@WORK@', str(work))
        if isinstance(value, dict):
            return {key: expand(item) for key, item in value.items()}
        if isinstance(value, list):
            return [expand(item) for item in value]
        return value

    for name in ('config_build.yaml', 'config_build_recipes.yaml', 'config_hwdb.yaml'):
        recipe = expand(json.loads((package / name).read_text()))
        (output / name).write_text(json.dumps(recipe, indent=2) + '\n')
    print(output)


if __name__ == '__main__':
    main()
