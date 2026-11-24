package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * How a [CoreNfcChipRead] behaves around the radio. Every value has a default that suits an
 * ordinary app: create it with no arguments, or do not pass one at all.
 *
 * Times are in milliseconds.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift, `CoreNfcReadSettings()` gives the
 * defaults; the full initializer takes every value.
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
 * @property waitAfterSystemEndMillis How long a new read waits before it begins a system session
 *   when the previous one was ended by the system (its time limit, the app leaving the screen) or
 *   refused because the reader was busy. Default 1000. A read ended by the app or the person
 *   needs no wait.
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
                "commandTimeoutMillis=$commandTimeoutMillis, waitAfterSystemEndMillis=$waitAfterSystemEndMillis)"

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
