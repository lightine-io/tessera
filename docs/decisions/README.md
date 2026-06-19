# Architecture Decision Records

This folder contains the project's Architecture Decision Records (ADRs). Each ADR captures a significant decision in a stable format: what was decided, why, what the consequences are, and what alternatives were considered.

ADRs exist because the *reasoning* behind a decision is often more valuable than the decision itself. A future contributor encountering an unfamiliar choice can read the relevant ADR and understand the context, the trade-offs, and the reasoning — without having to derive them from scratch or risk re-litigating settled questions.

The format is documented in `conventions.md`. ADRs are *fixed* once accepted: if a decision changes, a new ADR supersedes the old one; the old one is marked deprecated but remains in the record.

---

## Index

| ADR | Title | Status |
|---|---|---|
| [001](0001-kotlin-multiplatform.md) | Use Kotlin Multiplatform for shared logic | Accepted |
| [002](0002-native-ui-per-platform.md) | Native UI per platform (no Compose Multiplatform) | Accepted |
| [003](0003-modular-architecture.md) | Modular architecture from day one | Accepted |
| [004](0004-reader-not-oracle.md) | Reader, not oracle as foundational stance | Accepted |
| [005](0005-no-verification-hooks-initial.md) | No verification hooks in initial release | Accepted |
| [006](0006-no-isvalid-boolean.md) | No `isValid` boolean on results | Accepted |
| [007](0007-strict-backward-compat-from-0x.md) | Strict backward compatibility from 0.x (Position A) | Accepted |
| [008](0008-date-inference-hybrid.md) | Date inference — hybrid raw + computed + flag | Accepted |
| [009](0009-transliteration-profiles.md) | Per-state transliteration profiles, never inferred | Accepted |
| [010](0010-apache-2-license.md) | Apache 2.0 license at public release | Accepted |
| [011](0011-open-source-at-public-release.md) | Open source at public release | Accepted |
| [012](0012-recognition-types-live-with-tables.md) | Recognition-bearing value classes live with their lookup tables | Accepted |
| [013](0013-recognition-failures-are-warnings.md) | Recognition failures are warnings, not validation errors | Accepted |
| [014](0014-unicode-normalization-strategy.md) | Unicode normalization via platform-native normalizers (expect/actual) | Accepted |
| [015](0015-telemetry-contract-only-at-0-1-0.md) | Telemetry interface ships as contract-only at 0.1.0 with an open event hierarchy | Accepted |
| [016](0016-maven-coordinates-and-first-publish.md) | Maven Central coordinates, lockstep versioning, and first publication at 0.1.1 | Accepted |
| [017](0017-mobile-targets-and-build-stack.md) | Mobile target enablement and build stack (0.2.0) | Accepted |
| [018](0018-platform-minimums-and-managed-raise.md) | Platform minimums and the managed-raise policy | Accepted |
| [019](0019-ios-distribution-via-spm.md) | iOS distribution via Swift Package Manager | Accepted |
| [020](0020-camera-reading-architecture.md) | Camera reading architecture | Accepted |
| [021](0021-shared-mrz-camera-core-module.md) | Shared `mrz-camera-core` module | Accepted |
| [022](0022-operating-model-and-roles.md) | Operating model and project roles | Accepted |

---

## Adding a New ADR

When making a significant architectural or design decision:

1. Discuss the decision according to the contribution conventions (see `conventions.md`)
2. Once a decision is reached, draft an ADR using the format below
3. Number it sequentially (next available)
4. Add an entry to the table above

### Format

Each ADR follows this structure:

- **Title** (matches the filename)
- **Status** — Proposed, Accepted, Deprecated, or Superseded (with reference to superseding ADR if applicable)
- **Context** — what situation led to needing the decision
- **Decision** — what was decided, stated unambiguously
- **Consequences** — positive, negative, and neutral
- **Alternatives Considered** — other options weighed and why they were not chosen
- **Related Decisions** — cross-references to other ADRs
- **Related Documents** — pointers to the project documents this decision affects

### What Deserves an ADR

The bar is: *did this decision require deliberation that future contributors might want to revisit?* If yes, an ADR is appropriate. If the decision was obvious or trivial, an inline note in the relevant document suffices.

ADRs are not gates on every change. Most implementation work does not need a new ADR. ADRs exist for the decisions that shape the project's structure, posture, or commitments.

---

## Related Documents

- `../conventions.md` — defines the ADR format and contribution conventions
- `../principles.md` — the foundational principles many of these ADRs reference
- `../open-questions.md` — tracks decisions that have been deferred and may eventually become ADRs
