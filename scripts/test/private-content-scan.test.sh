#!/usr/bin/env bash
# Tests for scripts/private-content-scan.sh. Hermetic: a throwaway git repo per case.
# Fixture MRZ lines are built at run time so this file never trips the guard itself.
# Run: bash scripts/test/private-content-scan.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
scan="$here/../private-content-scan.sh"
fail=0
FILL="$(printf '<%.0s' $(seq 1 12))"
MRZ="P${FILL}ABCDEFGHIJ${FILL}KLMNOP"   # 40 chars, MRZ alphabet, with fillers

repo() {
  local d; d="$(mktemp -d)"; git -C "$d" init -q; echo "$d"
}
add() { mkdir -p "$(dirname "$1/$2")"; printf '%s\n' "$3" > "$1/$2"; git -C "$1" add -A; }
expect() { # expect <code> <label> <dir> [args]
  local want="$1" label="$2" dir="$3" got=0; shift 3
  CLAUDE_PROJECT_DIR="$dir" bash "$scan" "$@" >/dev/null 2>&1 || got=$?
  if [ "$got" != "$want" ]; then echo "  FAIL [$label] want exit $want, got $got"; fail=1; fi
}

d=$(repo); add "$d" "core/src/commonMain/kotlin/A.kt" "val x = \"$MRZ\""
expect 2 "MRZ in a main source set blocks" "$d" --mrz-only

d=$(repo); add "$d" "core/src/commonTest/kotlin/T.kt" "val x = \"$MRZ\""
expect 0 "MRZ in a test source set passes" "$d" --mrz-only

d=$(repo); add "$d" "Sources/UI/V.swift" "let x = \"$MRZ\""
expect 2 "MRZ in Swift Sources blocks" "$d" --mrz-only

d=$(repo); add "$d" "README.md" "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
expect 0 "ICAO specimen passes" "$d" --mrz-only

d=$(repo); add "$d" "README.md" "$MRZ  <!-- mrz-ok -->"
expect 0 "mrz-ok marker passes" "$d" --mrz-only

d=$(repo); add "$d" "README.md" "mentions examplecorp here"
expect 0 "no terms file: terms half skipped" "$d"
mkdir -p "$d/.claude"; printf '# terms\nexamplecorp\n' > "$d/.claude/private-content-terms.local"
expect 2 "a term in a tracked file blocks" "$d"
expect 0 "--mrz-only ignores terms" "$d" --mrz-only

d=$(repo); add "$d" "README.md" "MrzInvalidData is a type name"
mkdir -p "$d/.claude"; printf 'idda\n' > "$d/.claude/private-content-terms.local"
expect 0 "documented false positive passes" "$d"

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
