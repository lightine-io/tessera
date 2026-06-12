#!/usr/bin/env bash
# SessionStart hook: surface standing obligations so no session starts blind to open
# action-plan items.
#
# Why this exists: a handoff is a snapshot of one session's view at write time — it can
# honestly say "nothing is in flight" while a deferred action-plan item's trigger has
# fired (this happened: the post-tag items in ACTION-PLAN-2026-06-04 §5 were skipped at
# a session start because the release handoff reported nothing pending). Unchecked plan
# items are open work regardless of what the latest handoff says, so they are injected
# into every session mechanically — structural, not willpower.
#
# See CLAUDE.md "What to Do First" and .claude/session-handoff-template.md
# ("Standing Obligations").
set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Session marker for the Stop-hook handoff check (scripts/stop-handoff-check.sh):
# mark when this session started and clear the once-per-session reminder flag.
mkdir -p "$root/.handoffs"
touch "$root/.handoffs/.session-started"
rm -f "$root/.handoffs/.handoff-reminded"

plans_dir="$root/.plans"
[ -d "$plans_dir" ] || exit 0

# Top-level plans only: archive/ holds completed/superseded plans by convention
# (.claude/rules/folder-organization.md), and the glob does not descend into it.
items=$(grep -Hn '^[[:space:]]*- \[ \]' "$plans_dir"/*.md 2>/dev/null || true)
[ -n "$items" ] || exit 0

echo "STANDING OBLIGATIONS — unchecked action-plan items. These are open work even if the latest handoff says nothing is in flight:"
echo "$items" | sed "s|^$root/||"
