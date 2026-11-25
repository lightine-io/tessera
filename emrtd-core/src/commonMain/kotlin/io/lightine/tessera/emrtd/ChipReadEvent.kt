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
 * @property durationMillis How long the step took, in milliseconds. For
 *   [ChipReadStep.TAG_HANDOVER]: how long the connection to the tag had lasted.
 * @property systemCode The platform's own code, for a step that ended on one: today only
 *   [ChipReadStep.SESSION_END] on iPhone, with Core NFC's `NFCReaderError` code, and
 *   [ChipReadStep.SESSION_RENEW] when the system's end with that code led to the renewal. `null`
 *   for every other step and on Android.
 * @property renewal For [ChipReadStep.SESSION_RENEW] on iPhone: which new session of the read
 *   this is, from 1. `null` for every other step and on Android.
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
    public val systemCode: Int? = null,
    public val renewal: Int? = null,
) : TelemetryEvent {
    // Only the SDK makes these events, so new fields can be added later without breaking callers.

    /** Event name: `emrtd.chip.step`. */
    override val name: String get() = EVENT_NAME

    /** The fields; no document data. */
    override fun toString(): String =
        "ChipReadEvent($step, $outcome, protocol=$protocol, fileId=${fileId?.toString(16)}, byteCount=$byteCount, " +
            "statusWord=${statusWord?.toString(16)}, durationMillis=$durationMillis, systemCode=$systemCode, renewal=$renewal)"

    /** Constants for [ChipReadEvent]. */
    public companion object {
        /** The event name: `emrtd.chip.step`. */
        public const val EVENT_NAME: String = "emrtd.chip.step"

        /**
         * An event for a step that a platform module times itself, such as [ChipReadStep.SHEET_TEXT]
         * on iPhone: only the step, how it ended and how long it took.
         *
         * Internal: see [InternalTesseraChipApi].
         *
         * @param step Which step this is.
         * @param outcome How the step ended.
         * @param durationMillis How long the step took, in milliseconds.
         * @return The event, with no protocol, file, byte count or status word.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun platformStep(
            step: ChipReadStep,
            outcome: ChipReadStepOutcome,
            durationMillis: Long,
        ): ChipReadEvent = ChipReadEvent(step, outcome, null, null, null, null, durationMillis)

        /**
         * A [ChipReadStep.SESSION_END] event: the platform ended its reader session on its own,
         * with [systemCode], [durationMillis] after the session began. The outcome is
         * [ChipReadStepOutcome.FAILED]: the session ended without the read asking for it.
         *
         * Internal: see [InternalTesseraChipApi].
         *
         * @param systemCode The platform's raw code, or `null` when it gave none of its own kind.
         * @param durationMillis The time from the session's start to its end, in milliseconds.
         * @return The event, with no protocol, file, byte count or status word.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun sessionEnd(
            systemCode: Int?,
            durationMillis: Long,
        ): ChipReadEvent =
            ChipReadEvent(ChipReadStep.SESSION_END, ChipReadStepOutcome.FAILED, null, null, null, null, durationMillis, systemCode)

        /**
         * A [ChipReadStep.SESSION_RENEW] event: the read opened a new reader session, or could not.
         *
         * Internal: see [InternalTesseraChipApi].
         *
         * @param renewal Which new session of the read this is, from 1.
         * @param systemCode The platform's code of the session end that led to it (202 on iPhone),
         *   or `null` when the read ended the old session itself because no document was reported in
         *   time.
         * @param outcome [ChipReadStepOutcome.SUCCEEDED] when the new session began,
         *   [ChipReadStepOutcome.FAILED] when the system kept refusing it.
         * @param durationMillis The time from the decision to renew to the new session's begin, or
         *   to the giving up, in milliseconds.
         * @return The event, with no protocol, file, byte count or status word.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun sessionRenew(
            renewal: Int,
            systemCode: Int?,
            outcome: ChipReadStepOutcome,
            durationMillis: Long,
        ): ChipReadEvent = ChipReadEvent(ChipReadStep.SESSION_RENEW, outcome, null, null, null, null, durationMillis, systemCode, renewal)

        /**
         * A [ChipReadStep.TAG_HANDOVER] event: the read stopped using the tag between two commands
         * and asked the system for a fresh connection to it, [tagMillis] after the system reported
         * the tag. The outcome is [ChipReadStepOutcome.SUCCEEDED]: the handover was made.
         *
         * Internal: see [InternalTesseraChipApi].
         *
         * @param tagMillis How long the connection to the tag had lasted, in milliseconds.
         * @return The event, with no protocol, file, byte count or status word.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun tagHandover(tagMillis: Long): ChipReadEvent =
            ChipReadEvent(ChipReadStep.TAG_HANDOVER, ChipReadStepOutcome.SUCCEEDED, null, null, null, null, tagMillis)
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

    /**
     * Setting the text of the system NFC sheet on iPhone. Reported only when one setting took
     * longer than 500 milliseconds: [ChipReadStepOutcome.SUCCEEDED] when it returned, with the time
     * it took, or [ChipReadStepOutcome.FAILED] when it had not returned a second after the read's
     * ending, with the time so far. Each slow setting is reported once; the event never holds the
     * text.
     *
     * @since 1.0.0
     */
    SHEET_TEXT,

    /**
     * The system ended the NFC reader session on iPhone on its own: not at the read's request.
     * Reported once per session, with [ChipReadStepOutcome.FAILED], the time since the session
     * began, and Core NFC's raw `NFCReaderError` code in [ChipReadEvent.systemCode] (`null` when
     * the error was not of Core NFC's domain). The SDK reports the number and does not interpret
     * it. Codes from Core NFC's header (`NFCError.h`): 200 the person tapped Cancel, 201 the
     * session's 60-second limit passed, 202 the session ended unexpectedly, 203 the system is
     * busy, 1 NFC not supported, 2 the entitlement or Info.plist entries are missing, 6 the radio is
     * off, 7 the system setting or hardware is not eligible, 8 the person has not answered the
     * request to use the reader. On Android there is no such event.
     *
     * @since 1.0.0
     */
    SESSION_END,

    /**
     * On iPhone: the read closed its NFC reader session before any document was reported and
     * opened a new one ([ChipReadState.RenewingSession]). Reported once per new session, with
     * [ChipReadEvent.renewal] (which new session, from 1), the reason in
     * [ChipReadEvent.systemCode] (202 when the system ended the old session with that code, `null`
     * when the read ended it because no document was reported in time), and the time from the
     * decision to the new session's begin. [ChipReadStepOutcome.SUCCEEDED] when the new session
     * began, [ChipReadStepOutcome.FAILED] when the system kept refusing it as busy and the read
     * ended. On Android there is no such event.
     *
     * @since 1.0.0
     */
    SESSION_RENEW,

    /**
     * On iPhone: the read stopped using the tag between two commands and asked the system for a
     * fresh connection to the same document, before the system's limit on one tag connection
     * (see `CoreNfcReadSettings.tagTimeLimitMillis`). Reported once per handover, with
     * [ChipReadStepOutcome.SUCCEEDED] and, in [ChipReadEvent.durationMillis], how long the
     * connection to the tag had lasted. The read goes on from where it was, with no restart used.
     * On Android there is no such event.
     *
     * @since 1.0.0
     */
    TAG_HANDOVER,
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
