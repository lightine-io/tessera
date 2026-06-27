## Summary

<!-- 1-3 sentences describing what this PR does and why. Reviewers read this first. -->

## Documentation Impact

<!--
List every doc this PR touches or should have touched. If a doc was updated, name it.
If a commitment in a doc is now implemented, name the doc + the commitment.
If this PR introduces a new "deliberate gap" (intentionally incomplete behavior), say so
and link to where it's tracked (the project's issue tracker entry, file-level comment, locked test).

Examples:
- Updated `docs/features/mrz-data-model.md` to clarify `documentType` trimming rule.
- Implements `MrzCheckDigitMismatch` per `docs/features/mrz-error-taxonomy.md`.
- Deferred: name field truncation detection (tracked in handoff watch items).
-->

## Tests

<!--
- New tests added: <count + categories (unit / property / integration)>
- Tests modified: <list>
- Total project test count after this PR: <number>
- Any flakiness or environment dependencies: <list>
-->

## Open Questions Touched

<!--
Items in the project's issue tracker that this PR resolves, modifies, or adds.
- Resolved: <entry>
- Added: <entry>
- Modified: <entry>
-->

## Changelog

<!--
Confirm `CHANGELOG.md` `[Unreleased]` section is updated to reflect this PR's changes,
grouped Added / Changed / Deprecated / Removed / Fixed / Security per Keep a Changelog.
For non-trivial PRs this is required (per `docs/versioning.md` and CLAUDE.md).
-->

- [ ] CHANGELOG.md `[Unreleased]` updated, or N/A (explain why)

## Verification

<!-- Quick sanity checklist before requesting review. -->

- [ ] `./gradlew build` passes locally
- [ ] `bash scripts/check-cross-references.sh` reports all cross-references resolve
- [ ] No private content in the diff (per memory `feedback_private_content_scan.md`)
- [ ] Branch name follows convention (`feature/...`, `fix/...`, `docs/...`)
