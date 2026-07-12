#!/usr/bin/env bash
# Stop hook: a substantive session must end with a session handoff (audit finding B5;
# CLAUDE.md "Session Discipline" — previously enforced by willpower only).
#
# Logic, self-disarming by design:
#   - .handoffs/.session-started is touched by scripts/standing-obligations.sh at
#     SessionStart (which also clears the reminded + substantive flags).
#   - "Substantive" = at least one git commit since the marker, OR a YouTrack write
#     this session (.handoffs/.substantive, touched by scripts/mark-substantive.sh via
#     the PreToolUse hook on mcp__youtrack__ write tools). Decision-only sessions —
#     "wrote an ADR in the KB, resolved issues" — leave no git trace, and those are
#     exactly the sessions whose only durable local record is the handoff.
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

# mtime epoch, portably: GNU stat first (-c %Y) — on GNU, `stat -f %m` exits 0 while
# printing a filesystem report; on BSD/macOS, `stat -c` fails cleanly to the fallback.
since=$(stat -c %Y "$marker" 2>/dev/null || stat -f %m "$marker" 2>/dev/null || echo 0)
# `|| true`: outside a git repo (hermetic tests) the pipeline must not kill the hook.
commits=$(git -C "$root" log --oneline --since="@${since}" 2>/dev/null | wc -l | tr -d ' ' || true)
yt=""
[ -f "$hd/.substantive" ] && yt="yes"

if [ "${commits:-0}" -ge 1 ] && [ -n "$yt" ]; then
  what="${commits} commit(s) and YouTrack write(s)"
elif [ "${commits:-0}" -ge 1 ]; then
  what="${commits} commit(s)"
elif [ -n "$yt" ]; then
  what="YouTrack write(s) (no git trace; the handoff is this session's only durable local record)"
else
  exit 0
fi

touch "$flag"
cat <<JSON
{"decision":"block","reason":"This session made ${what} since it started, and no session handoff has been written (CLAUDE.md Session Discipline). Write SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md in .handoffs/ per .claude/session-handoff-template.md — or state explicitly that the session is still mid-work and continue. This reminder fires only once per session."}
JSON
exit 0
