package io.lightine.tessera.telemetry

/**
 * Receives diagnostic events about SDK operation. Consumers implement this interface to
 * route events to their own observability stack (logging, metrics, tracing, etc.).
 *
 * **0.1.0 behavior:** no SDK module emits events in 0.1.0. Registering a sink succeeds
 * but has no observable effect until the first emitting module ships (camera-based
 * reading in 0.2.0; NFC chip reading in 0.6.0). See
 * [ADR-015](https://lightine.youtrack.cloud/articles/TES-A-48)
 * for the reasoning.
 *
 * **Threading:** once SDK modules begin emitting events, [record] may be invoked from any
 * thread. Implementations must be thread-safe. The 0.1.0 release does not enforce this
 * — there are no callers — but the contract is documented now and locked under
 * [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37).
 */
public interface TelemetrySink {
    public fun record(event: TelemetryEvent)
}
