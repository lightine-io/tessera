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

newer="$tmp/.handoffs/SESSION-HANDOFF-2026-02-02-1200-newer.md"
cat > "$newer" <<'EOF'
# Session Handoff — newer
## ⭐ START HERE
- NEW-STATE main at deadbeef, clean tree
- second bullet of the live state
## What This Session Did
- LATER-SECTION must not leak into the banner
## Next Session Should
1. NEXT-ACTION do the thing
```
## FENCED-HEADING inside a code block must not end the section
```
2. SECOND-ACTION after the fence
## Things to Watch For
- WATCH-ITEM the trap
## Open Questions Touched
- HIDDEN-SECTION must not leak
EOF

echo "Test 1: injects the newest handoff's three action-bearing sections, nothing else"
# tmp is NOT a git repo here — also proves the staleness guard can't kill the hook.
out="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_contains "$out" "newer.md"                      # picked newest by filename sort
assert_contains "$out" "NEW-STATE main at deadbeef"    # START HERE present
assert_contains "$out" "second bullet of the live state"
assert_contains "$out" "NEXT-ACTION do the thing"      # Next Session Should present
assert_contains "$out" "FENCED-HEADING"                # fenced '## ' line printed as content
assert_contains "$out" "SECOND-ACTION after the fence" # section survives a fenced '## ' line
assert_contains "$out" "WATCH-ITEM the trap"           # Things to Watch For present
assert_contains "$out" "Full handoff"                  # pointer to the full file
assert_absent   "$out" "LATER-SECTION"                 # non-injected sections stay out
assert_absent   "$out" "HIDDEN-SECTION"
assert_absent   "$out" "OLD-STATE"                     # ignores the superseded handoff
assert_absent   "$out" "old stuff"
assert_absent   "$out" "STALE"                         # no git repo -> no staleness claim

echo "Test 2: no handoffs -> no banner, clean exit"
mkdir -p "$tmp/empty/.handoffs"
out2="$(CLAUDE_PROJECT_DIR="$tmp/empty" bash "$hook")"
assert_absent "$out2" "LATEST SESSION HANDOFF"

echo "Test 3: commits postdating the handoff -> STALE warning"
git -C "$tmp" init -q
touch -t 202601010000 "$newer"   # handoff mtime 2026-01-01, before the commit below
git -C "$tmp" -c user.email=t@t -c user.name=t -c commit.gpgsign=false \
  commit -q --allow-empty -m "postdates the handoff"
out3="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_contains "$out3" "STALE: 1 commit(s) postdate this handoff"

echo "Test 4: handoff newer than the last commit -> no STALE warning"
sleep 1
touch "$newer"                   # handoff mtime now after the commit
out4="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_absent "$out4" "STALE"

echo "Test 5: commit in the SAME second as the handoff write -> no false STALE"
commit_epoch="$(git -C "$tmp" log -1 --format=%ct)"
touch -t "$(date -r "$commit_epoch" +%Y%m%d%H%M.%S)" "$newer"  # mtime == commit time
out5="$(CLAUDE_PROJECT_DIR="$tmp" bash "$hook")"
assert_absent "$out5" "STALE"

if [ "$fail" -eq 0 ]; then
  echo "ALL PASS"
else
  echo "FAILURES ABOVE"
  exit 1
fi
