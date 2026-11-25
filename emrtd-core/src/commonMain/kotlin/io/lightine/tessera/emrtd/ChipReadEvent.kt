package io.lightine.tessera.emrtd

import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Telemetry emitted once per step of a chip read, with no document data.
 *
 * It holds only protocol names, status words, file identifiers, byte counts and timings: never
 * file content, keys or values from the document.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property step Which step this is.
 * @property outcome How the step ended.
 * @property protocol The protocol name, for example `PACE-ECDH-GM-AES-128`, `BAC` or `CA`, when the
 *   step runs one.
 * @property fileId The file identifier, for a file step.
 * @property byteCount The number of bytes read, for a file step that read the file.
 * @property statusWord The chip's status word, when the step ended on one.
 * @property durationMillis How long the step took, in milliseconds.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReadEvent internal constructor(
    public val step: ChipReadStep,
    public val outcome: ChipReadStepOutcome,
    public val protocol: String?,
    public val fileId: Int?,
    public val byteCount: Int?,
    public val statusWord: Int?,
    public val durationMillis: Long,
) : TelemetryEvent {
    // Only the SDK makes these events, so new fields can be added later without breaking callers.

    /** Event name: `emrtd.chip.step`. */
    override val name: String get() = EVENT_NAME

    /** The fields; no document data. */
    override fun toString(): String =
        "ChipReadEvent($step, $outcome, protocol=$protocol, fileId=${fileId?.toString(16)}, byteCount=$byteCount, " +
            "statusWord=${statusWord?.toString(16)}, durationMillis=$durationMillis)"

    /** Constants for [ChipReadEvent]. */
    public companion object {
        /** The event name: `emrtd.chip.step`. */
        public const val EVENT_NAME: String = "emrtd.chip.step"
    }
}

/**
 * A telemetry sink for chip reads that hands you [ChipReadEvent] directly, with no cast from the
 * general [TelemetryEvent]. It is a [TelemetrySink], so it goes wherever one goes: the `telemetry`
 * of the Android and iPhone read settings, `ChipReader`, `TelemetrySinkRegistry`.
 *
 * ```kotlin
 * val settings = NfcReadSettings(telemetry = ChipReadEventSink { event -> log(event.step, event.outcome) })
 * ```
 *
 * Events of other kinds, which a registered sink may also receive, are not passed on. Like every
 * sink, it can be called from any thread and must be thread-safe; an exception it throws is
 * ignored. The general [TelemetrySink] stays for apps that count every kind of event.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public fun interface ChipReadEventSink : TelemetrySink {
    /**
     * Handles one step event of a chip read.
     *
     * @param event The event: protocol names, status words, file identifiers, byte counts and
     *   timings, never document data.
     */
    public fun onEvent(event: ChipReadEvent)

    /** Passes [event] to [onEvent] when it is a [ChipReadEvent]; drops any other kind. */
    override fun record(event: TelemetryEvent) {
        if (event is ChipReadEvent) onEvent(event)
    }
}

/**
 * The steps of a chip read, for telemetry.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipReadStep {
    /** Reading a file. */
    READ_FILE,

    /** Running PACE. */
    PACE,

    /** Running BAC. */
    BAC,

    /** Selecting the eMRTD application. */
    SELECT_APPLICATION,

    /** Running chip authentication. */
    CHIP_AUTHENTICATION,

    /** Running active authentication. */
    ACTIVE_AUTHENTICATION,

    /** Running the passive-authentication checks (no chip command). */
    PASSIVE_AUTHENTICATION,

    /** The whole read. */
    READ,
}

/**
 * How a step ended, for telemetry.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipReadStepOutcome {
    /** The step did what it set out to do. */
    SUCCEEDED,

    /** The step did not, and the read went on or stopped. */
    FAILED,

    /** The step was not needed or not possible. */
    SKIPPED,
}
