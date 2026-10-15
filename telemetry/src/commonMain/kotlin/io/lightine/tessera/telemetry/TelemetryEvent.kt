package io.lightine.tessera.telemetry

/**
 * A diagnostic event that an SDK module delivers to a [TelemetrySink].
 *
 * This is an open interface, so you can implement it for your own events.
 *
 * @since 0.1.0
 */
public interface TelemetryEvent {
    /** Stable name of the event type. Use it to route or filter events. */
    public val name: String
}
