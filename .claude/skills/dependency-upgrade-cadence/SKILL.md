---
name: dependency-upgrade-cadence
description: Six-monthly procedure for bumping toolchain and dependencies to current stable. Invoke at each scheduled checkpoint (next 2026-10-01). Has side effects (PRs, version bumps); fires only when invoked explicitly.
disable-model-invocation: true
---

# Dependency Upgrade Cadence

The project bumps the toolchain and dependencies to current stable on a **six-monthly cadence**. This keeps the project from accumulating multi-year-stale tooling (which compounds upgrade pain) while not chasing every patch release (which is its own form of churn).

## When to invoke

- **Next scheduled check: 2027-04-01.** After that, every six months: 2027-10-01, 2028-04-01, etc. CI opens the checkpoint issue on the date (`dependency-cadence` label); work hangs on that issue.
- The exact day doesn't matter — anything within ±2 weeks of the date is fine. The cadence is the operational rhythm, not a hard deadline.

## What to bump each cycle

Kotlin (and KMP plugin), Gradle wrapper, JDK toolchain floor, dev tooling (Spotless, ktlint), runtime dependencies (kotlinx-datetime, etc.), test dependencies (kotest), and any other plugins pinned in `settings.gradle.kts` (foojay-resolver-convention, etc.).

The complete inventory is whatever has a version pinned in:

- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts`
- The `jvmToolchain(N)` calls across module `build.gradle.kts` files

## Also sweep deferred dependency risks

At each checkpoint, scan the project's issue tracker for **accepted-risk / deferred-dependency** entries (e.g. the Dependabot build-time-transitive triage accepted at `0.2.0`) and revisit each one: has upstream shipped a fix, is an alternative path now viable, or does the deferral still hold? Update or resolve the entry accordingly. This closes the loop so an accepted risk is re-examined on a schedule rather than forgotten between sessions.

## Also: the CLAUDE.md health review (5 minutes)

The constitution is read every session, so the cadence checkpoint doubles as its guard
against drift. Check, and fix in the cadence's docs PR:

- Still under the 200-line cap (CI-checked) and ideally well under it.
- No rule crept back in that belongs in a path-scoped `.claude/rules/*` file, a skill, or
  a hook — prose in CLAUDE.md is the last resort (judgment principle 7).
- Nothing stale: every pointer-table link resolves and still describes what it points at;
  the drivers and safety invariants match what hooks/CI actually enforce.
- The two cadence dates in this file and in the Conventions KB article (TES-A-20) are rolled
  forward.

## How to bump

1. Verify the latest stable of each via web search at upgrade time (knowledge cutoffs drift).
2. Check the compatibility matrix between Kotlin + Gradle + KGP.
3. Bump to the highest mutually supported triple.
4. JDK toolchain bump is a separate call — only move when the LTS situation warrants it and the build/test surface is stable.

## How to ship it

Split into **2–3 focused PRs per cycle**. The first cycle (2026-05-17) used three PRs:

1. Kotlin + KMP + Gradle + JDK toolchain
2. Dev tooling (Spotless + ktlint) + Gradle wrapper regeneration
3. Runtime + test dependencies + cadence rule + docs

The split keeps blast radius small if any single bump breaks something.

## Cross-references

- Human-facing summary in the Conventions KB article ([TES-A-20](https://lightine.youtrack.cloud/articles/TES-A-20)) under "Dependency Upgrade Cadence."
- The constitution ([`CLAUDE.md`](../../../CLAUDE.md)) carries only the pointer-table row ("Dependency cadence (Apr 1 / Oct 1 — CI opens the issue)"); the dates live here.
- The checkpoint issue is opened by `.github/workflows/cadence-reminder.yml` on the cadence date; this cycle's precedent: GitHub #257 (2026-10), shipped as PRs #258 (toolchain), #259 (dependencies) and the docs PR.
