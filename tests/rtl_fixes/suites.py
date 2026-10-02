"""Focused fixtures against fresh generated modules; no functional RTL edits."""
from pathlib import Path
import json,re,shutil
from common import ROOT,TOOLS,sha,write,module,ports,zero_inputs,observe,compile_model,command
HERE=Path(__file__).resolve().parent
ROCKET_PROBES={'dbg_system_redirect':('','csr_io_eret'),'dbg_wb_inst':('[31:0]','wb_reg_inst'),'dbg_saved_pc':('[39:0]','csr.reg_mepc'),'dbg_saved_cause':('[63:0]','csr.reg_mcause'),'dbg_scalar_exception':('','wb_reg_xcpt'),'dbg_interrupt':('','csr_io_interrupt'),'dbg_ibuf_pc':('[39:0]','ibuf_io_pc'),'dbg_exception':('','csr_io_exception'),'dbg_trap_pc':('[39:0]','csr_io_pc'),'dbg_cause':('[63:0]','csr_io_cause'),'dbg_retire':('','wb_valid'),'dbg_retire_pc':('[39:0]','wb_reg_pc')}
def prepare(sv,work):
 work.mkdir(parents=True,exist_ok=False)
 for n in ['EICG_wrapper.v','plusarg_reader.v']:shutil.copy2(sv.parent/n,work/n)
 return sv.read_text()
def check_run(binary,work,pattern,total,negative=False,extra=()):
 execution=command([binary,*extra],work/'run.log',timeout=600,expect=None)
 log=(work/'run.log').read_text();matches=re.findall(pattern,log,re.M)
 if len(matches)!=1:raise RuntimeError('Missing/duplicate result marker: '+str(work))
 groups=matches[0];groups=groups if isinstance(groups,tuple) else (groups,)
 got,passed,failed=map(int,groups)
 accepted=got==total and passed+failed==got and execution['exit_code']==(1 if negative else 0) and (failed>0 if negative else failed==0)
 result={'passed':accepted,'negative_control':negative,'cases':got,'checks_passed':passed,'checks_failed':failed,'execution':execution}
 write(work/'result.json',result)
 if not accepted:raise RuntimeError('Focused RTL gate failed: '+str(work))
 return result

def rocket(sv,work,jobs,negative,trap=False):
 s=prepare(sv,work);s=observe(s,'Rocket',ROCKET_PROBES);(work/'observed.sv').write_text(s);(work/'zero_inputs.h').write_text(zero_inputs(ports(s,'Rocket')))
 driver=HERE/'rocket_irq'/('trap_pc.cpp' if trap else 'interrupt_pc.cpp')
 binary,build=compile_model(work/'observed.sv',work,'Rocket','VRocket',driver,jobs)
 result=check_run(binary,work,r'^TRAP_PC cases=(\d+) passed=(\d+) failed=(\d+)$' if trap else r'^IRQ_REGRESSION cases=(\d+) passed=(\d+) failed=(\d+)$',512 if trap else 8640,negative)
 if trap and negative:
  log=(work/'run.log').read_text();rows=re.findall(r'TRAP_MISMATCH timing=(\d+) mode=(\d+) wbkind=(\d+) expected=([0-9a-f]+) actual=([0-9a-f]+) cause=([0-9a-f]+)',log)
  assert len(rows)==result['checks_failed'] and result['checks_passed']>=128
  assert all(int(mode)<3 and int(actual,16)==0x80000040 and int(cause,16)==[0x8000000000000007,2,1][int(mode)] for t,mode,k,expected,actual,cause in rows),'Negative control did not isolate stale vector PC'
 result.update(build=build,generated_sha256=sha(sv),driver_sha256=sha(driver));write(work/'result.json',result);return result

