#!/usr/bin/env python3
"""Verify pinned input provenance and configure the isolated build recipe."""
import argparse
import json
import shutil
import subprocess
from pathlib import Path
from common import ROOT,TARGET,BASELINE,MODEL_REVISION,evidence,git,require,save,source_manifest

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,required=True)
    p.add_argument('--model-root',type=Path,default=Path('/scratch/prashanth_ampere_optimized_20261021/gpu-model'))
    p.add_argument('--model',type=Path,default=Path('/scratch/prashanth_ampere_optimized_20261021/build/model/gpu_model_socket'))
    a=p.parse_args(); w=a.work.resolve()
    require(ROOT.resolve()==w/'chipyard','Checkout must live in the isolated work directory')
    subprocess.run(['git','-C',str(ROOT),'merge-base','--is-ancestor',BASELINE,'HEAD'],check=True)
    fixed={}
    for component in ('rocket-chip','saturn','shuttle','gemmini','hardfloat'):
        rel='generators/'+component
        pin=git(ROOT,'ls-tree',BASELINE,rel).split()[2]
        actual=git(ROOT/rel,'rev-parse','HEAD')
        require(actual==pin,'Processor/accelerator pin changed: '+rel)
        require(not git(ROOT/rel,'diff','HEAD','--','src'),'Processor/accelerator source changed: '+rel)
        fixed[rel]=actual
    require(git(a.model_root,'rev-parse','HEAD')==MODEL_REVISION,'GPU model revision changed')
    require(not git(a.model_root,'status','--porcelain','--untracked-files=no'),'GPU model has tracked edits')
    prior=Path('/scratch/prashanth_ampere_optimized_20261021/results/optimized-optimized/run.json')
    provenance=json.loads(prior.read_text())
    model=evidence(a.model)
    require(provenance['status']=='passed' and model['sha256']==provenance['binary_sha256']['model'],
            'Existing model executable does not match pinned previously validated binary')
    require(shutil.disk_usage(w).free>=100*2**30,'Less than 100 GiB free scratch')
    vivado=subprocess.check_output(['/ecad/tools/xilinx/Vivado/2022.1/bin/vivado','-version'],text=True)
    require('Vivado v2022.1' in vivado,'Expected Vivado 2022.1')
    for name in ('control','logs','results','tmp','fixtures','builds'): (w/name).mkdir(parents=True,exist_ok=True)
    alias='alveo_u250_quad_rocket_int8_gemmini_saturn_trafficgen_l2perf_30mhz'
    save(w/'control/config_build.yaml',{'build_farm':{'base_recipe':'build-farm-recipes/externally_provisioned.yaml',
      'recipe_arg_overrides':{'default_build_dir':str(w/'builds'),'build_farm_hosts':['localhost']}},
      'builds_to_run':[alias],'agfis_to_share':[],'share_with_accounts':{}})
    save(w/'control/config_build_recipes.yaml',{alias:{'PLATFORM':'xilinx_alveo_u250','TARGET_PROJECT':'firesim',
      'TARGET_PROJECT_MAKEFRAG':str(ROOT/'generators/firechip/chip/src/main/makefrag/firesim'),
      'DESIGN':'FireSim','TARGET_CONFIG':TARGET,'PLATFORM_CONFIG':'BaseXilinxAlveoU250Config',
      'deploy_quintuplet':None,'platform_config_args':{'fpga_frequency':30,'build_strategy':'NORETIMING'},
      'post_build_hook':None,'metasim_customruntimeconfig':None,
      'bit_builder_recipe':str(ROOT/'sims/firesim/deploy/bit-builder-recipes/xilinx_alveo_u250.yaml')}})
    save(w/'control/config_hwdb.yaml',{})
    old=Path('/scratch/prashanth_ampere_optimized_20261021/fixtures')
    for name in ('registry.json','hardware.config'): shutil.copy2(old/name,w/'fixtures'/name)
    registry=json.loads((w/'fixtures/registry.json').read_text())
    nn=next(k for k in registry['kernels'] if k['registryId']==1106)
    require(Path(nn['tracePath']).is_file() and Path(nn['dataRoot']).is_dir(),'Rodinia NN1 trace unavailable')
    tools={'vivado':vivado}
    for name,command in {'python':['python3','--version'],
                         'verilator':[str(ROOT/'.conda-env/bin/verilator'),'--version'],
                         'riscv_gcc':[str(ROOT/'.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc'),'--version'],
                         'g++':['g++','--version'],'make':['make','--version'],
                         'java':[str(ROOT/'.conda-env/bin/java'),'-version']}.items():
        tools[name]=subprocess.check_output(command,text=True,stderr=subprocess.STDOUT)
    save(w/'results/preflight.json',{'status':'passed','source':source_manifest(ROOT),
      'model_revision':MODEL_REVISION,'model':model,'model_provenance':evidence(prior),
      'preserved_processor_accelerator_pins':fixed,
      'tools':tools,
      'fpga_programmed':False,'rodinia':nn,'recipe':evidence(w/'control/config_build_recipes.yaml')})

if __name__=='__main__': main()
