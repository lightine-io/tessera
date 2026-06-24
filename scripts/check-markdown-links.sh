#!/usr/bin/env bash
# Verify every clickable markdown link in committed docs resolves to a
# file (or directory) a fresh cloner can actually see.
#
# Complements scripts/check-cross-references.sh, which checks backtick-only
# prose mentions of `*.md` filenames but does not check `[text](path)`
# markdown links and uses the local filesystem rather than the git index
# (so it does not catch links to gitignored files).
#
# This script uses `git ls-files` as ground truth, so a link to a file that
# exists locally but is not tracked (e.g., SESSION-HANDOFF-*, CONFORMANCE-*)
# is reported as broken, matching what a cloner sees.
#
# Known limitations:
# - Inline links only: `[text](url)`. Reference-style `[text][ref]` is not
#   scanned (this project uses reference style only for external URLs).
# - External URL liveness is not checked (no network).
# - Anchor fragments (#section) are stripped before resolution; the existence
#   of the destination heading is not verified.
# - Links inside fenced code blocks are still scanned. Rare in practice.
#
# Exit code: number of broken links (0 = all resolve).
#
# Run from project root.

set -euo pipefail

tracked_paths=$(mktemp)
trap 'rm -f "$tracked_paths"' EXIT

# Tracked files plus every ancestor directory of a tracked file. Git does not
# track directories directly, but directory links like [docs/](docs/) are
# legitimate on GitHub if the directory contains tracked files.
git ls-files | awk '
{
    print $0
    n = split($0, parts, "/")
    path = ""
    for (i = 1; i < n; i++) {
        path = (i == 1 ? parts[i] : path "/" parts[i])
        dirs[path] = 1
    }
}
END {
    for (d in dirs) print d
}' | LC_ALL=C sort -u > "$tracked_paths"

# Pure-bash path normalizer: collapses ./ and ../ segments. No symlink
# resolution. bash 3.2 compatible (macOS default).
normalize_path() {
    local path="$1"
    local IFS=/
    local part
    local -a parts=()
    local i=0
    for part in $path; do
        case "$part" in
            ''|.) ;;
            ..)
                # Pop logically (decrement i); leave stale slots above i in
                # the array — subsequent writes overwrite them, and the join
                # loop only reads 0..i-1. Avoids the bash 3.2 + set -u
                # empty-array unbound-variable bug that bites array rebuilds.
                if [ "$i" -gt 0 ]; then
                    local last_idx=$((i - 1))
                    if [ "${parts[$last_idx]}" != ".." ]; then
                        i=$((i - 1))
                        continue
                    fi
                fi
                parts[$i]=".."
                i=$((i + 1))
                ;;
            *)
                parts[$i]="$part"
                i=$((i + 1))
                ;;
        esac
    done
    if [ "$i" -eq 0 ]; then
        echo "."
        return
    fi
    local result="${parts[0]}"
    local j=1
    while [ "$j" -lt "$i" ]; do
        result="$result/${parts[$j]}"
        j=$((j + 1))
    done
    echo "$result"
}

broken=0
broken_details=""

while IFS= read -r md_file; do
    # `grep -o` returns 1 if no matches; tolerate that.
    matches=$(grep -noE '\[[^]]+\]\([^)]+\)' "$md_file" || true)
    [ -z "$matches" ] && continue

    while IFS= read -r entry; do
        # entry is "LINE:[text](target)"
        line_no="${entry%%:*}"
        match="${entry#*:}"
        # Strip "[text](" prefix and ")" suffix to extract target. Anchor on the
        # `](` boundary, not the first `(`, so a `(` inside the link *text*
        # (e.g. "[Glossary (KB)](url)") does not truncate the target. The grep
        # regex guarantees no `]` in the text, so `](` is unambiguous.
        target="${match#*\]\(}"
        target="${target%\)}"

        # Strip anchor and query.
        path_part="${target%%#*}"
        path_part="${path_part%%\?*}"
        [ -z "$path_part" ] && continue

        # Skip external schemes.
        case "$path_part" in
            http://*|https://*|mailto:*|tel:*) continue ;;
        esac

        # Absolute paths (rare in markdown) are repo-root-relative on GitHub.
        case "$path_part" in
            /*) combined="${path_part#/}" ;;
            *)
                src_dir=$(dirname "$md_file")
                if [ "$src_dir" = "." ]; then
                    combined="$path_part"
                else
                    combined="$src_dir/$path_part"
                fi
                ;;
        esac

        resolved=$(normalize_path "$combined")

        if ! grep -Fxq -- "$resolved" "$tracked_paths"; then
            broken=$((broken + 1))
            broken_details="${broken_details}${md_file}:${line_no}: ${match} -> not in git: ${resolved}"$'\n'
        fi
    done <<< "$matches"
done < <(git ls-files '*.md')

if [ "$broken" -eq 0 ]; then
    echo "All clickable markdown links in committed docs resolve."
else
    echo "$broken broken-for-cloner link(s):"
    echo ""
    printf '%s' "$broken_details"
fi

exit "$broken"
