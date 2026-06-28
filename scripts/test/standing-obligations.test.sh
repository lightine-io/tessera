#!/usr/bin/env bash
# Test for the SessionStart handoff banner in scripts/standing-obligations.sh.
# Hermetic: builds a throwaway project dir with fixture handoffs and points
# CLAUDE_PROJECT_DIR at it, so it never reads or mutates the real repo.
# Run: bash scripts/test/standing-obligations.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../standing-obligations.sh"
fail=0

assert_contains() { case "$1" in *"$2"*) ;; *) echo "  FAIL: expected to CONTAIN: $2"; fail=1;; esac; }
assert_absent()   { case "$1" in *"$2"*) echo "  FAIL: expected ABSENT:   $2"; fail=1;; *) ;; esac; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.handoffs"

# Two handoffs: the banner must pick the newer by FILENAME sort, not mtime.
cat > "$tmp/.handoffs/SESSION-HANDOFF-2026-01-01-0900-older.md" <<'EOF'
# Session Handoff — older
## ⭐ START HERE
- OLD-STATE must not appear (this is the superseded handoff)
## What This Session Did
- old stuff
EOF

cat > "$tmp/.handoffs/SESSION-HANDOFF-2026-02-02-1200-newer.md" <<'EOF'
# Session Handoff — newer
## ⭐ START HERE
- NEW-STATE main at deadbeef, clean tree
- second bullet of the live state
## What This Session Did
- LATER-SECTION must not leak into the banner
EOF

echo "Test 1: injects the NEWEST handoff's START HERE, bounded to that section"
out="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_contains "$out" "newer.md"                      # picked newest by filename sort
assert_contains "$out" "NEW-STATE main at deadbeef"    # body of START HERE present
assert_contains "$out" "second bullet of the live state"
assert_absent   "$out" "LATER-SECTION"                 # stops before the next ## heading
assert_absent   "$out" "OLD-STATE"                     # ignores the superseded handoff
assert_absent   "$out" "old stuff"

echo "Test 2: no handoffs -> no banner, clean exit"
rm -f "$tmp/.handoffs"/SESSION-HANDOFF-*.md
out2="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_absent "$out2" "LATEST SESSION HANDOFF"

if [ "$fail" -eq 0 ]; then
  echo "ALL PASS"
else
  echo "FAILURES ABOVE"
  exit 1
fi
