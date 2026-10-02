"""Shared helpers; all generated artifacts stay in the requested work directory."""
from pathlib import Path
import hashlib,json,os,re,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
TOOLS=ROOT/'.conda-env/bin'
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def write(p,obj):
 p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);t=p.with_suffix(p.suffix+'.tmp');t.write_text(json.dumps(obj,indent=2)+'\n');t.replace(p)
def command(args,log,*,cwd=None,env=None,timeout=3600,expect=0):
 start=time.monotonic()
 with Path(log).open('w') as f:r=subprocess.run(list(map(str,args)),cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=timeout)
 result={'command':list(map(str,args)),'exit_code':r.returncode,'host_seconds':time.monotonic()-start,'log':str(log)}
 if expect is not None and r.returncode!=expect:raise RuntimeError('Command failed: '+json.dumps(result))
 return result

def isolated_source(work,repo,commit):
 """Parent worktree plus an independent changed-component checkout."""
 dest=work/'source'
 subprocess.run(['git','-C',str(ROOT),'worktree','add','--detach',str(dest),'HEAD'],check=True,stdout=subprocess.DEVNULL)
 entries=subprocess.check_output(['git','-C',str(ROOT),'config','-f','.gitmodules','--get-regexp',r'^submodule\..*\.path$'],text=True)
 for line in entries.splitlines():
  rel=line.split(None,1)[1];src=ROOT/rel;out=dest/rel
  if not (src/'.git').exists():continue
  if out.exists():out.rmdir()
  if rel=='generators/'+repo:
   subprocess.run(['git','clone','--shared','--no-checkout',str(src),str(out)],check=True,stdout=subprocess.DEVNULL)
   subprocess.run(['git','-C',str(out),'checkout','--detach',subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip()],check=True,stdout=subprocess.DEVNULL)
   subprocess.run(['git','-C',str(out),'revert','--no-commit',commit],check=True)
  else:out.symlink_to(src,target_is_directory=True)
 write(work/'negative-source.json',{'root':str(dest),'component':repo,'removed_commit':commit,'component_diff':subprocess.check_output(['git','-C',str(dest/'generators'/repo),'diff','HEAD'],text=True)})
 return dest

def elaborate(source,config,work,jobs):
 work.mkdir(parents=True,exist_ok=True)
 cached=work/'elaboration.json'
 files=[source/'generators/rocket-chip/src/main/scala/rocket/RocketCore.scala',source/'generators/rocket-chip/src/main/scala/rocket/NBDcache.scala',source/'generators/saturn/src/main/scala/mem/Mem.scala',source/'generators/shuttle/src/main/scala/exu/Core.scala',source/'generators/chipyard/src/main/scala/config/RTLFixConfigs.scala']
 hashes={str(p):sha(p) for p in files}
 if cached.exists():
  d=json.loads(cached.read_text());assert d['source_hashes']==hashes and d.get('preserve_ports') and sha(d['generated_sv'])==d['generated_sha256'];return Path(d['generated_sv'])
 for n in ['tmp','classpath','sbt-targets','generated']:(work/n).mkdir(exist_ok=True)
 env=os.environ.copy();env['PATH']=str(TOOLS)+':'+str(ROOT/'.vecadd-sim/espresso-build')+':'+env['PATH']
 env.update(RISCV=str(ROOT/'.conda-env/riscv-tools'),TMPDIR=str(work/'tmp'),JAVA_TOOL_OPTIONS=f'-Xmx16G -Xss64M -XX:ActiveProcessorCount={jobs} -Djava.io.tmpdir={work / "tmp"}',SBT_OPTS='-Dsbt.supershell=false -Dsbt.server.forcestart=true')
 cache=Path(os.environ.get('RTL_FIX_CACHE',str(work.parent.parent/'cache')));cache.mkdir(exist_ok=True)
 env.update(COURSIER_CACHE=str(cache/'coursier/v1'),XDG_CACHE_HOME=str(cache))
 # Dependency downloads and compiled outputs stay in scratch.
 wrapper=work/'sbt-wrapper.py'
 launcher=[str(TOOLS/'java'),'-jar',str(source/'scripts/sbt-launch.jar'),'-Dsbt.supershell=false','-Dsbt.server.forcestart=true','-Dsbt.ivy.home='+str(cache/'ivy2'),'-Dsbt.global.base='+str(cache/'sbt'),'-Dsbt.boot.directory='+str(cache/'sbt/boot')]
 command(launcher+['projects'],work/'sbt-projects.log',cwd=source,env=env)
 ids=re.findall(r'^\[info\]\s+\*?\s+([\w-]+)\s*$',(work/'sbt-projects.log').read_text(),re.M)
 assert 'chipyard' in ids and 'rocketchip' in ids,ids
 settings='set Seq('+', '.join('LocalProject("'+i+'") / target := file("'+str(work/'sbt-targets'/i)+'")' for i in ids)+')'
 wrapper.write_text('import os,sys\nos.execv('+repr(str(TOOLS/'java'))+', '+repr(launcher+[settings])+' + sys.argv[1:])\n')
 make=['make','-C',source/'sims/verilator','CONFIG='+config,'EXTRA_CHISEL_OPTIONS=--emit-legacy-sfc','build_dir='+str(work/'generated'),'CLASSPATH_CACHE='+str(work/'classpath'),'SBT='+str(TOOLS/'python')+' '+str(wrapper),'firrtl']
 build=command(make,work/'elaboration.log',cwd=source,env=env,timeout=3600)
 firs=list((work/'generated').glob('*.sfc.fir'));assert len(firs)==1,firs
 # Use the same legacy compiler family as the previously validated FireSim RTL;
 # no replacement-memory black boxes or FPGA transforms are needed for these tops.
 sv=work/'generated/generated.sv'
 nodce=work/'preserve-ports.anno.json';write(nodce,[{'class':'firrtl2.transforms.NoDCEAnnotation$'}])
 lower=command([TOOLS/'java','-cp',work/'classpath/chipyard.jar','firrtl2.stage.FirrtlMain','-i',firs[0],'-o',sv,'-X','sverilog','-faf',nodce,'--target-dir',work/'generated'],work/'lowering.log',cwd=source,env=env,timeout=1800)
 assert sv.is_file() and sv.stat().st_size>0
 for name in ['EICG_wrapper.v','plusarg_reader.v']:
  matches=list((source/'generators/rocket-chip/src/main/resources').rglob(name));assert len(matches)==1,(name,matches)
  import shutil
  shutil.copy2(matches[0],sv.parent/name)
 write(cached,{'source':str(source),'config':config,'preserve_ports':True,'source_hashes':hashes,'generated_sv':str(sv),'generated_sha256':sha(sv),'scala_build':build,'lowering':lower})
 return sv

