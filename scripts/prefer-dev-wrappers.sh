#!/usr/bin/env bash
# Nudge the ASSISTANT from a raw/low-level vendor dev tool to the prescribed
# wrapper that owns that domain — Tessera's "how to drive dev."
#
# The drive-dev tooling (see .claude/rules/mobile-dev-workflow.md +
# docs/development-setup.md) is a SET, one driver per domain — the Android CLI
# is only one of them:
#   - Android SDK / emulator / device  -> the Android CLI  (`android sdk|avd|...`)
#   - iOS build / test / run / sim     -> the Xcode MCP     (mcpbridge)
#   - builds & tests                   -> ./gradlew
# Reaching for the raw vendor tool (sdkmanager, avdmanager, xcodebuild,
# `xcrun simctl`) skips the prescribed driver. This hook surfaces that so the
# choice is conscious, not habit. It is a NUDGE WITH AN OVERRIDE, not an
# absolute border like scripts/block-screenshot.sh.
#
# Wired as a PreToolUse hook on Bash in .claude/settings.json. The hook only
# sees the ASSISTANT's tool calls; a human's own terminal is never affected.
#
# Contract (matches scripts/block-screenshot.sh):
#   exit 0 — allow the command
#   exit 2 — block the command (message on stderr)
# FAIL-OPEN by design: missing input / parse problems / non-matches all exit 0,
# so this can never block a legitimate command. (`set -e`/`set -u` deliberately
# NOT used — a stray non-zero or unset var must never become a block.)
#
# OVERRIDE: if the prescribed driver genuinely cannot do the job, add the marker
# `raw-ok` anywhere in the command (e.g. a trailing `# raw-ok`). The override is
# the conscious checkpoint — use it deliberately, not reflexively.
#
# Deliberately NOT guarded here: plain `adb` (logcat / pm grant are legitimate
# raw uses; adb screenshots are blocked by block-screenshot.sh) and
# `xcrun devicectl` (the physical-device console loop the Xcode MCP doesn't own).
#
# Note: viewing THIS file or the rule doc via Bash `cat`/`grep` can match the
# patterns below and be blocked — use the Read tool for files, not Bash.

payload="$(cat 2>/dev/null || true)"
[ -z "$payload" ] && exit 0

cmd=""
if command -v jq >/dev/null 2>&1; then
    cmd="$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
fi
if [ -z "$cmd" ]; then
    # Tolerant fallback if jq is absent or the payload is not what we expect.
    cmd="$(printf '%s' "$payload" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' 2>/dev/null | head -1 || true)"
fi
[ -z "$cmd" ] && exit 0

# Conscious override — proceed when the prescribed driver genuinely can't.
printf '%s' "$cmd" | grep -iq 'raw-ok' && exit 0

# Android SDK / AVD raw tools -> the Android CLI. Matches bare or full-path
# invocations (e.g. .../cmdline-tools/latest/bin/sdkmanager); `android sdk ...`
# does not contain these names, so the CLI itself is never matched.
if printf '%s' "$cmd" | grep -Eq '(^|[^[:alnum:]_.-])(sdkmanager|avdmanager)([^[:alnum:]_]|$)'; then
    raw="$(printf '%s' "$cmd" | grep -oE 'sdkmanager|avdmanager' | head -1)"
    {
        echo "prefer-dev-wrappers: BLOCKED — drive Android via the Android CLI, not raw ${raw}."
        echo ""
        echo "  Command: $cmd"
        echo ""
        echo "  Use the prescribed wrapper for Android SDK / AVD management:"
        echo "    android sdk install|list|update|remove    (instead of sdkmanager)"
        echo "    android avd ...                           (instead of avdmanager)"
        echo "  The full drive-dev tool map (Android CLI, Xcode MCP, ./gradlew) is in"
        echo "  .claude/rules/mobile-dev-workflow.md. If the CLI genuinely can't do"
        echo "  this, append '# raw-ok' to the command to override deliberately."
    } >&2
    exit 2
fi

# iOS build / simulator raw tools -> the Xcode MCP (mcpbridge).
if printf '%s' "$cmd" | grep -Eiq '(^|[^[:alnum:]_.-])xcodebuild([^[:alnum:]_]|$)|xcrun[^|;&]*simctl'; then
    {
        echo "prefer-dev-wrappers: BLOCKED — drive iOS build/simulator via the Xcode MCP (mcpbridge), not raw xcodebuild / xcrun simctl."
        echo ""
        echo "  Command: $cmd"
        echo ""
        echo "  The Xcode MCP owns iOS build / test / run / simulator. See"
        echo "  .claude/rules/mobile-dev-workflow.md + docs/development-setup.md (Tier 2)."
        echo "  Physical-device tools (xcrun devicectl) are not guarded here. If the"
        echo "  MCP genuinely can't do this, append '# raw-ok' to override deliberately."
    } >&2
    exit 2
fi

exit 0
