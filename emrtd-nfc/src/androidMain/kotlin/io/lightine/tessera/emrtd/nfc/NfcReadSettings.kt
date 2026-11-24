package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTimeout
import kotlin.jvm.JvmOverloads

/**
 * How an [NfcChipRead] behaves around the radio. Every value has a default that suits an ordinary
 * app: create it with no arguments, or do not pass one at all.
 *
 * Times are in milliseconds. [linkLoss], [settleDelayMillis] and [commandTimeoutMillis] mean the
 * same, with the same defaults, as in the iPhone read's settings (`CoreNfcReadSettings`). The two
 * waits are Android's own: on iPhone the system session's 60-second limit bounds every wait.
 *
 * Kotlin callers use the constructor with named arguments; Java callers can use [Builder].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property linkLoss What the read does when the link to the chip is lost, usually because the
 *   document moved: the default, [LinkLossPolicy.Resume] with 2 restarts, waits for the document
 *   to come back and goes on from where it stopped, at most twice. Each new start opens the chip
 *   again with the same key, so a read can make up to 3 authentication attempts with that key,
 *   one per start. It never starts again after the chip answered with a refusal of the key: that
 *   ends the read at once. The MRZ and the card access number are not blocked by tries, so the
 *   worst a chip may do is answer more slowly. Use [LinkLossPolicy.End] for one attempt only.
 * @property settleDelayMillis How long the read waits after a document is found before the first
 *   command, so the hand can settle: frames are often lost in the first moment after detection.
 *   Default 400. Cancelling the read cuts it short.
 * @property commandTimeoutMillis The longest wait for the chip's answer to one command, set on the
 *   link as the platform's ISO-DEP time limit. When it passes, the platform reports the tag as lost
 *   (it cannot tell a slow chip from a document that left), so [linkLoss] decides what follows.
 *   Default 5000: ICAO Doc 9303 Part 10, appendix B.11, lets a chip take up to 5 seconds per block.
 * @property waitForReturnMillis How long the read waits for the document to come back after a lost
 *   link, counted from the loss, before it ends with [ChipReadEnding.Partial] or
 *   [ChipReadEnding.Failed]. Default 15000.
 * @property waitForDocumentMillis How long the read may take to find a document and set up the
 *   first link to it, counted from the start, across tags whose link could not be set up; `null`,
 *   the default, waits without a limit. When it passes, the read ends with
 *   [ChipReadEnding.EndedBySystem] and [NfcTimeout]. Once a first link is up it no longer applies:
 *   [waitForReturnMillis] bounds the waits after a lost link.
 * @throws IllegalArgumentException when [commandTimeoutMillis] is not positive or above
 *   [Int.MAX_VALUE], or a wait or a delay is negative.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NfcReadSettings
    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    constructor(
        public val linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS),
        public val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
        public val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        public val waitForReturnMillis: Long = DEFAULT_WAIT_FOR_RETURN_MILLIS,
        public val waitForDocumentMillis: Long? = null,
    ) {
        init {
            require(settleDelayMillis >= 0) { "settleDelayMillis must not be negative, was $settleDelayMillis" }
            require(commandTimeoutMillis in 1..Int.MAX_VALUE) {
                "commandTimeoutMillis must be positive and fit an Int, was $commandTimeoutMillis"
            }
            require(waitForReturnMillis >= 0) { "waitForReturnMillis must not be negative, was $waitForReturnMillis" }
            require(waitForDocumentMillis == null || waitForDocumentMillis >= 0) {
                "waitForDocumentMillis must not be negative when set, was $waitForDocumentMillis"
            }
        }

        /** The values, never document data. */
        override fun toString(): String =
            "NfcReadSettings(linkLoss=$linkLoss, settleDelayMillis=$settleDelayMillis, " +
                "commandTimeoutMillis=$commandTimeoutMillis, waitForReturnMillis=$waitForReturnMillis, " +
                "waitForDocumentMillis=$waitForDocumentMillis)"

        /**
         * Builds [NfcReadSettings] step by step, with the constructor's defaults.
         *
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder {
            private var linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS)
            private var settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS
            private var commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS
            private var waitForReturnMillis: Long = DEFAULT_WAIT_FOR_RETURN_MILLIS
            private var waitForDocumentMillis: Long? = null

            /** What the read does when the link to the chip is lost. */
            public fun linkLoss(policy: LinkLossPolicy): Builder = apply { linkLoss = policy }

            /** How long to wait after a document is found before the first command. */
            public fun settleDelayMillis(millis: Long): Builder = apply { settleDelayMillis = millis }

            /** The longest wait for the chip's answer to one command. */
            public fun commandTimeoutMillis(millis: Long): Builder = apply { commandTimeoutMillis = millis }

            /** How long to wait for the document to come back after a lost link. */
            public fun waitForReturnMillis(millis: Long): Builder = apply { waitForReturnMillis = millis }

            /** How long the read may take to find a document and link to it; `null` waits without a limit. */
            public fun waitForDocumentMillis(millis: Long?): Builder = apply { waitForDocumentMillis = millis }

            /**
             * The settings.
             *
             * @throws IllegalArgumentException as the [NfcReadSettings] constructor does.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): NfcReadSettings =
                NfcReadSettings(linkLoss, settleDelayMillis, commandTimeoutMillis, waitForReturnMillis, waitForDocumentMillis)
        }

        /** The default values. */
        public companion object {
            /** The restarts of the default [linkLoss]: 2. */
            public const val DEFAULT_RESTARTS: Int = 2

            /** The default [settleDelayMillis]: 400. */
            public const val DEFAULT_SETTLE_DELAY_MILLIS: Long = 400

            /** The default [commandTimeoutMillis]: 5000, the same as [IsoDepTransport.DEFAULT_TIMEOUT_MILLIS]. */
            public const val DEFAULT_COMMAND_TIMEOUT_MILLIS: Long = IsoDepTransport.DEFAULT_TIMEOUT_MILLIS.toLong()

            /** The default [waitForReturnMillis]: 15000. */
            public const val DEFAULT_WAIT_FOR_RETURN_MILLIS: Long = 15_000
        }
    }