def module(text,name):
 match=re.search(r'^module '+re.escape(name)+r'\s*\(',text,re.M)
 if not match:raise ValueError('Missing module '+name)
 a=match.start();b=text.index(');',a);end=text.index('endmodule',b)+len('endmodule');return a,b,end

def ports(text,name):
 a,b,_=module(text,name)
 return [(d,w or '',n) for d,w,n in re.findall(r'^\s*(input|output)\s+(?:wire\s+)?(\[[^]]+\]\s*)?(\w+)',text[a:b],re.M)]
def zero_inputs(portlist):
 lines=[]
 for d,w,n in portlist:
  if d!='input':continue
  bits=int(w.strip()[1:].split(':')[0])+1 if w else 1
  lines.extend([f'd.{n}[{i}]=0;' for i in range((bits+31)//32)] if bits>64 else [f'd.{n}=0;'])
 return '\n'.join(lines)+'\n'
def observe(text,name,probes):
 a,b,e=module(text,name);body=text[b:e]
 for width,signal in probes.values():
  if not re.search(r'\b'+re.escape(signal)+r'\b',body):raise ValueError('Missing observation signal '+signal)
 decl=',\n'+',\n'.join('output '+w+' '+n for n,(w,s) in probes.items())+'\n'
 end=e-len('endmodule');assign='\n'+'\n'.join('assign '+n+' = '+s+';' for n,(w,s) in probes.items())+'\n'
 return text[:b]+decl+text[b:end]+assign+text[end:]

def select_modules(text,top):
 """Extract whole generated modules in the top's dependency closure unchanged."""
 matches=list(re.finditer(r'^module (\w+)\s*\(',text,re.M));modules={}
 for match in matches:
  end=text.index('endmodule',match.end())+len('endmodule')
  modules[match.group(1)]=text[match.start():end]
 pending=[top];selected=set()
 instance=re.compile(r'^\s*(\w+)\s+(?:#\([^;]*?\)\s*)?\w+\s*\(',re.M)
 while pending:
  name=pending.pop()
  if name in selected:continue
  selected.add(name)
  for kind in instance.findall(modules[name]):
   if kind in modules and kind not in selected:pending.append(kind)
 return '\n\n'.join(modules[n] for n in modules if n in selected)+'\n',sorted(selected)

def compile_model(sv,work,top,prefix,driver,jobs,*,assertions=True):
 import shutil
 work.mkdir(parents=True,exist_ok=True);shutil.copy2(driver,work/'test.cpp')
 for n in ['EICG_wrapper.v','plusarg_reader.v']:
  src=sv.parent/n
  if src.resolve()!=(work/n).resolve():shutil.copy2(src,work/n)
 selected,names=select_modules(sv.read_text(),top);compile_sv=work/'selected.sv';compile_sv.write_text(selected)
 write(work/'module-selection.json',{'source':str(sv),'source_sha256':sha(sv),'selected_sha256':sha(compile_sv),'modules':names})
 args=['nice','-n','10',TOOLS/'verilator','--cc','--exe','--build','-j',str(jobs),'--top-module',top,'--prefix',prefix,'-Wno-fatal','-DPRINTF_COND=0','--Mdir',work/'build',compile_sv,work/'EICG_wrapper.v',work/'plusarg_reader.v',work/'test.cpp']
 if assertions:args.insert(4,'--assert')
 b=command(args,work/'build.log',timeout=1800);return work/'build'/prefix,b
