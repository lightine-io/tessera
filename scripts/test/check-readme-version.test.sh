#!/usr/bin/env bash
# Tests for scripts/check-readme-version.sh. Run: bash scripts/test/check-readme-version.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../check-readme-version.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

write_main() { # write_main <version>
  cat >"$tmp/README.md" <<EOT
## Install

Release $1. Minimums: minSdk 23.

    implementation(platform("io.lightine.tessera:tessera-bom:$1"))
    <version>$1</version>
.package(url: "https://github.com/lightine-io/tessera-swift", exact: "$1"),
EOT
}
write_swift() { # write_swift <version>
  mkdir -p "$tmp/swift"
  cat >"$tmp/swift/README.md" <<EOT
The current release is $1. More text.
set the dependency rule to **Exact Version** \`$1\`. Add both.
.package(url: "https://github.com/lightine-io/tessera-swift", exact: "$1"),
- [Release $1](https://github.com/lightine-io/tessera/releases/tag/v$1) - notes.
EOT
}
expect() { # expect <code> <label> <version>
  local got=0 out
  out=$(bash "$script" "$3" "$tmp/swift" 2>&1) || got=$?
  if [ "$got" != "$1" ]; then echo "  FAIL [$2] want exit $1, got $got: $out"; fail=1; fi
}

# The script reads the main README at ../README.md relative to itself, so run a copy.
mkdir -p "$tmp/repo/scripts"; cp "$script" "$tmp/repo/scripts/"
script="$tmp/repo/scripts/check-readme-version.sh"
cp_main() { cp "$tmp/README.md" "$tmp/repo/README.md"; }

write_main 1.2.3; write_swift 1.2.3; cp_main
expect 0 "both current"          1.2.3
expect 1 "wrong target version"  1.2.4
write_main 1.2.2; cp_main
expect 1 "stale main README"     1.2.3
write_main 1.2.3; write_swift 1.2.2; cp_main
expect 1 "stale swift README"    1.2.3
write_swift 1.2.3; sed -i.bak 's/tessera-bom:1.2.3/tessera-bom:1.2.2/' "$tmp/README.md"; cp_main
expect 1 "one stale spot"        1.2.3
write_main 1.2.3; sed -i.bak '/exact:/d' "$tmp/README.md"; cp_main
expect 1 "anchor missing"        1.2.3
write_main 1.2.3; cp_main
expect 2 "bad version argument"  latest

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
