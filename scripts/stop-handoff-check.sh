#!/usr/bin/env bash
# Stop hook: a substantive session must end with a session handoff (audit finding B5;
# CLAUDE.md "Session Discipline" — previously enforced by willpower only).
#
# Logic, self-disarming by design:
#   - .handoffs/.session-started is touched by scripts/standing-obligations.sh at
#     SessionStart (which also clears the reminded flag).
#   - "Substantive" = at least one git commit since the marker.
#   - Satisfied when any SESSION-HANDOFF-*.md is newer than the marker.
#   - Fires AT MOST ONCE per session (.handoffs/.handoff-reminded): the block tells
#     Claude to write the handoff (or consciously proceed if the session continues);
#     it never loops.
#
# Output: on trigger, the Stop-hook JSON {"decision":"block","reason":…} on stdout.
set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
hd="$root/.handoffs"
marker="$hd/.session-started"
flag="$hd/.handoff-reminded"

[ -f "$marker" ] || exit 0
[ -f "$flag" ] && exit 0

newest=$(ls -t "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | head -1 || true)
if [ -n "$newest" ] && [ "$newest" -nt "$marker" ]; then
  exit 0
fi

since=$(stat -f %m "$marker" 2>/dev/null || echo 0)
commits=$(git -C "$root" log --oneline --since="@${since}" 2>/dev/null | wc -l | tr -d ' ')
[ "${commits:-0}" -ge 1 ] || exit 0

touch "$flag"
cat <<JSON
{"decision":"block","reason":"This session has made ${commits} commit(s) since it started, and no session handoff has been written (CLAUDE.md Session Discipline). Write SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md in .handoffs/ per .claude/session-handoff-template.md — or state explicitly that the session is still mid-work and continue. This reminder fires only once per session."}
JSON
exit 0
