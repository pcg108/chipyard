#!/usr/bin/env bash
# Fresh LRC metasim build with source-before/source-after and binary hashes.
set -eo pipefail
if [[ $# != 1 ]]; then echo "usage: $0 FRESH_BUILD_DIRECTORY" >&2; exit 2; fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
if [[ -e "$1" ]]; then echo "Build directory must not exist: $1" >&2; exit 2; fi
mkdir -p "$1"
build_root=$(realpath "$1")
python3 - "$repo" "$build_root" <<'PY'
import hashlib,json,pathlib,subprocess,sys
repo,root=map(pathlib.Path,sys.argv[1:])
paths=[]
for base in ('generators/chipyard/src','generators/firechip'):
    paths.extend(p for p in (repo/base).rglob('*') if p.is_file() and p.suffix in ('.scala','.cc','.h'))
manifest={'source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},
          'chipyard_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),
          'firesim_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo/'sims/firesim',text=True).strip()}
(root/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY
cd "$repo/sims/firesim"
source sourceme-manager.sh --skip-ssh-setup
set -euo pipefail
export JAVA_TOOL_OPTIONS="-Xmx24G -Xss8M -Djava.io.tmpdir=$build_root/java_tmp"
mkdir -p "$build_root/java_tmp"
command=(make -C "$repo/sims/firesim/sim" verilator PLATFORM=xilinx_alveo_u250 TARGET_PROJECT=firesim
  "TARGET_PROJECT_MAKEFRAG=$repo/generators/firechip/chip/src/main/makefrag/firesim"
  DESIGN=FireSim TARGET_CONFIG=FireSimRocketWithRTLTrafficGenL2PutGetNoTracerVBoundaryOnlyConfig
  PLATFORM_CONFIG=BaseXilinxAlveoU250Config "GENERATED_DIR=$build_root" "OUTPUT_DIR=$build_root/driver")
command+=(EXTRA_VERILATOR_FLAGS=--assert)
printf '%q ' "${command[@]}" > "$build_root/command.sh"
printf '\n' >> "$build_root/command.sh"
"${command[@]}" > "$build_root/build.log" 2>&1
python3 - "$build_root" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); path=root/'build-manifest.json'
manifest=json.loads(path.read_text())
changed=[p for p,h in manifest['source_sha256'].items() if hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()!=h]
manifest['sources_changed_during_build']=changed
manifest['binary_sha256']=hashlib.sha256((root/'VFireSim').read_bytes()).hexdigest()
manifest['result']='FAIL: sources changed during build' if changed else 'PASS'
path.write_text(json.dumps(manifest,indent=2)+'\n')
if changed: raise SystemExit('Sources changed during build: '+', '.join(changed))
PY
