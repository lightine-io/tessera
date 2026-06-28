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
# Note (since the 2026-06 doc migration): the deferred-work backlog now lives in
# YouTrack Issues, so .plans/ is usually empty. This hook still (a) maintains the
# session markers below, (b) injects the latest handoff's START HERE block — the real
# orientation artifact now that .plans/ is usually empty, so the next session can't
# start blind to prior state — and (c) catches any *local* .plans items; the YouTrack
# board is the backlog of record (surfaced via .claude/rules/youtrack.md).
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

# Surface the latest handoff's START HERE so no session starts blind to prior state.
# This runs unconditionally (before the .plans early-exits below), because the handoff
# — not the now-usually-empty .plans sweep — is the real orientation artifact. Reads
# the file live each run, so it can never drift from what was actually written.
hd="$root/.handoffs"
latest=$(ls -1 "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | sort -r | head -1 || true)
if [ -n "$latest" ]; then
  echo "═══ LATEST SESSION HANDOFF — read before starting ═══"
  echo "From: $(basename "$latest")"
  echo ""
  awk '/^## .*START HERE/{g=1;print;next} g&&/^## /{exit} g{print}' "$latest"
  echo "═════════════════════════════════════════════════════"
  echo ""
fi

plans_dir="$root/.plans"
[ -d "$plans_dir" ] || exit 0

# Top-level plans only: archive/ holds completed/superseded plans by convention
# (.claude/rules/folder-organization.md), and the glob does not descend into it.
items=$(grep -Hn '^[[:space:]]*- \[ \]' "$plans_dir"/*.md 2>/dev/null || true)
[ -n "$items" ] || exit 0

echo "STANDING OBLIGATIONS — unchecked action-plan items. These are open work even if the latest handoff says nothing is in flight:"
echo "$items" | sed "s|^$root/||"
