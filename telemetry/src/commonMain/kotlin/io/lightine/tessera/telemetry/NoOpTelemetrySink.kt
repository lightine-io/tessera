package io.lightine.tessera.telemetry

/**
 * The default [TelemetrySink]. It discards every event.
 *
 * @since 0.1.0
 */
public object NoOpTelemetrySink : TelemetrySink {
    /** Discards [event]. */
    override fun record(event: TelemetryEvent) {
        // Intentionally empty.
    }
}
