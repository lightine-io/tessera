# Telemetry

This feature document describes the SDK's pluggable telemetry interface: the contract consumers implement to receive diagnostic events about SDK operation, the default no-op sink, the registration model, and the redaction utilities.

The SDK does not include a built-in telemetry destination and does not phone home. Telemetry is opt-in via a consumer-provided implementation; the SDK ships a no-op default so consumers who do not need telemetry can ignore it entirely.

**Status:** Living
**Available since:** 0.1.0 (contract only; see "0.1.0 Status" below)
**Platform availability:** Target-agnostic. The telemetry interface is pure logic and runs on every target the project supports.

---

## 0.1.0 Status

The 0.1.0 release publishes the **telemetry contract and its supporting machinery**, but **no 0.1.0 SDK module emits events**. Consumers can register a `TelemetrySink` in 0.1.0 and the registration succeeds, but no events will be delivered to it until a module that emits telemetry ships in a later release.

The first SDK modules to emit telemetry events are:

- **`mrz-camera-{platform}`** in release 0.2.0 (live camera reading).
- **`emrtd-nfc-{platform}`** in release 0.6.0 (NFC chip reading).

This shape — locking the public contract in 0.1.0 while the first callers ship later — follows from two project constraints. First, [`docs/scope.md`](../scope.md) commits the pluggable telemetry interface as part of 0.1.0. Second, [`docs/architecture.md`](../architecture.md)'s dependency graph shows `telemetry` as depended on by the camera and NFC modules only — `mrz-core` and `emrtd-core` do not emit events. The contract has to lock before any caller exists. The reasoning is recorded in [ADR-015](../decisions/0015-telemetry-contract-only-at-0-1-0.md).

Consumers integrating against 0.1.0 should treat the telemetry surface as forward-compatibility scaffolding: implement and register a sink now if you want your observability pipeline ready for 0.2.0+ events, but do not expect events from 0.1.0 SDK calls.

---

## Public Surface

The illustrative shape of the 0.1.0 surface:

```
interface TelemetrySink {
    fun record(event: TelemetryEvent)
}

interface TelemetryEvent {
    val name: String
}

object NoOpTelemetrySink : TelemetrySink

object TelemetrySinkRegistry {
    fun register(sink: TelemetrySink)
    fun reset()
    val current: TelemetrySink
}

object Redaction {
    fun redactMrzLine(line: String): String
}
```

The actual class names, method names, and registration mechanism are decided at implementation time. The shape above reflects what 0.1.0 ships.

All types live in the `io.lightine.tessera.telemetry` package within the `telemetry` module.

---

## Event Hierarchy

`TelemetryEvent` is an **open interface**, not a sealed hierarchy. This diverges from the project's existing result types (`ParseResult`, `TransliterationResult`, `GenerationResult`), which are sealed.

The reason is the combination of [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md) (strict backward compatibility from 0.x) and the timeline of event types. Future event types will be added in the releases of the modules that emit them — camera in 0.2.0, NFC in 0.6.0, and possibly others. Under ADR-007's strict reading, adding a new variant to a sealed hierarchy is a breaking change because consumer code that pattern-matches exhaustively stops compiling when the new variant lands. An open interface avoids this: consumers cannot exhaustively pattern-match on it, so adding a new implementation is additive.

The trade-off is that consumers lose compile-time exhaustiveness when handling events. The project's view is that this is acceptable for telemetry: consumers typically route events to an observability backend (Datadog, Honeycomb, OpenTelemetry, application logs) rather than pattern-matching on them. The `name: String` property gives consumers a stable string identifier they can route on; each future event type adds its own typed fields that consumers can read when they care about specifics. The full reasoning, including alternatives considered, is in [ADR-015](../decisions/0015-telemetry-contract-only-at-0-1-0.md).

---

## Sink Registration

A single `TelemetrySink` is registered at a time. The registry holds the currently-registered sink; SDK modules that emit events look it up via `TelemetrySinkRegistry.current`. The default is `NoOpTelemetrySink`, so the SDK never crashes for missing-sink reasons — it just discards events that would have been delivered.

Registration is a singleton mutable state, matching the precedent set by `TransliterationProfileRegistry` (see [`docs/features/transliteration.md`](transliteration.md)). The documented contract is **register at startup before concurrent use**. Once SDK modules begin emitting events (0.2.0+), the registered sink may be called from any thread, so consumer implementations must be thread-safe.

`TelemetrySinkRegistry.reset()` restores the no-op default. This is useful for test isolation; it is not intended as a runtime sink-swap mechanism.

Emitting components default their sink to `TelemetrySinkRegistry.current` (captured at construction), so registering at startup is enough — but they also accept an explicit `telemetry` argument to **override the registered default per instance** (e.g. `CameraXMrzScanner(context, owner, telemetry = mySink)`). With nothing registered and nothing passed, events route to `NoOpTelemetrySink` and are discarded.

---

