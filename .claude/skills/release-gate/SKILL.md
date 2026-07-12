---
name: release-gate
description: Pre-tag release ritual for Tessera. Invoke when a release issue becomes the session focus, when preparing to tag any version (0.x or later), or when the maintainer says "prepare the release" / "cut X.Y.Z". Runs the tech-stack review, the three review agents, and the mechanical pre-tag checks. Read-only until the maintainer approves each fix; the maintainer triggers the actual tag.
---

# Release Gate — the pre-tag ritual

Every gate is binary. Run them in order; report per-gate pass/fail to the maintainer.

1. **Tech-stack review** — run the
   [`pre-release-tech-stack-review`](../pre-release-tech-stack-review/SKILL.md) skill
   (trigger is pre-start of release work, but if it was missed — as it was for 0.5.0 —
   run it here; late beats never).
2. **Review agents, diverse lenses** — launch all three on the release diff
   (last tag → HEAD): `doc-consistency-reviewer`, `qa-coverage-reviewer`,
   `security-reviewer` (Agent tool, parallel). Diverse reviewers catch non-overlapping
   problems. Triage findings with the maintainer; fix or consciously accept each.
3. **Scope-vs-reality audit** — everything CHANGELOG/`[Unreleased]` and the feature
   docs claim for this version actually exists and is tested. Either implement the
   gap or re-scope the claim downward. Never tag before reality matches the claim.
4. **Empty-module guard** — `./gradlew publishToMavenLocal -Ptessera.skipSigning=true`
   green locally; every module that declares a target produces an artifact (CI
   publish-smoke mirrors this — do not rely on memory of it).
5. **ABI baselines** — `updateAndroidAbi --rerun-tasks` (it goes UP-TO-DATE-stale),
   then `checkAndroidAbi` / `checkKotlinAbi` green against committed dumps.
6. **CHANGELOG roll** — `[Unreleased]` → `[X.Y.Z] - YYYY-MM-DD`; fresh `[Unreleased]`
   opened in the same PR.
7. **Dependabot state** — `gh api repos/{owner}/{repo}/dependabot/alerts?state=open`:
   anything shipped-artifact-reachable is triaged before the tag (hybrid-by-
   reachability posture); build/test-only alerts get a recorded dismissal reason, not
   silence.
8. **Board** — release issue and everything in Staging flips to Done at the tag;
   device-QA issues must have passed through Test.

**The maintainer pushes the tag. The session never does.**
