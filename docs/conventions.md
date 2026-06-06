# Conventions

This document captures the operating rules for working on the project: how documents are written, how things are named, how decisions are recorded, how contributions happen. Where principles describe *what we value* and architecture describes *how the code is organized*, conventions describe *how we work together*.

These are conventions, not principles. They can change with reasonable cause; they are not foundational commitments. When a convention starts feeling forced or counterproductive, it is up for discussion.

This document is living. New conventions may be added as the project encounters new situations; existing conventions may be revised or removed when they no longer serve the project.

---

## Documentation Conventions

### What Every Document Must Include

Every document in `docs/` opens with a short paragraph stating what it is, who it is for, and how it relates to the broader project. A reader landing on the document with no context should be able to tell within a few sentences whether they are in the right place.

Every document explicitly declares whether it is *living* (subject to ongoing revision) or *fixed* (a snapshot of a point in time, such as an ADR after it is accepted). Living documents may evolve freely; fixed documents change only through replacement, not edit-in-place.

### What Every Feature Document Must Include

Feature documentation describes a specific capability of the SDK. Each feature document includes:

- **Purpose** — what the feature does, in one or two sentences
- **Platform availability** — which targets the feature is available on, and from which release. If the feature is target-specific (some are, by their nature), this is stated explicitly. If the feature is target-agnostic, this is stated explicitly.
- **API surface** — the public types, methods, and contracts the feature exposes
- **Inputs and outputs** — what the feature consumes and what it produces
- **Usage example** — for any public API a consumer calls, a copy-paste `## Usage` snippet (imports, the call, branching the result) **verified against the shipped API**, not invented or transcribed from an illustrative shape. An illustrative-but-wrong example is worse than none.
- **Errors and warnings** — every error and warning the feature can produce, typed and named
- **Related principles** — which project principles inform this feature's design
- **Related decisions** — which ADRs are relevant to this feature

This list is the minimum. Features may include more sections (diagrams, edge cases) as appropriate. An AI assistant adding or changing a public API auto-loads the usage-example requirement via [`.claude/rules/usage-example-required.md`](../.claude/rules/usage-example-required.md).

### What Every Decision Record Must Include

Architecture Decision Records (ADRs) capture significant decisions in a stable, reviewable format. Each ADR includes:

- **Title** — short, descriptive
- **Status** — proposed, accepted, deprecated, or superseded (with reference to superseding ADR if applicable)
- **Context** — what situation led to needing the decision
- **Decision** — what was decided, stated unambiguously
- **Consequences** — what becomes true (positive and negative) as a result of this decision
- **Alternatives considered** — other options weighed and why they were not chosen

ADRs are *fixed* once accepted. If a decision changes, a new ADR supersedes the old one; the old one is marked deprecated but remains in the record. The intent is that the reasoning behind every significant choice is preserved, not lost.

### What Every How-To / Setup Guide Must Include

How-to and setup guides walk a contributor through getting something working — machine setup, a platform toolchain, a development workflow. Unlike feature documents (which describe the SDK), these describe a *procedure*, and a stale or wrong procedure wastes a contributor's time silently. Each how-to / setup guide includes:

- **Prerequisites first** — what must already be true before the steps work, listed at the top, with a reference for where to get each tool. Prerequisites double as the capability gate: "iOS work requires macOS" is a prerequisite stated up front, not a branch discovered halfway down.
- **One verified happy path** — the single route the project has actually confirmed, written as the main line. Not a copied (and possibly stale) internet guide, and not every theoretical variation — one path known to run.
- **Targeted troubleshooting** — only the real "if X then Y" problems actually hit on this project, not an exhaustive tree of every possible failure. Full branching rots and bloats; a short list of genuine gotchas stays trustworthy.
- **A "Verified working: [date]" stamp** — the trust signal recording when this path was last confirmed against reality. It ties to the habit of checking current vendor documentation at write time (see [`.claude/rules/consult-vendor-docs.md`](../.claude/rules/consult-vendor-docs.md)).

