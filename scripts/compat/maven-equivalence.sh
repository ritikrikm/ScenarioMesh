#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <project-dir> [maven-args...]" >&2
  exit 2
fi

project_dir="$1"
shift
project_dir="$(cd "$project_dir" && pwd)"
extension_file="$project_dir/.mvn/extensions.xml"
extension_backup="$project_dir/.mvn/extensions.xml.scenariomesh-equivalence"
trace_file="$project_dir/target/maven-equivalence-events.log"
native_trace="$(mktemp)"
mesh_trace="$(mktemp)"
mesh_log="$(mktemp)"

restore_extension() {
  if [ -f "$extension_backup" ]; then
    mv "$extension_backup" "$extension_file"
  fi
  rm -f "$native_trace" "$mesh_trace" "$mesh_log"
}
trap restore_extension EXIT

if [ ! -f "$extension_file" ]; then
  echo "ScenarioMesh extension file not found: $extension_file" >&2
  exit 2
fi

run_contract() {
  CONTRACT_ENV=equivalence-env \
  CONTRACT_EXCLUDED=inherited-excluded \
  CONTRACT_OVERLAY=inherited-overlay \
  mvn -B clean test "$@"
}

mv "$extension_file" "$extension_backup"
(
  cd "$project_dir"
  run_contract "$@"
)
test -f "$trace_file"
sort "$trace_file" > "$native_trace"

mv "$extension_backup" "$extension_file"
(
  cd "$project_dir"
  run_contract "$@" 2>&1 | tee "$mesh_log"
)
test -f "$trace_file"
sort "$trace_file" > "$mesh_trace"

diff -u "$native_trace" "$mesh_trace"
test -f "$project_dir/target/scenariomesh/summary.json"
grep -Fq 'MAVEN_OWNERSHIP owner=SCENARIOMESH' "$mesh_log"
if grep -Fq 'SUREFIRE_CAPSULE' "$mesh_log"; then
  echo "unexpected SUREFIRE_CAPSULE diagnostic" >&2
  exit 1
fi

echo "Maven equivalence proven for: mvn test $*"