def saturn(sv,work,jobs,negative):
 work.mkdir(parents=True,exist_ok=False);results=[]
 for extended,total in [(False,1008),(True,39984)]:
  out=work/('extended' if extended else 'basic');s=prepare(sv,out);(out/'zero_inputs.h').write_text(zero_inputs(ports(s,'VectorMemUnit')))
  driver=HERE/'saturn_mem_order'/('page_bounds_extended.cpp' if extended else 'page_bounds.cpp')
  binary,build=compile_model(sv,out,'VectorMemUnit','VMem',driver,jobs)
  result=check_run(binary,out,r'^SATURN_PAGE_BOUNDS checks=(\d+) passed=(\d+) failed=(\d+)$',total,negative)
  result.update(build=build,generated_sha256=sha(sv),driver_sha256=sha(driver));write(out/'result.json',result);results.append(result)
 write(work/'result.json',{'passed':True,'negative_control':negative,'cases':results});return results

def cache_wrapper(s):
 # Select the scalar L1 and its real arbiter by the RocketTile instance wiring.
 a,b,e=module(s,'RocketTile');tile=s[b:e]
 caches=re.findall(r'\b(NonBlockingDCache(?:_\d+)?)\s+dcache\s*\(',tile)
 arbiters=re.findall(r'\b(HellaCacheArbiter(?:_\d+)?)\s+dcacheArb\s*\(',tile)
 if len(caches)!=1 or len(arbiters)!=1:raise ValueError('Ambiguous RocketTile cache/arbiter: '+repr((caches,arbiters)))
 cache,arb=caches[0],arbiters[0]
 probes={'dbg_s2valid':('','s2_valid_masked'),'dbg_s2replay':('','s2_replay'),'dbg_s2addr':('[39:0]','s2_req_addr'),'dbg_s2data':('[63:0]','s2_req_data'),'dbg_s2hit':('','s2_hit'),'dbg_s3valid':('','s3_valid'),'dbg_s3addr':('[39:0]','s3_req_addr'),'dbg_s3data':('[63:0]','s3_req_data'),'dbg_s3way':('[3:0]','s3_way')}
 s=observe(s,cache,probes);cp=ports(s,cache);ap=ports(s,arb);cpd={n:(d,w) for d,w,n in cp};apd={n:(d,w) for d,w,n in ap}
 other='io_requestor_2_' if any(n.startswith('io_requestor_2_') for n in apd) else 'io_requestor_0_'
 assert any(n.startswith(other) for n in apd),'Missing second requestor'
 external=cp+[(d,w,n.replace(other,'io_vec_')) for d,w,n in ap if n.startswith(other)]
 wires=[];cc=[];assigns=[];ac=[]
 for d,w,n in cp:
  suffix=n.removeprefix('io_cpu_');mem='io_mem_'+suffix;req='io_requestor_1_'+suffix
  if n.startswith('io_cpu_') and mem in apd:
   wires.append('wire '+w+'cache_'+suffix+';');cc.append('.'+n+'(cache_'+suffix+')')
   if req not in apd and d=='output':assigns.append('assign '+n+' = cache_'+suffix+';')
  else:cc.append('.'+n+'('+n+')')
 for d,w,n in ap:
  if n in ['clock','reset']:v=n
  elif n.startswith('io_mem_'):
   v='cache_'+n.removeprefix('io_mem_');assert 'io_cpu_'+n.removeprefix('io_mem_') in cpd
  elif n.startswith('io_requestor_1_'):
   v=n.replace('io_requestor_1_','io_cpu_');assert v in cpd
  elif n.startswith(other):v=n.replace(other,'io_vec_')
  elif n.startswith('io_requestor_0_'):v="'0" if d=='input' else ''
  else:raise ValueError('Unexpected arbiter port '+n)
  ac.append('.'+n+'('+v+')')
 wrapper='\nmodule ArbitratedCache(\n'+',\n'.join(d+' '+w+n for d,w,n in external)+'\n);\n'+'\n'.join(wires+assigns)+'\n'+cache+' cache(\n'+',\n'.join(cc)+'\n);\n'+arb+' arb(\n'+',\n'.join(ac)+'\n);\nendmodule\n'
 return s+wrapper,external,{'cache_module':cache,'arbiter_module':arb,'secondary_requestor':other,'wrapper_sha256':__import__('hashlib').sha256(wrapper.encode()).hexdigest()}

