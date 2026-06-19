# Open Questions

This document tracks decisions that have been deliberately deferred — to implementation time, to a future release, or to a moment when more information is available. The purpose is to ensure no deferred item is forgotten between design and implementation.

This document is living. Items are added when a decision is deferred during design. When a decision is made, the entry is marked **Resolved** with a reference to where the resolution was recorded (a feature doc, an ADR, or scope.md) — never deleted. Once an entry is *fully* resolved (no open sub-question, no live trigger), a housekeeping pass graduates it verbatim to [`open-questions-resolved.md`](open-questions-resolved.md), keeping this file lean for its actual job: what is open *now*. Partially-resolved entries stay here until every part closes. An entry that lingers without progress is itself a signal that the question may need attention.

Each entry includes a short description of the question, where it was deferred from, and what kind of resolution it requires.

---

## Deferred to Implementation Time

These questions are not answerable from design alone. They will be settled when implementation begins, often after experimentation or measurement.

### Public API exact names and signatures

The illustrative Kotlin-flavored shapes in feature documents (e.g., `MrzParser.parse(input)`, `MrzGenerator.generateTD3(...)`) describe the intended contracts but do not lock the exact class names, method names, parameter ordering, or visibility modifiers. The final shapes are decided at implementation time, recorded as feature documentation is updated.

**Source:** `mrz-parsing.md`, `mrz-generation.md`, `mrz-validation.md`, `lookup-tables.md`, `transliteration.md`

**Resolution:** Update each affected feature document with the final API shape once implementation lands.

### Validator string-input and explicit-format overloads

`mrz-validation.md` documents `MrzValidator.validate(input: String)`, `validate(input: List<String>)`, and the corresponding overloads with an explicit `MrzFormat`. The first validator slice ships only `validate(document: MrzDocument)`. The string-input path is the standalone validation surface for consumers who want to validate previously-extracted data without re-parsing; it is not blocking the parser-internal validation path.

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Status of Implementation".

**Resolution:** Add string-input overloads in a follow-up slice. They should reuse the same per-format validators that `validate(document)` dispatches to, so a check digit failure detected by the standalone string path produces the same typed error as one detected by the parser-internal path.

### `ValidationResult.passedChecks` shape

`mrz-validation.md` describes `passedChecks` as a transparency surface — "the validators that ran and passed (exposed for transparency; consumers can confirm what was actually verified, not just what failed)." The first validator slice ships `ValidationResult` with `validationFailures` and `warnings` only. Committing to a shape for `passedChecks` (typed enum/sealed list, plain string list, or richer record) before the validator catalog is broader would be a guess about consumer needs (Principle 2).

**Source:** First validator implementation slice; aligns with `mrz-validation.md` "Status of Implementation".

**Resolution:** Decide the shape when more semantic checks land and the catalog is broader. Add `passedChecks` to `ValidationResult` with a default value to keep the addition non-breaking (Principle 9).

### Validator options (configurable thresholds)

`mrz-validation.md` "Date Range Conventions" commits to thresholds being "configurable through the validator's options, with the documented defaults applied when no configuration is provided." The first warning slice ships the implausibly-far threshold as a private constant in `MrzValidator` (10 years, matching `mrz-error-taxonomy.md`). Building a `ValidationOptions`-style surface now would be a guess about which other thresholds eventually need configuring (Principle 11 — internal first, promote when justified).

**Source:** First warning implementation slice; aligns with `mrz-validation.md` "Date Range Conventions".

**Resolution:** When a second configurable threshold lands (likely the date-of-birth `MAX_PLAUSIBLE_AGE_YEARS` cap, or expiry-window thresholds revisited under real-world data), introduce a `ValidationOptions` value class with named, defaulted properties and a single `MrzValidator.validate(document, referenceTime, options)` overload. Keep the defaults exactly matching the current private constants so the addition is non-breaking (Principle 9).

## Deferred to a Future Release

These questions concern functionality that is intentionally not in the current scope. They are tracked here so they are not forgotten when their release approaches.

### Lenient and tolerant parsing modes

The parser currently operates in strict mode only. Lenient mode (tolerating real-world deviations such as extra whitespace) and tolerant mode (recovering from OCR confusions using check-digit-guided disambiguation) are intentionally deferred. They are additive capabilities; the strict-only API does not constrain their later addition.

**Source:** `mrz-parsing.md` ("Strictness")

**Resolution:** Partially resolved (2026-05-29 0.2.0 pre-release review, [ADR-020](decisions/0020-camera-reading-architecture.md)). **Lenient mode ships in 0.2.0** alongside live camera — consumer-chosen, with strict remaining the default and raw values always preserved; lenient forgives benign format noise without changing any value. **Tolerant mode** (check-digit-guided OCR disambiguation) is deferred to **0.3.0** (pre-captured still-image reading, where there is no next frame to retry) and, when built, must *surface* candidate corrections rather than silently overwrite (reader-not-oracle). Live camera handles OCR noise via strict-parse-and-retry across frames.

### Sex field encoding choice (`<` vs `X`)

The generator currently encodes `Sex.UNSPECIFIED` as the filler character `<` by default. Future configuration may allow choosing `X` explicitly. The current decision is made; the configurability is deferred.

**Source:** `mrz-generation.md` ("Edge Cases Worth Calling Out")

**Resolution:** Add configuration option in a future release if consumer demand justifies it.

### Profile inheritance for transliteration

The initial transliteration system does not support profiles inheriting from each other. A "based on ICAO default with overrides" pattern is a possible future enhancement.

**Source:** `transliteration.md` ("Edge Cases Worth Calling Out")

**Resolution:** Add inheritance mechanism in a future release if multiple profiles share substantial common content.

### Multiple profiles per state

A state may have multiple transliteration conventions (different document types, different time periods). The current model represents this as multiple profiles with distinct identifiers (e.g., `XYZ-CURRENT`, `XYZ-LEGACY`). A more structured approach may be added later if needed.

**Source:** `transliteration.md` ("Edge Cases Worth Calling Out")

**Resolution:** Revisit if real-world use cases require structured per-context profile selection.

### Per-language conditionals in non-Latin transliteration

ICAO Doc 9303 Part 3 Section 6 (Annex G) defines transliteration tables for Cyrillic (§6.B) and Arabic (§6.C) scripts in addition to Latin (§6.A). Several entries in those tables carry per-language conditionals — recommendations that differ depending on which language the name is in. As examples in §6.B: `Г` transliterates to `G` except for some languages where it transliterates to `H`; the first character of certain names follows a different rule in Ukrainian than in other Cyrillic-using languages; certain Serbian-language conventions diverge from the general Cyrillic rules. Arabic §6.C has similar per-language structure.

The SDK's `TransliterationProfile` interface in `0.1.0` does not model a "primary language of the name being transliterated" parameter. The interface assumes one identifier per profile (typically a country code), which is sufficient for the Latin-only `0.1.0` coverage where Annex G's recommendations are uniform per codepoint. Extending to Cyrillic / Arabic raises the design question of how to surface language-conditional rules: an additional profile parameter, sub-profiles selected by language, a `LanguageHint` enum, or some other mechanism.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F12) — surfaced during Phase 2 review of Annex G when comparing the Latin-only `buildIcaoLatinMappings` against the full Annex G scope.

**Resolution:** Resolve when non-Latin script profiles ship (post-`0.1.0`, no scheduled release yet). The resolution must (a) name the mechanism the SDK uses to express language-conditional rules, (b) update the `TransliterationProfile` interface and `TransliterationProfileRegistry` if a new selection axis is required, (c) ensure the chosen mechanism is non-breaking to consumers who already use Latin-only profiles. Cross-reference from `transliteration.md` when the first non-Latin profile design begins.

### MIXED read method semantics

The `ReadMethod.MIXED` enum value represents results that combine MRZ from camera with chip data from NFC. This becomes meaningful only when both reading paths are implemented (release 0.6.0 and later).

**Source:** `mrz-data-model.md` (`ReadMethod` enum), `mrz-error-taxonomy.md` (chip/camera mismatch warning example)

**Resolution:** Define exact semantics when NFC reading is implemented and combined-result use cases are concrete.

### Image and capture metadata exposure

