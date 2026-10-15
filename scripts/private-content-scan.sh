#!/usr/bin/env bash
# Scan tracked files for real document data and private terms. Blocks on a hit.
#
# Two halves:
#   1. MRZ guard (no secrets, runs everywhere, CI included): a line in a main source set,
#      Sources/, or a Markdown file holding a 30+ character MRZ-alphabet run with at least
#      one '<' filler is blocked. Test sources are not scanned: they hold synthetic MRZ from
#      the SDK's own generator. Always allowed: the ICAO Doc 9303 specimen
#      (UTOERIKSSON, L898902C3) and lines marked `mrz-ok`.
#   2. Terms scan (local only): case-insensitive substrings from the gitignored
#      .claude/private-content-terms.local, over tracked Markdown, Kotlin, Gradle, Swift,
#      string catalogs, and YAML. Only the maintainer has the terms file, so this half
#      cannot run in CI. A worktree uses the main checkout's terms file.
#
# Usage:  scripts/private-content-scan.sh            both halves
#         scripts/private-content-scan.sh --mrz-only the CI half (`repo · rules`)
# Called by the PreToolUse hook on `git push` (.claude/settings.json); run it by hand
# before a manual push. The same file serves lightine-io/tessera-swift (copy it there).
#
# Exit: 0 clean (or no terms file), 2 blocked (hits on stderr; exit 2 blocks the hook).

set -euo pipefail

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
cd "$PROJECT_ROOT"
MODE="${1:-all}"

# --- 1. MRZ guard -------------------------------------------------------------------
MRZ_ALLOW='UTOERIKSSON|L898902C3|mrz-ok'
MRZ_HITS=$(
    git ls-files -z -- '*/src/*Main/**' 'Sources/**' '*.md' \
            ':!:*/src/*Test*/**' ':!:Tests/**' \
        | xargs -0 grep -EHn '[A-Z0-9<]{30,}' 2>/dev/null \
        | grep -E ':.*<' \
        | grep -vE "$MRZ_ALLOW" \
        || true
)
if [ -n "$MRZ_HITS" ]; then
    {
        echo "private-content-scan: BLOCKED - MRZ-looking literal outside test sources."
        echo ""
        echo "$MRZ_HITS"
        echo ""
        echo "Real document data must never be committed. If this is a curated synthetic"
        echo "or specimen example, append an 'mrz-ok' marker comment to the line."
    } >&2
    exit 2
fi
if [ "$MODE" = "--mrz-only" ]; then
    echo "private-content-scan: MRZ guard clean."
    exit 0
fi

# --- 2. Terms scan ------------------------------------------------------------------
TERMS_FILE=".claude/private-content-terms.local"
if [ ! -f "$TERMS_FILE" ]; then
    common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
    main_terms="$(dirname "${common:-.}")/$TERMS_FILE"
    [ -f "$main_terms" ] && TERMS_FILE="$main_terms"
fi
if [ ! -f "$TERMS_FILE" ]; then
    echo "private-content-scan: MRZ guard clean; no terms file ($TERMS_FILE), terms scan skipped."
    exit 0
fi

# Case-insensitive alternation of the terms; blank and '#' lines skipped. Extended regex:
# BRE \| is not portable (BSD grep treats it literally).
PATTERN=$(grep -vE '^[[:space:]]*$|^[[:space:]]*#' "$TERMS_FILE" | paste -sd'|' - || true)
if [ -z "$PATTERN" ]; then
    echo "private-content-scan: MRZ guard clean; terms file is empty."
    exit 0
fi

# Generic words that contain a term. Each entry needs a reason here.
#   InvalidData - an English compound in CHANGELOG history, not private content.
FALSE_POSITIVES='InvalidData'

HITS=$(
    git ls-files -z -- '*.md' '*.kt' '*.gradle*' '*.swift' '*.xcstrings' '*.yml' \
        | xargs -0 grep -iEHn "$PATTERN" 2>/dev/null \
        | grep -vE "$FALSE_POSITIVES" \
        || true
)
if [ -z "$HITS" ]; then
    echo "private-content-scan: no private content found in tracked files."
    exit 0
fi

{
    echo "private-content-scan: BLOCKED - possible private content in tracked files."
    echo ""
    echo "$HITS"
    echo ""
    echo "Remove or rewrite it, then re-run. A generic word that only contains a term goes"
    echo "into FALSE_POSITIVES in this script, with its reason."
} >&2
exit 2