This shape applies to **every** how-to / setup guide, on every platform, for the life of the project — mobile today, web / desktop / AR when they arrive. It is defined by *what the document is* (a procedure to get something working), not by which platform or release it covers. An AI assistant editing any guide auto-loads this convention via [`.claude/rules/guide-style.md`](../.claude/rules/guide-style.md); the contributor-facing router that points at these guides is [`contributor-map.md`](contributor-map.md).

### Cross-References Between Documents

Documents reference each other by relative file path within the repository. References are explicit: "see `architecture.md`" rather than vague allusions like "see the architecture doc somewhere."

Principles are referenced by number: "Principle 1" or "Principle 1 — Reader, Not Oracle." Numbers are stable; the eleven principles are fixed in their numbering even if their order in the document changes.

When a document references another that does not yet exist, the reference is included anyway and the missing document is tracked as a known gap. Forward references are acceptable and signal intent; they should be resolved by writing the missing document, not by deleting the reference.

---

## Target-Agnostic Discipline

A persistent risk in this project is unconsciously assuming a specific platform. The first concrete features are mobile, but the project's commitments must hold across mobile, backend, web, desktop, and any future target.

When proposing or documenting any rule, behavior, or commitment, the question to ask is:

> *"Does this assume a specific platform, or does it apply wherever the SDK runs?"*

If a rule assumes a specific platform, one of the following must be true:

- The rule is restated as a target-agnostic principle, with platform-specific implementations documented separately
- The rule is explicitly scoped to that platform ("this applies only to the Android UI module")
- The rule is moved to platform-specific documentation, not project-wide documentation

Platform-specific examples and illustrations are welcome — but they must be examples of an underlying principle, not the principle itself.

This discipline applies to documents, code comments, public APIs, and conversations during design.

---

## Naming Conventions

### Modules

Module names follow the pattern `{domain}-{role}[-{platform}]`:

- `{domain}` identifies the subject area (`mrz`, `emrtd`, `types`, `telemetry`, `logging`)
- `{role}` identifies what the module does (`core` for pure logic, `nfc` or `camera` for I/O, `ui` for user interface)
- `{platform}` is appended only for platform-specific modules (`android`, `ios`, etc.)

This pattern is consistent across the project. New modules added later follow the same convention.

### Packages

Within a module, internal packages reflect functional areas — for example, `parsing`, `generation`, `validation`, `transliteration`. Each internal package has a clean public API surface within its module, which makes future promotion to a standalone module mechanical (Principle 11).

Package paths use a stable root namespace owned by the project, followed by descriptive segments. The exact root namespace is finalized when the project's identity is locked for publication.

### Errors

Error types are named for the specific failure they represent. Generic catch-all names are not used.

Examples of well-named errors:

- `MrzCheckDigitFailed`
- `NfcAuthenticationFailed`
- `CameraPermissionDenied`
- `DocumentTypeUnsupported`

Examples of names that would be rejected:

- `SdkException`
- `GeneralError`
- `UnknownFailure`

Each error carries enough context to be actionable — which field, which step, which input position.

### Public vs Internal APIs

Each module declares which symbols are part of its public API. Symbols not exposed publicly are implementation detail and may change between versions without notice. The project uses Kotlin's `internal` visibility modifier to enforce this where the language supports it, and documentation conventions for cases where it does not.

Public APIs are designed to be stable across the lifetime of a major version (Principle 9). Adding to a public API is non-breaking; removing requires a deprecation cycle.

---

## Scope Honesty: Identity-Level vs Project-Level

