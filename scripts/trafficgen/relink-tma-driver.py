#!/usr/bin/env python3
"""Relink a driver-only change against immutable, freshly built RTL objects."""
import argparse,hashlib,json,os,shlex,shutil,subprocess,time
from pathlib import Path
REPO=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('base',type=Path);p.add_argument('output',type=Path)
a=p.parse_args();base=a.base.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False);objects=base/'VFireSim.csrc'
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(8<<20),b''):h.update(block)
    return h.hexdigest()
def dump(name,value):(out/name).write_text(json.dumps(value,indent=2)+'\n')
manifest=json.loads((base/'build-manifest.json').read_text());assert manifest['result']=='PASS'
assert sha(base/'VFireSim')==manifest['binary_sha256']
sources={path:sha(path) for path in manifest['source_sha256']}
changed={path for path,h in sources.items() if h!=manifest['source_sha256'][path]}
allowed={str(REPO/'generators/firechip/bridgestubs/src/main/cc/bridges/trafficgen.cc')}
assert changed==allowed,('unexpected source changes',changed)
rebuild=[]
for path in objects.glob('*.d'):
    deps=shlex.split(path.read_text().replace('\\\n',' '))[1:]
    if changed.intersection(deps):rebuild.append(path.with_suffix('.o').name)
assert rebuild==['trafficgen.o'],rebuild
compile_command=None;link_command=None
for line in (base/'build.log').read_text().splitlines():
    if not line.startswith(str(REPO/'.conda-env/bin/x86_64-conda-linux-gnu-c++')):continue
    argv=shlex.split(line)
    if '-o' not in argv:continue
    dest=argv[argv.index('-o')+1]
    if '-c' in argv and dest=='trafficgen.o':compile_command=argv
    if '-c' not in argv and dest==str(base/'VFireSim') and 'Vemul__ALL.a' in argv:link_command=argv
assert compile_command and link_command
compile_command[compile_command.index('-o')+1]=str(out/'trafficgen.o')
compile_command=['-I'+str(objects) if arg=='-I.' else arg for arg in compile_command]
inputs=[]
for i,arg in enumerate(link_command):
    if arg.endswith(('.o','.a')) and not arg.startswith('-'):
        old=Path(arg) if Path(arg).is_absolute() else objects/arg
        inputs.append(old)
        link_command[i]=str(out/arg if arg in rebuild else old)
link_command[link_command.index('-o')+1]=str(out/'VFireSim')
commands=[compile_command,link_command];dump('commands.json',commands)
(out/'commands.sh').write_text('#!/usr/bin/env bash\nset -euo pipefail\ncd '+shlex.quote(str(out))+'\n'+'\n'.join(shlex.join(c) for c in commands)+'\n')
frozen=inputs+[base/'VFireSim',base/'FireSim-generated.sv',base/'build-manifest.json']
before={str(x):sha(x) for x in frozen};dump('original-before.json',before);dump('source-before.json',sources)
for path in changed:
    target=out/'source-snapshot'/Path(path).relative_to(REPO);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
record={'base':str(base),'source_sha256':sources,'changed_sources':sorted(changed),'generated_sv_sha256':sha(base/'FireSim-generated.sv'),
        'old_binary_sha256':manifest['binary_sha256'],'rebuilt_objects':rebuild,'rtl_recompiled':False}
start=time.monotonic()
try:
    env=dict(os.environ);env['PATH']=str(REPO/'.conda-env/bin')+':'+env.get('PATH','');env['LD_LIBRARY_PATH']=str(REPO/'.conda-env/lib')
    with (out/'build.log').open('w') as log:
        for command in commands:
            log.write(shlex.join(command)+'\n');log.flush();subprocess.run(command,cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    after={str(x):sha(x) for x in frozen};dump('original-after.json',after);assert after==before
    assert {path:sha(path) for path in sources}==sources
    libraries=subprocess.check_output(['ldd',str(out/'VFireSim')],text=True,env=env)
    (out/'runtime-libraries.txt').write_text(libraries);assert 'libboost_serialization.so.1.85.0' in libraries and 'not found' not in libraries
    record.update(result='PASS',binary_sha256=sha(out/'VFireSim'),original_inputs_unchanged=True,sources_changed_during_build=[])
except Exception as e:record.update(result='FAIL',error=str(e));raise
finally:
    record['wall_seconds']=time.monotonic()-start;dump('build-manifest.json',record)
