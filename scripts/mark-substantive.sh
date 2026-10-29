#!/usr/bin/env bash
# PreToolUse hook on YouTrack write tools (any MCP server name): mark this session substantive.
#
# A session that only writes to YouTrack (an ADR in the KB, resolved issues) leaves no
# commit, so the Stop hook could not see it. This marker lets stop-handoff-check.sh ask for
# a handoff after YouTrack writes too. Read-only YouTrack tools do not fire it.
# The matcher in .claude/settings.json lists the YouTrack write tools by name, under any
# server name; re-check it when the YouTrack MCP server adds a write tool.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/session-lib.sh"

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
sid="$(tessera_session_id)"
state="$(tessera_handoff_dir "$root")/.state"
mkdir -p "$state"
touch "$state/$sid.substantive"
