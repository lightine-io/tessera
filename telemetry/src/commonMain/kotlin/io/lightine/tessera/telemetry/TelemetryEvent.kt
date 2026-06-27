package io.lightine.tessera.telemetry

/**
 * A diagnostic event emitted by an SDK module and delivered to a [TelemetrySink].
 *
 * `TelemetryEvent` is an **open interface**, not a sealed hierarchy. Future event types
 * are added in the releases of the modules that emit them (camera in 0.2.0, NFC in
 * 0.6.0, possibly others). Open-interface additions are non-breaking under
 * [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37)
 * because consumers cannot exhaustively pattern-match on an open interface. The
 * divergence from the project's sealed result-type pattern (`ParseResult`,
 * `TransliterationResult`, etc.) is intentional and recorded in
 * [ADR-015](https://lightine.youtrack.cloud/articles/TES-A-48).
 *
 * **0.1.0 ships no concrete implementations.** Consumers can implement custom event
 * types if they want to test their sink wiring, but the first SDK-emitted events ship
 * with the camera module in 0.2.0.
 */
public interface TelemetryEvent {
    /**
     * A stable string identifier for this event type. Consumers may route or filter on
     * this property without inspecting concrete subtype fields.
     */
    public val name: String
}
