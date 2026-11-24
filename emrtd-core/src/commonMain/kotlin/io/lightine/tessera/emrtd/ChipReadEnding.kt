package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipKeyCheckDigitMismatch
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcUnavailable

/**
 * How a chip read through a platform entry point ended. Every read has exactly one ending.
 *
 * [Complete], [Partial] and [Failed] mirror [ChipReadResult]; the others come from the platform:
 * the person, the app or the system ended the read, or it could not start. No ending's
 * `toString()` carries document data.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipReadEnding {
    /**
     * How many attempts sent commands to a chip: 0 when none did, more than 1 after a lost link.
     * A read that ends while it waits for the document after a lost link counts only the attempts
     * that ran, so this can be one less than the [ChipReadUpdate.attempt] of the last updates,
     * which already name the attempt the read was getting ready for.
     */
    public abstract val attempts: Int

    /** The progress when the read ended: 1.0 only for [Complete]; the last value otherwise. */
    public abstract val progress: ChipReadProgress

    /**
     * Every step ran; [read] holds what the chip gave.
     *
     * @property read The files, the views and the checks.
     * @since 1.0.0
     */
    public class Complete internal constructor(
        public val read: ChipRead,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `Complete(...)` with counts only. */
        override fun toString(): String = "Complete($read, attempts=$attempts)"
    }

    /**
     * Access was granted, then the read stopped before every step ran.
     *
     * After lost links the read is the one from the latest attempt that was granted access.
     *
     * @property read What was read and checked before the stop.
     * @property stoppedBy Why it stopped.
     * @property stoppedIn The state the read was in when it stopped (the file and its byte counts,
     *   never content), or `null` when unknown.
     * @since 1.0.0
     */
    public class Partial internal constructor(
        public val read: ChipRead,
        public val stoppedBy: ChipError,
        public val stoppedIn: ChipReadState?,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `Partial(...)` with counts, the error and the state. */
        override fun toString(): String = "Partial($read, stoppedBy=$stoppedBy, stoppedIn=$stoppedIn, attempts=$attempts)"
    }

    /**
     * No access was granted in any attempt, or the chip is blank, or the read could not go on
     * for a reason that is not about the key.
     *
     * @property error Why.
     * @property access How far access got: `null` when no access protocol completed.
     * @since 1.0.0
     */
    public class Failed internal constructor(
        public val error: ChipError,
        public val access: AccessReport?,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `Failed(` the error `)`. */
        override fun toString(): String = "Failed($error, attempts=$attempts)"
    }

    /**
     * The chip answered the access protocol with a refusal of the key, or reported the key as
     * blocked, suspended or limited. The read was not tried again with this key.
     *
     * Usually the MRZ values or the card access number do not match the document. When
     * [attempts] is more than 1, the chip that refused may be a different document placed on the
     * phone after the link was lost; the SDK cannot tell, and nothing from the earlier attempts is
     * returned.
     *
     * @property error The refusal: the protocol, the reason and the chip's status word.
     * @since 1.0.0
     */
    public class KeyNotAccepted internal constructor(
        public val error: ChipAccessDenied,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `KeyNotAccepted(` the error `)`. */
        override fun toString(): String = "KeyNotAccepted($error, attempts=$attempts)"
    }

    /**
     * The MRZ key's check digits do not match their fields, so nothing was sent to the chip and no
     * chip attempt was used. See [ChipReadOptions.allowCheckDigitMismatch].
     *
     * @property error Which fields, never their values.
     * @since 1.0.0
     */
    public class KeyRejected internal constructor(
        public val error: ChipKeyCheckDigitMismatch,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `KeyRejected(` the fields `)`. */
        override fun toString(): String = "KeyRejected($error)"
    }

    /**
     * The person or the app ended the read.
     *
     * It carries no files. The ending is given at once, while the running attempt still holds
     * what it read and has not checked it against the chip's signed hashes; waiting for those
     * checks would delay the ending, and handing the files over unchecked would make them look
     * like a [ChipRead], which always comes with its checks. An app that wants what the chip gives
     * lets the read end on its own: a stop after access was granted ends with [Partial].
     *
     * @property by Who ended it.
     * @since 1.0.0
     */
    public class Cancelled internal constructor(
        public val by: By,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `Cancelled(` who `)`. */
        override fun toString(): String = "Cancelled(by=$by, attempts=$attempts)"

        /**
         * Who ended a read.
         *
         * May gain members in a later release: a `when` over it needs an `else` branch.
         *
         * @since 1.0.0
         */
        public enum class By {
            /** The person, for example with the Cancel button of the system's NFC sheet on iPhone. */
            PERSON,

            /** The app, through the read's handle. */
            APP,
        }
    }

    /**
     * The system ended the read: the NFC session timed out or was ended, NFC was turned off, or
     * the app left the screen.
     *
     * It carries no files, for the reason given at [Cancelled].
     *
     * @property reason What the platform reported.
     * @since 1.0.0
     */
    public class EndedBySystem internal constructor(
        public val reason: NfcError,
        override val attempts: Int,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** `EndedBySystem(` the reason `)`. */
        override fun toString(): String = "EndedBySystem($reason, attempts=$attempts)"
    }

    /**
     * The read could not start: NFC is missing, off, not permitted, busy, or another read is
     * running.
     *
     * @property reason Why.
     * @since 1.0.0
     */
    public class NotStarted internal constructor(
        public val reason: NfcUnavailable.Reason,
        override val progress: ChipReadProgress,
    ) : ChipReadEnding() {
        /** Always 0: nothing was sent. */
        override val attempts: Int get() = 0

        /** `NotStarted(` the reason `)`. */
        override fun toString(): String = "NotStarted($reason)"
    }
}
