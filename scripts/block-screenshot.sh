#!/usr/bin/env bash
# Block the ASSISTANT from running screen-capture / screen-recording commands.
#
# Why: a screenshot pulled into an AI assistant's context can exceed image-size
# limits and destroy the whole session's context (it has happened, and cost a
# full session). Tessera's mobile workflow inspects state as TEXT instead —
# adb logcat, uiautomator dump, Xcode MCP diagnostics. See
# .claude/rules/mobile-dev-workflow.md and docs/development-setup.md.
#
# Wired as a PreToolUse hook on Bash in .claude/settings.json. The hook only
# sees the ASSISTANT's tool calls; a human's own terminal is never affected.
#
# Contract (matches scripts/private-content-scan.sh):
#   exit 0 — allow the command
#   exit 2 — block the command (message on stderr)
#
# FAIL-OPEN by design: missing input / parse problems / non-matches all exit 0,
# so this can never block a legitimate command. Only a positive screenshot-
# command match exits 2. (`set -e`/`set -u` are deliberately NOT used — a stray
# non-zero or unset var must never become a block.)
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

# Screen-capture / -recording command signatures (case-insensitive):
#   macOS:       screencapture ...
#   Android:     adb ... screencap / adb ... screenrecord
#   Android CLI: android screen capture ... — the prescribed driver's OWN
#                screenshot command ("Outputs the device screen to a PNG"),
#                found in an audit 2026-07-04; without this line the tool the
#                workflow prescribes would carry a side door around this border.
#                (`android screen resolve` stays allowed: it only reads an
#                already-captured file and outputs text coordinates — inert
#                while capture is blocked. `android layout` is the text path.)
#   iOS sim:     xcrun simctl io ... screenshot / ... recordVideo
if printf '%s' "$cmd" | grep -iEq 'screencapture|adb[^|;&]*screen(cap|record)|(^|[^[:alnum:]_.-])android[[:space:]]+screen[[:space:]]+capture|simctl[^|;&]*io[^|;&]*(screenshot|recordvideo)'; then
    {
        echo "block-screenshot: BLOCKED — screen capture/recording is disabled for the assistant."
        echo ""
        echo "  Command: $cmd"
        echo ""
        echo "  A screenshot in the assistant's context can exceed image-size limits"
        echo "  and destroy the whole session. Inspect state as TEXT instead:"
        echo "    Android: adb logcat / uiautomator dump"
        echo "    iOS:     Xcode MCP diagnostics"
        echo "  See .claude/rules/mobile-dev-workflow.md. A human can still run this"
        echo "  in their own terminal."
    } >&2
    exit 2
fi

exit 0
