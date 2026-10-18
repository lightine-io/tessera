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
write_meta() { # write_meta <version>: gradle.properties and llms.txt beside the README
  printf 'kotlin.code.style=official\nversion=%s\n' "$1" >"$tmp/repo/gradle.properties"
  printf '# Tessera\n\nCurrent release: %s. Maven group.\n' "$1" >"$tmp/repo/llms.txt"
}
write_meta 1.2.3

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
write_meta 1.2.2; expect 1 "stale build version"   1.2.3
write_meta 1.2.3; sed -i.bak 's/^version=.*/version=1.2.2/' "$tmp/repo/gradle.properties"
expect 1 "gradle.properties behind the tag" 1.2.3
write_meta 1.2.3; sed -i.bak 's/Current release: 1.2.3/Current release: 1.2.2/' "$tmp/repo/llms.txt"
expect 1 "stale llms.txt"        1.2.3
write_meta 1.2.3; expect 0 "all current again" 1.2.3
expect 2 "bad version argument"  latest

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
