#!/usr/bin/env bash
# Release gate: the build version (gradle.properties), llms.txt, and both READMEs' install
# instructions must name the version being tagged (TES-168, TES-236). Usage: scripts/check-readme-version.sh <version> [tessera-swift-dir]
# Each anchor below must appear in its README, and every version it names must equal
# <version>. A missing anchor fails too, so a rewritten README cannot slip past silently.
set -uo pipefail

version="${1:-}"
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]]; then
  echo "usage: $0 <version, e.g. 0.5.2> [tessera-swift-dir]" >&2
  exit 2
fi
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
main_readme="$here/../README.md"
gradle_props="$here/../gradle.properties"
llms="$here/../llms.txt"
swift_readme="${2:-../tessera-swift}/README.md"   # relative to the working directory
V='[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z]+)*'
fail=0

# The version sits between the prefix and suffix patterns.
check() { # check <file> <label> <prefix ERE> <suffix ERE>
  local file="$1" label="$2" pre="$3" post="$4" found line
  if [ ! -f "$file" ]; then echo "FAIL $file: file not found"; fail=1; return; fi
  found=$(grep -oE "${pre}${V}${post}" "$file" | grep -oE "$V" | head -n 100 || true)
  if [ -z "$found" ]; then
    echo "FAIL $file: no version found for $label (expected $version)"; fail=1; return
  fi
  while IFS= read -r line; do
    if [ "$line" != "$version" ]; then
      echo "FAIL $file: $label names $line, expected $version"; fail=1
    fi
  done <<<"$found"
}

check "$gradle_props" 'the build version=' '^version=' '$'
check "$llms" 'the "Current release:" line' '^Current release: ' '\.'
check "$main_readme" 'the "Release X" line'          '^Release '                   '\.'
check "$main_readme" 'the BOM coordinate'            'tessera-bom:'                '"'
check "$main_readme" 'the Maven <version>'           '<version>'                   '</version>'
check "$main_readme" 'the Package.swift exact:'      'exact: "'                    '"'
check "$swift_readme" 'the "current release is" sentence' 'current release is '    '\.'
check "$swift_readme" 'the Xcode Exact Version rule' 'Exact Version\*\* `'         '`'
check "$swift_readme" 'the Package.swift exact:'     'exact: "'                    '"'
check "$swift_readme" 'the release notes link'       'releases/tag/v'              '\)'

if [ "$fail" -eq 0 ]; then echo "Release versions all name $version"; else exit 1; fi
