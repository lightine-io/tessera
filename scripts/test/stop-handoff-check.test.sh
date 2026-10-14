#!/usr/bin/env bash
# Tests for scripts/stop-handoff-check.sh and scripts/mark-substantive.sh.
# Hermetic: a throwaway git repo. Run: bash scripts/test/stop-handoff-check.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../stop-handoff-check.sh"
mark="$here/../mark-substantive.sh"
fail=0

assert_contains() { case "$1" in *"$2"*) ;; *) echo "  FAIL: expected to CONTAIN: $2"; fail=1;; esac; }
assert_absent()   { case "$1" in *"$2"*) echo "  FAIL: expected ABSENT:   $2"; fail=1;; *) ;; esac; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q
gitc() { git -C "$tmp" -c user.email=t@t -c user.name=t -c commit.gpgsign=false "$@"; }
run()  { printf '{"session_id":"%s","hook_event_name":"Stop"}' "$1" | CLAUDE_PROJECT_DIR="$tmp" bash "$hook"; }
st="$tmp/.handoffs/.state"

echo "Test 1: no session marker -> silent"
assert_absent "$(run a)" "decision"

echo "Test 2: marker, no commits, no YouTrack writes -> silent"
mkdir -p "$st"; touch "$st/a.started"
assert_absent "$(run a)" "decision"

echo "Test 3: YouTrack writes only -> block, names YouTrack (marker via mark-substantive.sh)"
printf '{"session_id":"a"}' | CLAUDE_PROJECT_DIR="$tmp" bash "$mark"
out="$(run a)"
assert_contains "$out" '"decision":"block"'
assert_contains "$out" "YouTrack write(s)"
assert_contains "$out" 'CLAUDE.md \"The Work Loop\"'

echo "Test 4: asks at most once per session"
assert_absent "$(run a)" "decision"

echo "Test 5: another session's markers do not leak"
touch "$st/b.started"
assert_absent "$(run b)" "decision"

echo "Test 6: commits only -> block with the count"
rm -f "$st/b.reminded"
sleep 1
gitc commit -q --allow-empty -m "work"
out="$(run b)"
assert_contains "$out" "1 commit(s)"
assert_absent   "$out" "YouTrack"

echo "Test 7: a handoff newer than the marker -> satisfied"
touch "$st/c.started"; sleep 1
touch "$tmp/.handoffs/SESSION-HANDOFF-2026-02-02-1200-done.md"
assert_absent "$(run c)" "decision"

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
