# Tessera

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.tessera/tessera-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.tessera/tessera-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/lightine-io/tessera/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/tessera/actions/workflows/check.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

A vendor-neutral SDK for reading, validating, and generating identity document data.

Tessera reads Machine Readable Zones (MRZ) from passports, national ID cards, residence permits, machine-readable visas, and similar travel documents conforming to ICAO Doc 9303. It returns extracted data verbatim, with structured validation results — leaving all trust decisions to the integrating application.

> **Status:** In active `0.x` development. `v0.3.0` is the current release on Maven Central (`io.lightine.tessera`), adding headless saved-image (pre-captured image) MRZ reading on Android and iOS, on top of the `0.2.x` live-camera reading — see [Installation](#installation) and [`CHANGELOG.md`](CHANGELOG.md). The `1.0.0` milestone marks the public-stability and open-source release commitment per [ADR-011](https://lightine.youtrack.cloud/articles/TES-A-41); pre-`1.0.0` releases follow the same strict backward-compatibility commitments as post-`1.0.0` releases. See [`docs/versioning.md`](https://lightine.youtrack.cloud/articles/TES-A-8) for the policy.

---

## What it does

- **Parses all ICAO Doc 9303 MRZ formats**: TD1, TD2, TD3, MRV-A, MRV-B
- **Validates** structurally, by check digit, and semantically — without making trust decisions
- **Generates** valid MRZs from structured input, supporting round-trip use cases
- **Exposes everything it extracts** — raw fields, computed values, validation results, and warnings — so the consumer always knows what was observed and what was inferred
- **Reads MRZ from a live camera or a saved image** — headless: Android (CameraX + ML Kit) and iOS (AVFoundation + Apple Vision) read live frames or a pre-captured image file through the same parser, with the consumer owning all UI. You can also supply the MRZ string directly. Manual entry and NFC-chip reading are planned for later releases (manual entry `0.4.0`, NFC `0.6.0` — see the [roadmap](https://lightine.youtrack.cloud/articles/TES-A-62)).
- **Runs anywhere the core technology stack supports** — initial mobile targets (Android, iOS) plus future support for backend, desktop, and web

---

## What it deliberately does not do

Tessera is a reader, not an oracle. It surfaces observations; the consumer makes trust decisions. Specifically, the SDK does not:

- Decide whether a document is "valid" or "trustworthy" — that depends on the consumer's threat model
- Verify document authenticity against external registries
- Perform face matching or liveness detection (these may be added later as separate capabilities)
- Store any data — no persistence, no caching, no telemetry by default
- Phone home — no network calls, no analytics, no licensing checks

These are deliberate boundaries. See [`docs/principles.md`](https://lightine.youtrack.cloud/articles/TES-A-5) for the reasoning.

---

## Quick example

This example compiles against the published API (verified symbol-by-symbol; the project's documentation rules require it). The public API follows strict backward compatibility throughout `0.x` ([ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37)).

```kotlin
val result = MrzParser.parse("""
    P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
    L898902C36UTO7408122F1204159ZE184226B<<<<<10
""".trimIndent())

when (result) {
    is ParseResult.Success -> {
        val doc = result.document as TD3  // io.lightine.tessera.mrz.model.TD3
        println("Name: ${doc.commonFields.primaryIdentifier}, ${doc.commonFields.secondaryIdentifier}")
        println("Document number: ${doc.commonFields.documentNumber}")
        // ... use the parsed data
    }
    is ParseResult.PartialSuccess -> {
        // Data extracted, but some validations failed.
        // Read result.document and result.metadata.validationFailures to decide.
    }
    is ParseResult.Failure -> {
        // The input was structurally too broken to construct a document.
        println("Parse failed: ${result.error}")
    }
}
```

The result type makes the three possible outcomes explicit. The consumer cannot accidentally treat a `PartialSuccess` as a `Success`.

**More examples** — validation, generation, transliteration, live-camera scanning — are in the [feature guides](https://lightine.youtrack.cloud/articles/TES-A-16); every guide has a copy-paste Usage section.

---

## Installation

Tessera is published to Maven Central under the `io.lightine.tessera` group. The current release is `0.3.0` (JVM + Android; iOS via Swift Package Manager — see [Platforms](#platforms)).

### Gradle (Kotlin DSL)

Use the BOM to keep every Tessera module on one version:

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.3.0"))
    implementation("io.lightine.tessera:tessera-mrz-core")  // MRZ parsing, validation, generation
}
```

Or pin the module version directly, without the BOM:

```kotlin
implementation("io.lightine.tessera:tessera-mrz-core:0.3.0")
```

`tessera-mrz-core` pulls in `tessera-types` transitively — most integrators need only this one module.

### Maven

```xml
<dependency>
    <groupId>io.lightine.tessera</groupId>
    <artifactId>tessera-mrz-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

### Swift Package Manager (iOS)

In Xcode: **File → Add Package Dependencies…**, enter `https://github.com/lightine-io/tessera-swift`, and choose `0.3.0`. Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/lightine-io/tessera-swift", from: "0.3.0"),
]
```

Then `import Tessera`. The iOS binary ships as the `Tessera` XCFramework (minimum deployment target iOS 18, per [ADR-018](https://lightine.youtrack.cloud/articles/TES-A-45)). The Swift surface is provisional through the `0.x` line — see [`tessera-swift`](https://github.com/lightine-io/tessera-swift).

> **Android** ships the same coordinates as AAR artifacts (e.g. `tessera-mrz-camera-android` for live-camera reading) — use the Gradle/Maven blocks above. See [Platforms](#platforms) for the per-target capability matrix.

---

## Documentation

The project's documentation is structured for two audiences: integrators (who want to use the SDK) and contributors (who want to understand or extend it).

### For integrators

- [`docs/scope.md`](https://lightine.youtrack.cloud/articles/TES-A-62) — what the SDK supports, what it does not, and what is planned
- [`docs/getting-started.md`](https://lightine.youtrack.cloud/articles/TES-A-10) — dependency → parse → validate → generate in ten minutes
- [`docs/guides/android-integration.md`](https://lightine.youtrack.cloud/articles/TES-A-14) — Android: from empty app to a working live-camera MRZ scan
- [`docs/guides/ios-integration.md`](https://lightine.youtrack.cloud/articles/TES-A-18) — iOS: the same journey in Swift, including the provisional Flow-collection pattern
- [`docs/features/`](https://lightine.youtrack.cloud/articles/TES-A-16) — usage guides for every capability, each with a copy-paste Usage example
- **API reference** — KDoc ships as javadoc jars with every module on Maven Central (your IDE picks them up automatically); a hosted Dokka site is a tracked deferral in the project's issue tracker
- [`docs/reading-risks.md`](https://lightine.youtrack.cloud/articles/TES-A-11) — what each reading method establishes, what it does not, and what additional verification might be needed
- [Glossary — YouTrack KB](https://lightine.youtrack.cloud/articles/TES-A-4) — definitions of MRZ, eMRTD, BAC, PACE, and other terms used throughout the documentation
- [`docs/versioning.md`](https://lightine.youtrack.cloud/articles/TES-A-8) — versioning policy and release commitments

### For contributors

- [`docs/principles.md`](https://lightine.youtrack.cloud/articles/TES-A-5) — the foundational principles every design decision honors
- [`docs/architecture.md`](https://lightine.youtrack.cloud/articles/TES-A-9) — module structure, dependency graph, and technology choices
- [`docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) — how documentation is written, how decisions are made, how contributions happen
- [`docs/testing.md`](https://lightine.youtrack.cloud/articles/TES-A-15) — testing discipline (tests alongside implementation, synthetic data only)
- [`docs/contributor-setup.md`](https://lightine.youtrack.cloud/articles/TES-A-12) — one-time machine setup for contributors (clone, Git identity, SSH commit signing)
- [`docs/decisions/`](https://lightine.youtrack.cloud/articles/TES-A-17) — Architecture Decision Records capturing the reasoning behind major choices
- the project's issue tracker — decisions that have been deliberately deferred, tracked so they are not forgotten

### For maintainers

- [`docs/publishing-setup.md`](https://lightine.youtrack.cloud/articles/TES-A-57) — one-time setup for publishing to Maven Central (PGP signing key, Sonatype Central Portal user token, Gradle credential storage). Maintainer-only; contributors do not need this

---

## Platforms

Tessera is built with Kotlin Multiplatform. Targets activate per-release as the corresponding reading methods land — see [`docs/scope.md`](https://lightine.youtrack.cloud/articles/TES-A-62) for the full roadmap.

Active as of `0.3.0`:

- **JVM** — the pure core logic (parsing, validation, generation, lookup tables, transliteration profiles, telemetry contract)
- **Android** — core logic plus headless live-camera and saved-image reading (CameraX + ML Kit). Minimum API level 23 (Android 6.0), per [ADR-018](https://lightine.youtrack.cloud/articles/TES-A-45)
- **iOS** — core logic plus headless live-camera and saved-image reading (AVFoundation + Apple Vision), distributed as an XCFramework via Swift Package Manager. Minimum deployment target iOS 18, per [ADR-018](https://lightine.youtrack.cloud/articles/TES-A-45)

The architecture supports further targets — Web (JS / Wasm), Desktop (JVM and native) — without changes to the core logic. They are not part of the initial releases but can be activated when there is a use case.

---

## Versioning

Tessera follows [Semantic Versioning 2.0.0](https://semver.org/) with strict backward-compatibility commitments from the first release onward — including the 0.x line. This is stricter than the convention in many open source projects, where 0.x signals "API may change without notice." The choice is deliberate: see [`docs/versioning.md`](https://lightine.youtrack.cloud/articles/TES-A-8) for the reasoning.

---

## License

Tessera is released under the Apache License 2.0. The full license text is in the [`LICENSE`](LICENSE) file at the project root. See [`docs/decisions/0010-apache-2-license.md`](https://lightine.youtrack.cloud/articles/TES-A-39) for the reasoning behind the license choice.

---

## Security

Tessera is used in trust-related contexts. Security reports are taken seriously and handled privately. See [`SECURITY.md`](SECURITY.md) for the disclosure process, the supported-versions matrix, and what is in and out of scope.

---

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) is the short pointer for new contributors; [`docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) holds the full contribution rules; [`docs/contributor-setup.md`](https://lightine.youtrack.cloud/articles/TES-A-12) covers one-time machine setup. The short version:

- Decisions of architectural or scope significance are recorded as ADRs
- Disagreement is welcome — the project's culture is dispute-driven, grounded in the principles
- New conventions are added through normal contribution: proposal, discussion, agreement, then an edit

The project is in active `0.x` development. The formal open-source release happens at `1.0.0` per [ADR-011](https://lightine.youtrack.cloud/articles/TES-A-41).

---

## Acknowledgments

Tessera builds on the work of the International Civil Aviation Organization (ICAO), whose Doc 9303 series defines the standards this SDK implements. The SDK references those standards rather than reproducing them.

The project's design owes a debt to the broader open source community's work on identity document standards, MRZ parsing libraries that came before, and the Kotlin Multiplatform ecosystem that makes shared cross-platform logic practical.
