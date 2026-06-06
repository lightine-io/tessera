---
paths:
  - "docs/features/**"
  - "**/commonMain/**/*.kt"
  - "**/androidMain/**/*.kt"
  - "**/iosMain/**/*.kt"
---

# A usage example for every public API

**When you add or change a public API, its feature document gets a copy-paste usage example — and the example must compile against the shipped API, not be invented or transcribed from an older sketch.** This is the documentation sibling of the "Tests for every new public API" rule in [`CLAUDE.md`](../../CLAUDE.md): a test proves the API works; an example shows a consumer how to call it. A public API a consumer cannot see used is one they cannot adopt.

## What the example must be

- **In the feature document** (`docs/features/`), as a `## Usage` section with a fenced `kotlin` block: the imports, the call, and how the result is handled (branch the sealed result type — the consumer decides what the outcome *means*). One example per primary entry point is enough; not every overload.
- **Verified against the real code, symbol by symbol.** Read the actual signatures, field names, and result variants *before* writing — do not transcribe from memory or from an illustrative shape. This is not pedantry: the backfill that introduced this rule found, *by verifying*, a compile error in the README's marquee example (`as MrzDocument.TD3` → `as TD3`), a doc claiming a field the shipped type did not have (`ValidationResult.passedChecks`), and a telemetry mechanism the camera silently ignored. An illustrative-but-wrong example is worse than none. (The verification habit: [`known-pitfalls.md`](../known-pitfalls.md) → "Claiming a Gap Without Verifying the Files.")
- **Honest about provisional surfaces.** If a contract is not yet locked (e.g. a `0.x`-provisional iOS/Swift projection), the example says so rather than implying permanence.

## KDoc

Put the worked example in the feature document now. A full Dokka `@sample` inside the source — wiring a sample source set so the snippet is compiler-checked — is a heavier, separable enhancement, not required by this rule. This rule is about the feature docs being usable.

## Scope

Fires on feature documents and public source (`commonMain` and the platform mains). It applies to the **public surface a consumer calls** — not internal helpers or private functions. A pure-data type surfaced only as another API's result is exercised by that API's example; it does not need its own. The trigger is *a public API was added or changed*, not *any edit in these paths*.

## Cross-references

- The test sibling: [`CLAUDE.md`](../../CLAUDE.md) → "Tests for every new public API."
- Human-facing mirror: [`../../docs/conventions.md`](../../docs/conventions.md) → "What Every Feature Document Must Include."
- The authoring duty to surface what's reusable or exposable: [`future-proof-duty.md`](future-proof-duty.md).
