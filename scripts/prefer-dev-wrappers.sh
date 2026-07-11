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
# OVERRIDE: if the prescribed driver genuinely cannot do the job, end the command
# with a trailing `# raw-ok` comment. ONLY the trailing-comment form counts —
# `raw-ok` appearing elsewhere (argument text, a grep pattern searching for it)
# does not disarm the guard (it did before 2026-07-11; that was a silent-bypass
# hole). The override is the conscious checkpoint — use it deliberately, not
# reflexively. Regression cases live in scripts/test-prefer-dev-wrappers.sh.
#
# Deliberately NOT guarded here: plain `adb` (logcat / pm grant are legitimate
# raw uses; adb screenshots are blocked by block-screenshot.sh) and
# `xcrun devicectl` (the physical-device console loop the Xcode MCP doesn't own).
#
# Matching is anchored to COMMAND-TOKEN POSITION (start of the command, or right
# after `&&` / a pipe), not a bare substring. So merely *naming* a raw tool inside
# argument text — a PR body, a commit message, a `grep` pattern, this file's own
# path — does not trip the hook; only an actual invocation does. (Earlier this
# substring-matched and false-positived on `gh pr create --body "...sdkmanager..."`;
# see .claude/known-pitfalls.md "Reaching for the Raw Vendor Tool When the Rule
# Can't Reach".) The conscious `# raw-ok` override still covers the rare case where
# a real invocation must go through.

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

# Flatten newlines/tabs to spaces so a multi-line command (a heredoc body, a
# quoted PR/commit message) is matched as ONE line: `^` then means "start of the
# whole command", and a tool name sitting inside argument text stays out of
# command-token position. (`$cmd` — the original, unflattened — is still shown
# in the message below.)
flat="$(printf '%s' "$cmd" | tr '\n\r\t' '   ')"

# Conscious override — proceed when the prescribed driver genuinely can't.
# TRAILING-COMMENT FORM ONLY: `... # raw-ok` at the end of the command. A bare
# substring match let any command that merely *mentioned* raw-ok (e.g. a grep
# searching for it) silently bypass the guard — found live 2026-07-11.
printf '%s' "$flat" | grep -Eiq '#[[:space:]]*raw-ok[[:space:]]*$' && exit 0

# A command token = start-of-command, or right after `&&` / a pipe, then an
# optional path prefix (.../cmdline-tools/latest/bin/sdkmanager). `android sdk ...`
# never contains these names, so the prescribed CLI is never matched; and a bare
# MENTION in an argument (preceded by ordinary text or quotes) is intentionally
# left alone. `;` and bare `&` are deliberately NOT separators here — they appear
# too often in prose ("the sdkmanager & avdmanager tools") to anchor on safely;
# the cost is missing the rare `cd x; sdkmanager` form, acceptable for a nudge.
# A pipe counts as a separator only when PRECEDED BY WHITESPACE: regex
# alternations inside quoted grep/rg patterns (`raw\|sdkmanager`, `raw|sdkmanager`)
# have no space before the `|`, while a real pipeline almost always does
# (`cmd | sdkmanager`). Found live 2026-07-11: a grep whose PATTERN named the
# tools after `\|` was falsely blocked. Accepted miss: `cmd| sdkmanager`
# (no space before the pipe) slips through — same nudge tradeoff as `;` above.
# Env-var prefixes (`TESSERA_LOCAL_XCFRAMEWORK=1 xcodebuild ...`) are part of the
# command token — without this, an env prefix dodged the guard entirely (gap
# found by the 2026-07-11 test sweep; the documented iOS commands were never
# actually being intercepted).
token='(^|&&|[[:space:]][|])[[:space:]]*([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*([[:alnum:]_./~-]*/)?'

# Android SDK / AVD / emulator / deploy raw tools -> the Android CLI.
# `emulator` (the SDK's emulator binary) and `adb install` joined 2026-07-04
# (audit): they were the remaining old-way paths around `android emulator start`
# and `android run`. Other `adb` uses (logcat, pm grant, exec-out, input) stay
# deliberately unguarded — they are the prescribed text-inspection path.
if printf '%s' "$flat" | grep -Eq "${token}(sdkmanager|avdmanager|emulator)([^[:alnum:]_.-]|\$)|${token}adb[[:space:]][^|;&]*[[:space:]]install([^[:alnum:]_.-]|\$)|${token}adb[[:space:]]+install([^[:alnum:]_.-]|\$)"; then
    raw="$(printf '%s' "$flat" | grep -oE "${token}(sdkmanager|avdmanager|emulator|adb)" | grep -oE 'sdkmanager|avdmanager|emulator|adb' | head -1)"
    {
        echo "prefer-dev-wrappers: BLOCKED — drive Android via the Android CLI, not raw ${raw}."
        echo ""
        echo "  Command: $cmd"
        echo ""
        echo "  Use the prescribed wrapper:"
        echo "    android sdk install|list|update|remove    (instead of sdkmanager)"
        echo "    android avd ...                           (instead of avdmanager)"
        echo "    android emulator start|stop|list|...      (instead of raw emulator)"
        echo "    android run ...                           (instead of adb install)"
        echo "  The full drive-dev tool map (Android CLI, Xcode MCP, ./gradlew) is in"
        echo "  .claude/rules/mobile-dev-workflow.md. If the CLI genuinely can't do"
        echo "  this, append '# raw-ok' to the command to override deliberately."
    } >&2
    exit 2
fi

# iOS build / simulator raw tools -> the Xcode MCP (mcpbridge).
if printf '%s' "$flat" | grep -Eiq "${token}xcodebuild([^[:alnum:]_.-]|\$)|${token}xcrun[[:space:]][^|;&]*simctl"; then
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
