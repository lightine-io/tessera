# Tessera

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.tessera/tessera-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.tessera/tessera-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/lightine-io/tessera/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/tessera/actions/workflows/check.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

Tessera is a vendor-neutral, open-source SDK that reads, validates, and generates the data in the MRZ (machine-readable zone) of passports and ID cards. It is built with Kotlin Multiplatform and runs on Android, iOS, and the JVM. Tessera reports what it read and what it checked. Your app decides what to do with the result. The current release is `0.5.1`.

## Install

Gradle (Kotlin DSL), for Android and the JVM:

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.5.1"))
    implementation("io.lightine.tessera:tessera-mrz-core")                // parse, validate, generate
    implementation("io.lightine.tessera:tessera-mrz-camera-android")      // Android: headless camera reading
    implementation("io.lightine.tessera:tessera-mrz-camera-ui-android")   // Android: scanner screen
}
```

Use only the lines you need. A JVM app needs only the first two, plus `kotlin { jvmToolchain(21) }`, because the classes target Java 21.

Maven (JVM):

```xml
<dependency>
    <groupId>io.lightine.tessera</groupId>
    <artifactId>tessera-mrz-core-jvm</artifactId>
    <version>0.5.1</version>
</dependency>
```

Maven does not read Gradle module metadata. Use the `-jvm` artifact, as shown, so its dependencies arrive in the compile scope.

Swift Package Manager (iOS), in `Package.swift`:

```swift
.package(url: "https://github.com/lightine-io/tessera-swift", exact: "0.5.1"),
// In your target's dependencies:
.product(name: "Tessera", package: "tessera-swift"),
.product(name: "TesseraUI", package: "tessera-swift"),  // the scanner screen
```

In Xcode, choose **File > Add Package Dependencies** and enter the same URL.

## First MRZ

This parses the specimen passport MRZ from ICAO Doc 9303, which is published test data:

```kotlin
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult

fun main() {
    val mrz = """
        P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
        L898902C36UTO7408122F1204159ZE184226B<<<<<10
    """.trimIndent()
    when (val result = MrzParser.parse(mrz)) {
        is ParseResult.Success -> {
            val fields = result.document.commonFields
            println("Name: ${fields.primaryIdentifier}, ${fields.secondaryIdentifier}")
            println("Document number: ${fields.documentNumber}")
        }
        is ParseResult.PartialSuccess -> println("Read, but some checks failed")
        is ParseResult.Failure -> println("This is not a readable MRZ")
    }
}
```

For a complete, runnable walkthrough, pick your platform:

- [Read your first MRZ on Android](https://lightine.youtrack.cloud/articles/TES-A-76)
- [Read your first MRZ on iOS](https://lightine.youtrack.cloud/articles/TES-A-77)
- [Read your first MRZ on the JVM](https://lightine.youtrack.cloud/articles/TES-A-10)

## Documentation

The full documentation is in the [public knowledge base](https://lightine.youtrack.cloud/articles/TES-A-3). Start at [Use the SDK](https://lightine.youtrack.cloud/articles/TES-A-16).

- [Parse an MRZ into fields](https://lightine.youtrack.cloud/articles/TES-A-25) - every format, input form, and option.
- [Use the default scanner screen](https://lightine.youtrack.cloud/articles/TES-A-74) - the ready-made scan flow on Android and iOS.
- [Read an MRZ from the live camera](https://lightine.youtrack.cloud/articles/TES-A-40) - the headless camera scanner.
- [MRZ result types and fields](https://lightine.youtrack.cloud/articles/TES-A-33) - reference for documents, fields, and results.
- [Errors, failures, and warnings](https://lightine.youtrack.cloud/articles/TES-A-28) - reference for everything the SDK can report.

What changed in each version is in [`CHANGELOG.md`](CHANGELOG.md).

## Platforms

| Target | Minimum |
|---|---|
| Android | `minSdk` 23 |
| iOS | iOS 18 |
| JVM | Java 21 |
| Kotlin | 2.4 |

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. The [Contribute](https://lightine.youtrack.cloud/articles/TES-A-78) pages cover machine setup, testing, and conventions.

## Security

Report vulnerabilities privately. [`SECURITY.md`](SECURITY.md) explains how.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