A persistent risk in this project is conflating *project-level* concerns (the codebase, its module structure, its POM metadata, its namespace) with *identity-level* concerns (the developer's signing keys, accounts, personal tooling) — or worse, naming the second as if it were the first. The result is documentation that quietly imposes project-specific patterns on things that are actually about the contributor as a person, and naming that misleads future contributors (and future-you) about what scope a decision actually has.

When proposing or documenting any artifact, naming convention, or instruction, the question to ask is:

> *"Is this scoped to this project, or to the developer working on it?"*

Two distinct categories emerge cleanly:

- **Project-level** — the project's published namespace (`io.lightine` per [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md)), Maven coordinates, POM metadata, build configuration, module structure, ADRs. These follow the project; they should be project-named where naming is required.
- **Identity-level** — signing keys (SSH for commits, PGP for artifacts), credential stores, account credentials, personal tooling preferences. These follow the developer across any project they work on; they should be identity-named or scope-agnostic, not project-named.

There is also a middle ground worth recognizing:

- **Maintainer-level for this project** — things like "the Sonatype token used to publish this project's artifacts." The *account* is identity-level; the *token's use* is project-publishing-specific. Naming should reflect the immediate scope (the token name can be environment-scoped, e.g., `publishing-laptop` vs `publishing-ci`) while the account itself is treated as identity-level.

When the discipline reveals an existing inconsistency (something named project-specifically that should be identity-level, or the reverse), the resolution is to align name, configuration, and documentation together — not pick one and leave the others mismatched. Mixed signals are worse than either honest choice.

### Dual-path contributor docs for identity-level setup

When a contributor-facing setup document covers an identity-level thing (an SSH signing key, a GPG key, a developer account), the document is written with two paths at the top:

> **Choose your path:**
> - Already have X set up and want to reuse it? → [quick setup, brief steps]
> - Setting up from scratch? → [full walkthrough]

Both paths converge at the project-specific configuration steps. The contributor picks their own path; the document does not assume what they already have.

This pattern is the operational expression of [Principle 1 — Reader, not oracle](principles.md) applied to documentation: the document presents the information; the contributor makes their own trust decisions about their own setup. Forcing every contributor down a from-scratch path treats an identity-level decision (which keys they want to use) as if it were a project mandate, which is exactly the inconsistency this convention exists to prevent.

---

## Internal Packages First

The project follows Principle 11: new features start as internal packages within existing modules. They are promoted to standalone modules only when at least one of the following clearly applies:

- Independent reuse — the feature is genuinely useful without its parent module
- Independent evolution — the feature changes at a different pace than its parent
- Independent testing — the feature requires its own testing context
- Independent ownership — the feature is owned or maintained by different people
- Independent shipping — the feature releases on a different schedule
- Optional inclusion — consumers should be able to exclude the feature

Until one of these applies, a clear internal package boundary with a defined public API is enough. The promotion to a standalone module, when it happens, is mechanical because the boundary already exists.

This avoids both extremes: the monolith that grows without internal structure, and the over-modularized project where every concept is a separate artifact regardless of need.

---

## Module Boundaries

Each module's name describes what belongs in it. The `types` module is types-only — classes (data, value, sealed, abstract), interfaces (regular, sealed, fun), enums, type aliases, and the companion objects belonging to those types. No top-level functions, no top-level non-const properties, no extension functions on third-party types. Top-level `object` declarations are allowed only when they represent a singleton instance of a type (e.g., a case of a `sealed interface`), not as namespaces for functions.

This matters because broadly-named modules — `common`, `core`, `utils` — accumulate unrelated helpers over years until no one remembers the original boundary. The result is a junk drawer that every module depends on and no module should. Once that has happened, splitting the module back apart is a substantial migration; preventing it costs one Gradle file at the moment the temptation first appears.

When shared non-type code is needed, the convention is to create a new module (e.g., `tessera-utils`) rather than relaxing the discipline. The cost of a new module is one directory, one `build.gradle.kts`, and one `include(":...")` line in `settings.gradle.kts`. The name describes what the module is for; contributors know where to look and where not to look.

The published artifactId at first Maven Central release is the deliberately-chosen `tessera-types`, locked under [ADR-007](decisions/0007-strict-backward-compat-from-0x.md) once 0.1.1 ships per [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md); the discipline rule preserves the meaning the name promises.

Full operational detail in the [`types-module-discipline`](../.claude/rules/types-module-discipline.md) rule (path-scoped to `types/**`, auto-loaded when working in the types module).

---

## Adding a New MRZ Format

Adding a new MRZ format (beyond the ICAO Doc 9303 formats already supported) is a multi-phase exercise. Each phase is its own PR; the format moves from "data class only" through "readable" through "generatable" through "auto-detected" in deliberate increments. The phasing keeps reviews focused on one concern at a time and lets consumers benefit from partial coverage earlier than under a single monolithic PR.

A format is "added" when Phase 1 lands, "round-trip complete" when Phase 2 lands, and "complete" when Phase 3 lands. A release tag does not require every supported format to be complete in every phase, but the release notes describe what is shipped and what is deferred per format. The Status tables in the feature documents are the per-feature source of truth for what's implemented versus documented.

### Phase 1 — Reading and recognition

The format is parseable and validatable. The minimum needed for a consumer to extract typed data and see typed validation findings.

1. Define the format specification in the shared `formats/` package within `mrz-core` — field positions, field widths, check digit positions, character set rules. Implement the appropriate spec interface (`MrzFormatSpec` for visa-shape formats with no composite digit; `MrzFormatSpecWithComposite` for TD-family formats per ICAO Doc 9303 Parts 4–6)
2. Add a new variant to the `MrzDocument` sealed hierarchy reflecting the format's fields
3. Implement the format-specific parser (`MrzParser.parse{FORMAT}` overloads with the `referenceTime: Instant` parameter)
4. Implement the format-specific validator path (`MrzValidator.validate(document)` dispatch)
5. Add lock tests for the format spec, parser tests for happy paths and the documented error paths, validator tests for per-field findings, and update the `MrzDocumentTest` sealed-exhaustiveness check
6. Update the relevant feature documents — illustrative shapes and Status tables in `mrz-data-model.md`, `mrz-parsing.md`, `mrz-validation.md`
7. Update the changelog under `[Unreleased]`

The format is "added" when Phase 1 lands. Check digits in test fixtures are computed via the SDK's check-digit primitive directly (a generator is not required at this phase).

### Phase 2 — Generation

The format is generatable from a `MrzDocument` instance. Round-trip tests confirm that parse-then-generate and generate-then-parse return the original input for valid cases.

1. Implement the format-specific generator (`MrzGenerator.generate{FORMAT}` overloads)
2. Add round-trip property tests against the format's synthetic fixtures
3. Add or extend synthetic test fixtures generated through the format's own generator path, including the canonical example from the format's source specification
4. Update `mrz-generation.md` — illustrative shape and Status table — and any other affected feature docs
5. Update the changelog

The format is "round-trip complete" when Phase 2 lands.

### Phase 3 — Auto-detect integration

The format is reachable through `MrzParser.parse(input)` auto-detect. This phase exists separately because auto-detect's dispatch rules are designed with every supported format in mind, not added one at a time; a Phase 3 update typically lands as a single PR covering auto-detect integration for whichever formats are then in Phase 1 or beyond.

1. Update the auto-detect dispatch logic with structural cues that distinguish the new format from existing formats
2. Add tests covering the new dispatch path (happy path plus relevant edge cases — especially disambiguation between formats with overlapping shapes)
3. Update `mrz-parsing.md` "Auto-Detect Behavior" to describe the new cue
4. Update the changelog

The format is "complete" when Phase 3 lands.

### Cross-cutting

- `scope.md` lists the format as supported as soon as Phase 1 lands. The scope claim is that the SDK supports the format; the Status tables in feature docs record which phases are implemented.
- The same phasing applies in spirit to non-MRZ formats added in the future (chip data formats, document image formats, etc.), with the specific steps adjusted for the relevant subsystems.

---

## Code Style

Code style follows the idiomatic conventions of each target language and platform — Kotlin code reads as idiomatic Kotlin, Swift wrappers read as idiomatic Swift, and so on. The overarching commitment is: code is written to be readable by people who do not have the original author's context.

### Kotlin

The project uses the **Kotlin official code style** (`kotlin.code.style=official` in `gradle.properties`). Formatting and linting are enforced through:

- **[Spotless](https://github.com/diffplug/spotless)** — applied at the project root, formats and checks every Kotlin source file (`*.kt`) and Gradle build script (`*.gradle.kts`) across all modules.
- **[ktlint](https://github.com/pinterest/ktlint)** — Spotless's chosen backend for Kotlin. Version pinned in `gradle/libs.versions.toml`.
- **`.editorconfig`** at project root — editor-level consistency for indentation (4 spaces), line endings (LF), encoding (UTF-8), and ktlint rule overrides.

Two commands cover daily use:

- `./gradlew spotlessApply` — auto-formats every Kotlin and Kotlin Gradle file in the project.
- `./gradlew spotlessCheck` — verifies formatting; fails the build on any violation.

`spotlessCheck` runs as part of the standard `./gradlew check` and `./gradlew build` lifecycle, so style violations break the build by default.

### Dependency Upgrade Cadence

The project bumps the toolchain and dependencies to current stable on a **six-monthly cadence**: next checkpoint **2026-10-01**, then every six months after that. The exact day doesn't matter (±2 weeks is fine); the cadence is the operational rhythm, not a hard deadline.

Each cycle bumps the items pinned in `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle.kts`, and the `jvmToolchain(N)` calls across module `build.gradle.kts` files: Kotlin (and KMP plugin), Gradle, JDK toolchain floor (when LTS situation warrants), dev tooling (Spotless, ktlint), runtime dependencies (kotlinx-datetime), test dependencies (kotest), and Gradle settings plugins (foojay-resolver-convention).

The full operational detail — what to bump, how to verify compatibility, how to split into PRs to keep blast radius small — lives in the [`dependency-upgrade-cadence`](../.claude/skills/dependency-upgrade-cadence/SKILL.md) skill, with a short summary and the "next date" reminder in [`CLAUDE.md`](../CLAUDE.md).

### Pre-Release Tech-Stack Review

Before starting work on each release (`0.2.0`, `0.3.0`, etc.), the project does a brief tech-stack review. This is milestone-driven and complementary to the clock-driven 6-monthly upgrade cadence above: the cadence asks "are we on current stable?"; the pre-release review asks "are our underlying choices still right for what we're about to build?"

The review surfaces: foundational architectural choices that may need revisiting, new dependencies the upcoming subsystem will need (camera lib for `0.2.0`, NFC lib for `0.6.0`, etc.), local-machine tooling the build can't auto-provision (platform SDKs, CLIs, test hardware), and API-stability commitments the release would lock in. Output is a brief decision record (an ADR if significant, a recap-style working note otherwise) naming the project's expectations — contributors track their own local installs separately. The 2026-05-17 pre-`0.1.0` recap is the working precedent.

Full operational detail in the [`pre-release-tech-stack-review`](../.claude/skills/pre-release-tech-stack-review/SKILL.md) skill, with a short summary in [`CLAUDE.md`](../CLAUDE.md).

### Development Setup & Mobile Workflow

The local build/dev toolchain — JDK, Android SDK, Xcode, the agent-facing CLI/MCP tooling — is documented in [`development-setup.md`](development-setup.md): a tiered (core / Android / iOS), per-OS, living sibling of [`contributor-setup.md`](contributor-setup.md) (Git identity and signing) and [`publishing-setup.md`](publishing-setup.md) (release credentials). You set up only the tier you work in.

Mobile development is **CLI-driven and text-first**: Android via Google's Android CLI, iOS via the Xcode MCP, with device and screen state inspected as text rather than screenshots (a screenshot pulled into an AI assistant's context can exceed size limits and destroy the session). The AI-facing operational form of this is the path-scoped [`mobile-dev-workflow`](../.claude/rules/mobile-dev-workflow.md) rule, enforced by a screenshot-blocking hook in [`.claude/settings.json`](../.claude/settings.json); `development-setup.md` is the human-facing counterpart.

### Pre-commitment alignment check

When making a foundational decision — tech-stack choices, scope-defining wording, architectural commitments, anything ADR-007 backward-compatibility will lock at `0.1.0` — verify alignment with the primary docs (`scope.md`, ADRs, `open-questions.md`, feature docs) before committing. Derived sources (recaps, summaries, prior interpretations) can drift from the primary over time; acting on a drifted derived source propagates the drift forward into new decisions.

This is not "verify everything." Most decisions are routine and the project's documentation system can be trusted by default. The check applies when the cost of being wrong is high: pre-release tech-stack reviews, scope or principle adjustments, anything that 0.x backward-compatibility will lock. The working example is the pre-`0.1.0` recap drift caught by [PR #33](https://github.com/lightine-io/tessera/pull/33), where `scope.md`'s actual wording about per-release target activation had been over-stated by a derived recap; the check would have prevented carrying that drift into 0.1.0 path decisions.

Full operational detail in the "Pre-commitment alignment check" rule in [`CLAUDE.md`](../CLAUDE.md) and the full working pattern in [`.claude/working-patterns.md`](../.claude/working-patterns.md).

### Folder and File Organization

Project folders and files follow a consistent placement-and-naming convention: visible folders (`docs/`, `scripts/`, source modules, etc.) hold project deliverables that contributors interact with directly; dot-prefix folders (`.claude/`, `.github/`, `.handoffs/`, `.recaps/`, `.conformance/`, `.spec/`) hold project infrastructure — AI-facing docs, tool config, working notes, maintainer reference material — regardless of whether those folders' contents are committed or gitignored. Naming is purpose-driven rather than folder-driven: dated working notes use `<CATEGORY>-YYYY-MM-DD[-HHMM][-<slug>].md` (uppercase category, UTC date), evergreen documentation files use lowercase-hyphen names with the `.md` extension (ADRs add a 4-digit numeric prefix), and root-level project files follow the long-standing software convention of UPPERCASE filenames.

Full operational detail in the [`folder-organization`](../.claude/rules/folder-organization.md) rule (path-scoped, auto-loaded when working with markdown files), with a short summary in [`CLAUDE.md`](../CLAUDE.md).

### Swift, other languages

Conventions for Swift wrappers and any future languages are added to this document when those source sets are introduced. The same principle applies: idiomatic per-language style, enforced by the language's standard tooling (e.g., SwiftLint for Swift), with configuration committed at the project root.

---

## Contribution Conventions

### How Decisions Are Made

Decisions of architectural or scope significance are recorded as ADRs (see "Documentation Conventions" above). Decisions about a specific feature live in that feature's documentation. Smaller decisions about implementation detail do not need a record beyond the code itself.

When a decision is contested, the path is:

1. Discussion grounded in the principles
2. Examination of the specific consequences of each option
3. Either: agreement, or escalation to maintainers if no agreement is reached

Decisions are not made by authority; they are made by reasoning that the participants can stand behind. When a maintainer decides unilaterally, the reasoning is recorded so others can engage with it.

### How Disagreements Are Resolved

Disagreement is welcome. Principle 4 (Honest about what we know) implies that confident statements should be testable; if someone disagrees, the test is whether the disagreement points at a real flaw or a difference in values.

When a contributor and a maintainer disagree:

- The disagreement is articulated specifically — what is the option being rejected, what is the option being preferred, and why
- The relevant principles are consulted
- If both options are consistent with the principles, the maintainer's preference resolves the disagreement, but the alternative is recorded for future reconsideration

This convention is light and informal; it does not need a process tool or a workflow. It is captured here so the project's culture is explicit rather than implicit.

### How New Conventions Are Added

New conventions are added to this document through normal contribution: a proposal, discussion, agreement, then an edit to this document. Conventions that are imposed without discussion tend to be ignored; conventions that are discussed and agreed upon tend to be followed.

When this document grows large enough that finding a specific convention becomes difficult, sections are split into focused sub-documents and this document becomes an index. That moment has not arrived; this convention is recorded so it is recognized when it does.

---

## How This Document Relates to Principles

Conventions implement principles (defined in `principles.md`), but they are not principles themselves. Principles are the bedrock; conventions are how we navigate day-to-day work in light of the bedrock.

When a convention seems to conflict with a principle, the principle wins and the convention is revised. When a convention seems to conflict with a different convention, that is a sign one or both conventions need refinement.

The principles do not need conventions to remain valid. Conventions need principles to be coherent.
