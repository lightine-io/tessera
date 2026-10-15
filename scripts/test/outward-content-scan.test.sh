#!/usr/bin/env bash
# Tests for scripts/outward-content-scan.sh. Hermetic: a throwaway project folder.
# Run: bash scripts/test/outward-content-scan.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../outward-content-scan.sh"
fail=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.claude"
printf '# terms\nexamplecorp\na.b\n' > "$tmp/.claude/private-content-terms.local"
printf 'body names ExampleCorp\n' > "$tmp/body.md"

expect() { # expect <code> <label> <payload>
  local got=0
  printf '%s' "$3" | CLAUDE_PROJECT_DIR="$tmp" bash "$hook" >/dev/null 2>&1 || got=$?
  if [ "$got" != "$1" ]; then echo "  FAIL [$2] want exit $1, got $got"; fail=1; fi
}

expect 2 "gh pr create body with a term" '{"tool_name":"Bash","tool_input":{"command":"gh pr create --body \"for examplecorp\""}}'
expect 2 "gh body file with a term" "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"gh pr create --body-file $tmp/body.md\"}}"
expect 0 "gh read command passes" '{"tool_name":"Bash","tool_input":{"command":"gh pr view 12 examplecorp"}}'
expect 0 "clean gh text passes" '{"tool_name":"Bash","tool_input":{"command":"gh issue comment 3 --body \"looks good\""}}'
expect 0 "terms are fixed strings, not regex" '{"tool_name":"Bash","tool_input":{"command":"gh pr comment 1 --body \"axb\""}}'
expect 2 "YouTrack article write with a term" '{"tool_name":"mcp__youtrack__update_article","tool_input":{"articleId":"TES-A-1","content":"Built for ExampleCorp"}}'
expect 0 "clean YouTrack write passes" '{"tool_name":"mcp__youtrack__create_issue","tool_input":{"summary":"Fix parser"}}'
expect 0 "malformed payload fails open" 'not json'

rm "$tmp/.claude/private-content-terms.local"
expect 0 "no terms file: nothing to scan" '{"tool_name":"Bash","tool_input":{"command":"gh pr create --body \"examplecorp\""}}'

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
