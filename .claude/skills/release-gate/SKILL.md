---
name: release-gate
description: Pre-tag release ritual for Tessera. Invoke when a release card becomes the session focus, when preparing to tag any version (0.x or later), or when the maintainer says "prepare the release" / "cut X.Y.Z". Runs the tech-stack review, the two review agents, the documentation checks, and the mechanical pre-tag checks. Read-only until the maintainer approves each fix; the maintainer pushes the tag.
---

# Release gate: the pre-tag ritual

The release triggers and the post-release review are in `.claude/how-we-work.md`
(sections "Releases" and "Reviews").

Every gate is binary. Run them in order and report pass or fail per gate to the
maintainer. Record the results as a comment on the release card.

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
   A release that adds a new reading method (chip reading over NFC is the first) needs
   more than these lines: a written technology review, done before the first code of
   that method, in the internal KB under History (the NFC one is "Tech-stack review
   before NFC", TES-209), and a requirements map written from the standard's text
   (TES-301). The gate checks that both exist and that the release matches them. Any
   later new reading method (for example a USB reader) starts the same way: the review
   comes before its first code, and the gate refuses the release without it (TES-209).
2. **Review agents.** Launch `qa-coverage-reviewer` and `security-reviewer` in parallel
   on the release diff (last tag to HEAD). Triage each finding with the maintainer: fix
   it or accept it with a recorded reason. A finding to fix before the tag becomes its
   own card in Develop, and the release card is marked blocked until it merges; it is
   never folded into the release-prep PR. Every other finding goes to New
   ([how we work](../../how-we-work.md), "During work").
3. **Documentation.**
   - `python3 -I scripts/check-docs.py code-docs --swift ../tessera-swift` exits 0.
   - `python3 -I scripts/check-docs.py style --kdoc --swift ../tessera-swift` exits 0.
   - `python3 -I scripts/check-docs.py shelf --live` exits 0. It reads the live public KB
     anonymously over HTTPS (no token): every ✓ in the capability matrix has its how-to
     page, and the repo-root `llms.txt` lists every consumer tutorial and how-to by exact
     title with its KB article URL. Publish the release's pages first. Exit 2 means the KB
     could not be read (network or HTTP error): rerun, never skip it.
   - `python3 -I scripts/check-docs.py samples --live` exits 0. It reads every public
     tutorial and how-to the same way and compares each Kotlin code block with its region
     in the `:samples` module (`samples/kotlin`), which CI compiles. A changed block, a
     block with no region, or a region with no block is a finding: fix the page or the
     sample so they match again. Exit 2 means the KB could not be read: rerun, never skip it.
   - Scope against reality: everything `[Unreleased]` and the release's pages claim
     exists and is tested. Implement the gap or narrow the claim; never tag before they
     match.
   - The release's draft pages are ready to publish at the tag.
4. **Empty-module guard.** `./gradlew publishToMavenLocal -Ptessera.skipSigning=true` is
   green locally, and every module that declares a target produces an artifact (CI
   `publish · smoke` runs the same command; do not rely on memory of it). The same
   job's Maven smoke step (`samples/maven-smoke`: a plain Maven build imports the BOM
   and compiles against the root `tessera-mrz-core`) is green on the release commit.
5. **ABI baselines.** `checkKotlinAbi` is green against the committed dumps for every
   target (jvm, klib, and android under `api/android/`).
6. **CHANGELOG roll.** `[Unreleased]` becomes `[X.Y.Z] - YYYY-MM-DD`, with a fresh
   `[Unreleased]` opened in the same PR.
7. **Dependabot.** `gh api repos/{owner}/{repo}/dependabot/alerts?state=open`: each open
   alert has its card per the guide's "Vulnerabilities" section (shipped dependency: a
   Fixed Date card due 60 days after the alert; build-only or test-only: Intangible, or
   dismissed with a recorded reason). A card past its due date is raised before the tag.
8. **Board.** Test is empty, or the owner accepts its open cards in writing on the release
   card. After the release run succeeds, CI (`youtrack-sync.yml`) sets "Fixed in" on every
   card in Staging, the release card included, and moves it to Done; check that it did.
9. **tessera-swift is complete before the tag.** The release workflow tags tessera-swift
   at its `main` head right after merging the Package.swift pin, so an open PR there is
   left out of the tag (the 0.5.0 incident, TES-138).
   `gh pr list --repo lightine-io/tessera-swift --state open` must be empty. The workflow
   enforces this and fails the publish step otherwise (merge, then re-run the job).
   The workflow also waits for the pin PR's checks in tessera-swift and stops before the
   merge and the tag if any fails (TES-218); the next gate catches that before the tag.
   Its merged work is in the CHANGELOG too: tessera-swift has no changelog of its own and
   no entry check, so list what merged there since the last release,
   `gh pr list --repo lightine-io/tessera-swift --state merged --search "merged:>=<date of the last tag>"`,
   and for each PR find its line under `[Unreleased]` or the words "no changelog" with a
   reason in its body. A PR with neither gets an entry before the tag.
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
13. **KB install versions.** Search the KB for the previous release number
    (`search_articles` with `project: TES <previous version>`, or the KB search box).
    Every hit must be version history ("or later", "since"). An install snippet, a
    "Written for Tessera" footer, or a "current release" sentence on the old number is
    updated with the release's pages, and only after the page's code was checked against
    this release. By hand until the shelf check can do it.
14. **Size.** Build the release locally,
    `./gradlew publishToMavenLocal -Ptessera.skipSigning=true -Dmaven.repo.local=<temp dir>`,
    and record on the release card the size of every `.jar`, `.aar` and `.klib`, and of
    each new runtime dependency's main file. After the release run, add the size of
    `TesseraCore.xcframework.zip` (named `Tessera.xcframework.zip` up to 0.7.0). Compare
    with the previous release card (the first baseline, for 0.7.0, is on TES-29). There is
    no fixed limit: explain in one line on the card any growth of more than 10% in the
    SDK's own files, and every new runtime dependency.

**The maintainer pushes the tag. The session never does.**
