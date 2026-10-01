#!/usr/bin/env bash
# Install the tracked workload sources without changing the FireMarshal gitlink.
set -euo pipefail

if [[ $# -gt 1 || ${1:-} == --help || ${1:-} == -h ]]; then
  echo "usage: $0 [FIREMARSHAL_DIR]"
  echo "Copies only TrafficGen source/config files; preserves built binaries and other files."
  echo "Differing existing sources are backed up under TMPDIR (default /tmp)."
  [[ $# -le 1 ]] && exit 0 || exit 2
fi

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_root=$repo/scripts/trafficgen/workloads
marshal_root=${1:-$repo/software/firemarshal}
if [[ ! -f "$marshal_root/marshal" || ! -d "$marshal_root/test/bare" ]]; then
  echo "Not an initialized FireMarshal checkout: $marshal_root" >&2
  exit 1
fi
marshal_root=$(cd -- "$marshal_root" && pwd)
destination_root=$marshal_root/example-workloads
files=(
  trafficgen-hello.yaml
  trafficgen-hello/hello.c
  trafficgen-hello/trafficgen.h
  trafficgen-hello/mmio.h
  trafficgen-hello/Makefile
  trafficgen-hello/build.sh
)

# Check every path before backing up or replacing any source.
for relative in "${files[@]}"; do
  if [[ ! -f "$source_root/$relative" ]]; then
    echo "Missing packaged source: $source_root/$relative" >&2
    exit 1
  fi
  destination=$destination_root/$relative
  if [[ -L "$destination" || ( -e "$destination" && ! -f "$destination" ) ]]; then
    echo "Refusing to replace a symlink or non-file: $destination" >&2
    exit 1
  fi
done

backup_root=
for relative in "${files[@]}"; do
  destination=$destination_root/$relative
  if [[ -f "$destination" ]] && ! cmp -s -- "$source_root/$relative" "$destination"; then
    if [[ -z "$backup_root" ]]; then
      backup_root=$(mktemp -d "${TMPDIR:-/tmp}/trafficgen-workload-backup.XXXXXXXX")
      printf '%s\n' "$destination_root" > "$backup_root/original-destination.txt"
    fi
    mkdir -p -- "$backup_root/$(dirname -- "$relative")"
    cp -p -- "$destination" "$backup_root/$relative"
  fi
done

copied=0
for relative in "${files[@]}"; do
  destination=$destination_root/$relative
  if [[ -f "$destination" ]] && cmp -s -- "$source_root/$relative" "$destination"; then
    continue
  fi
  mkdir -p -- "$(dirname -- "$destination")"
  cp -p -- "$source_root/$relative" "$destination"
  copied=$((copied + 1))
done

if [[ -n "$backup_root" ]]; then
  echo "Previous differing sources: $backup_root"
fi
echo "Installed $copied changed source/config files into $destination_root"
echo "Generated binaries, objects, and unrelated files were preserved."
