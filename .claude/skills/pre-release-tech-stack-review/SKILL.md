---
name: pre-release-tech-stack-review
description: Tech-stack review to run before starting work on each release (0.x, x.0, x.y). Invoke at release-prep time, before any code is written. Has side effects (decision records, possible ADRs); fires only when invoked explicitly.
disable-model-invocation: true
---

# Pre-Release Tech-Stack Review

Before starting work on each `0.x` (or later `x.0` / `x.y` minor) release, do an explicit tech-stack review. This is milestone-driven and complementary to the clock-driven [`dependency-upgrade-cadence`](../dependency-upgrade-cadence/SKILL.md). Where the cadence asks "are we on current stable?", this review asks "are our underlying choices still right for what we're about to build?"

## Trigger

**Before any code is written for the next release.** Not before tagging — pre-tag is "verify we shipped what we said" (the recap pattern). Pre-start is when the discussion has maximum leverage; once code is being written, retroactive tech-stack changes are expensive.

## Scope of the review

- **Revisit foundational architectural choices** (KMP, native UI per platform, sealed type hierarchies, etc.) against the upcoming release's actual demands.
- **Identify new dependencies** the upcoming subsystem will need (e.g., camera library for 0.2.0, NFC library for 0.6.0, transliteration approach for 0.1.0).
- **Identify local-machine tooling** the build can't auto-provision (platform SDKs, CLIs, test hardware — e.g., Android command-line tools + Android SDK for 0.2.0, Xcode + simulators for iOS, an NFC-capable test device for 0.6.0).
- **Flag API-stability commitments** the release would lock in that should be reconsidered first.
- **Surface tech-stack drift** that the 6-monthly cadence didn't catch (e.g., a foundational tool reaching end-of-life).

## Output

A brief decision record — a new ADR if significant, or a recap-style working note if smaller. At minimum, name what was reviewed and what was decided.

The output names *project* expectations. How each contributor satisfies local-tooling prerequisites on their own machine is theirs to track (e.g., via personal notes or AI-assistant memory). The 2026-05-17 pre-0.1.0 recap is the working precedent for this format.

## What this is not

Not a full design review of the upcoming release. The release's own design lives in the relevant feature docs and gets developed slice-by-slice as usual. The tech-stack review is the narrow architectural-fit pass that gates the start of release work.

## Cross-references

- Human-facing summary in [`../../../docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) under "Pre-Release Tech-Stack Review."
- Project-level summary lives in [`../../../CLAUDE.md`](../../../CLAUDE.md) under "Pre-Release Tech-Stack Review."
