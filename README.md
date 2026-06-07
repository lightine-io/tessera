# Tessera

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.tessera/tessera-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.tessera/tessera-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/lightine-io/tessera/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/tessera/actions/workflows/check.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

A vendor-neutral SDK for reading, validating, and generating identity document data.

Tessera reads Machine Readable Zones (MRZ) from passports, national ID cards, residence permits, machine-readable visas, and similar travel documents conforming to ICAO Doc 9303. It returns extracted data verbatim, with structured validation results — leaving all trust decisions to the integrating application.

> **Status:** In active `0.x` development. `v0.2.1` is the current release on Maven Central (`io.lightine.tessera`), adding headless live-camera MRZ reading on Android and iOS — see [Installation](#installation) and [`CHANGELOG.md`](CHANGELOG.md). The `1.0.0` milestone marks the public-stability and open-source release commitment per [ADR-011](docs/decisions/0011-open-source-at-public-release.md); pre-`1.0.0` releases follow the same strict backward-compatibility commitments as post-`1.0.0` releases. See [`docs/versioning.md`](docs/versioning.md) for the policy.

---

## What it does

- **Parses all ICAO Doc 9303 MRZ formats**: TD1, TD2, TD3, MRV-A, MRV-B
- **Validates** structurally, by check digit, and semantically — without making trust decisions
- **Generates** valid MRZs from structured input, supporting round-trip use cases
- **Exposes everything it extracts** — raw fields, computed values, validation results, and warnings — so the consumer always knows what was observed and what was inferred
- **Reads MRZ from a live camera** — headless: Android (CameraX + ML Kit) and iOS (AVFoundation + Apple Vision) feed frames through the same parser, with the consumer owning all UI. You can also supply the MRZ string directly. Pre-captured images, manual entry, and NFC-chip reading are planned for later releases (pre-captured image `0.3.0`, manual entry `0.4.0`, NFC `0.6.0` — see the [roadmap](docs/scope.md)).
- **Runs anywhere the core technology stack supports** — initial mobile targets (Android, iOS) plus future support for backend, desktop, and web

---

## What it deliberately does not do

Tessera is a reader, not an oracle. It surfaces observations; the consumer makes trust decisions. Specifically, the SDK does not:

- Decide whether a document is "valid" or "trustworthy" — that depends on the consumer's threat model
- Verify document authenticity against external registries
- Perform face matching or liveness detection (these may be added later as separate capabilities)
- Store any data — no persistence, no caching, no telemetry by default
- Phone home — no network calls, no analytics, no licensing checks

These are deliberate boundaries. See [`docs/principles.md`](docs/principles.md) for the reasoning.

---

## Quick example

The following is illustrative — the actual API may differ in detail. See [`docs/features/`](docs/features/) for the current contracts.

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

---

## Installation

Tessera is published to Maven Central under the `io.lightine.tessera` group. The current release is `0.2.1` (JVM + Android; iOS via Swift Package Manager — see [Platforms](#platforms)).

### Gradle (Kotlin DSL)

Use the BOM to keep every Tessera module on one version:

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.2.1"))
    implementation("io.lightine.tessera:tessera-mrz-core")  // MRZ parsing, validation, generation
}
```

Or pin the module version directly, without the BOM:

```kotlin
implementation("io.lightine.tessera:tessera-mrz-core:0.2.1")
```

`tessera-mrz-core` pulls in `tessera-types` transitively — most integrators need only this one module.

### Maven

```xml
<dependency>
    <groupId>io.lightine.tessera</groupId>
    <artifactId>tessera-mrz-core</artifactId>
    <version>0.2.1</version>
</dependency>
```

### Swift Package Manager (iOS)

In Xcode: **File → Add Package Dependencies…**, enter `https://github.com/lightine-io/tessera-swift`, and choose `0.2.1`. Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/lightine-io/tessera-swift", from: "0.2.1"),
]
```

Then `import Tessera`. The iOS binary ships as the `Tessera` XCFramework (minimum deployment target iOS 18, per [ADR-018](docs/decisions/0018-platform-minimums-and-managed-raise.md)). The Swift surface is provisional through the `0.x` line — see [`tessera-swift`](https://github.com/lightine-io/tessera-swift).

> **Android** ships the same coordinates as AAR artifacts (e.g. `tessera-mrz-camera-android` for live-camera reading) — use the Gradle/Maven blocks above. See [Platforms](#platforms) for the per-target capability matrix.

---

## Documentation

The project's documentation is structured for two audiences: integrators (who want to use the SDK) and contributors (who want to understand or extend it).

### For integrators

- [`docs/scope.md`](docs/scope.md) — what the SDK supports, what it does not, and what is planned
- [`docs/features/`](docs/features/) — feature-by-feature documentation of every capability
- [`docs/reading-risks.md`](docs/reading-risks.md) — what each reading method establishes, what it does not, and what additional verification might be needed
- [`docs/glossary.md`](docs/glossary.md) — definitions of MRZ, eMRTD, BAC, PACE, and other terms used throughout the documentation
- [`docs/versioning.md`](docs/versioning.md) — versioning policy and release commitments

### For contributors

- [`docs/principles.md`](docs/principles.md) — the foundational principles every design decision honors
- [`docs/architecture.md`](docs/architecture.md) — module structure, dependency graph, and technology choices
- [`docs/conventions.md`](docs/conventions.md) — how documentation is written, how decisions are made, how contributions happen
- [`docs/testing.md`](docs/testing.md) — testing discipline (tests alongside implementation, synthetic data only)
- [`docs/contributor-setup.md`](docs/contributor-setup.md) — one-time machine setup for contributors (clone, Git identity, SSH commit signing)
- [`docs/decisions/`](docs/decisions/) — Architecture Decision Records capturing the reasoning behind major choices
- [`docs/open-questions.md`](docs/open-questions.md) — decisions that have been deliberately deferred, tracked so they are not forgotten

### For maintainers

- [`docs/publishing-setup.md`](docs/publishing-setup.md) — one-time setup for publishing to Maven Central (PGP signing key, Sonatype Central Portal user token, Gradle credential storage). Maintainer-only; contributors do not need this

---

## Platforms

Tessera is built with Kotlin Multiplatform. Targets activate per-release as the corresponding reading methods land — see [`docs/scope.md`](docs/scope.md) for the full roadmap.

Active as of `0.2.1`:

- **JVM** — the pure core logic (parsing, validation, generation, lookup tables, transliteration profiles, telemetry contract)
- **Android** — core logic plus headless live-camera reading (CameraX + ML Kit). Minimum API level 23 (Android 6.0), per [ADR-018](docs/decisions/0018-platform-minimums-and-managed-raise.md)
- **iOS** — core logic plus headless live-camera reading (AVFoundation + Apple Vision), distributed as an XCFramework via Swift Package Manager. Minimum deployment target iOS 18, per [ADR-018](docs/decisions/0018-platform-minimums-and-managed-raise.md)

The architecture supports further targets — Web (JS / Wasm), Desktop (JVM and native) — without changes to the core logic. They are not part of the initial releases but can be activated when there is a use case.

---

## Versioning

Tessera follows [Semantic Versioning 2.0.0](https://semver.org/) with strict backward-compatibility commitments from the first release onward — including the 0.x line. This is stricter than the convention in many open source projects, where 0.x signals "API may change without notice." The choice is deliberate: see [`docs/versioning.md`](docs/versioning.md) for the reasoning.

---

## License

Tessera is released under the Apache License 2.0. The full license text is in the [`LICENSE`](LICENSE) file at the project root. See [`docs/decisions/0010-apache-2-license.md`](docs/decisions/0010-apache-2-license.md) for the reasoning behind the license choice.

---

## Security

Tessera is used in trust-related contexts. Security reports are taken seriously and handled privately. See [`SECURITY.md`](SECURITY.md) for the disclosure process, the supported-versions matrix, and what is in and out of scope.

---

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) is the short pointer for new contributors; [`docs/conventions.md`](docs/conventions.md) holds the full contribution rules; [`docs/contributor-setup.md`](docs/contributor-setup.md) covers one-time machine setup. The short version:

- Decisions of architectural or scope significance are recorded as ADRs
- Disagreement is welcome — the project's culture is dispute-driven, grounded in the principles
- New conventions are added through normal contribution: proposal, discussion, agreement, then an edit

The project is in active `0.x` development. The formal open-source release happens at `1.0.0` per [ADR-011](docs/decisions/0011-open-source-at-public-release.md).

---

## Acknowledgments

Tessera builds on the work of the International Civil Aviation Organization (ICAO), whose Doc 9303 series defines the standards this SDK implements. The SDK references those standards rather than reproducing them.

The project's design owes a debt to the broader open source community's work on identity document standards, MRZ parsing libraries that came before, and the Kotlin Multiplatform ecosystem that makes shared cross-platform logic practical.
