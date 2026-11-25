package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * How a [CoreNfcChipRead] behaves around the radio. Every value has a default that suits an
 * ordinary app: create it with no arguments, or do not pass one at all.
 *
 * Every time is a `Long` in milliseconds, as in the Android read's settings (`NfcReadSettings`),
 * where [linkLoss], [settleDelayMillis], [commandTimeoutMillis] and [observerOn] mean the same,
 * with the same defaults. There is no wait-for-document limit as on Android
 * (`NfcReadSettings.waitForDocumentMillis`): the system session's own 60-second limit bounds the
 * wait for a document, and the system ends the read when it passes
 * ([io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem]); cancel the read for a shorter limit.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift, `CoreNfcReadSettings()` gives the
 * defaults; the full initializer takes every value. To change one value without naming the others,
 * use [Builder]: `CoreNfcReadSettings.Builder().settleDelayMillis(millis: 0).build()`, or
 * [toBuilder] on an existing object.
 *
 * @property linkLoss What the read does when the link to the chip is lost, usually because the
 *   document moved: the default, [LinkLossPolicy.Resume] with 2 restarts, waits for the document
 *   to come back in the same system session and goes on from where it stopped, at most twice.
 *   Each new start opens the chip again with the same key, so a read can make up to 3
 *   authentication attempts with that key, one per start. It never starts again after the chip
 *   answered with a refusal of the key: that ends the read at once. The MRZ and the card access
 *   number are not blocked by tries, so the worst a chip may do is answer more slowly. Use
 *   [LinkLossPolicy.End] for one attempt only.
 * @property settleDelayMillis How long the read waits after the system finds a document before the
 *   first command, so the hand can settle: frames are often lost in the first second after
 *   detection. Default 400. Cancelling the read or the end of the system session cuts it short.
 * @property commandTimeoutMillis The longest wait for the chip's answer to one command. When it
 *   passes, the command counts as a lost link: nothing more is sent on that link, a late answer is
 *   dropped, and [linkLoss] decides what follows. Default 5000: ICAO Doc 9303 Part 10, appendix
 *   B.11, lets a chip take up to 5 seconds per block, and Core NFC itself may wait far longer.
 *   The SDK's own command time limit counts as a lost link, so the chip may still answer the
 *   command after the limit, and that late answer is dropped unread: when the late answer was a
 *   refusal of the key, the read does not know it, and [linkLoss] may start again with the same
 *   key, within its restarts.
 * @property waitAfterSystemEndMillis How long a new read waits before it begins a system session
 *   when the previous one was ended by the system (its time limit, the app leaving the screen) or
 *   refused because the reader was busy. Default 1000. A read ended by the app or the person
 *   needs no wait.
 * @property telemetry Where the read's step events go (step, protocol, status word, file id, byte
 *   count, time): the sink you give here. With `null`, the default, the sink registered in
 *   `TelemetrySinkRegistry` when the read starts, which is a sink that drops everything when none
 *   is registered. The events never hold document or chip data.
 * @property observerOn The thread the observer is called on: [ObserverThread.MAIN], the main
 *   queue, by default, so the updates reach a screen without a hop of your own; [ObserverThread.CALLER]
 *   for the read's own threads. Either way the calls come one at a time, in order, with the ending
 *   last, exactly once, and none comes before `start` returned.
 * @throws IllegalArgumentException when [commandTimeoutMillis] is not positive, or
 *   [settleDelayMillis] or [waitAfterSystemEndMillis] is negative.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcReadSettings
    @Throws(IllegalArgumentException::class)
    constructor(
        public val linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS),
        public val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
        public val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        public val waitAfterSystemEndMillis: Long = DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS,
        public val telemetry: TelemetrySink? = null,
        public val observerOn: ObserverThread = ObserverThread.MAIN,
    ) {
        /** The defaults: [LinkLossPolicy.Resume] with 2 restarts, 400, 5000 and 1000 milliseconds. */
        public constructor() : this(LinkLossPolicy.Resume(DEFAULT_RESTARTS))

        init {
            require(settleDelayMillis >= 0) { "settleDelayMillis must not be negative" }
            require(commandTimeoutMillis > 0) { "commandTimeoutMillis must be positive" }
            require(waitAfterSystemEndMillis >= 0) { "waitAfterSystemEndMillis must not be negative" }
        }

        /** The values, never document data. */
        override fun toString(): String =
            "CoreNfcReadSettings(linkLoss=$linkLoss, settleDelayMillis=$settleDelayMillis, " +
                "commandTimeoutMillis=$commandTimeoutMillis, waitAfterSystemEndMillis=$waitAfterSystemEndMillis, " +
                "observerOn=$observerOn)"

        /**
         * A builder that starts from these values, so one value can change without naming the
         * others.
         *
         * @since 1.0.0
         */
        public fun toBuilder(): Builder =
            Builder()
                .linkLoss(linkLoss)
                .settleDelayMillis(settleDelayMillis)
                .commandTimeoutMillis(commandTimeoutMillis)
                .waitAfterSystemEndMillis(waitAfterSystemEndMillis)
                .telemetry(telemetry)
                .observerOn(observerOn)

        /**
         * Builds [CoreNfcReadSettings] step by step, with the constructor's defaults.
         *
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder {
            private var linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS)
            private var settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS
            private var commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS
            private var waitAfterSystemEndMillis: Long = DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS
            private var telemetry: TelemetrySink? = null
            private var observerOn: ObserverThread = ObserverThread.MAIN

            /** What the read does when the link to the chip is lost. */
            public fun linkLoss(policy: LinkLossPolicy): Builder = apply { linkLoss = policy }

            /** How long to wait after a document is found before the first command. */
            public fun settleDelayMillis(millis: Long): Builder = apply { settleDelayMillis = millis }

            /** The longest wait for the chip's answer to one command. */
            public fun commandTimeoutMillis(millis: Long): Builder = apply { commandTimeoutMillis = millis }

            /** How long a new read waits after the system ended the previous session. */
            public fun waitAfterSystemEndMillis(millis: Long): Builder = apply { waitAfterSystemEndMillis = millis }

            /** Where the read's step events go; `null` uses the sink registered when the read starts. */
            public fun telemetry(sink: TelemetrySink?): Builder = apply { telemetry = sink }

            /** The thread the observer is called on. */
            public fun observerOn(thread: ObserverThread): Builder = apply { observerOn = thread }

            /**
             * The settings.
             *
             * @throws IllegalArgumentException as the [CoreNfcReadSettings] constructor does.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): CoreNfcReadSettings =
                CoreNfcReadSettings(linkLoss, settleDelayMillis, commandTimeoutMillis, waitAfterSystemEndMillis, telemetry, observerOn)
        }

        /** The default values. */
        public companion object {
            /** The restarts of the default [linkLoss]: 2. */
            public const val DEFAULT_RESTARTS: Int = 2

            /** The default [settleDelayMillis]: 400. */
            public const val DEFAULT_SETTLE_DELAY_MILLIS: Long = 400

            /** The default [commandTimeoutMillis]: 5000. */
            public const val DEFAULT_COMMAND_TIMEOUT_MILLIS: Long = 5_000

            /** The default [waitAfterSystemEndMillis]: 1000. */
            public const val DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS: Long = 1_000
        }
    }
