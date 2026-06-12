#!/usr/bin/env bash
# Scan outward-bound `gh` content for private-content terms (audit finding B6).
#
# Companion to private-content-scan.sh: that script scans tracked files before
# `git push`, but `gh pr create --body …`, `gh release edit --notes …`, issue
# comments, etc. publish text that never enters git — the push scan cannot see it.
# This hook reads the PreToolUse JSON on stdin, extracts the Bash command text,
# and scans it — plus any file passed via --body-file / --notes-file / -F —
# against the same gitignored terms file. Non-publishing gh commands pass through.
#
# Exit codes: 0 = clean or not applicable; 2 = term matched (hook blocks the call).
set -euo pipefail

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
TERMS_FILE="$PROJECT_ROOT/.claude/private-content-terms.local"
[ -f "$TERMS_FILE" ] || exit 0

input=$(cat)
cmd=$(printf '%s' "$input" | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)
[ -n "$cmd" ] || exit 0

# Only outward-publishing gh commands are scanned; reads and local commands pass.
case "$cmd" in
  *"gh pr create"* | *"gh pr edit"* | *"gh pr comment"* | *"gh pr review"* | \
  *"gh issue create"* | *"gh issue comment"* | *"gh issue edit"* | \
  *"gh release create"* | *"gh release edit"* | \
  *"gh repo edit"* | *"gh gist create"*) ;;
  *) exit 0 ;;
esac

scan_text="$cmd"
# Pull in any referenced body/notes files (--body-file x, --notes-file=x, -F x).
while IFS= read -r f; do
  [ -n "$f" ] && [ -f "$f" ] && scan_text="$scan_text
$(cat "$f")"
done < <(printf '%s' "$cmd" | grep -oE '(--body-file|--notes-file|-F)[= ][^ ]+' | sed -E 's/^[^ =]+[= ]//' || true)

hit=0
# Skip blank and comment (leading-#) lines — the terms file has a comment header
# (same stripping as private-content-scan.sh). Match terms as FIXED strings (-F):
# treating a term as a regex lets metacharacters in it match unrelated text —
# this script's own installation PR was blocked by exactly that before -F.
while IFS= read -r term; do
  [ -z "${term//[[:space:]]/}" ] && continue
  case "$term" in \#*) continue ;; esac
  if printf '%s' "$scan_text" | grep -qiF -- "$term"; then
    hit=1
  fi
done < "$TERMS_FILE"

if [ "$hit" -ne 0 ]; then
  echo "BLOCKED: outward gh content matches a private-content term (terms list: .claude/private-content-terms.local — term not echoed). Remove the private context from the PR/issue/release text, then retry." >&2
  exit 2
fi
exit 0
