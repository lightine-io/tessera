# Getting Started

Your first ten minutes with Tessera: add the dependency, parse an MRZ, read the validation verdict, and generate an MRZ back. Everything on this page is **platform-free** — the core is pure logic with no platform I/O, so the same code runs on the JVM (backend, CLI, desktop), in Android apps, and on iOS. Live-camera scanning is platform-specific and has its own guide: [MRZ Camera Reading](features/mrz-camera-reading.md).

**Verified: 2026-06-12** — every snippet below is checked symbol-by-symbol against the published `0.2.1` API (the project's [usage-example rule](conventions.md) requires it).

---

## Prerequisites

- A Kotlin or Java project built with Gradle or Maven. (Maven and Swift Package Manager install blocks are in the [README](../README.md#installation).)
- **JVM consumers: Java 21 or newer at runtime** — the published JVM artifacts target JVM 21 bytecode. Android consumers: `minSdk` 23+; iOS: deployment target 18+ (see [Platforms](../README.md#platforms)).
- No other setup: the core has no platform dependencies, makes no network calls, and persists nothing.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.2.1"))
    implementation("io.lightine.tessera:tessera-mrz-core")
}
```

The BOM pins every Tessera module to the same version; `tessera-mrz-core` carries parsing, validation, and generation.

## 2. Parse an MRZ

`MrzParser.parse` auto-detects the format (TD1/TD2/TD3/MRV-A/MRV-B) and returns a sealed result — the three outcomes are explicit, so a partially-valid read can never masquerade as a clean one:

```kotlin
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult

val result = MrzParser.parse(
    """
    P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
    L898902C36UTO7408122F1204159ZE184226B<<<<<10
    """.trimIndent(),
)

when (result) {
    is ParseResult.Success -> {
        val doc = result.document as TD3
        println("Name: ${doc.commonFields.primaryIdentifier}, ${doc.commonFields.secondaryIdentifier}")
        println("Document number: ${doc.commonFields.documentNumber}")
    }
    is ParseResult.PartialSuccess -> {
        // Data was extracted, but some validations failed. The document AND the
        // failures are both available — the SDK reports; you decide what they mean.
        println("Extracted with ${result.metadata.validationFailures.size} validation failure(s)")
    }
    is ParseResult.Failure -> {
        println("Structurally too broken to build a document: ${result.error}")
    }
}
```

The sample MRZ is the official ICAO Doc 9303 specimen (Utopia/Eriksson) — safe synthetic data. Never test with real documents; generate fixtures instead (step 4).

Details, per-format entry points, and input forms: [MRZ Parsing](features/mrz-parsing.md).

## 3. Validate

Parsing already validates — `result.metadata.validationFailures` and `.warnings` are populated on every parse, and `PartialSuccess` *is* the "data extracted but something failed" signal. The standalone validator exists for re-checking an already-parsed document (for example, with a different reference time). Steps 3 and 4 reuse `doc` from the `Success` branch above:

```kotlin
import io.lightine.tessera.mrz.validation.MrzValidator

val verdict = MrzValidator.validate(doc)          // referenceTime defaults to now
println("Failures: ${verdict.validationFailures.size}, warnings: ${verdict.warnings.size}")
```

There is deliberately **no `isValid` boolean** — the SDK reports observations (check-digit mismatches, implausible dates, unknown country codes) and the consumer decides what disqualifies a document ([ADR-006](decisions/0006-no-isvalid-boolean.md)). Catalog of checks: [MRZ Validation](features/mrz-validation.md).

## 4. Generate

Generation is the inverse of parsing — useful for test fixtures and round-trip verification. The simplest demonstration is a round trip of the document parsed above:

```kotlin
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator

when (val generated = MrzGenerator.generate(doc)) {
    is GenerationResult.Success -> generated.mrz.forEach(::println)  // the MRZ lines
    is GenerationResult.Failure -> println("Cannot produce a conformant MRZ: ${generated.error}")
}
```

Generation never silently emits an invalid MRZ — input that cannot produce a conformant line fails with a typed error. Building documents from scratch and transliteration of non-MRZ characters: [MRZ Generation](features/mrz-generation.md), [Transliteration](features/transliteration.md).

## Troubleshooting

- **`Failure` on input that "looks fine"** — the parser is strict by default: exact line lengths (44 for TD3), no stray whitespace. With Kotlin multiline strings, `trimIndent()` (as above) removes the indentation that would otherwise break line lengths.
- **`PartialSuccess` is not an error.** A real, slightly-damaged document still parses; the failures list tells you exactly what didn't check out. Treating it as a hard failure throws away data the SDK deliberately preserved.
- **Unknown-country warnings on valid documents** — recognition tables are deliberately conservative; an unknown code is a *warning*, not a failure ([ADR-013](decisions/0013-recognition-failures-are-warnings.md)).

## Where to go next

- [Feature guides index](features/README.md) — every capability, each with a Usage section.
- [Android integration](guides/android-integration.md) — from empty app to a working live-camera scan.
- [MRZ Camera Reading](features/mrz-camera-reading.md) — the camera capability reference (Android and iOS).
- [Reading risks](reading-risks.md) — what each reading method does and does not establish; read before trusting any single method.