When pre-captured image reading and live camera reading are implemented, the SDK has access to metadata about the image or capture: timestamps (EXIF `DateTimeOriginal`, capture time), device identifiers (camera make and model), GPS coordinates if present in EXIF, indicators of editing or screenshot origin, and similar. Exposing this metadata to consumers would help them assess risks the SDK currently surfaces only as "this came from a pre-captured image, here's the risk profile."

This aligns with Principle 5 (Transparency — if we have the data, we expose it) and Principle 1 (Reader, not oracle — consumer interprets the metadata). It also has real complications: metadata can be stripped, fabricated, or simply absent; some fields are PII (notably GPS coordinates) and warrant careful handling; reliability documentation is essential or consumers may over-trust untrustworthy data.

**Source:** Design conversation about how consumers can better assess pre-captured image risk; aligns with `reading-risks.md`.

**Resolution:** Design when pre-captured image reading work begins (release 0.3.0 target). Settle which fields are exposed, how reliability is documented, how PII is handled, and whether the same approach extends to live camera capture metadata.

### Security review pass before public release

Before the 1.0.0 public release, perform a systematic security review pass. The pass should reference the "Areas for Further Analysis" section of `reading-risks.md` and confirm which theoretical concerns are real, which are moot, and which require mitigation. Items confirmed as real either get fixed or get documented in `reading-risks.md` so consumers can account for them. Items confirmed as moot get marked as such so future contributors do not re-litigate them.

The pass is most usefully scheduled after release 0.6.0 (NFC chip reading lands — significant cryptographic surface) but before 1.0.0 (the moment public stability commitments take effect).

**Source:** `reading-risks.md` ("Areas for Further Analysis"); design conversation about theoretical risk handling.

**Resolution:** Schedule and perform the review pass before tagging 1.0.0. Update `reading-risks.md` and other affected documents with the findings.

### Triage the existing Dependabot alerts before the 0.2.0 tag

After the 0.2.0 camera slices, Dependabot reports **10 vulnerability alerts on the default branch (4 high, 6 moderate)** against the transitive dependency graph — surfaced now that A4's `dependency-submission` job feeds Dependabot the whole tree. These are *existing-tree* CVEs; the per-PR `dependency-review` gate only blocks *newly introduced* moderate+ CVEs, so it does not clear what is already there. For a security-sensitive identity SDK, the high-severity ones in particular should be triaged before the `0.2.0` tag. The maintainer confirmed (2026-06-06) this should be handled pre-tag, "at some point."

**Source:** observed during the 2026-06-06 B2/C5 session (`git push` output + the repo Dependabot dashboard).

**Resolution:** Before tagging `0.2.0`, review the alerts on the repo's Dependabot dashboard; upgrade what is fixable (coordinate with the dependency-upgrade cadence), and for anything not upgradable record a one-line accepted-risk rationale. Not a freeze gate, but a pre-tag security-hygiene step.

**Update (2026-06-06): triaged — accept-risk for `0.2.0`; residual folded into the dependency cadence.** All ten alerts are **build-time-only plugin-classpath transitives with zero shipped-artifact exposure.** Every one resolves under `settings.gradle.kts` (the plugin/build classpath), none appears in `gradle/libs.versions.toml`, and the published modules (`mrz-core`, `telemetry`, …) link none of them — an SDK consumer never receives them. They execute only on the maintainer's machine and in CI during build/publish, over the project's own trusted inputs (no attacker-controlled XML / strings / JSON), so realistic exploitability is negligible.

- **Root cause — eight of the nine *live* alerts are the vanniktech maven-publish plugin `0.36.0`.** Its `com.vanniktech:central-portal` Sonatype-upload client pulls `jose4j 0.9.5` (high) and BouncyCastle `bcprov`/`bcpkix`/`bcutil 1.79`; its `gradle-maven-publish-plugin` half pulls `jackson-core 2.15.3`, `jdom2 2.0.6` (high), and `commons-lang3 3.16.0`; and `bcpg` (high) rides the PGP-signing path `signAllPublications()` wires up. The lone non-vanniktech alert, `opentelemetry-api` (moderate), is a settings-classpath transitive of the foojay JDK-resolver. The tenth alert, `httpclient`, is **already effectively mitigated** — Gradle resolves it to `4.5.14`, past the `4.5.13` fix — so there are **nine live, not ten** (verified via `./gradlew buildEnvironment`).
- **Why no upgrade lands now.** Both plugins are **already at their latest stable releases** (vanniktech `0.36.0`, Jan 2025; foojay `1.0.0`, May 2025), so there is no plugin bump that pulls the patched libraries — the clean lever is already pulled. The CVE fixes (`bcprov`/`bcpkix`/`bcpg`→`1.84`, `jose4j`→`0.9.6`, `jackson`→`2.18.6`, `jdom2`→`2.0.6.1`, `commons-lang3`→`3.18.0`, `opentelemetry`→`1.62.0`) landed *after* the plugins' release dates, and there has been no newer plugin release to refresh them.
- **Why not force-pin now.** The only remaining lever is force-overriding those transitives on the build classpath. That is **deliberately declined for `0.2.0`**: it means hand-overriding the live PGP-signing and Sonatype-upload stack — the exact path entangled with the pre-tag signing-key + Sonatype-token rotation — and carrying a manual override that must be *removed* once vanniktech refreshes its deps. For build-time-only CVEs with no consumer exposure, that is poor risk/reward right before the tag.

**Decision:** accept the risk for `0.2.0`; **not a tag gate — but explicitly do not forget it.** This entry stays open, and the **2026-10-01 dependency-upgrade cadence now sweeps accepted-risk entries** (added to that skill) so it is re-examined on a schedule rather than left to memory. Re-check sooner if vanniktech ships a release with refreshed transitives. At each revisit, weigh three options in order of preference: (1) a **vanniktech bump** if one has shipped; (2) if upstream is still stale, **evaluate an alternative publishing/signing path** (e.g. a different Central-portal plugin, or `maven-publish` + the Gradle signing plugin directly) that does not carry the aged transitives — a cleaner long-term fix than overrides; (3) **force-pin** the transitives only as a last resort. Posture: serious but proportionate — checked rather than hand-waved, reasoning on the record, fixed properly at the right time, without churning the publishing path to zero out a dashboard.

### Automate the cross-repo SPM (iOS) publish — post-0.2.0

For `0.2.0`, the iOS side of a release is finished **by hand**: the release workflow builds the `Tessera` XCFramework, attaches the zip to the main repo's GitHub Release, and prints its checksum; a maintainer then updates `Package.swift` in the separate `lightine-io/tessera-swift` repo with that URL + checksum and tags it. The Maven side is automatic (the workflow stages the upload; a maintainer clicks "release" on Sonatype). The asymmetry is **structural** — the SPM manifest lives in a separate, protected repo (signed commits, PR-required, immutable tags, no bypass), so automating it needs a cross-repo write that satisfies those rules.

**Decision (2026-06-07):** keep iOS **manual for `0.2.0`** — the first release is the proving run for the exact `Package.swift` shape + checksum flow — and **automate the cross-repo SPM publish post-tag**, as the first release-tooling task toward `0.3.0`. Approach to evaluate then: a GitHub App / fine-grained PAT scoped to `tessera-swift` that opens + merges the `Package.swift` PR and creates the signed tag within that repo's rulesets. Not a `0.2.0`-tag blocker.

### Platform I/O and UI module scaffolding

The pre-implementation checklist names `mrz-camera-{platform}`, `emrtd-nfc-{platform}`, and `mrz-camera-ui-{platform}` modules as scaffold targets. They are not scaffolded in 0.1.0 because each requires its corresponding platform toolchain (AGP for Android variants, Xcode for iOS variants) and there is no implementation in 0.1.0 that would exercise an empty-shell module. Empty platform modules add build configuration that has to be maintained without delivering any value until the corresponding feature work begins.

**Source:** Pre-implementation scaffolding session; aligns with `architecture.md` ("as appropriate" wording in the checklist) and Principle 11.

