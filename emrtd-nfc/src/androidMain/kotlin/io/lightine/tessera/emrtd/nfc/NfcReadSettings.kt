package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTimeout
import kotlin.jvm.JvmOverloads

/**
 * What one Android chip read may do beyond [ChipReadOptions][io.lightine.tessera.emrtd.ChipReadOptions]:
 * the link-loss policy the read follows, and the platform's timings.
 *
 * Kotlin callers use the constructor with named arguments; Java callers can use [Builder].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property linkLoss What the read does when the link to the chip is lost. Whatever the policy,
 *   the read starts again only after a loss of the link itself, never after the chip refused the
 *   key, and every new start runs the access protocol again with the same key: the MRZ and
 *   card-access-number keys are not blocked by tries, so with the default, [LinkLossPolicy.Resume]
 *   with 2 restarts, a read makes at most three access attempts, all with a key no chip has
 *   refused. A chip that refuses the key on a later attempt ends the read with
 *   [ChipReadEnding.KeyNotAccepted] and nothing from earlier attempts: another document may have
 *   been placed on the phone, and the SDK cannot tell. See [LinkLossPolicy] for what each policy
 *   keeps of the work already read.
 * @property timeoutMillis How long to wait for the chip's answer to one command, in milliseconds:
 *   the same limit [IsoDepTransport] sets on the link. The default, 5 seconds, allows for the slow
 *   commands a chip may run.
 * @property settleDelayMillis How long the read waits after a document is found before it
 *   connects, in milliseconds. Frames are often lost in the first moment after the tag is found,
 *   while the hand that holds the document settles; the default, 400, waits that out.
 * @property waitForReturnMillis How long the read waits for the document to come back after a
 *   lost link, in milliseconds, before it ends; the default is 15 seconds.
 * @property waitForDocumentMillis How long the first wait for a document may last, in
 *   milliseconds; `null`, the default, waits without a limit. When it passes and no document was
 *   found, the read ends as a system-style timeout, with [ChipReadEnding.EndedBySystem] and
 *   [NfcTimeout]. Later waits are not bounded by it.
 * @throws IllegalArgumentException when [timeoutMillis] is not positive, or a delay is negative.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NfcReadSettings
    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    constructor(
        public val linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(2),
        public val timeoutMillis: Int = IsoDepTransport.DEFAULT_TIMEOUT_MILLIS,
        public val settleDelayMillis: Long = 400,
        public val waitForReturnMillis: Long = 15_000,
        public val waitForDocumentMillis: Long? = null,
    ) {
        init {
            require(timeoutMillis > 0) { "timeoutMillis must be positive, was $timeoutMillis" }
            require(settleDelayMillis >= 0) { "settleDelayMillis must not be negative, was $settleDelayMillis" }
            require(waitForReturnMillis >= 0) { "waitForReturnMillis must not be negative, was $waitForReturnMillis" }
            require(waitForDocumentMillis == null || waitForDocumentMillis >= 0) {
                "waitForDocumentMillis must not be negative when set, was $waitForDocumentMillis"
            }
        }

        /** The policy and the timings; nothing here is document data. */
        override fun toString(): String =
            "NfcReadSettings(linkLoss=$linkLoss, timeoutMillis=$timeoutMillis, settleDelayMillis=$settleDelayMillis, " +
                "waitForReturnMillis=$waitForReturnMillis, waitForDocumentMillis=$waitForDocumentMillis)"

        /**
         * Builds [NfcReadSettings] step by step, with the constructor's defaults.
         *
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder {
            private var linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(2)
            private var timeoutMillis: Int = IsoDepTransport.DEFAULT_TIMEOUT_MILLIS
            private var settleDelayMillis: Long = 400
            private var waitForReturnMillis: Long = 15_000
            private var waitForDocumentMillis: Long? = null

            /** What the read does when the link to the chip is lost. */
            public fun linkLoss(policy: LinkLossPolicy): Builder = apply { linkLoss = policy }

            /** How long to wait for the chip's answer to one command. */
            public fun timeoutMillis(millis: Int): Builder = apply { timeoutMillis = millis }

            /** How long to wait after a document is found before the read connects. */
            public fun settleDelayMillis(millis: Long): Builder = apply { settleDelayMillis = millis }

            /** How long to wait for the document to come back after a lost link. */
            public fun waitForReturnMillis(millis: Long): Builder = apply { waitForReturnMillis = millis }

            /** How long the first wait for a document may last; `null` waits without a limit. */
            public fun waitForDocumentMillis(millis: Long?): Builder = apply { waitForDocumentMillis = millis }

            /**
             * The settings.
             *
             * @throws IllegalArgumentException when the timeout is not positive, or a delay is
             *   negative.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): NfcReadSettings =
                NfcReadSettings(linkLoss, timeoutMillis, settleDelayMillis, waitForReturnMillis, waitForDocumentMillis)
        }
    }
