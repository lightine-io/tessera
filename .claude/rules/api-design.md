---
paths:
  - "**/*.kt"
---

# API and Kotlin design essentials

- **Verbatim extraction.** Never normalize, correct, or infer "intended" values: the
  raw value is the value. Validation findings accompany data; they never gate
  construction (only structural impossibility does). Computed and quality signals ride
  alongside the raw value; the consumer decides.
- **Result and error shape.** Operations with success, partial, and fail outcomes use a
  sealed result type with all three variants. Every error is typed and specific (no
  generic `SdkException` or `UnknownFailure`) and domain-prefixed (`Mrz*`, `Nfc*`,
  `Camera*`, `Chip*`). A new error type ships with a test that produces it and an entry
  in the observations reference ([TES-A-28](https://lightine.youtrack.cloud/articles/TES-A-28)).
- **Forward-compatible additions only** within a major version; removals deprecate
  first. A public API change updates the ABI dumps (`checkKotlinAbi` fails otherwise)
  and carries the feature checklist in the PR template
  ([documentation rule](documentation.md)).
- **Internal packages first.** Promote to a standalone module only for independent
  reuse, evolution, shipping, or optional inclusion.
- **Tests ship with the feature.** Every public API and error type is exercised;
  inverse pairs get property-based coverage (`parse(generate(x)) == x`); fixtures are
  synthetic (SDK generator) or ICAO test vectors, never real document data.
- **Kotlin gotchas that bit us.** Don't name a constructor parameter `field` in a class
  whose custom getter uses string interpolation. Kotlin/Native and Cocoa: APIs that
  hold delegates, observers, or targets weakly need a strong Kotlin-side reference for
  the object's whole lifetime; verify on a physical device over time.
- **Platform behavior:** read the vendor's docs first ([platform work](platform-work.md));
  state rules target-agnostically where the principle holds.
