#!/usr/bin/env bash
# Stop hook: a substantive session ends with a handoff. Asks once, never loops.
#
#   - session-banner.sh writes .handoffs/.state/<session>.started at SessionStart.
#   - Substantive = a commit since that marker, or a YouTrack write this session
#     (.state/<session>.substantive, written by mark-substantive.sh).
#   - Satisfied when any SESSION-HANDOFF-*.md is newer than the marker.
#   - Asks at most once per session (.state/<session>.reminded).
# .handoffs/ is the main checkout's folder, shared by worktree sessions (session-lib.sh).
#
# Output on trigger: the Stop-hook JSON {"decision":"block","reason":...} on stdout.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/session-lib.sh"

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
sid="$(tessera_session_id)"
hd="$(tessera_handoff_dir "$root")"
marker="$hd/.state/$sid.started"
flag="$hd/.state/$sid.reminded"

[ -f "$marker" ] || exit 0
[ -f "$flag" ] && exit 0

newest=$(ls -t "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | head -1 || true)
if [ -n "$newest" ] && [ "$newest" -nt "$marker" ]; then
  exit 0
fi

# mtime epoch: GNU stat first (on GNU, `stat -f` exits 0 with a filesystem report).
since=$(stat -c %Y "$marker" 2>/dev/null || stat -f %m "$marker" 2>/dev/null || echo 0)
commits=$(git -C "$root" log --oneline --since="@${since}" 2>/dev/null | wc -l | tr -d ' ' || true)
yt=""
[ -f "$hd/.state/$sid.substantive" ] && yt="yes"

if [ "${commits:-0}" -ge 1 ] && [ -n "$yt" ]; then
  what="${commits} commit(s) and YouTrack write(s)"
elif [ "${commits:-0}" -ge 1 ]; then
  what="${commits} commit(s)"
elif [ -n "$yt" ]; then
  what="YouTrack write(s) (no git trace; the handoff is this session's only local record)"
else
  exit 0
fi

touch "$flag"
cat <<JSON
{"decision":"block","reason":"This session made ${what} and has no handoff yet (CLAUDE.md \"The Work Loop\", step 5). Write .handoffs/SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md per .claude/session-handoff-template.md, or say the session is still mid-work and continue. This reminder fires once per session."}
JSON
exit 0
