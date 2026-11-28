#!/usr/bin/env bash
# Tests for the jq filters that board-check.yml uses to find cards with required content
# missing (scripts/board-missing-*.jq). Hermetic: made-up cards, no network.
# Run: bash scripts/test/board-check-filters.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail=0
expect() { # expect <name> <actual> <expected>
  if [ "$2" != "$3" ]; then echo "  FAIL: $1: got '$2', expected '$3'"; fail=1; fi
}

cards='[
 {"idReadable":"T-1","description":"> **Outcome**\n> x","customFields":[
   {"name":"Stage","value":{"name":"Options"}},{"name":"Type","value":{"name":"Bug"}},
   {"name":"Platform","value":null},{"name":"Service","value":{"name":"SDK"}},
   {"name":"Goal","value":null},{"name":"Class of service","value":{"name":"Standard"}}]},
 {"idReadable":"T-2","description":null,"customFields":[
   {"name":"Stage","value":{"name":"Staging"}},{"name":"Type","value":null},
   {"name":"Platform","value":{"name":"iOS"}},{"name":"Service","value":{"name":"SDK"}},
   {"name":"Goal","value":null}]},
 {"idReadable":"T-3","description":"Text first.\n> **Outcome**","customFields":[
   {"name":"Stage","value":{"name":"Next"}},{"name":"Type","value":{"name":"Process improvement"}},
   {"name":"Platform","value":{"name":"All"}},{"name":"Service","value":null},
   {"name":"Goal","value":null},{"name":"Class of service","value":{"name":"Intangible"}}]}
]'
missing() { printf '%s' "$cards" | jq -r --arg f "$1" -f "$here/../board-missing-field.jq" | tr '\n' ' '; }

expect "empty Type" "$(missing Type)" "T-2 "
expect "empty Platform" "$(missing Platform)" "T-1 "
# A field the card does not list at all counts as empty.
expect "absent class of service" "$(missing 'Class of service')" "T-2 "
# Goal: only product cards (a Service) in Options or Next. T-2 is in Staging, T-3 is process work.
expect "Goal on product cards" "$(printf '%s' "$cards" | jq -r -f "$here/../board-missing-goal.jq" | tr '\n' ' ')" "T-1 "
# Outcome: the block must be the first thing in the description.
expect "Outcome block" "$(printf '%s' "$cards" | jq -r -f "$here/../board-missing-outcome.jq" | tr '\n' ' ')" "T-2 T-3 "
expect "no cards" "$(printf '[]' | jq -r --arg f Type -f "$here/../board-missing-field.jq")" ""

[ "$fail" -eq 0 ] && echo "board-check-filters: all passed." || exit 1
