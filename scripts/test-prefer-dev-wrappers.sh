#!/usr/bin/env bash
# Regression tests for scripts/prefer-dev-wrappers.sh.
#
# Each case feeds a PreToolUse-shaped payload through the hook and asserts the
# exit code: 0 = allowed, 2 = blocked. Run directly:
#
#   scripts/test-prefer-dev-wrappers.sh
#
# Exit 0 when every case passes; exit 1 with a per-case report otherwise.
# The cases marked "regression" reproduce real incidents — keep them forever.

set -u

HOOK="$(cd "$(dirname "$0")" && pwd)/prefer-dev-wrappers.sh"
[ -x "$HOOK" ] || { echo "hook not found/executable: $HOOK"; exit 1; }

pass=0
fail=0

check() {
    # check <expected-exit: allow|block> <label> <command>
    local expect="$1" label="$2" cmd="$3" got rc
    printf '{"tool_input":{"command":%s}}' "$(printf '%s' "$cmd" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" \
        | "$HOOK" >/dev/null 2>&1
    rc=$?
    case "$expect" in
        allow) [ "$rc" -eq 0 ] && got=ok || got=FAIL ;;
        block) [ "$rc" -eq 2 ] && got=ok || got=FAIL ;;
        *) got=FAIL ;;
    esac
    if [ "$got" = ok ]; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "FAIL [$label] expected=$expect got-exit=$rc"
        echo "     cmd: $cmd"
    fi
}

# --- core contract: raw invocations are blocked -------------------------------
check block "raw sdkmanager"            'sdkmanager --install "platforms;android-37"'
check block "raw avdmanager"            'avdmanager list avd'
check block "raw emulator"              'emulator -avd Medium_Phone_API_37'
check block "path-prefixed sdkmanager"  "$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list"
check block "adb install"               'adb install app-debug.apk'
check block "adb -s serial install"     'adb -s RFCXB052YYT install app/build/outputs/apk/debug/app-debug.apk'
check block "chained && sdkmanager"     'echo hi && sdkmanager --list'
check block "real pipeline into emulator" 'echo start | emulator -avd Foo'
check block "raw xcodebuild"            'xcodebuild build -scheme TesseraUI'
check block "env-prefixed xcodebuild"   'TESSERA_LOCAL_XCFRAMEWORK=1 xcodebuild build -scheme TesseraUI'
check block "xcrun simctl"              'xcrun simctl list devices'

# --- core contract: prescribed drivers and legitimate raw uses are allowed ----
check allow "android cli sdk"           'android sdk list'
check allow "android cli run"           'android run --apks app-debug.apk --device RFCXB052YYT'
check allow "adb logcat"                'adb -s RFCXB052YYT logcat -d'
check allow "adb pm grant"              'adb shell pm grant io.lightine.tessera.harness android.permission.CAMERA'
check allow "adb shell input"           'adb shell input tap 540 1130'
check allow "xcrun devicectl"           'xcrun devicectl device info details --device X'
check allow "gradlew"                   './gradlew :mrz-camera-ui-android:testAndroidHostTest'

# --- override: trailing-comment form only --------------------------------------
check allow "trailing raw-ok"           'xcodebuild build -scheme TesseraUI # raw-ok'
check allow "trailing raw-ok, spaces"   'sdkmanager --list   #  raw-ok  '
check block "raw-ok mid-command (regression 2026-07-11: silent bypass)" \
    'grep -rn "raw-ok" .handoffs/ && sdkmanager --list'
check block "raw-ok inside grep pattern does not disarm a real invocation" \
    'sdkmanager --list --channel=raw-okay'

# --- mentions in argument text are not invocations ------------------------------
check allow "BRE alternation in grep pattern (regression 2026-07-11: false positive)" \
    'grep -n -i "raw vendor\|raw tool\|sdkmanager\|xcodebuild\|simctl" .claude/known-pitfalls.md'
check allow "ERE alternation in grep pattern" \
    'grep -En "sdkmanager|avdmanager|emulator" docs/notes.md'
check allow "tool named in prose/PR body" \
    'gh pr create --body "replaces raw sdkmanager usage with the android cli"'
check allow "tool named after semicolon in prose" \
    'echo "the sdkmanager & avdmanager tools are wrapped"'
check allow "grep FOR raw-ok in handoffs (no raw invocation at all)" \
    'grep -rn -i "raw-ok" .handoffs/ | head'

# --- documented accepted misses (nudge tradeoffs, not defects): keep visible ----
# `cd x; sdkmanager` and `cmd| sdkmanager` are known misses; if a future edit
# turns them into blocks, that is fine — flip the expectations consciously.
check allow "known miss: semicolon separator" 'cd /tmp; sdkmanager --list'
check allow "known miss: pipe with no space before" 'echo x| sdkmanager --list'

echo ""
echo "prefer-dev-wrappers tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
