#!/usr/bin/env python3
"""Build the pinned Rodinia NN1 target launcher with real MMIO markers."""
import argparse
import shutil
import subprocess
from pathlib import Path
from common import ROOT, evidence, require, save

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    a=p.parse_args(); out=a.output.resolve(); out.mkdir(parents=True,exist_ok=True)
    hello=ROOT/'scripts/trafficgen/workloads/trafficgen-hello'
    bare=ROOT/'software/firemarshal/test/bare'
    source=(hello/'hello.c').read_text()
    edits=[('#include "trafficgen.h"','#include "trafficgen.h"\n#include "l2_perf.h"'),
           ('    write_reg(TG_SLOT(0) + TG_SUBMIT_AND_CLOSE, 1);',
            '    if (l2perf_start(663201)) return 41;\n'
            '    write_reg(TG_SLOT(0) + TG_SUBMIT_AND_CLOSE, 1);\n'
            '    if (l2perf_snapshot()) return 42;\n'
            '    unsigned sampled = 0; const uint64_t roi_start = cpu_cycle();'),
           ('      if (read_reg(TG_GLOBAL_DONE) & 1U) break;',
            '      if (!sampled && cpu_cycle() - roi_start >= 256) {\n'
            '        if (l2perf_snapshot()) return 43; sampled = 1;\n'
            '      }\n'
            '      if (read_reg(TG_GLOBAL_DONE) & 1U) break;'),
           ('    // Protocol failures and stalled sessions',
            '    if (!sampled && l2perf_snapshot()) return 43;\n'
            '    if (l2perf_stop()) return 44;\n'
            '    // Protocol failures and stalled sessions'),
           ('    printf("[target] PASS TrafficGen test=%u final_cycle=%llu\\n", TRAFFICGEN_TEST_CASE,',
            '    if (l2perf_drain_end()) return 45;\n'
            '    if (l2perf_read(L2PERF_STATE) != L2PERF_IDLE) return 46;\n'
            '    printf("[target] PASS L2PERF markers window=663201 snapshots=2\\n");\n'
            '    printf("[target] PASS TrafficGen test=%u final_cycle=%llu\\n", TRAFFICGEN_TEST_CASE,')]
    for old,new in edits:
        require(source.count(old)==1, 'Pinned launcher changed: '+old)
        source=source.replace(old,new)
    main=out/'rodinia-markers.c'; main.write_text(source)
    shutil.copy2(Path(__file__).with_name('l2_perf.h'),out/'l2_perf.h')
    prefix=ROOT/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-'
    cc=str(prefix)+'gcc'
    flags=['-march=rv64gcv','-mabi=lp64d','-mcmodel=medany','-O2','-fno-common','-fno-builtin',
           '-I'+str(bare),'-I'+str(hello),'-I'+str(out),'-DTRAFFICGEN_TEST_CASE=1',
           '-DTRAFFICGEN_SINGLE_REGISTRY_ID=1106']
    commands=[]; objects=[]
    for path in [main,bare/'crt.S',bare/'syscalls.c']:
        obj=out/(path.stem+'.o'); objects.append(str(obj))
        commands.append([cc,*flags,'-c',str(path),'-o',str(obj)])
    elf=out/'rodinia.elf'
    commands.append([cc,*flags,'-T',str(bare/'link.ld'),'-static','-nostdlib','-nostartfiles',
                     *objects,'-lgcc','-o',str(elf)])
    for command in commands: subprocess.run(command,check=True)
    save(out/'manifest.json',{'commands':commands,'firmware':evidence(elf),
        'source':evidence(main),'control_header':evidence(out/'l2_perf.h'),
        'expected_markers':['START','SNAPSHOT','SNAPSHOT','STOP','DRAIN_END'],'window_id':663201})

if __name__=='__main__': main()
