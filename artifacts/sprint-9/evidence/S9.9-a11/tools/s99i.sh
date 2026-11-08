#!/usr/bin/env bash
# Ephemeral HOST-side instrumentation guard for S9.9-a11.
# Fail-closed: refuses to call adb unless the runner is the exact allowlisted isolated runner
# and -e class names exactly one fully qualified Class#method (no commas, no whole-class runs).
set -euo pipefail
RUNNER='io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner'
SPEC="${1:-}"
shift || true
RE='^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*#[A-Za-z_][A-Za-z0-9_]*$'
if [[ ! "$SPEC" =~ $RE ]]; then
  echo "S99I REFUSED: expected exactly one FQCN#method, got '${SPEC}'. Nothing was executed." >&2
  exit 64
fi
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  if [[ "${args[i]:-}" == "-e" && "${args[i+1]:-}" == "class" ]]; then
    echo "S99I REFUSED: duplicate -e class argument. Nothing was executed." >&2
    exit 64
  fi
  if [[ "${args[i]:-}" == *","* ]]; then
    echo "S99I REFUSED: comma in instrumentation arguments (multi-class run). Nothing was executed." >&2
    exit 64
  fi
done
echo "S99I RUN class=$SPEC runner=$RUNNER" >&2
exec adb shell am instrument -w -e class "$SPEC" "$@" "$RUNNER"
