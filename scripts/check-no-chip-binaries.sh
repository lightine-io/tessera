#!/usr/bin/env bash
# CI rule (TES-162): no binary chip files in the test resources of eMRTD modules.
# Chip data in tests is built at test time (fake chip, test-only DER writer) or typed as
# hex text from ICAO's published examples; a committed dump could be a real document's.
# Fails on any file under emrtd-*/src/*/resources whose extension is .bin .der .sod .dg*
# .cer .crt .pem .p12 .ldif .ml, or whose content is binary. Usage: scripts/check-no-chip-binaries.sh [repo-root]
set -uo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
fail=0
while IFS= read -r -d '' f; do
  rel="${f#"$root"/}"
  lower="$(printf '%s' "${f##*/}" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    *.bin|*.der|*.sod|*.dg|*.dg[0-9]*|*.cer|*.crt|*.pem|*.p12|*.ldif|*.ml)
      echo "::error file=$rel::chip file in test resources (by extension)"; fail=1; continue ;;
  esac
  if [ -s "$f" ] && ! LC_ALL=C grep -qI . "$f"; then
    echo "::error file=$rel::binary file in eMRTD resources"; fail=1
  fi
done < <(find "$root" -path '*/.git' -prune -o -path '*/build' -prune -o -path '*/.claude' -prune -o \
  -type f -path "$root/emrtd-*/src/*/resources/*" -print0 2>/dev/null)
if [ "$fail" -ne 0 ]; then
  echo "No binary chip files in eMRTD test resources: build chip data at test time or use hex text from ICAO's published examples (.claude/rules/api-design.md)." >&2
fi
exit "$fail"
