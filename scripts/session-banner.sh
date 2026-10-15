#!/usr/bin/env bash
# SessionStart hook: the orientation banner. Prints, in order:
#   1. ssh-agent self-heal (macOS only; signed commits hang on an empty agent);
#   2. open Dependabot alerts, from the API, never from a note;
#   3. git state: branch, changed files, ahead/behind;
#   4. the latest handoff's three action sections (START HERE, Next Session Should,
#      Things to Watch For), with a STALE warning when commits postdate it.
# Housekeeping: writes this session's start marker for stop-handoff-check.sh, and moves
# all but the newest 10 handoffs into .handoffs/archive/ once there are more than 20.
# .handoffs/ is the main checkout's folder, shared by worktree sessions (session-lib.sh).
# Every network or git failure is silent: the banner is an aid, never a blocker.
# TESSERA_BANNER_OFFLINE=1 skips the network (tests).

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/session-lib.sh"

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
sid="$(tessera_session_id)"
hd="$(tessera_handoff_dir "$root")"

mkdir -p "$hd/.state"
find "$hd/.state" -type f -mtime +7 -exec rm -f {} + 2>/dev/null || true
rm -f "$hd/.state/$sid.reminded" "$hd/.state/$sid.substantive"
touch "$hd/.state/$sid.started"

# Archive: keep the newest 10 top-level handoffs once there are more than 20.
count=$(ls -1 "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | wc -l | tr -d ' ' || true)
if [ "${count:-0}" -gt 20 ]; then
  mkdir -p "$hd/archive"
  ls -1 "$hd"/SESSION-HANDOFF-*.md | sort -r | tail -n +11 | while IFS= read -r f; do
    mv "$f" "$hd/archive/"
  done
fi

if [ "$(uname -s)" = "Darwin" ] && ! ssh-add -l >/dev/null 2>&1; then
  ssh-add --apple-load-keychain >/dev/null 2>&1 || true
  if ! ssh-add -l >/dev/null 2>&1; then
    echo "WARNING: ssh-agent is empty; the first signed commit will hang. Ask Padre to run: ssh-add --apple-load-keychain"
    echo ""
  fi
fi

if [ "${TESSERA_BANNER_OFFLINE:-0}" != "1" ]; then
  alerts=$(cd "$root" && gh api 'repos/{owner}/{repo}/dependabot/alerts?state=open&per_page=100' --cache 1h --jq length 2>/dev/null || true)
  if [ -n "${alerts:-}" ] && [ "$alerts" -gt 0 ] 2>/dev/null; then
    echo "Open Dependabot alerts: $alerts (source: gh api; trust it over any note)"
    echo ""
  fi
fi

status=$(git -C "$root" status --porcelain=v1 -b 2>/dev/null || true)
if [ -n "$status" ]; then
  branch=$(printf '%s\n' "$status" | head -1 | sed 's/^## //')
  changed=$(printf '%s\n' "$status" | tail -n +2 | grep -c . || true)
  echo "Git: $branch; ${changed:-0} changed file(s)"
  echo ""
fi

latest=$(ls -1 "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | sort -r | head -1 || true)
[ -n "$latest" ] || exit 0

echo "=== LATEST SESSION HANDOFF ==="
echo "From: $(basename "$latest") (full file: $latest)"
# mtime epoch: GNU stat first (on GNU, `stat -f` exits 0 with a filesystem report).
# +1: --since is inclusive, and a commit in the same second does not postdate the handoff.
h_epoch=$(stat -c %Y "$latest" 2>/dev/null || stat -f %m "$latest" 2>/dev/null || echo 0)
stale=$(git -C "$root" log --oneline --since="@$((h_epoch + 1))" 2>/dev/null | wc -l | tr -d ' ' || true)
if [ "${stale:-0}" -gt 0 ]; then
  echo "STALE: ${stale} commit(s) postdate this handoff; trust git log and status over it."
fi
echo ""
# A column-0 code fence toggles `fence`, so a "## " line inside an example neither ends
# nor starts a section. Renaming a heading means changing this pattern, the template, and
# scripts/test/session-banner.test.sh together.
awk '
  /^```/ { if (g) print; fence = !fence; next }
  !fence && (/^## (.* )?START HERE/ || /^## Next Session Should/ || /^## Things to Watch For/) { g=1; print; next }
  !fence && /^## / { g=0; next }
  g { print }
' "$latest"
echo "=============================="
