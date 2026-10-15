#!/usr/bin/env bash
# PreToolUse hook on Bash: block screen capture and recording by the assistant.
#
# Why: a full-size screenshot in an assistant's context can exceed image limits and end
# the whole session (it happened once and cost a session). State is read as text instead;
# see .claude/rules/platform-work.md. Only the assistant's Bash calls reach this hook; a
# human's terminal is never affected, and MCP tools that return images are not covered.
#
# Exit: 0 allow; 2 block (reason on stderr). Fails open: a missing or odd payload, or any
# error, never produces exit 2 (an error exit is non-blocking for a PreToolUse hook).
# Regression cases: scripts/test/block-screenshot.test.sh.

set -euo pipefail

payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0

cmd=""
if command -v jq >/dev/null 2>&1; then
    cmd="$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
fi
if [ -z "$cmd" ]; then
    # Fallback when jq is absent or the payload is unexpected.
    cmd="$(printf '%s' "$payload" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' 2>/dev/null | head -1 || true)"
fi
[ -n "$cmd" ] || exit 0

# Reading the manual captures nothing. Long-form --help only: short -h is too common
# (`df -h && ...`) to disarm the border safely.
if printf '%s' "$cmd" | grep -Eq '(^|[[:space:]])--help([[:space:]]|$)'; then
    exit 0
fi

# Capture and recording signatures, case-insensitive:
#   macOS        screencapture
#   Android      adb ... screencap | screenrecord; the Android CLI's own `android screen capture`
#                (`android screen resolve` and `android layout` stay allowed: text only)
#   iOS sim      xcrun simctl io ... screenshot | recordVideo
if printf '%s' "$cmd" | grep -iEq 'screencapture|adb[^|;&]*screen(cap|record)|(^|[^[:alnum:]_.-])android[[:space:]]+screen[[:space:]]+capture|simctl[^|;&]*io[^|;&]*(screenshot|recordvideo)'; then
    {
        echo "block-screenshot: BLOCKED - screen capture and recording are off for the assistant."
        echo ""
        echo "  Command: $cmd"
        echo ""
        echo "  Read state as text: adb logcat, android layout or uiautomator dump on Android;"
        echo "  Xcode MCP diagnostics on iOS. See .claude/rules/platform-work.md."
        echo "  A human can run this in their own terminal."
    } >&2
    exit 2
fi
exit 0
