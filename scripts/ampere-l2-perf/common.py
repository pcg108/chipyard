"""Shared identities and evidence helpers for the isolated Ampere L2 build."""
import hashlib
import json
import subprocess
from pathlib import Path

TARGET = 'FireSimQuadRocketInt8GemminiSaturnTrafficGenL2PerfConfig'
QUINTUPLET = 'xilinx_alveo_u250-firesim-FireSim-' + TARGET + '-BaseXilinxAlveoU250Config'
BASELINE = 'd0646221af1361919fa13b2a43584eeca8db4dd9'
MODEL_REVISION = '44c13392a6b05e359dba56dc3e8b4cfc2a00c395'
ROOT = Path(__file__).resolve().parents[2]

class ValidationFailure(RuntimeError):
    pass

def require(value, message):
    if not value:
        raise ValidationFailure(message)

def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()

def evidence(path):
    path = Path(path).resolve()
    return {'path': str(path), 'sha256': digest(path)}

def generated_dir(root=ROOT, target=TARGET):
    return Path(root)/'sims/firesim/sim/generated-src/xilinx_alveo_u250'/QUINTUPLET

def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)

def git(repo, *arguments):
    return subprocess.check_output(['git', '-C', str(repo), *arguments], text=True).strip()

def submodule_records(root):
    """Walk actual gitlinks, including manually cloned/unregistered modules."""
    root=Path(root).resolve();records=[];visited=set()
    def walk(repo):
        resolved=repo.resolve()
        if resolved in visited:return
        visited.add(resolved)
        entries=subprocess.check_output(['git','-C',str(repo),'ls-tree','-rz','HEAD']).split(b'\0')
        for entry in entries:
            if not entry:continue
            metadata,name=entry.split(b'\t',1)
            mode,kind,pin=metadata.decode().split()
            if mode!='160000':continue
            child=repo/name.decode();rel=str(child.relative_to(root))
            record={'path':rel,'declared_pin':pin,'initialized':(child/'.git').exists()}
            if record['initialized']:
                record['actual_head']=git(child,'rev-parse','HEAD')
            records.append(record)
            if record['initialized']:walk(child)
    walk(root)
    return sorted(records,key=lambda item:item['path'])

def source_manifest(root):
    # Record every checked-out submodule plus tracked and untracked source edits.
    submodules = submodule_records(root)
    diff = subprocess.check_output(['git', '-C', str(root), 'diff', '--binary', 'HEAD'])
    paths = git(root, 'ls-files', '--others', '--exclude-standard').splitlines()
    untracked = {p: digest(Path(root)/p) for p in paths if (Path(root)/p).is_file()
                 and p.split('/')[0] in ('generators','scripts','tests','project')
                 and not p.endswith(('.elf', '.o', '.pyc'))}
    component_edits = {}
    for entry in submodules:
        rel=entry['path']; repo=Path(root)/rel
        if not (repo/'.git').exists():
            component_edits[rel]={'uninitialized':True}
            continue
        patch=subprocess.check_output(['git','-C',str(repo),'diff','--binary','HEAD'])
        new_paths=git(repo,'ls-files','--others','--exclude-standard').splitlines()
        component_edits[rel]={'actual_head':entry['actual_head'],'patch_sha256':hashlib.sha256(patch).hexdigest(),
          'untracked':{p:digest(repo/p) for p in new_paths if (repo/p).is_file()
                       and not p.endswith(('.elf','.o','.pyc','.log'))}}
    return {'baseline': BASELINE, 'head': git(root, 'rev-parse', 'HEAD'),
            'branch': git(root, 'branch', '--show-current'), 'submodules': submodules,
            'tracked_diff_sha256': hashlib.sha256(diff).hexdigest(), 'untracked': untracked,
            'component_edits':component_edits}
