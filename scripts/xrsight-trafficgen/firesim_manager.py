#!/usr/bin/env python3
"""Invoke the pinned FireSim manager, forwarding resource ownership to SSH workers.

Use inside firesim_resource_guard.py for builds; requires sourced FireSim environment.
"""
import argparse
import os
from pathlib import Path
import runpy
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chipyard', type=Path, required=True)
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    if not command:
        parser.error('FireSim manager arguments required after --')
    from fabric.api import env
    names = ['ILLIXR_BUILD_GUARD_TAG','ILLIXR_BUILD_CPUS','MAKEFLAGS','OMP_NUM_THREADS',
             'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','JAVA_HEAP_SIZE','TMPDIR','XDG_CACHE_HOME',
             'CCACHE_DIR','COURSIER_CACHE','SBT_OPTS','FS_DIR','CY_DIR','RISCV','PATH',
             'FIRESIM_ENV_SOURCED','FIRESIM_SOURCED']
    env.shell_env = {key: os.environ[key] for key in names if key in os.environ}
    env.abort_on_prompts = True
    deploy = args.chipyard.resolve() / 'sims/firesim/deploy'
    os.chdir(deploy)
    sys.path.insert(0, str(deploy))
    sys.argv = [str(deploy / 'firesim'), *command]
    runpy.run_path(sys.argv[0], run_name='__main__')


if __name__ == '__main__':
    main()
