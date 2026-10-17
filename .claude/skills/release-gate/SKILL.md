---
name: release-gate
description: Pre-tag release ritual for Tessera. Invoke when a release issue becomes the session focus, when preparing to tag any version (0.x or later), or when the maintainer says "prepare the release" / "cut X.Y.Z". Runs the tech-stack review, the two review agents, the documentation checks, and the mechanical pre-tag checks. Read-only until the maintainer approves each fix; the maintainer pushes the tag.
---

# Release gate: the pre-tag ritual

Every gate is binary. Run them in order and report pass or fail per gate to the
maintainer. Record the results as a comment on the release issue.

1. **Tech-stack review.** Best at the start of release work; if it was skipped, do it
   here. Answer each in one or two lines:
   - Do the foundational choices (KMP, native UI per platform, sealed result types) still
     fit what this release builds?
   - Which new dependencies does it need, and are they maintained, licence-compatible
     with Apache-2.0, and free of known CVEs?
   - Which local tooling can the build not provision (SDKs, CLIs, test hardware such as
     an NFC-capable device)?
   - Which API commitments would this release lock in that deserve a second look?
   - Has any tool reached end of life since the last dependency cadence?
   A significant change of direction becomes an ADR in the internal KB (TES-A-17).
2. **Review agents.** Launch `qa-coverage-reviewer` and `security-reviewer` in parallel
   on the release diff (last tag to HEAD). Triage each finding with the maintainer: fix
   it or accept it with a recorded reason.
3. **Documentation.**
   - `python3 -I scripts/check-docs.py code-docs --swift ../tessera-swift` exits 0.
   - `python3 -I scripts/check-docs.py style --kdoc --swift ../tessera-swift` exits 0.
   - `python3 -I scripts/check-docs.py shelf <folder>` exits 0. The folder holds the
     release's public pages: the vault's `kb-public/new/`, or an export of the public KB
     arranged the same way. The check reads local files only; the repo-root `llms.txt`
     must list every consumer tutorial and how-to by title.
   - Scope against reality: everything `[Unreleased]` and the release's pages claim
     exists and is tested. Implement the gap or narrow the claim; never tag before they
     match.
   - The release's draft pages are ready to publish at the tag.
4. **Empty-module guard.** `./gradlew publishToMavenLocal -Ptessera.skipSigning=true` is
   green locally, and every module that declares a target produces an artifact (CI
   `publish · smoke` runs the same command; do not rely on memory of it).
5. **ABI baselines.** `checkKotlinAbi` is green against the committed dumps for every
   target (jvm, klib, and android under `api/android/`).
6. **CHANGELOG roll.** `[Unreleased]` becomes `[X.Y.Z] - YYYY-MM-DD`, with a fresh
   `[Unreleased]` opened in the same PR.
7. **Dependabot.** `gh api repos/{owner}/{repo}/dependabot/alerts?state=open`: anything
   a shipped artifact can reach is triaged before the tag; build-only and test-only
   alerts get a recorded dismissal reason.
8. **Board.** The release issue and everything in Staging move to Done at the tag.
   Issues with the `device-qa` label have passed through Test.
9. **tessera-swift is complete before the tag.** The release workflow tags tessera-swift
   at its `main` head right after merging the Package.swift pin, so an open PR there is
   left out of the tag (the 0.5.0 incident, TES-138).
   `gh pr list --repo lightine-io/tessera-swift --state open` must be empty. The workflow
   enforces this and fails the publish step otherwise (merge, then re-run the job).
   The workflow also waits for the pin PR's checks in tessera-swift and stops before the
   merge and the tag if any fails (TES-218); the next gate catches that before the tag.
10. **tessera-swift builds against this release's framework.**
    `scripts/test-swift-against-local-framework.sh` exits 0. It builds the XCFramework,
    copies it into `../tessera-swift/.local-xcframework/`, and runs tessera-swift's tests
    with `TESSERA_LOCAL_XCFRAMEWORK=1` on a simulator (pass the tessera-swift path and a
    simulator name as arguments if the defaults do not fit). It takes several minutes and
    restores any existing local framework on exit.
11. **KB and doc links.** `python3 -I scripts/kb-linkcheck.py --quiet --allow-internal`
    exits 0. Drop `--allow-internal` once TES-169 and TES-178 are closed; then no public
    page may link into the internal KB.

12. **README install versions.** `scripts/check-readme-version.sh X.Y.Z ../tessera-swift`
    exits 0. It fails, naming the file and the stale version, unless both READMEs'
    install blocks (the Gradle BOM, the Maven version, the Swift `exact:`, the release
    line) name the version being tagged. Update both READMEs first (the 0.5.1 incident,
    TES-168); tessera-swift's README change must be merged before the tag (gate 9).

**The maintainer pushes the tag. The session never does.**
