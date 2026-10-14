#!/usr/bin/env bash
# PreToolUse hook: block outward text that holds a private term.
#
# The push scan (private-content-scan.sh) sees only tracked files. Text published
# another way never enters git: `gh pr create --body`, release notes, issue comments,
# and YouTrack writes (issues, comments, KB articles, public pages included). This hook
# reads the hook payload on stdin and scans:
#   - Bash calls to a publishing `gh` command: the command text plus any file passed with
#     --body-file, --notes-file, or -F;
#   - mcp__youtrack__ write tools: every value in the tool input.
# Terms come from the gitignored .claude/private-content-terms.local (the main checkout's
# copy when running in a worktree). No terms file: nothing to scan, exit 0.
# The same file serves lightine-io/tessera-swift (copy it there).
#
# Exit: 0 clean or not applicable; 2 a term matched (the hook blocks; the term is not echoed).

set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
terms="$root/.claude/private-content-terms.local"
if [ ! -f "$terms" ]; then
  common=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  [ -n "$common" ] && terms="$(dirname "$common")/.claude/private-content-terms.local"
fi
[ -f "$terms" ] || exit 0

payload=$(cat)
parsed=$(printf '%s' "$payload" | python3 -I -c '
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
tool = data.get("tool_name", "")
inp = data.get("tool_input", {}) or {}
if tool == "Bash":
    print("bash")
    print(inp.get("command", ""))
elif tool.startswith("mcp__youtrack__"):
    print("youtrack")
    print(json.dumps(inp, ensure_ascii=False))
' 2>/dev/null || true)
[ -n "$parsed" ] || exit 0
kind=$(printf '%s\n' "$parsed" | head -1)
text=$(printf '%s\n' "$parsed" | tail -n +2)

if [ "$kind" = "bash" ]; then
  case "$text" in
    *"gh pr create"* | *"gh pr edit"* | *"gh pr comment"* | *"gh pr review"* | \
    *"gh issue create"* | *"gh issue comment"* | *"gh issue edit"* | \
    *"gh release create"* | *"gh release edit"* | \
    *"gh repo edit"* | *"gh gist create"*) ;;
    *) exit 0 ;;
  esac
  files=$(printf '%s' "$text" | grep -oE '(--body-file|--notes-file|-F)[= ][^ ]+' | sed -E 's/^[^ =]+[= ]//' || true)
  while IFS= read -r f; do
    if [ -n "$f" ] && [ -f "$f" ]; then
      text="$text
$(cat "$f")"
    fi
  done <<EOF
$files
EOF
fi

# Terms are FIXED strings (-F): a regex metacharacter in a term must not match unrelated text.
while IFS= read -r term || [ -n "$term" ]; do
  [ -z "${term//[[:space:]]/}" ] && continue
  case "$term" in \#*) continue ;; esac
  if printf '%s' "$text" | grep -qiF -- "$term"; then
    echo "BLOCKED: outward text matches a private term (list: .claude/private-content-terms.local; the term is not echoed). Remove the private context and retry." >&2
    exit 2
  fi
done < "$terms"
exit 0
