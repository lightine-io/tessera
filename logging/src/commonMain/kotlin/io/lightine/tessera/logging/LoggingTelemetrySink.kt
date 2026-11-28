package io.lightine.tessera.logging

import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink

/**
 * A [TelemetrySink] that prints each SDK diagnostic event to the platform log.
 *
 * This is opt-in and meant for debugging. The SDK never logs on its own; nothing is printed
 * until your app registers this sink:
 *
 * `TelemetrySinkRegistry.register(LoggingTelemetrySink())`
 *
 * Each event is printed as one line: the event's [TelemetryEvent.name], a space, and the event's own
 * text (what its `toString()` returns). The events of the Tessera modules build that text without
 * document data. The events of your own types print whatever their `toString()` returns, so keep
 * personal data out of it.
 *
 * On Android the line goes to Logcat at debug level under [tag]. On the JVM it goes to standard
 * output as `tag: line`.
 *
 * @param tag The log tag. The default is `Tessera`.
 * @since 1.2.0
 */
public class LoggingTelemetrySink(
    private val tag: String = "Tessera",
) : TelemetrySink {
    /**
     * Prints one event to the platform log.
     *
     * @param event The event to print.
     * @since 1.2.0
     */
    override fun record(event: TelemetryEvent) {
        writeLogLine(tag, "${event.name} $event")
    }
}
