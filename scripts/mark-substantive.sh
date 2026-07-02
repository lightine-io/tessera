#!/usr/bin/env bash
# PreToolUse hook (mcp__youtrack__ write tools): mark this session substantive.
#
# Why this exists: scripts/stop-handoff-check.sh originally defined "substantive" as
# ≥1 git commit — but decision-only sessions (write an ADR in the KB, resolve issues,
# file follow-ups) leave no git trace, and those are exactly the sessions whose only
# durable local record IS the handoff. This flag lets the Stop hook remind about a
# handoff for YouTrack-write sessions too.
#
# Touched on any mcp__youtrack__ write-tool call (matcher in .claude/settings.json);
# cleared at SessionStart by scripts/standing-obligations.sh. Read-only YouTrack tools
# (get_*/search_*/find_*) intentionally do NOT fire this — reading is not substance.
#
# NOTE: the settings.json matcher regex is a manually-maintained allowlist mirror of
# the YouTrack MCP server's write-tool verbs (create|update|add|change|link|log|manage).
# If the server ships a write tool with a new verb, the flag silently stops covering it
# — re-check the matcher whenever the YouTrack MCP server is updated.
set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
mkdir -p "$root/.handoffs"
touch "$root/.handoffs/.substantive"
