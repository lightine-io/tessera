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
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
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
     * The platform's own code for the failure that ended the read, as the platform reported it,
     * when it gave one: on iPhone, Core NFC's `NFCReaderError` code of the session's end or of
     * the failed connect that led to this ending (for example 2, 7 or 8 for [NotStarted] with
     * `NOT_PERMITTED`, `NOT_ELIGIBLE` or `ACCESS_NOT_ANSWERED`). `null` when the ending did not
     * come from a code of the platform, and always on Android, whose NFC calls report no codes.
     * A fact for diagnosis: the typed reason is what the app acts on.
     *
     * @since 1.0.0
     */
    public open val systemCode: Int?
        get() = null

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
        override val systemCode: Int? = null,
    ) : ChipReadEnding() {
        /** `Partial(...)` with counts, the error and the state. */
        override fun toString(): String = "Partial($read, stoppedBy=$stoppedBy, stoppedIn=$stoppedIn, attempts=$attempts)"
    }

    /**
     * No access was granted in any attempt, or the chip is blank, or the read could not go on
     * for a reason that is not about the key. On Android this is also the ending when the app's
     * own wait for a document (`NfcReadSettings.waitForDocumentMillis`) passed with none found:
     * [error] is a [io.lightine.tessera.types.errors.NfcTimeout] with limit `WAIT_FOR_DOCUMENT` and
     * [attempts] is 0. The app set that limit, so it is not [EndedBySystem].
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
        override val systemCode: Int? = null,
    ) : ChipReadEnding() {
        /** `Failed(` the error `)`. */
        override fun toString(): String = "Failed($error, attempts=$attempts)"
    }

    /**
     * The chip refused the access protocol with this key: it refused the reader's proof of the
     * key ([ChipAccessDenied.Reason.KEY_NOT_MATCHED]), reported the key as blocked, suspended,
     * deactivated or limited, or refused a step of the protocol with a status word that does not
     * say why ([ChipAccessDenied.Reason.CHIP_REFUSED]). A [ChipAccessDenied.Reason.CHIP_REFUSED]
     * is not always about the key: some chips answer a wrong key that way, and a chip also
     * refuses a protocol step it does not support (for example a PACE setting); [error] keeps the
     * chip's status word, and the SDK does not guess which. The read was not tried again with
     * this key.
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
     * The key was refused before anything was sent to the chip: no reader was turned on, no system
     * sheet was shown, and no chip attempt was used. Two causes:
     * - The MRZ key's check digits do not match their fields: [error] is a
     *   [ChipKeyCheckDigitMismatch]. See [ChipReadOptions.allowCheckDigitMismatch].
     * - The key was already used: an earlier read cleared it at its ending, or the app cleared it.
     *   [error] is a [ChipAccessDenied] with [ChipAccessDenied.Reason.KEY_CLEARED]. A new read
     *   needs a new key, built from the document's details again.
     *
     * @property error Why: which fields, never their values, or the cleared key.
     * @since 1.0.0
     */
    public class KeyRejected internal constructor(
        public val error: ChipError,
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
         * May gain members in a later release: an exhaustive `when` over it then stops compiling until
         * the new member is handled. An `else` branch keeps it compiling without handling the new member:
         * that is your choice, and today the compiler warns that such an `else` is redundant.
         *
         * @since 1.0.0
         */
        public enum class By {
            /**
             * The person, with the Cancel button of the system's NFC sheet on iPhone. Android
             * never reports it: Android shows no system sheet with a Cancel button for a read, so
             * only the app can end an Android read early (its own button calls the read's
             * `cancel()`, which is [APP]).
             */
            PERSON,

            /** The app, through the read's handle. */
            APP,
        }
    }

    /**
     * The system ended the read, or the platform closed its link while the app had not cancelled.
     * What leads here differs by platform:
     * - iPhone: the system NFC session ended on its own: its time limit passed, the app went to
     *   the background, or the system ended it for another reason; or NFC became unavailable
     *   after a document was found.
     * - Android: NFC, or its option for reading tags, was turned off during the read
     *   ([NfcUnavailable.Reason.DISABLED], [NfcUnavailable.Reason.TAG_READING_DISABLED]); or the
     *   read's Activity was destroyed ([NfcUnavailable.Reason.ACTIVITY_DESTROYED], the
     *   Activity-owning start only). The app's own wait for a document is not the system's: it
     *   ends as [Failed]. An Activity that only pauses (the app goes to the background, a
     *   dialog, the screen locking) does not end the read: the link drops as when the document
     *   moves, and the read waits for the document's return.
     * - Both: a link the platform closed under the read, with no cause it reports, ends here with
     *   [NfcUnavailable.Reason.CLOSED]; never as [Cancelled] by the app.
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
        override val systemCode: Int? = null,
    ) : ChipReadEnding() {
        /** `EndedBySystem(` the reason `)`. */
        override fun toString(): String = "EndedBySystem($reason, attempts=$attempts)"
    }

    /**
     * The read could not start: NFC is missing, off, not permitted, not eligible, not yet
     * answered by the person, busy, or another read is running. See [systemCode] for the
     * platform's own code, when it gave one.
     *
     * @property reason Why.
     * @since 1.0.0
     */
    public class NotStarted internal constructor(
        public val reason: NfcUnavailable.Reason,
        override val progress: ChipReadProgress,
        override val systemCode: Int? = null,
    ) : ChipReadEnding() {
        /** Always 0: nothing was sent. */
        override val attempts: Int get() = 0

        /** `NotStarted(` the reason `)`. */
        override fun toString(): String = "NotStarted($reason)"
    }
}
