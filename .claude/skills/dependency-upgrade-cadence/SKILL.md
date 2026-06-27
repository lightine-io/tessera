---
name: dependency-upgrade-cadence
description: Six-monthly procedure for bumping toolchain and dependencies to current stable. Invoke at each scheduled checkpoint (next 2026-10-01). Has side effects (PRs, version bumps); fires only when invoked explicitly.
disable-model-invocation: true
---

# Dependency Upgrade Cadence

The project bumps the toolchain and dependencies to current stable on a **six-monthly cadence**. This keeps the project from accumulating multi-year-stale tooling (which compounds upgrade pain) while not chasing every patch release (which is its own form of churn).

## When to invoke

- **Next scheduled check: 2026-10-01.** After that, every six months: 2027-04-01, 2027-10-01, 2028-04-01, etc.
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

- Human-facing summary in [`../../../docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) under "Dependency Upgrade Cadence."
- Project-level summary and the "next date" reminder live in [`../../../CLAUDE.md`](../../../CLAUDE.md) under "Dependency Upgrade Cadence."
