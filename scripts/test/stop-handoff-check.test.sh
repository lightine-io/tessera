#!/usr/bin/env bash
# Test for the Stop-hook handoff reminder in scripts/stop-handoff-check.sh.
# Hermetic: throwaway project dir with its own git repo; never reads or mutates
# the real repo. Run: bash scripts/test/stop-handoff-check.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../stop-handoff-check.sh"
fail=0

assert_contains() { case "$1" in *"$2"*) ;; *) echo "  FAIL: expected to CONTAIN: $2"; fail=1;; esac; }
assert_absent()   { case "$1" in *"$2"*) echo "  FAIL: expected ABSENT:   $2"; fail=1;; *) ;; esac; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.handoffs"
git -C "$tmp" init -q
gitc() { git -C "$tmp" -c user.email=t@t -c user.name=t -c commit.gpgsign=false "$@"; }
run() { CLAUDE_PROJECT_DIR="$tmp" bash "$hook"; }

echo "Test 1: no session marker -> silent"
out="$(run)"
assert_absent "$out" "decision"

echo "Test 2: marker, but no commits and no YouTrack writes -> silent"
touch "$tmp/.handoffs/.session-started"
out="$(run)"
assert_absent "$out" "decision"

echo "Test 3: YouTrack writes only (no commits) -> block, names YouTrack"
touch "$tmp/.handoffs/.substantive"
out="$(run)"
assert_contains "$out" '"decision":"block"'
assert_contains "$out" "YouTrack write(s)"
assert_absent   "$out" "commit(s) and"       # not the combined wording

echo "Test 4: reminder fires at most once per session"
out="$(run)"                                  # .handoff-reminded now set by Test 3
assert_absent "$out" "decision"

echo "Test 5: commits only -> block with commit count"
rm -f "$tmp/.handoffs/.handoff-reminded" "$tmp/.handoffs/.substantive"
sleep 1                                       # commit strictly after the marker second
gitc commit -q --allow-empty -m "work"
out="$(run)"
assert_contains "$out" '"decision":"block"'
assert_contains "$out" "1 commit(s)"
assert_absent   "$out" "YouTrack"

echo "Test 6: commits AND YouTrack writes -> combined wording"
rm -f "$tmp/.handoffs/.handoff-reminded"
touch "$tmp/.handoffs/.substantive"
out="$(run)"
assert_contains "$out" "1 commit(s) and YouTrack write(s)"

echo "Test 7: handoff newer than the marker -> satisfied, silent"
rm -f "$tmp/.handoffs/.handoff-reminded"
sleep 1
touch "$tmp/.handoffs/SESSION-HANDOFF-2026-02-02-1200-done.md"
out="$(run)"
assert_absent "$out" "decision"

if [ "$fail" -eq 0 ]; then
  echo "ALL PASS"
else
  echo "FAILURES ABOVE"
  exit 1
fi
