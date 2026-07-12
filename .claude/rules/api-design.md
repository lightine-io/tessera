---
paths:
  - "**/*.kt"
---

# API & Kotlin Design Essentials

Distilled 2026-07-12 from `working-patterns.md` / `known-pitfalls.md` at the
constitution migration; full history in git and the internal KB archive note.

- **Verbatim extraction.** Never normalize, correct, or infer "intended" values — the
  raw value is the value. Validation findings accompany data; they never gate
  construction (only structural impossibility does). Computed/quality signals ride
  alongside the raw value; the consumer decides.
- **Result and error shape.** Operations with success/partial/fail semantics use a
  sealed result type with all three variants. Every error is typed and specific — no
  generic `SdkException`/`UnknownFailure` — and domain-prefixed (`Mrz*`, `Nfc*`,
  `Camera*`, `Chip*`). New error type ⇒ a test that produces it, and a taxonomy entry
  ([TES-A-28](https://lightine.youtrack.cloud/articles/TES-A-28)).
- **Forward-compatible additions only** within a major version; removals deprecate
  first. Public API change ⇒ update the feature KB article + usage example (see
  [`usage-example-required.md`](usage-example-required.md)) + the ABI dumps.
- **Internal packages first** (Principle 11). Promote to a standalone module only for
  independent reuse, evolution, shipping, or optional inclusion.
- **Tests ship with the feature.** Every public API and error type is exercised;
  inverse pairs get property-based coverage (`parse(generate(x)) == x`); fixtures are
  synthetic (SDK generator) or ICAO test vectors — never real document data.
- **Kotlin gotchas that bit us.** Don't name a constructor parameter `field` in a
  class whose custom getter uses string interpolation. Kotlin/Native ↔ Cocoa: APIs
  holding delegates/observers/targets weakly need an explicit strong Kotlin-side
  reference for the object's whole lifetime — verify on a physical device over time.
- **Platform behavior: consult vendor docs first**
  ([`consult-vendor-docs.md`](consult-vendor-docs.md)); state new rules and behaviors
  target-agnostically where the principle holds.