**Resolution:** Partially resolved (2026-05-29) — the **camera I/O modules (`mrz-camera-android`, `mrz-camera-ios`) are scaffolded in 0.2.0** with their first implementation, per [ADR-017](decisions/0017-mobile-targets-and-build-stack.md) and [ADR-020](decisions/0020-camera-reading-architecture.md). The remaining named modules stay on their roadmap schedule (NFC I/O `emrtd-nfc-{platform}` at 0.6.0; UI `mrz-camera-ui-{platform}` at 0.5.0). Keep this entry until those land.

### iOS OCR analysis-rate throttle (`analysisInterval`) as a public setting

`AVCaptureMrzScanner` analyses at most one camera frame per a fixed internal interval (`analysisInterval`, 200 ms ≈ 5 frames/sec — the "don't OCR every frame" practice). Apple Vision is far heavier than is useful for a document held still in front of the lens, so capping the analysis rate bounds the memory sawtooth and saves battery without perceptibly delaying detection. The interval is currently a **private** constant, not a constructor parameter — consumers cannot tune it. Whether to expose it — and at what name / type / default, and whether symmetrically on Android's `CameraXMrzScanner` (which has no throttle because ML Kit keeps up at frame rate) — is deferred.

The decision **not** to expose it now is deliberate (made with the user, 2026-05-31): per Principle 2 (the option that assumes less wins) and Principle 11 (don't promote before justified), and because [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) locks any public API from `0.x`, exposing a knob nobody has asked for would be a speculative, permanent commitment. The throttle itself is kept — only its *public exposure* is deferred. Note the throttle does **not** affect a live camera preview a consumer draws: the preview is fed by the camera at full frame rate, independent of the (lower) analysis rate — so the on-screen video is smooth regardless.

**Status:** Open — deferred; keep `analysisInterval` private for now. Headless device verification (2026-05-31) showed smooth analysis at ~5 fps with bounded memory and live decodes; no consumer need has surfaced.

**Source:** 2026-05-31 iOS camera-stall fix session; decision made with the user.

**Trigger:** A real camera **UI / preview** is built (a sample app or a consumer integration) and the analysis rate is observed to actually matter — detection feels too slow, or a consumer asks to tune the battery / responsiveness trade-off. Revisit the name, type, default, and Android symmetry then.

### Camera MRZ-candidate detection vs real OCR output

The analyse-frame core locates an MRZ by finding a run of consecutive recognized lines whose count and length exactly match a known ICAO shape (TD3/MRV-A 2×44, TD1 3×30, etc.). The Android live-device slice showed this exact-shape match is brittle against the bundled ML Kit general-Latin recognizer reading a screen-rendered MRZ: line 1's long `<` filler run is **collapsed** (ML Kit does not emit long runs of identical `<`, and reads `<<` as `«`), so the line never reaches its nominal length and no candidate is found — while *any* two consecutive equal-length lines of surrounding prose can spuriously match. Line 2 (few fillers) read at exact length reliably. No clean `Decoded` → `Success` was achieved on the screen/monospace target; the parser correctly *rejected* the garbage reads (`MrzCharacterSetViolation` on the `«`), which is reader-not-oracle working as intended. Caveat: a screen-rendered generic-monospace MRZ is a weak proxy — real OCR-B on paper, or a region-cropped frame, may behave differently. The finding suggests the deferred refinements — an image-level ROI crop to the MRZ band before OCR, and/or a length-tolerant / sliding-window candidate matcher — are likely *necessary* for the Android convenience layer to actually decode, not merely nice-to-have. Relates to the tolerant-mode work deferred to 0.3.0 (see "Lenient and tolerant parsing modes" above).

**Status:** Open — exact-shape matching is insufficient against real ML Kit output; an ROI crop and/or a length-tolerant matcher is the likely resolution, designed with the tolerant-mode work.

**Source:** 2026-05-30 Android live-device slice.

**Trigger:** When the Android convenience layer needs to reliably decode — the 0.3.0 still-image / tolerant-mode work, or earlier if device testing against a printed OCR-B sample or a real document is pursued.

### 0.3.0 scope decisions (parked) — platform scope, OCR-core module shape, tolerant mode, EXIF metadata

**Parked 2026-06-08 (maintainer), at the close of the 0.3.0 pre-release tech-stack review.** The review (a six-dimension read-only pass per the [`pre-release-tech-stack-review`](../.claude/skills/pre-release-tech-stack-review/SKILL.md) skill) found the `0.2.x` foundation extends to 0.3.0 (pre-captured image reading) with **no architectural change, no new Android/iOS dependencies, and no new local tooling** — the analyse-frame seam (`MrzFrameAnalyzer` + `MrzTextRecognizer<F>`, [ADR-020](decisions/0020-camera-reading-architecture.md)/[ADR-021](decisions/0021-shared-mrz-camera-core-module.md)) is the reuse point, and both platform OCR engines already accept still images. Four maintainer-owned forks remain open; **#1 and #2 gate the start of 0.3.0 code**, #3 and #4 size the release. This entry is the committed anchor of that parked state (the review's full working record is a maintainer-local note).

1. **Platform scope** — Android+iOS only (mirrors the 0.2.0 camera precedent; JVM stays pure string parsing), vs. adding a JVM/desktop OCR path (requires a Tesseract-class native-binary dependency: heavy maintenance + supply-chain surface, a third OCR vendor). *Technical lean: Android+iOS only.*
2. **Shared OCR-core module shape** — keep `mrz-camera-core` as the shared core a still-image module depends on (zero coordinate churn; a "camera"-named dependency for a non-camera feature is a naming wart, not a defect), vs. introducing a neutral `mrz-ocr-core` (cleaner name, but `tessera-mrz-camera-core` is already published at `0.2.1`, so a rename now means deprecating a published coordinate under [ADR-007](decisions/0007-strict-backward-compat-from-0x.md)). *Technical lean: keep.*
3. **Tolerant mode in 0.3.0** — include check-digit-guided candidate-surfacing per ADR-020's deferral (requires settling the surfaced-candidate result shape pre-start — it lands on locked types and must surface, never overwrite), vs. shipping only the internal length-tolerant candidate matcher and deferring tolerant mode again. *Lean: include, per the ADR-020 plan; the matcher fix is likely necessary either way (see "Camera MRZ-candidate detection vs real OCR output" above).*
4. **EXIF/capture-metadata exposure in 0.3.0** — include (see "Image and capture metadata exposure" above; GPS as a distinct, gated, never-default-logged PII surface; the type *shape* settled pre-start, the field list growable), vs. defer to keep 0.3.0 focused on the core read + opt-in. *Genuinely close; no lean recorded.*

Pre-start API-stability locks the review flagged regardless of the forks (ADR-007 locks each at ship): the **opt-in mechanism** for saved-image reading (lean: a required, non-defaulted capability parameter — no permissive default, not a bare `Boolean`; the safe default is "cannot read saved images", so there must be no default), making **provenance a constructor parameter** on `MrzFrameAnalyzer` (its KDoc already prescribes this when a non-camera source lands; default `LIVE_CAMERA` keeps it non-breaking), and the **still-image input type `F`** per platform.

**Source:** 0.3.0 pre-release tech-stack review (2026-06-08); parked by the maintainer pending his calls.

**Resolution:** When the maintainer makes the four calls, record them in the `scope.md` 0.3.0 pre-release-review block (mirroring the 0.1.0/0.2.0 blocks), plus an ADR if the module-shape or opt-in decision warrants one, then mark this entry Resolved.

---

## Deferred Pending External Information

These questions cannot be settled by us alone — they depend on documentation, decisions, or developments outside the project.

### External spec data licensing strategy

ICAO Doc 9303 (Machine Readable Travel Documents) is freely downloadable as PDFs from `icao.int` in six languages — see the [Doc 9303 page](https://www.icao.int/publications/doc-series/doc-9303). All 13 parts are accessible without payment or registration. The technical content the SDK needs is in:

- **Part 3** — common specs: document type codes (Section 4), country codes (Section 5), transliteration tables (Section 6 / Annex G), MRZ alphabet, check digit algorithm
- **Part 4** — TD3 (passports), including the canonical sex character set (§4.1)
- **Parts 5–7** — TD1, TD2, MRV-A, MRV-B specifics

**Reading the spec is unambiguously fine.** The SDK's algorithms (check digit, alphabet, format layouts) were implemented from the spec's technical descriptions — algorithms are not copyrightable.

**Embedding the spec's data tables verbatim is the open question.** ICAO's stated copyright position (per the site copyright notice) is restrictive: *"None of the materials provided on this web site may be used, reproduced or transmitted, in whole or in part, in any form or by any means... without permission in writing from ICAO."* Whether technical tables qualify as facts (uncopyrightable in many jurisdictions) versus creative compilations (copyrightable) is a legal question the project has not yet resolved. The conservative path for an Apache-2.0 open-source project is to avoid verbatim ICAO content until the question is settled.

This was the umbrella entry for four related downstream questions on which it bore directly:

- **Sex value canonical set per ICAO Doc 9303** — resolved by reading Part 4 §4.2.2.2 during the pre-tag audit; see the entry below.
- **Document type code table completeness** — populated from Part 4 §4.4 (the harmonized P-prefix set) and Part 5 Appendix B (the `AC` Crew Member Certificate code); see the entry below.
- **Country code table completeness** — populated from the published ISO 3166-1 alpha-3 list and ICAO Doc 9303 Part 3 §5 extensions; see the entry below.
- **Transliteration profile coverage completeness** — Latin section (Annex G §6.A) populated to full coverage; non-Latin scripts (Cyrillic, Greek, Arabic) remain deferred to a future release; see the entry below.

**Source:** Pre-`0.1.0`-tag recap (2026-05-18) — the audit established that the spec is accessible and corrected a prior framing in this document ("no authoritative copy on hand") that was incorrect.

**Resolution:** The project's operative posture from the pre-tag conformance pass is **cite-and-implement**: read Doc 9303, implement the technical content based on our understanding, cite section numbers in code KDoc and feature docs. This is path (4) above — facts-not-copyrightable, applied to the specific technical tables shipped in `0.1.0` (ISO 3166-1 alpha-3 list, the §5 extensions, the harmonized document codes, and the Annex G Latin transliteration table). The umbrella's original "stay deferred" framing was over-cautious; it served its purpose by forcing the conversation that produced the cite-and-implement posture.

What remains for `1.0.0`:

1. **Legal review of the cite-and-implement posture before public release.** The specific scope: confirm that the technical data shipped in `0.1.0` (and any further bulk additions from Doc 9303 before `1.0.0`) is defensible under the project's Apache-2.0 license terms. Path (1) — requesting ICAO permission — remains available if review surfaces a concern.
2. **Non-Latin transliteration coverage.** Cyrillic, Greek, and Arabic tables (Annex G §6.B, §6.C, §6.D) are not in `0.1.0`. When added, the same cite-and-implement posture applies. See the "Transliteration profile coverage completeness" entry below for the related design question on per-language conditionals.

Paths (2) — alternative sources — and (3) — defer to consumer-provided data — are no longer in active consideration for the spec-derived data the SDK ships, though they remain options for future tables where licensing concerns become acute.

Each downstream entry below records its own resolution against this posture.

### Specific document type implementations

Some document types are in scope but their specific format details require documentation that may not be currently public. The architecture supports them; implementation is added when documentation becomes available.

**Source:** `scope.md` ("Specific Document Implementations")

**Resolution:** Implement each as documentation becomes available.

### Transliteration profile coverage completeness

The transliteration profiles that ship in `mrz-core` (`IcaoDefaultTransliterationProfile` and `AzeTransliterationProfile`) draw their Latin-script mappings from a shared internal helper (`buildIcaoLatinMappings()`). The Latin portion of ICAO Doc 9303 Part 3 §6.A (Annex G) is now covered in full as of the pre-`0.1.0`-tag conformance audit (2026-05-18, `CONFORMANCE-NOTES-2026-05-18.md` finding F5, with the F2 schwa correction and F13 codepoint-disambiguation cross-check). Non-Latin scripts (Cyrillic §6.B, Arabic §6.C, and the Greek table) are not yet implemented.

Per-profile overrides on top of the shared table evolved in two passes. **At 2026-05-18 (PR #43):** `AzeTransliterationProfile` overrode only the schwa pair (the load-bearing divergence ADR-009 originally called out), inheriting Annex G no-expansion for everything else. **At 2026-05-19 (pre-tag empirical pass):** AZE practice was verified against sample documents + fluent-speaker testimony + the [ALA-LC romanization table](https://www.loc.gov/catdir/cpso/romanization/azerbaij.pdf), which revealed a systematic phonetic Anglicization pattern. `AzeTransliterationProfile` now ships 8 overrides (`Ə/ə → A`, `Ç/ç → CH`, `Ğ/ğ → GH`, `Ş/ş → SH`, `X/x → KH`, `C/c → J`, `J/j → ZH`, `Q/q → G`). The four overrides on letters already in the MRZ alphabet (`C`, `J`, `Q`, `X`) required the profile to consult its override map before the MRZ-alphabet passthrough check — see ADR-009 "Implementation Note: Override Lookup Order".

Both profiles' fallback policy is to map any unmapped character to the filler `<`, so partial coverage of non-Latin scripts is safe: a consumer transliterating a Cyrillic or Arabic name through the current profiles gets filler output rather than a runtime failure. Adding entries to the underlying tables (or new per-script profiles) is a non-breaking change provided the existing mappings stay stable.

**Source:** First implementation slice for `TransliterationProfile` (2026-05-17 session); aligns with `docs/features/transliteration.md` ("The ICAO Default Profile", "Country-Specific Profiles") and ADR-009.

**Resolution:** Latin section resolved (PR-4 of the conformance pass). Non-Latin scripts remain deferred to a post-`0.1.0` release. When they are added, three resolution points come up:

1. **Per-language conditionals.** §6.B and §6.C use per-language exceptions that the `0.1.0` profile interface does not model — see the new "Per-language conditionals in non-Latin transliteration" entry under "Deferred to a Future Release" above.
2. **Profile structure.** Decide whether non-Latin tables belong inside the existing default profile (treating Cyrillic / Arabic as universal) or in separate per-script profiles selected explicitly by the consumer (parallel to country-specific overrides).
3. **AZE `Ö` / `Ü` empirical verification.** The current no-expansion inheritance is recorded as a working choice pending real-document evidence; see the entry below.

Country-specific profile expansions for additional issuing states ship per consumer demand.

### No publicly-findable regulation on MRZ transliteration for the issuing state coded AZE

Per the 2026-05-18 conformance audit's Phase 4 research, no specific regulation defining MRZ name transliteration for the issuing state coded `AZE` was located in publicly-searchable sources (searches against the state's publicly-available legal information system, cabinet-level resolutions, migration-service references, BGN/PCGN, and ECHR case-law sources). The original `AzeTransliterationProfile` (single schwa override, `Ə/ə → A`) was justified via the BGN/PCGN + ICAO chain plus observed practice.

The 2026-05-19 pre-tag pass extended the profile to 8 systematic overrides covering AZE's phonetic Anglicization pattern (see [ADR-009](decisions/0009-transliteration-profiles.md) for the full reframe). The citable basis is now stronger than at conformance time:

- **Primary source: ALA-LC romanization table for the AZE Latin alphabet** (US Library of Congress / British Library standard). ALA-LC produces `ch`, `gh`, `kh`, `sh`, `ġ` (for Q), `ă` (for Ə), `ı̐` (for I), `i` (for İ), `ȯ` (for Ö), `u̇` (for Ü). When the MRZ alphabet strips ALA-LC's diacritics to ASCII, the result matches every observed AZE encoding (for the letters ALA-LC covers).
- **Secondary source: empirical sample documents** (passport + 2 ID cards) verified the rules for `Ç`, `Ğ`, `İ`, `I`, `Ə` directly.
- **Tertiary source: fluent speaker's testimony with worked examples** verified `X → KH`, `Ş → SH`, `Q → G`, `J → ZH`, `C → J`.

A specific government regulation is still not in publicly-searchable form. Two possibilities remain equally consistent with the search outcome: (a) no specific regulation exists and the issuing authority follows ICAO + a local convention captured by ALA-LC; (b) a regulation exists but is not publicly accessible. The project's posture no longer depends on resolving this — the profile rests on ALA-LC plus the corroborating evidence.

**Source:** Pre-`0.1.0`-tag conformance audit (2026-05-18, finding F23); 2026-05-19 empirical update.

**Resolution:** Partially resolved. The substantive question (what does AZE encode in the MRZ?) is answered by the ALA-LC chain + corroborating evidence. The narrower question (is there a citable national regulation?) is open; revisit if such a regulation surfaces and update `AzeTransliterationProfile` KDoc + [ADR-009](decisions/0009-transliteration-profiles.md) accordingly. No `0.1.0` action required.

### AZE profile `J → ZH` and `C → J` empirical basis

Of the 8 overrides in `AzeTransliterationProfile`, five (`Ç → CH`, `Ğ → GH`, `Ş → SH`, `X → KH`, `Q → G`) are derivable from the ALA-LC romanization table (Library of Congress standard) and corroborated by either sample documents or worked examples. Two — `J → ZH` and `C → J` — are not in ALA-LC's explicit table (ALA-LC treats both as plain Latin letters that pass through). They were added based on:

- A fluent speaker's testimony with worked examples (`Jalə → ZHALA`, `Cəlal → JALAL`)
- The phonetic Anglicization principle that explains the other 6 overrides (AZE J is /ʒ/ = English "zh"; AZE C is /dʒ/ = English "j")
- Internal consistency: in the AZE profile, every letter whose source phonetic value diverges from the corresponding English letter is overridden, so leaving J and C alone would be inconsistent with the systematic pattern

The two overrides remain the empirically weakest links in the profile. If a future sample passport contains either letter in the name field and shows passthrough (`J → J`, `C → C`) instead, the overrides should be removed.

**Source:** 2026-05-19 pre-tag empirical pass.

**Resolution:** Confirm against a real sample document containing `J` or `C` in the name field when available. Either confirm current behavior (no change) or remove the override and update `AzeTransliterationProfile` KDoc + [ADR-009](decisions/0009-transliteration-profiles.md). Tracking so the gap is not forgotten.

### Driver's license format choice (mDoc vs proprietary)

When driver's license NFC reading is added in a future release, the choice between standard mDoc-compliant licenses (ISO 18013-5) and proprietary national formats depends on which markets the project prioritizes.

**Source:** `scope.md` ("Beyond 1.0")

**Resolution:** Decide when driver's license NFC work begins, based on consumer needs at that time.

### Trust anchor source for chip signature verification

Cryptographic verification of NFC chip signatures requires trust anchors (typically Country Signing Certificate Authority certificates, distributed via the ICAO Public Key Directory or similar). The choice of trust anchor source is its own design problem, deferred until chip signature verification is on the active roadmap.

**Source:** `scope.md` ("Beyond 1.0")

**Resolution:** Design when chip signature verification is added.

### Distribution channels (Maven Central, CocoaPods, SPM)

**JVM coordinate shape, lockstep versioning, BOM, first-publish version, and first-publish scope are resolved by [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md).** The published groupId is `io.lightine.tessera` (backed by the verified Sonatype namespace at `io.lightine`); artifactIds follow the `tessera-<module>` convention; modules version in lockstep with a `tessera-bom` artifact for version alignment; the first Maven Central publication shipped at 0.1.1 (published 2026-05-29) with all five current modules plus the BOM; no snapshot builds at 0.x.

What remained open under this entry — the iOS distribution channel (CocoaPods vs Swift Package Manager) — is now resolved (see Resolution). The only distribution question still future is the **web (JS/Wasm) channel (npm)**, decided when/if the web target activates.

**Source:** Implicit; not yet referenced.

**Resolution:** JVM distribution resolved by [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md) and **executed** — `io.lightine.tessera:*:0.1.1` was published to Maven Central on 2026-05-29 (publishing slices in PRs [#88](https://github.com/lightine-io/tessera/pull/88)–[#90](https://github.com/lightine-io/tessera/pull/90)). iOS distribution is resolved (2026-05-29) — **Swift Package Manager** (Kotlin/Native XCFramework wrapped as a Swift package; CocoaPods rejected as legacy) per [ADR-019](decisions/0019-ios-distribution-via-spm.md), within a **one-channel-per-ecosystem** model (Maven Central for JVM/Android/desktop, SPM for iOS, npm for web when that target activates); execution lands in the 0.2.0 iOS slice. The iOS **packaging mechanics are now built** (the `mrz-camera-ios` SPM slice): the `Tessera` XCFramework assembly + the `packTesseraXCFramework` zip task are wired and CI-verified, and the open `Package.swift`-location sub-decision is resolved to a **dedicated distribution repo** (see [ADR-019 execution notes](decisions/0019-ios-distribution-via-spm.md#execution-notes-020-ios-slice)). The remaining iOS publication step — create the distribution repo, attach the zip to the GitHub release, finalize `Package.swift` — lands with the 0.2.0 release cut.

### Swift `Flow` / coroutines ergonomics for the SPM consumer

The `Tessera` XCFramework (ADR-019) exposes `MrzCameraScanner.results` as a Kotlin `Flow<MrzScanResult>` and the `suspend` analyse/recognize functions through Kotlin/Native's default Objective-C/Swift export. That export is functional but not idiomatic Swift: a `Flow` surfaces as a Kotlin handle a Swift caller collects through the generated coroutines bridge, not as a native `AsyncSequence`/`async` API. A nicer Swift experience — e.g. adopting [SKIE](https://skie.touchlab.co/) (which maps `Flow` to `AsyncSequence` and `suspend` to Swift `async`), or hand-writing a thin callback/`AsyncStream` adapter — is a candidate refinement.

**Source:** Surfaced in the `mrz-camera-ios` SPM packaging slice (the XCFramework header review).

**Status:** Partially resolved — adapter still deferred. The **freeze question this entry raised is decided (2026-06-04, pre-0.2.0 review)**: the Objective-C/Swift *projection* of the camera API (the `Flow`/`suspend` export) is **explicitly marked provisional through `0.x`** — **not** locked at the `0.2.0` tag — recorded in the `MrzCameraScanner` KDoc. So a later idiomatic-Swift surface (a hand-written `AsyncStream`/callback adapter, or [SKIE](https://skie.touchlab.co/)) remains a **legal, non-breaking change** under [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) for the whole `0.x` line, rather than a 1.0-only change. **Still deferred:** building that adapter — not a blocker for first iOS distribution (the API is usable from Swift as-is); revisit when there is a real Swift consumer, or as a 0.3.0+ polish item, weighing SKIE's value against adding a Gradle-plugin dependency to the build (ADR-020's "Swift-friendly headless API" consideration).

### Local regulatory considerations for the project's author

The project's author is in a jurisdiction whose regulatory frameworks for open source software, contributor patent grants, government data handling, and conflict-of-interest rules differ from US/EU contexts and are evolving. Specific legal review may be warranted before public release, particularly if the SDK is later deployed in any government context.

**Source:** Design conversation about author location and local context.

**Resolution:** Consult applicable local legal guidance before public release. Apache 2.0 with explicit patent grant (ADR-010) is a defensive choice that helps where local frameworks are less codified, but does not substitute for legal review.

### `mrz-camera-android` ABI baseline — the AGP-KMP android target is not covered by Kotlin's abiValidation

**Accepted gap (in force since the `0.2.x` releases; first committed record 2026-06-10).** `abiValidation` is enabled on every published module, but Kotlin's tool only dumps targets registered through the Kotlin `targets` DSL (JVM, iOS, …). The android target configured via Google's `com.android.kotlin.multiplatform.library` plugin's `android {}` block is not seen by it, so `mrz-camera-android` has **no committed `api/` baseline** and its `checkKotlinAbi` passes **vacuously** (empty compared to empty). An accidental public-API break in the Android-only surface (`CameraXMrzScanner`, `MlKitMrzTextRecognizer`) would **not** be caught by CI — unlike every other published module, whose baselines are committed and gated. This is a tooling limitation, not a configuration miss. Until 2026-06-10 the acceptance was recorded only in a machine-local handoff, and the module's build-file comment overclaimed protection (fixed alongside this entry).

**Compensating control:** review the Android-only public surface by hand on every change to `mrz-camera-android` — [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) still applies; it is just not machine-gated for this one target. The shared contract types live in `mrz-camera-core`, which **is** baselined — the uncovered surface is only the Android-side layer.

**Source:** `0.2.x` abiValidation rollout (PR [#152](https://github.com/lightine-io/tessera/pull/152)); the vacuous-gate behavior verified and root-caused in the 2026-06-10 gaps audit.

**Resolution / trigger:** at each dependency-cadence checkpoint (next 2026-10-01), re-check whether Kotlin's `abiValidation` (or an AGP-side equivalent) has gained android-target dumping — adopt it and commit the baseline the moment it exists. If the consumer audience grows before then, consider interim hardening: a CI assertion that baselined modules' dumps are non-empty, or a third-party Android ABI-check tool.

---

## Future Project Toolkit

This is not a project-specific item but is recorded here so it is not lost. It can be moved to a separate document later if it grows.

### Reusable patterns and document templates for future projects

The patterns established during this project's design — dispute-driven discussion, principles-first design, the specific document templates (ADR format, feature doc structure, etc.) — are not specific to this SDK. They could be useful for future projects undertaken by the same author. Extracting them into a reusable toolkit (outside this project's repository, in a personal `~/code/.shared-context/` or similar) would let future projects start with the same disciplines without re-deriving them.

**Source:** Design conversation about future-project considerations.

**Resolution:** Extract patterns to a separate personal toolkit at a future point. Not blocking this project's progress; tracked here so it does not get lost.

---

## Future Improvements to Consider

These are not deferred decisions and not blocking. They are improvements to the project's documentation system or process that may be worth making *if certain conditions arise*. Each entry includes the trigger that would justify revisiting.

The list is intentionally short. Premature improvements are noise; trigger-based items get acted on when relevant rather than gathering dust.

### Gradle wrapper `distributionSha256Sum` pinning

The wrapper downloads the Gradle distribution over HTTPS with `validateDistributionUrl=true` but no `distributionSha256Sum` checksum pin — a supply-chain hardening gap relative to the project's otherwise-pinned posture (SHA-pinned GitHub Actions, content-filtered repositories). Parked, on a date: **pin it at the next wrapper bump (the 2026-10-01 dependency cadence)**, taking the checksum from Gradle's official distribution-checksums page. (This parking was previously recorded only in a gitignored plan file; re-recorded here 2026-06-12 so it survives.)

**Trigger:** the 2026-10-01 dependency-upgrade cadence (wrapper bump), or any earlier wrapper change.

### Binary-size / performance posture

The project has no measured size baseline (AAR, XCFramework) and no dependency-size evaluation policy — camera/OCR SDKs are size-sensitive for mobile consumers, and without a baseline there is no warning system if a dependency bloats. Deliberately deferred (recorded 2026-06-12, from the gaps audit): no targets are set now; the cheap first step is measuring and recording a baseline at a release-prep pass.

**Trigger:** the next release-prep pass (record a baseline), a consumer raising size concerns, or the 1.0.0 readiness review (decide whether to commit targets).

### Document Evolution section in conventions.md

Add explicit guidance on how documents evolve: when feature docs get rewritten vs. extended, when sections move to their own docs, when ADRs get superseded vs. updated, how to handle obsolete content.

**Trigger:** When documentation patterns start drifting noticeably between docs, or when a contributor asks "how should this document change?"

### Lessons-learned log

Add a LESSONS.md or RETROSPECTIVE.md capturing what went well and what didn't, after each release or milestone. Useful for long-term project health.

**Trigger:** After the first internal release (0.1.0) ships, when there is actually a milestone to retrospect on.

### Code precedent examples

Once implementation has produced idiomatic code in the project, consider whether to extract small example snippets into the documentation as "this is what a parser implementation in this project looks like." Not pre-written — emerges from real first implementations.

**Trigger:** After 0.1.0 lands, if Claude Code consistently produces non-idiomatic code that requires correction.

### Runnable camera sample app

A small runnable sample app (Android first, iOS later) that integrates the headless camera reader end-to-end — points the camera at a synthetic MRZ and prints the parsed result. It would double as the on-device test harness for the 0.2.0 camera work *and* as living integration documentation (ties to "Code precedent examples" above). 0.2.0 ships written integration docs (snippets + a standalone guide) instead; a runnable sample is deferred to keep 0.2.0 scoped to the headless SDK plus docs.

**Source:** 2026-05-29 0.2.0 pre-release review ([ADR-020](decisions/0020-camera-reading-architecture.md)); deferred from PR-F (consumer integration docs).

**Trigger:** When the headless camera reader is stable on a platform and a runnable demo would add more than the written snippets + integration guide already provide — likely late in 0.2.0 or alongside the 0.5.0 UI.

### CI and repository hardening for the mobile build

Non-blocking hardening items surfaced by the security reviews of the 0.2.0 build-foundation slices (which enabled the Android then iOS targets on the core modules, [ADR-017](decisions/0017-mobile-targets-and-build-stack.md)):

1. **CI does not compile the mobile targets.** The `check` workflow runs `./gradlew check` on an `ubuntu-latest` runner, which compiles and tests only the JVM target. Neither mobile target is covered. The **Android** target compiles via `assemble`/`build` (not `check`) and the runner has no Android SDK provisioned — Linux *can* compile it once the SDK is present. The **iOS** targets compile via Kotlin/Native, which requires a **macOS** runner with Xcode; a Linux runner cannot build Apple targets at all. So a `commonMain` change that breaks Android or iOS compilation — or a future `androidMain`/`iosMain` source file that does not compile — would pass CI and surface only on a developer machine. At the build-foundation slices the exposure is minimal (the only platform-specific sources are the two one-line Normalization `actual`s, both verified locally — Android on the JVM-identical `java.text.Normalizer`, iOS on the simulator across the full 577-test suite), but it widens as the camera slices add real `androidMain`/`iosMain` code. Closing the Android side means a CI job that provisions the Android SDK and runs `./gradlew compileAndroidMain` (compile-only, no emulator); closing the iOS side means a `macos-latest` job with Xcode that runs the Konan compile (and ideally the `iosSimulatorArm64` tests) — both kept separate from the fast JVM `check`.
2. **`google()` repository has no content filter.** `settings.gradle.kts` adds Google's Maven repository without a `content { includeGroupByRegex(...) }` filter, so Gradle may consult it for any group, not just the Google-owned ones (`com.android.*` / `com.google.*` / `androidx.*`). With a locked version catalog and `FAIL_ON_PROJECT_REPOS` already set, the risk is low; the best-practice hardening is to scope each `google()` declaration to those groups.

**Source:** 2026-05-30 build-foundation-slice security reviews (`security-reviewer` subagent), Android then iOS; all items rated low / info.

**Update (2026-05-30, `mrz-camera-android` analyse-frame slice):** the **Android side of item 1 and item 2 are done.** `settings.gradle.kts` now content-filters both `google()` declarations to `com.android.*` / `com.google.*` / `androidx.*` (item 2 closed). A new `android-compile` CI job (`.github/workflows/check.yml`, `ubuntu-latest` + the runner's Android SDK) runs `./gradlew compileAndroidMain` across every module — so the camera module's ML Kit `androidMain`, and the core modules' Android targets, are now type-checked on CI (item 1, Android side, closed). **Still open:** the **macOS iOS-compile job** (item 1, iOS side), which lands with the first real `iosMain` code (`mrz-camera-ios`).

**Update (2026-05-30, `mrz-camera-ios` slice):** **item 1 is now fully closed.** A new `ios-compile` CI job (`.github/workflows/check.yml`, `macos-latest` + the runner's Xcode, with `~/.konan` cached) runs `./gradlew iosSimulatorArm64Test compileKotlinIosArm64 compileKotlinIosX64` — so the core modules' iOS targets and the new `mrz-camera-ios` `iosMain` (AVFoundation/Vision) are compiled on CI, and the common host tests run on the Simulator. With both the Android and iOS sides covered, a `commonMain`/`androidMain`/`iosMain` compile break can no longer pass CI. **Item 2 was closed earlier; only item 3 (SHA-pinning GitHub Actions) remains** as a deferred, non-mobile chore.

3. **GitHub Actions pinned by floating tag, not commit SHA** (repo-wide, all workflows — surfaced in the camera slice's CI review). `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, and `actions/cache` (the last added by the `ios-compile` job) are referenced by major-version tag (`@v6` etc.), which is mutable. All are first-party (GitHub / Gradle Foundation) so the risk is low, but pinning to a commit SHA with a version comment is best-practice supply-chain hardening. Deferred as a separate, non-mobile chore (would touch every workflow at once; Dependabot `actions` updates can automate the SHA bumps if enabled).

**Trigger:** Items 1 and 2 are done (Android `android-compile` + iOS `ios-compile` CI jobs; both `google()` declarations content-filtered). The remaining **item 3** (SHA-pin GitHub Actions) is a deferred repo-wide chore — act on it before the first public push, or let Dependabot `actions` updates automate the SHA bumps once enabled.

**Update (2026-05-30, 0.2.0 pre-tag cross-platform security audit):** the audit (`security-reviewer`, two passes — camera code + supply-chain/publishing/repo) found **no high-severity or code-vulnerability issues** across the 0.2.0 camera surface; the actionable findings were cheap doc/contract hardening, applied directly (PII "do-not-log-verbatim" KDoc on `MrzScanResult`/`ParseResult`; a single-thread lifecycle contract on `MrzCameraScanner`; a RENDEZVOUS-invariant comment on `CameraXMrzScanner`). It also surfaced three further **deferred, non-blocking** mechanical-hardening items, tracked here alongside item 3:

4. **Gradle wrapper has no `distributionSha256Sum`.** `gradle-wrapper.properties` sets `validateDistributionUrl=true` (URL-pattern check) but does not pin the distribution zip's SHA-256, so a CDN/DNS-compromise at download time would not be caught. Add `distributionSha256Sum=<sha>` (from `services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`) at the next wrapper bump (cadence: 2026-10-01).
5. **No dependency CVE / license scan in CI.** Dependabot handles declared-dependency bumps, but no CI step enumerates the transitive graph for CVEs or license conflicts (e.g. the ML Kit `transport-backend-cct` transitive stack noted in [`reading-risks.md`](reading-risks.md)). Candidates: GitHub's `dependency-review-action` (PR-scoped, low-noise) or OWASP Dependency-Check. Most valuable before the 1.0.0 public release; useful to baseline at 0.2.0.
6. **No mechanical guard that ML Kit stays the bundled model.** A future `mlkit-text-recognition` version could switch to a Play-Services delivery model; a Gradle/CI assertion that `text-recognition` (bundled) — not `text-recognition-default` (Play Services) — is on the graph would catch that. Belt-and-suspenders; the pinned catalog version already constrains it.

On **item 3**: the repository is already public, so the "before first public push" trigger has effectively passed — item 3 (SHA-pin Actions) is now *due* rather than future, though it remains a separate repo-wide chore (best done in one pass across all workflows, or via Dependabot SHA bumps). Items 4–6 are not 0.2.0-tag blockers. The SPM artifact-authenticity item (checksum binds integrity not authenticity) is tracked separately in [ADR-019's execution notes](decisions/0019-ios-distribution-via-spm.md#execution-notes-020-ios-slice) as a release-runbook decision.

**Update (2026-06-05, action-plan A4 / Q6): item 5 CVE-scan resolved (first-party pattern).** Added two CI workflows, both first-party (matching `check.yml`'s no-third-party-on-the-supply-chain stance): `dependency-submission.yml` (`gradle/actions/dependency-submission`) submits the **full resolved transitive Gradle graph** to GitHub on push-to-main and on PRs — closing the gap that GitHub cannot resolve a Gradle graph natively, so Dependabot alerts (already enabled) now see the whole tree; and `dependency-review.yml` (`actions/dependency-review-action`) is a **blocking PR gate** that fails a PR introducing a `moderate`-or-higher CVE. The chosen pattern is the GitHub/Gradle-native one over OWASP Dependency-Check / OSV-Scanner specifically to avoid adding a third-party scanner to the build. **License *gating* is deliberately deferred** (not part of "resolved"): an incomplete `allow-licenses` list would fail legitimate Apache-2.0/MIT deps, so licenses are *surfaced* in the PR summary now and a curated allow-list can tighten the gate once the transitive license set is enumerated. **Items 3 and 6 follow in their own changes** (SHA-pinning Actions; the ML-Kit bundled-model assertion); item 4 stays on the 2026-10-01 wrapper-bump cadence.

**Update (2026-06-05, action-plan A4): item 6 resolved — ML-Kit bundled-model guard, with a corrected discriminator.** A `verifyMlKitBundledModel` Gradle task in `mrz-camera-android` (run by the `android-compile` CI job and locally) fails the build if ML Kit is no longer the bundled model. **The discriminator in this item's original text was wrong:** it is *not* the absence of a `text-recognition-default` / Play-Services artifact. The bundled artifact `com.google.mlkit:text-recognition` **transitively depends on** `com.google.android.gms:play-services-mlkit-text-recognition` (the recognizer API), so that coordinate is on the graph in **both** the bundled and unbundled flavors — a guard keyed on its absence would always fail. The true marker is the bundled *model* component **`com.google.mlkit:text-recognition-bundled-common`**, which is on `androidRuntimeClasspath` only when the model is linked into the app; the task asserts its presence. Verified both ways before committing: passes as-is, and fails (with the intended message) when the dependency is temporarily swapped to the unbundled `play-services-mlkit-text-recognition`. (Caught by resolving the actual dependency graph rather than trusting the item's framing — the consult-vendor-docs / verify-against-reality habit.)

**Update (2026-06-05, action-plan A4): item 3 resolved — GitHub Actions SHA-pinned.** Every action across all three workflows (`check.yml`, `dependency-submission.yml`, `dependency-review.yml`) is now pinned to a full commit SHA with a trailing `# vX.Y.Z` comment — `actions/checkout` (v6.0.3), `actions/setup-java` (v5.2.0), `actions/cache` (v5.0.5), `gradle/actions/setup-gradle` + `gradle/actions/dependency-submission` (v6.1.0), `actions/dependency-review-action` (v5.0.0) — closing the mutable-tag exposure (a `@v6` tag can be repointed). Dependabot's already-enabled `github-actions` updates bump both the SHA and the version comment, so this stays current without manual churn. A header note in `check.yml` records the convention so new actions are pinned the same way. **With this, items 1–3 and 5–6 of the entry are resolved; only item 4 (wrapper `distributionSha256Sum`) remains, parked on the 2026-10-01 wrapper-bump cadence.** Separately, repository **secret scanning + push protection were enabled** (2026-06-05) — the mechanical secret-detection half the security-reviewer resolution paired with the CVE scan.

### CONTRIBUTING.md at project root

GitHub recognizes a top-level `CONTRIBUTING.md` and surfaces it on PR creation. The current `docs/conventions.md` covers what a CONTRIBUTING.md would cover. A small top-level file pointing to conventions.md may be useful when the project goes public on GitHub.

**Trigger:** Before the first public push to GitHub or equivalent.

**Resolution:** Resolved (2026-05-20). Added a short [`CONTRIBUTING.md`](../CONTRIBUTING.md) at the project root pointing to `docs/conventions.md`, `.claude/git-workflow.md`, `docs/versioning.md`, `docs/testing.md`, `docs/principles.md`, `docs/open-questions.md`, the PR template, and `SECURITY.md`. The file is intentionally short — it does not duplicate the full conventions, just makes them discoverable from GitHub's contributor flow. Landed alongside `SECURITY.md`, `.github/CODEOWNERS`, `.github/dependabot.yml`, and `.github/workflows/check.yml` in the pre-public-readiness pass.

### CHANGELOG.md initial entry

The project commits to Keep a Changelog format (see `docs/versioning.md`). The actual `CHANGELOG.md` file does not yet exist. It will be created with the first internal release entry.

**Trigger:** Before tagging 0.1.0.

**Resolution:** Resolved — `CHANGELOG.md` exists at project root in Keep a Changelog format. The initial `[0.1.0]` entry is populated by the tag commit; the `[Unreleased]` section above it accumulates entries for the next release per the conventions in `docs/versioning.md`.

### Tessera-specific security-reviewer subagent

The Claude Code optimization audit in PR [#66](https://github.com/lightine-io/tessera/pull/66) identified a security-reviewer subagent as a candidate AI-tooling addition. Deferred because at `0.1.0` (pure parsing/validation/generation) the security surface is narrow — limited to PII handling in logs/error messages, input validation on MRZ parsers, dependency hygiene, and avoiding hardcoded real-looking document data in tests. The built-in `security-review` skill that ships with Claude Code is generic-but-sufficient for this surface. Designing a Tessera-specific subagent now would produce a thin prompt-only artifact without enough code to ground its guidance against. The real security surface arrives in `0.2.0` (camera + image processing), `0.5.0` (BAC), `0.6.0` (PACE/NFC crypto), at which point a domain-aware subagent has actual patterns to enforce.

**Trigger:** During the Pre-Release Tech-Stack Review for `0.2.0` (per the [`pre-release-tech-stack-review`](../.claude/skills/pre-release-tech-stack-review/SKILL.md) skill). Decide: ship a domain-aware subagent in `.claude/agents/security-reviewer.md`, OR confirm the built-in skill remains sufficient. The decision becomes load-bearing once sensitive code starts landing.

**Resolution:** Resolved (2026-05-29 0.2.0 pre-release review) — **ship it.** A Tessera-specific `.claude/agents/security-reviewer.md` is added (read-only; *advise-don't-dictate*) with a broad mandate: PII in logs/errors, input validation, camera-buffer memory hygiene, supply-chain (dependency vulnerabilities, licenses, plugin provenance), publishing (signing, POM, no committed secrets), and repo/GitHub settings. It is paired with mechanical CI checks (dependency CVEs, secret scanning) so coverage does not depend on a session remembering to invoke it. The 0.2.0 camera/image surface is where a domain-aware reviewer earns its keep.

### Dokka multi-module aggregation and hosted docs site

The Dokka 2 wiring shipped with publishing infrastructure slice 2 generates per-module HTML javadoc jars (`tessera-types-<v>-javadoc.jar`, `tessera-mrz-core-<v>-javadoc.jar`, etc.) — one self-contained docs set per artifact, matching how Maven Central distributes attached files. The trade-off: KDoc cross-references that span modules (e.g., a `[MrzParser]` reference in `types/vocabulary/ReadMethod.kt` pointing at a class in `mrz-core`) cannot be resolved by Dokka when documenting `types` in isolation, so they render as plain text instead of clickable links in the published HTML. ~5-7 such references exist at 0.1.1; Dokka emits non-fatal warnings during `publishToMavenLocal` for each. IDE navigation (IntelliJ project model) is unaffected — only the published HTML loses click-to-navigate for these references.

The proper fix is **Dokka multi-module aggregation** + a **hosted docs site**: configure a root-level `dokka { }` block that treats all modules as one project, generate a unified HTML site with full cross-linking, and host it (GitHub Pages, Netlify, or a project-owned subdomain like `docs.lightine.io`). Per-module javadoc jars then become either minimal (own pages only) or `JavadocJar.Empty()` since the real docs live at the hosted URL referenced from each module's POM `url`. This is what major Kotlin ecosystem libraries do (kotlinx.coroutines, kotlinx.serialization at `kotlinlang.org/api/...`).

Deferred because at the project's current scale (single maintainer, narrow 0.1.x public API surface) the few unlinked cross-references in published HTML are a mild UX paper-cut, not a broken experience — and the proper fix requires standing up docs-hosting infrastructure that is its own multi-decision conversation (where the site lives, how versioning works in URLs, what CI publishes it, what versions stay alive at the hosted endpoint). That conversation deserves its own slice rather than getting wedged into a publishing-infrastructure PR.

**Trigger:** When public-API browsing UX matters enough to justify the infrastructure work — typically around the `1.0.0` polish pass (public stability commitment lands; the API is wide enough to benefit from rich cross-module navigation; the project is mature enough to deserve a real docs site). Could also trigger earlier if external integrators provide feedback that the per-module HTML is hard to navigate. Decision form: an ADR locking the docs-hosting target + a publishing-infrastructure slice wiring up aggregation + CI deployment.

### Cross-project planning tool (YouTrack vs GitHub Projects vs current setup)

**Resolved (2026-06-19):** Adopted **YouTrack** as the inner planning + Knowledge-Base tool for Tessera — ahead of the original two-project trigger below. The operating model and roles are recorded in [ADR-022](decisions/0022-operating-model-and-roles.md) and [`workflow.md`](workflow.md); session access is gated by [`.claude/rules/youtrack.md`](../.claude/rules/youtrack.md). The original deferral is retained below for the record; it graduates to [`open-questions-resolved.md`](open-questions-resolved.md) in a future housekeeping pass.

When future projects under `io.lightine` start (potentially with shared contributors), unified visibility across projects may justify a dedicated project-management tool. The current setup (GitHub Issues + `docs/open-questions.md`, `.handoffs/`, ADRs, `CHANGELOG.md`) is sufficient for a single active project; adding tooling now would create stale data and split the source of truth across more places (Principle 11 — internal/simple first, promote when justified).

Three realistic options when this is revisited:

- **Stay with current setup** — GitHub Issues per repo + the existing markdown infrastructure. Lowest overhead; works fine if cross-project coordination stays informal.
- **GitHub Projects** — free, integrated, supports cross-repo boards under a GitHub organization. Provides backlog and roadmap views without adopting a new tool or new login.
- **YouTrack** (JetBrains) — free for up to 10 users on cloud. Strongest for customizable workflows and serious project management. Highest overhead.

**Trigger:** When **both** conditions are true: (a) a second active project exists under `io.lightine` (actual code, actual work — not just an idea), and (b) cross-project visibility or coordination cost becomes a real felt pain. Until both hold, the existing infrastructure is sufficient.

### Mechanical guard against reading credential-bearing files into AI context

On 2026-05-29 an assistant Read the whole of `~/.gradle/gradle.properties` (to check one non-secret line), pulling the maintainer's PGP signing key, its passphrase, and the Sonatype token into the session transcript — an unintended, persistent exposure. This is a general risk for *any* contributor's AI tooling, not one machine: credential-bearing files (`~/.gradle/gradle.properties`, `.env`, `*.pem`, SSH keys) can be read whole by mistake.

Current mitigations are **advisory only** — the "Reading a Whole Credential-Bearing File" pitfall in `.claude/known-pitfalls.md` and a grep-only flag in the maintainer's `reference_local_jdk_setup` auto-memory. By the project's own "Prescribe the path, prohibit the border" pattern, an advisory rule relies on the assistant remembering; the durable fix is **mechanical enforcement**, like the existing `Bash(git push *)` private-content-scan hook and the `block-screenshot.sh` PreToolUse hook.

Candidate design (for a dedicated session): a PreToolUse hook that blocks or warns when a tool call targets a known secret-bearing path (a denylist of globs — `~/.gradle/gradle.properties`, `**/.env*`, `**/*.pem`, `~/.ssh/id_*`, etc.), steering the assistant to a grep-the-one-line alternative; fail-open and human-terminal-unaffected, mirroring the screenshot hook. Honest open design points: whether a PreToolUse hook can intercept the **Read tool** at all (the screenshot and private-content hooks guard **Bash** — so `cat` / `grep -r` are covered there, but a Read-tool guard may need a different mechanism), the denylist scope, and block-vs-warn.

**Source:** 2026-05-29 0.2.0-review session — the exposure described above, plus the user's request to address it generally (for all contributors), not per-machine.

**Trigger:** A dedicated session, separate from feature work, when there's appetite to design the guard. Security-relevant but low-urgency; until then the advisory pitfall + memory flag are the interim mitigation.

---

## How to Use This Document

When making a deferred decision:

1. Find the entry here
2. Make and record the decision in the appropriate document
3. Mark the entry **Resolved** with a reference to the resolution — do not delete it. A later housekeeping pass moves fully-resolved entries to [`open-questions-resolved.md`](open-questions-resolved.md); partially-resolved entries stay until every part closes.

When deferring a new decision during design or implementation:

1. Add an entry here under the appropriate section
2. Cross-reference from the document where the deferral was made
3. Note what kind of resolution is needed and roughly when

The goal is that no deferred item is forgotten and no implementation work begins while critical questions are unresolved without explicit acknowledgment.
