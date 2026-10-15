#!/usr/bin/env bash
# Shared by the session hooks: session-banner.sh, stop-handoff-check.sh, mark-substantive.sh.
# Sourced, never run. Two answers:
#   tessera_handoff_dir ROOT  .handoffs/ in the MAIN checkout, so a worktree session reads
#                             and writes the same handoffs as the main checkout.
#   tessera_session_id        the session_id from the hook payload on stdin ("default"
#                             when absent), so concurrent sessions keep separate markers.

tessera_handoff_dir() {
  local root="$1" common=""
  common=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  if [ -n "$common" ] && [ "$(basename "$common")" = ".git" ]; then
    printf '%s/.handoffs' "$(dirname "$common")"
  else
    printf '%s/.handoffs' "$root"
  fi
}

tessera_session_id() {
  local payload="" id=""
  payload=$(cat 2>/dev/null || true)
  id=$(printf '%s' "$payload" | tr '\n' ' ' \
    | sed -n 's/.*"session_id"[[:space:]]*:[[:space:]]*"\([A-Za-z0-9._-]*\)".*/\1/p' | head -1)
  printf '%s' "${id:-default}"
}
