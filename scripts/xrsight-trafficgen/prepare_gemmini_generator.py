#!/usr/bin/env python3
"""Install pinned stock Gemmini under a private Scala namespace.

The installed main Gemmini project is an experimental MX fork required by
Radiance. Keep it intact; compile the stock FP32 generator alongside it.
"""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
PIN='8c3f9923a44a2fe2c7930587be297d6d4f8c09ca'
def main():
 p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);a=p.parse_args()
 files=subprocess.check_output(['git','-C',str(a.source),'ls-tree','-r','--name-only',PIN,'src/main/scala'],text=True).splitlines()
 records={}
 for name in files:
  if not name.endswith('.scala'):continue
  original=subprocess.check_output(['git','-C',str(a.source),'show',PIN+':'+name])
  # Keep literal resource/header paths unchanged; rename Scala references only.
  text=re.sub(r'("(?:[^"\\]|\\.)*")|\bgemmini\b',
              lambda m:m[1] if m[1] is not None else 'illixr_fp32_gemmini',original.decode())
  rel=Path(name).relative_to('src/main/scala/gemmini')
  dest=a.destination/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text)
  records[str(rel)]={'original_sha256':hashlib.sha256(original).hexdigest(),'namespaced_sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
 (a.destination/'source-manifest.json').write_text(json.dumps({'revision':PIN,'namespace':'illixr_fp32_gemmini','transformation':'Scala package/reference rename only','files':records},indent=2)+'\n')
if __name__=='__main__':main()
