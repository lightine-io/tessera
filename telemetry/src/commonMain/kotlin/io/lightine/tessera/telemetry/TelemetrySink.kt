package io.lightine.tessera.telemetry

/**
 * Receives diagnostic events from SDK modules.
 *
 * SDK modules can call [record] from any thread, so an implementation must be thread-safe.
 *
 * An exception thrown by [record] is ignored: the SDK drops it and reading continues. It never
 * becomes a camera or read failure. Coroutine cancellation is not ignored.
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
