---
name: dependency-upgrade-cadence
description: Six-monthly procedure for bumping toolchain and dependencies to current stable, on April 1 and October 1 (CI opens the checkpoint issue). Has side effects (PRs, version bumps); fires only when invoked explicitly.
disable-model-invocation: true
---

# Dependency upgrade cadence

The toolchain and dependencies move to current stable every six months: often enough
that tooling never goes years stale, rarely enough that patch releases are not churn.

## When

April 1 and October 1. `.github/workflows/cadence-reminder.yml` opens the checkpoint
issue (label `dependency-cadence`) on the day; the work hangs on that issue. Within two
weeks of the date is fine.

## What to bump

Dependabot opens no version-update PRs for Gradle (`open-pull-requests-limit: 0`,
TES-148): this cadence is the only scheduled version pass. Security updates are separate
and arrive at once; a shipped dependency with a fixed advisory is upgraded immediately.

Everything with a pinned version in `gradle/libs.versions.toml`,
`gradle/wrapper/gradle-wrapper.properties`, `settings.gradle.kts`, and the
`jvmToolchain(N)` calls: Kotlin and the KMP plugin, Gradle, the JDK toolchain floor,
Spotless and ktlint, runtime and test dependencies, settings plugins.

## How

1. Look up the latest stable of each on the web (knowledge cutoffs drift).
2. Check the Kotlin, Gradle, and KGP compatibility matrix; take the highest supported
   triple.
3. Move the JDK toolchain only when the LTS situation calls for it.
4. Ship two or three focused PRs: toolchain (Kotlin, KMP, Gradle, JDK); dev tooling and
   the wrapper; runtime and test dependencies with docs. Precedent: GitHub #257, shipped
   as #258, #259, and a docs PR.

## Also, each time

- **Accepted risks.** Revisit every accepted-risk or deferred-dependency issue (for
  example the build-time Dependabot triage, the wrapper `distributionSha256Sum`, the
  `mrz-camera-android` ABI tooling): has upstream fixed it, or does the deferral hold?
- **Tech-stack index.** Every key in `libs.versions.toml` has its row in TES-A-23.
- **CLAUDE.md health review (5 minutes).** Well under the 200-line cap; no rule crept
  back that belongs in a path rule, a skill, or a hook; every pointer-table link still
  says what it claims; the invariants name what really checks them. Fix it in the
  cadence's docs PR.
