# Tessera

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.tessera/tessera-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.tessera/tessera-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/lightine-io/tessera/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/tessera/actions/workflows/check.yml)

Tessera is a free, open-source SDK that reads, validates, and generates the data in the MRZ (machine-readable zone) of passports, ID cards, and visas. It runs on Android, iOS, and the JVM. It reports what it read and checked; your app decides what to do with that.

It is for developers adding document reading to apps or backends. Documentation: [Start here](https://lightine.youtrack.cloud/articles/TES-A-16).

## Common tasks

- Ready-made scanner screen. Android: [Read your first MRZ on Android with the ready-made screen](https://lightine.youtrack.cloud/articles/TES-A-76). iOS: [Read your first MRZ on iOS with the ready-made screen](https://lightine.youtrack.cloud/articles/TES-A-77).
- Camera behind your own UI. Android: [Read your first MRZ on Android with the headless camera](https://lightine.youtrack.cloud/articles/TES-A-142). iOS: [Read your first MRZ on iOS with the headless camera](https://lightine.youtrack.cloud/articles/TES-A-143).
- Parse an MRZ string. JVM and Kotlin: [Read your first MRZ on the JVM](https://lightine.youtrack.cloud/articles/TES-A-10). iOS: [Parse an MRZ string on iOS](https://lightine.youtrack.cloud/articles/TES-A-113).
- Read an MRZ from a saved photo. Android: [Read an MRZ from a saved image on Android](https://lightine.youtrack.cloud/articles/TES-A-64). iOS: [Read an MRZ from a saved image on iOS](https://lightine.youtrack.cloud/articles/TES-A-116).
- Generate test MRZ data. Kotlin: [Generate an MRZ in Kotlin](https://lightine.youtrack.cloud/articles/TES-A-26).

## What works where

✓ yes, ◐ partial, ✗ no, n/a not applicable. All rows: [Capability matrix](https://lightine.youtrack.cloud/articles/TES-A-139).

| Capability | JVM | Android | iOS |
|---|---|---|---|
| Parse and read fields | ✓ | ✓ | ◐ |
| Validate and check digits | ✓ | ✓ | ◐ |
| Generate an MRZ | ✓ | ✓ | ◐ |
| Transliterate names | ✓ | ✓ | ✓ |
| Live camera, no UI | n/a | ✓ | ◐ |
| Saved image | ◐ | ✓ | ✓ |
| Ready-made scanner screen | n/a | ✓ | ✓ |
| Diagnostic events | ✓ | ✓ | ◐ |

Tessera's own code makes no network calls and logs nothing. On Android, the text recognition (Google ML Kit) uploads usage metrics to Google, never images or text; an app that must make no network calls can turn this off. See [SECURITY.md](SECURITY.md#scope).

## Install

Release 0.6.0. Minimums: minSdk 23, iOS 18, Java 21. Modules: [Choose your pieces](https://lightine.youtrack.cloud/articles/TES-A-140).

`build.gradle.kts` (the JVM needs only the first two lines):

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.6.0"))
    implementation("io.lightine.tessera:tessera-mrz-core")
    implementation("io.lightine.tessera:tessera-mrz-camera")
    implementation("io.lightine.tessera:tessera-mrz-camera-ui")
}
```

`pom.xml` (JVM, Maven):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.lightine.tessera</groupId>
            <artifactId>tessera-bom</artifactId>
            <version>0.6.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>io.lightine.tessera</groupId>
        <artifactId>tessera-mrz-core</artifactId>
    </dependency>
</dependencies>
```

`Package.swift` (iOS):

```swift
.package(url: "https://github.com/lightine-io/tessera-swift", exact: "0.6.0"),
.product(name: "Tessera", package: "tessera-swift"),
.product(name: "TesseraUI", package: "tessera-swift"),
```

## Go further

[Tutorials](https://lightine.youtrack.cloud/articles/TES-A-141) · [How-to guides](https://lightine.youtrack.cloud/articles/TES-A-88) · [Reference](https://lightine.youtrack.cloud/articles/TES-A-135) · [Explanation](https://lightine.youtrack.cloud/articles/TES-A-83). Changes per release: [`CHANGELOG.md`](CHANGELOG.md).

Questions and bugs: [GitHub issues](https://github.com/lightine-io/tessera/issues). Vulnerabilities: report privately, see [`SECURITY.md`](SECURITY.md). To contribute: [`CONTRIBUTING.md`](CONTRIBUTING.md). Licence: Apache License 2.0 ([`LICENSE`](LICENSE)).
