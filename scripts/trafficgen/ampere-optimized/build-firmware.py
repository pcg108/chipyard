#!/usr/bin/env python3
"""Build single and repeated Rodinia launch tests using pinned FireMarshal sources."""
import argparse, hashlib, json, shutil, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--cc',default=str(root/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc'))
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
bare=root/'software/firemarshal/test/bare'
hello=root/'scripts/trafficgen/workloads/trafficgen-hello'
flags=['-mcmodel=medany','-Wall','-O2','-fno-common','-fno-builtin','-I'+str(bare),'-I'+str(hello)]
commands=[]
for kind,source,defines in [('single',hello/'hello.c',['-DTRAFFICGEN_TEST_CASE=1','-DTRAFFICGEN_SINGLE_REGISTRY_ID=1106']),
                            ('repeat',Path(__file__).with_name('rodinia-repeat.c'),[])]:
 directory=out/kind;directory.mkdir(exist_ok=True)
 objects=[]
 for path in [source,bare/'crt.S',bare/'syscalls.c']:
  obj=directory/(path.stem+'.o');objects.append(str(obj))
  cmd=[a.cc,*flags,*defines,'-c',str(path),'-o',str(obj)]
  commands.append(cmd);subprocess.run(cmd,check=True)
 cmd=[a.cc,'-T',str(bare/'link.ld'),'-static','-nostdlib','-nostartfiles',*objects,'-lgcc','-o',str(directory/(kind+'.elf'))]
 commands.append(cmd);subprocess.run(cmd,check=True)
shutil.copy2(out/'single/single.elf',out/'rodinia.elf')
files=[bare/name for name in ('crt.S','syscalls.c','link.ld','util.h','encoding.h')]
files += [hello/'hello.c',hello/'trafficgen.h',hello/'mmio.h',Path(__file__).with_name('rodinia-repeat.c')]
manifest={'commands':commands,'source_sha256':{str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in files},
          'firmware_sha256':{str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (out/'rodinia.elf',out/'repeat/repeat.elf')}}
(out/'firmware-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest['firmware_sha256'],indent=2))
