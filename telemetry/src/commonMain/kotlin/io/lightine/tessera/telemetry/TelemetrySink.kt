package io.lightine.tessera.telemetry

/**
 * Receives diagnostic events from SDK modules.
 *
 * SDK modules can call [record] from any thread, so an implementation must be thread-safe.
 *
 * @since 0.1.0
 */
public interface TelemetrySink {
    /**
     * Handles one event.
     *
     * @param event The event to record.
     */
    public fun record(event: TelemetryEvent)
}
