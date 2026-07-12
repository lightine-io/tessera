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
# session markers below, (b) injects the latest handoff's action-bearing sections —
# the real orientation artifact now that .plans/ is usually empty, so the next session
# can't start blind to prior state — and (c) catches any *local* .plans items; the
# YouTrack board is the backlog of record (surfaced via .claude/rules/youtrack.md).
#
# See CLAUDE.md "What to Do First" and .claude/session-handoff-template.md
# ("Standing Obligations").
set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Session markers for the Stop-hook handoff check (scripts/stop-handoff-check.sh):
# mark when this session started and clear the per-session flags — the once-per-session
# reminder, and the substantive marker touched by scripts/mark-substantive.sh on
# YouTrack write-tool calls.
mkdir -p "$root/.handoffs"
touch "$root/.handoffs/.session-started"
rm -f "$root/.handoffs/.handoff-reminded" "$root/.handoffs/.substantive"

# Surface the latest handoff's ACTION-BEARING sections so no session starts blind to
# prior state. START HERE alone proved insufficient: sessions oriented from the banner
# without opening the file, so "Next Session Should" and "Things to Watch For" — the two
# sections that direct the next session's behavior — went unread. All three are injected
# now; the remaining sections (What Is in Flight, Superseded, Lessons) stay in the file.
# Runs unconditionally (before the .plans early-exits below), because the handoff — not
# the now-usually-empty .plans sweep — is the real orientation artifact. Reads the file
# live each run, so it can never drift from what was actually written.
# Once-per-session ssh-agent check: commit signing hangs ~2min when the agent is
# empty (macOS clears it at login). The key's passphrase lives in the macOS keychain
# (maintainer ran `ssh-add --apple-use-keychain` 2026-07-12), so loading is
# non-interactive: self-heal here instead of warning. `--apple-load-keychain` is
# macOS-only and touches no key material directly — it asks the OS keychain to load
# whatever it holds. Fails silently elsewhere; warn only if the agent is still empty.
if ! ssh-add -l >/dev/null 2>&1; then
  ssh-add --apple-load-keychain >/dev/null 2>&1 || true
  if ! ssh-add -l >/dev/null 2>&1; then
    echo "⚠ ssh-agent has no identities (keychain auto-load failed) — the first signed commit will hang. Ask the maintainer to run: ssh-add --apple-load-keychain"
    echo ""
  fi
fi

# Source-truth security line: open Dependabot alerts, from the API — never from a
# handoff (2026-07-12 audit: handoffs said "1 critical" while GitHub held 15 alerts).
# --cache bounds latency; every failure path (offline, no gh, no repo, no permission)
# is silent — this line is an enhancement, never a blocker.
alerts=$(gh api 'repos/{owner}/{repo}/dependabot/alerts?state=open&per_page=100' --cache 1h --jq length 2>/dev/null || true)
if [ -n "${alerts:-}" ] && [ "$alerts" -gt 0 ] 2>/dev/null; then
  echo "⚠ Open Dependabot alerts: $alerts (source: gh api — trust this over any note; posture: hybrid-by-reachability)"
  echo ""
fi

hd="$root/.handoffs"
latest=$(ls -1 "$hd"/SESSION-HANDOFF-*.md 2>/dev/null | sort -r | head -1 || true)
if [ -n "$latest" ]; then
  echo "═══ LATEST SESSION HANDOFF — orientation ═══"
  echo "From: $(basename "$latest")"
  echo "Injected: START HERE · Next Session Should · Things to Watch For. Full handoff (in-flight detail, superseded facts, lessons): ${latest#"$root"/}"

  # Staleness guard: commits that postdate the handoff mean work happened after it was
  # written (or a session ended without one) — the banner must not be read as current
  # truth. mtime is trustworthy here: .handoffs/ is gitignored and never leaves this
  # machine, so the clone/rsync mtime-clobber caveat does not apply.
  # +1: git --since is inclusive, and a commit in the same second as the handoff write
  # is not "postdating" it — for a warning, a false positive is worse than a false
  # negative. `|| true`: outside a git repo (hermetic tests) the pipeline must not
  # kill the hook.
  # mtime epoch, portably: GNU stat wants -c %Y; BSD/macOS wants -f %m. GNU must be
  # tried FIRST — on GNU, `stat -f %m` exits 0 while printing a filesystem report
  # (garbage for the arithmetic below); on BSD, `stat -c` fails cleanly.
  h_epoch=$(stat -c %Y "$latest" 2>/dev/null || stat -f %m "$latest" 2>/dev/null || echo 0)
  stale=$(git -C "$root" log --oneline --since="@$((h_epoch + 1))" 2>/dev/null | wc -l | tr -d ' ' || true)
  if [ "${stale:-0}" -gt 0 ]; then
    echo "⚠ STALE: ${stale} commit(s) postdate this handoff — trust git log/status over the banner."
  fi

  echo ""
  # Section extraction. Column-0 code fences toggle `fence` so a literal "## " line
  # inside a fenced example neither ends nor starts a section (it would otherwise
  # silently truncate the injected section). Renaming any of the three headings
  # requires updating this pattern AND scripts/test/standing-obligations.test.sh.
  awk '
    /^```/ { if (g) print; fence = !fence; next }
    !fence && (/^## (.* )?START HERE/ || /^## Next Session Should/ || /^## Things to Watch For/) { g=1; print; next }
    !fence && /^## / { g=0; next }
    g { print }
  ' "$latest"
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
