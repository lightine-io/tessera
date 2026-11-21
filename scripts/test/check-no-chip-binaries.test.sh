#!/usr/bin/env bash
# Tests for scripts/check-no-chip-binaries.sh. Run: bash scripts/test/check-no-chip-binaries.test.sh
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../check-no-chip-binaries.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
expect() { # expect <code> <label>
  bash "$script" "$tmp" >/dev/null 2>&1; code=$?
  if [ "$code" -ne "$1" ]; then echo "FAIL: $2 (exit $code, wanted $1)"; fail=1; else echo "ok: $2"; fi
}
res="$tmp/emrtd-core/src/commonTest/resources"
mkdir -p "$res" "$tmp/mrz-core/src/commonTest/resources"
printf 'hex 60145F01\n' >"$res/vectors.txt"
expect 0 "text resource passes"
printf '\x00\x01\xff' >"$tmp/mrz-core/src/commonTest/resources/x.bin"
expect 0 "other modules are not covered"
printf 'text' >"$res/ef.sod"
expect 1 "extension .sod fails"
rm "$res/ef.sod"; printf '\x00\x01\xff\xfe' >"$res/blob"
expect 1 "binary content fails"
rm "$res/blob"; printf 'x' >"$res/EF.DG2"
expect 1 "extension .DG2 (upper case) fails"
exit "$fail"
