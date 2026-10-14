package io.lightine.tessera.telemetry

/**
 * Holds the [TelemetrySink] that SDK modules send events to. The initial sink is [NoOpTelemetrySink].
 *
 * Register a sink at app startup, before concurrent use. Concurrent registration is not supported.
 *
 * @since 0.1.0
 */
public object TelemetrySinkRegistry {
    private var sink: TelemetrySink = NoOpTelemetrySink

    /**
     * Sets the current sink, replacing the previous one.
     *
     * @param sink The sink to use from now on.
     */
    public fun register(sink: TelemetrySink) {
        this.sink = sink
    }

    /** Sets the current sink back to [NoOpTelemetrySink]. */
    public fun reset() {
        this.sink = NoOpTelemetrySink
    }

    /** The current sink. It is [NoOpTelemetrySink] until you register one. */
    public val current: TelemetrySink
        get() = sink
}
