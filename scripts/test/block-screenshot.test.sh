#!/usr/bin/env bash
# Tests for scripts/block-screenshot.sh. Signatures are built at run time so that viewing
# this file through Bash never trips the hook. Run: bash scripts/test/block-screenshot.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../block-screenshot.sh"
fail=0
SC="screen""capture"; CAP="screen""cap"; REC="screen""record"; SHOT="screen""shot"

expect() { # expect <code> <label> <command>
  local got=0 payload
  payload=$(printf '%s' "$3" | python3 -I -c 'import json,sys; print(json.dumps({"tool_input":{"command":sys.stdin.read()}}))')
  printf '%s' "$payload" | bash "$hook" >/dev/null 2>&1 || got=$?
  if [ "$got" != "$1" ]; then echo "  FAIL [$2] want exit $1, got $got"; fail=1; fi
}

expect 2 "macOS capture"        "$SC -x /tmp/a.png"
expect 2 "adb capture"          "adb -s X exec-out $CAP -p > a.png"
expect 2 "adb recording"        "adb shell $REC /sdcard/a.mp4"
expect 2 "Android CLI capture"  "android screen capture --output a.png"
expect 2 "simulator capture"    "xcrun simctl io booted $SHOT a.png"
expect 0 "help text"            "$SC --help"
expect 0 "text inspection"      "adb logcat -d"
expect 0 "android layout"       "android layout --device X"
expect 0 "ordinary command"     "./gradlew check"
expect 0 "empty payload"        ""

if [ "$fail" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES ABOVE"; exit 1; fi