## Usage

Implement `TelemetrySink`, then register it once at startup; the camera reader (the first emitter, 0.2.0) records to whatever is registered. The example compiles against the shipped API.

```kotlin
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry

// record() may be invoked from any thread once emitting modules are active — keep it thread-safe.
class LoggingSink : TelemetrySink {
    override fun record(event: TelemetryEvent) {
        // event carries non-PII diagnostics (counts, confidence, outcome) — never document fields.
        println("${event.name}: $event")
    }
}

// Register once at application startup, before constructing any SDK component that emits:
TelemetrySinkRegistry.register(LoggingSink())
```

---

## Redaction

The SDK's events may carry sensitive content — MRZ lines, document numbers, names, dates of birth. Consumers who pipe events into observability backends are responsible for redaction policy, but the SDK provides utilities to make redaction straightforward.

The 0.1.0 release ships one helper:

- **`Redaction.redactMrzLine(line: String): String`** — replaces every alphanumeric character in an MRZ line with `*`, preserving filler `<` characters and the line's length. The output is the same shape as the input, so log readers can still see "this was an MRZ line of length N" without seeing the content. Inputs that are already malformed (wrong length, non-MRZ characters) are not validated; the function masks alphanumerics regardless.

More helpers will ship alongside the events that need them. Examples likely in 0.2.0+:

- `redactDocumentNumber(value: String): String`
- `redactName(name: String): String`
- `redactDateOfBirth(value: String): String`

The redaction surface is deliberately minimal in 0.1.0 because there are no events to redact yet. Designing speculative helpers for event shapes that do not exist would lock the wrong surface under [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md).

---

## What Telemetry Is Not

Telemetry is **distinct from logging**. The `telemetry` module is for consumer-observable events that flow out of the SDK; the `logging` module (planned alongside the first I/O modules) is for SDK-internal diagnostic logging that the SDK itself writes. The boundary is recorded in [`docs/architecture.md`](../architecture.md).

Telemetry is also **not analytics**, **not crash reporting**, and **not licensing**. The SDK does not phone home for any reason. Consumers wire telemetry into whatever observability stack they already operate; the SDK has no opinion on where events go or what consumers do with them. This is part of [Principle 10](../principles.md) (Privacy by Default).

---

## When Telemetry Is Invoked

In 0.1.0: never. No SDK module calls `TelemetrySinkRegistry.current.record(...)`.

In 0.2.0+: the camera reading module is the first real emitter. Its analyse-frame core (in the platform-agnostic `mrz-camera-core` module) emits one `CameraFrameEvent` per analysed frame — the outcome (decoded / no-MRZ / OCR-failed), recognized-line count, MRZ-region-found flag, a coarse OCR confidence, and the detected MRZ format. The event carries **diagnostics only and no document data** (no recognized text, no parsed field values), so a sink routes to any backend without leaking PII. Finer-grained events (frame lifecycle, OCR timing) may follow with the owns-the-camera-session layer; the taxonomy grows with the release.

In 0.6.0+: the NFC chip reading module will emit events for tag detection, authentication attempts, data group reads, and parse outcomes. The exact event taxonomy is designed alongside that release.

The empty 0.1.0 emission set is a property of the dependency graph, not an oversight: see [ADR-015](../decisions/0015-telemetry-contract-only-at-0-1-0.md) and the "0.1.0 Status" section above.

---

## Status of Implementation

| Area | Status |
|---|---|
| `TelemetrySink` interface | Shipped (0.1.0) |
| `TelemetryEvent` open interface | Shipped (0.1.0) |
| `NoOpTelemetrySink` default | Shipped (0.1.0) |
| `TelemetrySinkRegistry` singleton | Shipped (0.1.0) |
| `Redaction.redactMrzLine` | Shipped (0.1.0) |
| Concrete `TelemetryEvent` types | First one shipped: `CameraFrameEvent` (camera 0.2.0, analyse-frame slice). NFC events deferred to 0.6.0 |
| Additional redaction helpers | Shipped alongside the events that need them |
| Thread-safety enforcement | Documented as consumer responsibility; not enforced in 0.1.0 |

---

## Related Documents

- [ADR-003](../decisions/0003-modular-architecture.md) — modular architecture; defines `telemetry` as a cross-cutting module.
- [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md) — strict backward compatibility from 0.x; the reason `TelemetryEvent` is an open interface.
- [ADR-015](../decisions/0015-telemetry-contract-only-at-0-1-0.md) — the decision recording this slice.
- [`docs/architecture.md`](../architecture.md) — module dependency graph and the `telemetry`/`logging` distinction.
- [`docs/scope.md`](../scope.md) — 0.1.0 release commitment that scopes this work.
- [`docs/principles.md`](../principles.md) — Principles 4, 5, 9, 10 referenced above.
- [Glossary — YouTrack KB](https://lightine.youtrack.cloud/articles/TES-A-4) — definition of Telemetry.
