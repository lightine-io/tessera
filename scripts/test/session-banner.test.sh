#!/usr/bin/env bash
# Tests for scripts/session-banner.sh. Hermetic: throwaway folders, network off.
# Run: bash scripts/test/session-banner.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../session-banner.sh"
fail=0
export TESSERA_BANNER_OFFLINE=1

assert_contains() { case "$1" in *"$2"*) ;; *) echo "  FAIL: expected to CONTAIN: $2"; fail=1;; esac; }
assert_absent()   { case "$1" in *"$2"*) echo "  FAIL: expected ABSENT:   $2"; fail=1;; *) ;; esac; }
gitc() { git -C "$1" -c user.email=t@t -c user.name=t -c commit.gpgsign=false "${@:2}"; }
run() { printf '{"session_id":"%s"}' "$2" | CLAUDE_PROJECT_DIR="$1" bash "$hook"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.handoffs"

cat > "$tmp/.handoffs/SESSION-HANDOFF-2026-01-01-0900-older.md" <<'EOF'
# Session Handoff - older
## START HERE
- OLD-STATE must not appear
EOF
newer="$tmp/.handoffs/SESSION-HANDOFF-2026-02-02-1200-newer.md"
cat > "$newer" <<'EOF'
# Session Handoff - newer
## ⭐ START HERE
- NEW-STATE main at deadbeef, clean tree
## What This Session Did
- LATER-SECTION must not leak
## Next Session Should
1. NEXT-ACTION do the thing
```
## FENCED-HEADING inside a code block must not end the section
```
2. SECOND-ACTION after the fence
## Things to Watch For
- WATCH-ITEM the trap
## Other
- HIDDEN-SECTION must not leak
EOF

echo "Test 1: newest handoff, three sections only; marker written for this session"
out="$(run "$tmp" s1)"
assert_contains "$out" "newer.md"
assert_contains "$out" "NEW-STATE main at deadbeef"
assert_contains "$out" "NEXT-ACTION do the thing"
assert_contains "$out" "FENCED-HEADING"
assert_contains "$out" "SECOND-ACTION after the fence"
assert_contains "$out" "WATCH-ITEM the trap"
assert_absent   "$out" "LATER-SECTION"
assert_absent   "$out" "HIDDEN-SECTION"
assert_absent   "$out" "OLD-STATE"
assert_absent   "$out" "STALE"
[ -f "$tmp/.handoffs/.state/s1.started" ] || { echo "  FAIL: no marker for s1"; fail=1; }

echo "Test 2: no handoffs -> no banner"
mkdir -p "$tmp/empty"
out="$(run "$tmp/empty" s2)"
assert_absent "$out" "LATEST SESSION HANDOFF"

echo "Test 3: git state line, and STALE when commits postdate the handoff"
git -C "$tmp" init -q
touch -t 202601010000 "$newer"
printf '.handoffs/\n' > "$tmp/.gitignore"
git -C "$tmp" add .gitignore
gitc "$tmp" commit -q -m "postdates the handoff"
touch "$tmp/dirty.txt"
out="$(run "$tmp" s3)"
assert_contains "$out" "STALE: 1 commit(s) postdate this handoff"
assert_contains "$out" "Git: "
assert_contains "$out" "1 changed file(s)"

echo "Test 4: a handoff newer than the last commit -> no STALE"
sleep 1
touch "$newer"
out="$(run "$tmp" s4)"
assert_absent "$out" "STALE"

echo "Test 5: a worktree session reads the main checkout's handoffs"
gitc "$tmp" worktree add -q "$tmp/wt" -b wt-branch
out="$(run "$tmp/wt" s5)"
assert_contains "$out" "NEW-STATE main at deadbeef"
[ -f "$tmp/.handoffs/.state/s5.started" ] || { echo "  FAIL: worktree marker not in the main checkout"; fail=1; }

echo "Test 6: more than 20 handoffs -> newest 10 stay, the rest move to archive/"
arch="$(mktemp -d)"
mkdir -p "$arch/.handoffs"
for i in $(seq -w 1 22); do touch "$arch/.handoffs/SESSION-HANDOFF-2026-03-${i}-0900-x.md"; done
run "$arch" s6 >/dev/null
top=$(ls -1 "$arch/.handoffs"/SESSION-HANDOFF-*.md | wc -l | tr -d ' ')
archived=$(ls -1 "$arch/.handoffs/archive"/SESSION-HANDOFF-*.md | wc -l | tr -d ' ')
[ "$top" = "10" ] || { echo "  FAIL: expected 10 kept, got $top"; fail=1; }
[ "$archived" = "12" ] || { echo "  FAIL: expected 12 archived, got $archived"; fail=1; }
[ -f "$arch/.handoffs/SESSION-HANDOFF-2026-03-22-0900-x.md" ] || { echo "  FAIL: newest moved"; fail=1; }
rm -rf "$arch"

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