def cache(sv,work,jobs,negative):
 s=prepare(sv,work);wrapped,p,selection=cache_wrapper(s);model=work/'observed.sv';model.write_text(wrapped);write(work/'selection.json',selection)
 results=[]
 suites=[('minimal',1),('overlap',26880),('eviction',26880)]
 if not negative:suites += [('arbitration',6720),('backpressure',9600)]
 for name,total in suites:
  out=work/name;out.mkdir();(out/'zero_inputs.h').write_text(zero_inputs(p))
  binary,build=compile_model(model,out,'ArbitratedCache','VCache',HERE/'cache'/(name+'.cpp'),jobs)
  modes=[('normal',[])]
  if name=='overlap' and not negative:modes.append(('missing-store',['--negative']))
  for label,extra in modes:
   execution=command([binary,*extra],out/(label+'.log'),timeout=900,expect=None);log=(out/(label+'.log')).read_text();lines=re.findall(r'^RESULT (.*)$',log,re.M);assert len(lines)==1
   counts={k:int(v) for k,v in re.findall(r'(\w+)=(\d+)',lines[0])}
   if extra:ok=execution['exit_code']==0 and counts['failures']==counts['expected_failures']==total
   elif negative:ok=execution['exit_code']==1 and counts['failures']>0 and counts['failures']==counts['expected_failures'] and 'counter expected 1 actual 0' in log
   else:ok=execution['exit_code']==0 and counts['failures']==0
   ok=ok and counts['tests']==total
   result={'suite':name,'mode':label,'passed':ok,'negative_control':negative,'counts':counts,'build':build,'execution':execution,'generated_sha256':sha(sv),'driver_sha256':sha(HERE/'cache'/(name+'.cpp'))};results.append(result);write(work/'result.json',{'passed':all(x['passed'] for x in results),'cases':results})
   if not ok:raise RuntimeError('Cache gate failed: '+str(out)+' '+label)
 return results

def shuttle(sv,work,jobs,negative):
 s=prepare(sv,work)
 probes={'dbg_exception':('','csr_io_exception'),'dbg_cause':('[63:0]','csr_io_cause'),'dbg_done':('[63:0]','iregfile_31'),'dbg_result':('[63:0]','iregfile_10'),'dbg_trap_count':('[63:0]','iregfile_18')}
 s=observe(s,'ShuttleCore',probes);(work/'observed.sv').write_text(s);(work/'zero_inputs.h').write_text(zero_inputs(ports(s,'ShuttleCore')))
 prefix=ROOT/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-';bins=[];firmware=[]
 for reject in (0,1):
  for sqrt in (0,1):
   name=f'reject{reject}-sqrt{sqrt}';elf=work/(name+'.elf');binary=work/(name+'.bin')
   command([str(prefix)+'gcc','-march=rv64gc','-mabi=lp64d','-mcmodel=medany','-nostdlib','-nostartfiles','-Wl,-Ttext=0x80000000','-Wl,--build-id=none',f'-DREJECT={reject}',f'-DSQRT={sqrt}',HERE/'shuttle_fdiv/rejected.S','-o',elf],work/(name+'-build.log'))
   command([str(prefix)+'objcopy','-O','binary','--only-section=.text',elf,binary],work/(name+'-objcopy.log'))
   command([str(prefix)+'objdump','-d',elf],work/(name+'.dis'))
   bins.append(binary);firmware.append({'file':str(elf),'sha256':sha(elf)})
 driver=HERE/'shuttle_fdiv/run.cpp';binary,build=compile_model(work/'observed.sv',work,'ShuttleCore','VShuttle',driver,jobs)
 result=check_run(binary,work,r'^SHUTTLE_FDIV cases=(\d+) passed=(\d+) failed=(\d+)$',12,negative,bins)
 if negative:
  rows=re.findall(r'SHUTTLE_CASE reject=(\d+) sqrt=(\d+) bubbles=(\d+) expected=([0-9a-f]+) actual=([0-9a-f]+) traps=(\d+) pass=(\d+)',(work/'run.log').read_text())
  assert len(rows)==12,'Incomplete bare-metal control cases'
  assert all(ok=='1' or (reject=='1' and traps=='1' and actual!=expected) for reject,sqrt,bubbles,expected,actual,traps,ok in rows),'Unexpected negative-control failure'
 result.update(build=build,firmware=firmware,generated_sha256=sha(sv),driver_sha256=sha(driver));write(work/'result.json',result);return result
