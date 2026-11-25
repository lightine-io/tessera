package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol

/**
 * Receives a chip read's updates and its one ending.
 *
 * Calls never overlap and come in order: every [onUpdate], then exactly one [onEnding], then
 * nothing more. Which thread a call comes on is the read's setting: the platform read entry points
 * hand every call to the main thread by default ([ObserverThread.MAIN], in the order the SDK made
 * them, none before `start` returned), so an observer can update a screen directly. With
 * [ObserverThread.CALLER] most calls come from the read's own thread, but the first update and the
 * updates and ending that a start or a cancel causes may come on the thread that called it. Return
 * fast in either case.
 *
 * Updates and endings never carry document data: no file content, no key, no value read from the
 * document. An exception thrown by the observer is ignored and never changes the read.
 *
 * The platform read entry points call both. The low-level [ChipReader.read] takes only a
 * [ChipReadUpdateListener], the update half, because its result is the ending; an observer is one.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface ChipReadObserver : ChipReadUpdateListener {
    /**
     * A change of state or progress.
     *
     * @param update Where the read is, how far it got, and which attempt it is.
     */
    override fun onUpdate(update: ChipReadUpdate)

    /**
     * How the read ended. Called exactly once per read, after the last [onUpdate].
     *
     * @param ending The ending, with the read's result when there is one.
     */
    public fun onEnding(ending: ChipReadEnding)
}

/**
 * Receives a chip read's updates, with no ending: what the low-level [ChipReader.read] calls, where
 * the result it returns is the ending. A [ChipReadObserver] is one too.
 *
 * Calls come on the calling coroutine, one at a time and in order. An exception thrown here is
 * ignored and never changes the read.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public fun interface ChipReadUpdateListener {
    /**
     * A change of state or progress.
     *
     * @param update Where the read is, how far it got; always attempt 1 of 1 from [ChipReader.read].
     */
    public fun onUpdate(update: ChipReadUpdate)
}

/**
 * One update of a chip read: its state, its progress, and which attempt it is.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property state Where the read is.
 * @property progress How far the read got.
 * @property attempt Which attempt the update belongs to, from 1. A read starts again after a lost
 *   link only when its [LinkLossPolicy] allows it; each new start is a new attempt.
 *   [ChipReadState.LinkLost] belongs to the attempt that lost the link, each time it is reported;
 *   every update after it belongs to the attempt that starts when the document is back. So
 *   `attempt + 1` is the "try 2 of 3" to show with [ChipReadState.LinkLost].
 * @property maxAttempts The most attempts this read can make: 1, plus the restarts the app allowed.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReadUpdate internal constructor(
    public val state: ChipReadState,
    public val progress: ChipReadProgress,
    public val attempt: Int,
    public val maxAttempts: Int,
) {
    /** The state, the progress and the attempt; never document data. */
    override fun toString(): String = "ChipReadUpdate($state, $progress, attempt $attempt of $maxAttempts)"
}

/**
 * Where a chip read is.
 *
 * A read goes through these states in order: [Starting], [WaitingForDocument], [DocumentFound],
 * [Connecting], [OpeningAccess], then [ReadingFile] and [AuthenticatingChip] in the order the chip
 * needs, then [CheckingFiles]. [NoAnswerYet] can come after [OpeningAccess] while the chip has not
 * answered yet. [LinkLost] and [Stopping] can come at any point after the read starts. Each state
 * holds only identifiers and counts, never document data.
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
public sealed class ChipReadState {
    /**
     * The read is starting: the SDK checks that NFC can be used and, on some platforms, waits for
     * the previous read to let go of the reader.
     *
     * @since 1.0.0
     */
    public data object Starting : ChipReadState()

    /**
     * The reader is on and waits for a document. A good moment to ask the person to hold the
     * document against the phone.
     *
     * The update repeats every few seconds while nothing is found, with a larger [waitedMillis],
     * so the app can change its hint after a while, for example to "move the phone slowly along
     * the document".
     *
     * @property waitedMillis How long the reader has waited so far, in milliseconds.
     * @since 1.0.0
     */
    public class WaitingForDocument internal constructor(
        public val waitedMillis: Long,
    ) : ChipReadState() {
        /** `WaitingForDocument(` the time waited `)`. */
        override fun toString(): String = "WaitingForDocument(${waitedMillis}ms)"
    }

    /**
     * A document is found, and the read has not sent it anything yet. The moment to say "found,
     * hold still": the platform waits a short moment here before the first command, so the
     * document can settle.
     *
     * @since 1.0.0
     */
    public data object DocumentFound : ChipReadState()

    /**
     * The link to the document's chip is being set up.
     *
     * @since 1.0.0
     */
    public data object Connecting : ChipReadState()

    /**
     * The reader is opening the chip with the access key: it reads EF.CardAccess, then runs PACE
     * or BAC.
     *
     * @property protocol The access protocol, once chosen; `null` while the reader still reads
     *   which protocols the chip offers.
     * @since 1.0.0
     */
    public class OpeningAccess internal constructor(
        public val protocol: ChipAccessProtocol?,
    ) : ChipReadState() {
        /** `OpeningAccess(` the protocol `)`. */
        override fun toString(): String = "OpeningAccess($protocol)"
    }

    /**
     * The reader is reading a file. The update repeats for each part of the file the chip sends.
     *
     * @property file Which file, by its identifiers.
     * @property bytesRead How many of its bytes have arrived.
     * @property bytesTotal The file's length in bytes, known once its first part arrived (the
     *   length the file states in its first bytes); `null` before that.
     * @since 1.0.0
     */
    public class ReadingFile internal constructor(
        public val file: ChipFileRef,
        public val bytesRead: Int,
        public val bytesTotal: Int?,
    ) : ChipReadState() {
        /** The file and the counts, never content. */
        override fun toString(): String = "ReadingFile($file, $bytesRead of ${bytesTotal ?: "?"} bytes)"
    }

    /**
     * The reader runs a check with the chip's own key: chip authentication or active
     * authentication.
     *
     * @property method Which one.
     * @since 1.0.0
     */
    public class AuthenticatingChip internal constructor(
        public val method: ChipAuthMethod,
    ) : ChipReadState() {
        /** `AuthenticatingChip(` the method `)`. */
        override fun toString(): String = "AuthenticatingChip($method)"
    }

    /**
     * Every file is read; the SDK runs the passive-authentication checks on the phone. The chip
     * is no longer needed.
     *
     * @since 1.0.0
     */
    public data object CheckingFiles : ChipReadState()

    /**
     * The link to the chip was lost before an answer: usually the document moved away. The moment
     * to say "connection lost, hold the document again".
     *
     * The read goes on by itself when the document is found again: the next state is
     * [DocumentFound], and the read starts again from the beginning as a new attempt. This state
     * lasts until then, or until the read ends.
     *
     * @property restartsLeft How many more times the read can start again after a lost link,
     *   counting the start it is now waiting for (at least 1).
     * @since 1.0.0
     */
    public class LinkLost internal constructor(
        public val restartsLeft: Int,
    ) : ChipReadState() {
        /** `LinkLost(` the restarts left `)`. */
        override fun toString(): String = "LinkLost(restartsLeft=$restartsLeft)"
    }

    /**
     * A document is found, but its chip has not answered the read's first commands: usually it is
     * found but sits where it gets too little power to answer. The moment to say "found, move the
     * document a little", not "hold it again": the document is still there.
     *
     * The first commands are the ones before the key is used: selecting and reading files, asking
     * the chip for a random number (BAC's GET CHALLENGE) and choosing the protocol and password
     * (PACE's MSE:Set AT). Nothing was asked of the chip yet about the key, so these tries cost
     * nothing: they never use one of [LinkLossPolicy]'s restarts and never count as a new attempt.
     * A chip can answer some of these commands and then go silent; that is still this state, and
     * [tries] keeps counting. The read keeps trying, about every 2 seconds, while the person moves
     * the document, for a time the platform sets (on Android `NfcReadSettings.waitForReturnMillis`,
     * counted from the first unanswered try; on iPhone the system session's time). When the chip
     * answers all of them, the read goes on and the next state is [OpeningAccess] with the
     * protocol. When the time passes, the read ends as a document that never answered:
     * [ChipReadEnding.Failed] with [io.lightine.tessera.types.errors.NfcNoAnswer] (when an earlier
     * try of the read got past these commands, with the lost link instead). This state lasts until
     * then: the found,
     * connecting and opening steps of each new try are not reported again.
     *
     * @property tries How many tries in a row got no answer so far, from 1.
     * @since 1.0.0
     */
    public class NoAnswerYet internal constructor(
        public val tries: Int,
    ) : ChipReadState() {
        /** `NoAnswerYet(` the tries `)`. */
        override fun toString(): String = "NoAnswerYet(tries=$tries)"
    }

    /**
     * The app cancelled the read. The link is being released; the ending follows at once.
     *
     * @since 1.0.0
     */
    public data object Stopping : ChipReadState()
}

/**
 * A file on the chip, by its identifiers only.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fileId The two-byte file identifier, for example `0x0102` for DG2.
 * @property inMasterFile `true` for a file of the master file (EF.CardAccess, EF.CardSecurity,
 *   EF.ATR/INFO), `false` for a file of the eMRTD application.
 * @property dataGroup The data group number (1 to 16) for a data group of the application, else
 *   `null`.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipFileRef internal constructor(
    public val fileId: Int,
    public val inMasterFile: Boolean,
) {
    public val dataGroup: Int?
        get() = if (!inMasterFile && fileId in 0x0101..0x0110) fileId - 0x0100 else null

    /** Equal when the identifier and the place are equal. */
    override fun equals(other: Any?): Boolean = other is ChipFileRef && other.fileId == fileId && other.inMasterFile == inMasterFile

    /** A hash of the identifier and the place. */
    override fun hashCode(): Int = fileId * 2 + if (inMasterFile) 1 else 0

    /** The identifier in hexadecimal, and the data group when it is one. */
    override fun toString(): String {
        val id = fileId.toString(16).uppercase().padStart(4, '0')
        val where = if (inMasterFile) "master file" else dataGroup?.let { "DG$it" } ?: "application"
        return "ChipFileRef($id, $where)"
    }
}

/**
 * A check with the chip's own key (ICAO Doc 9303 Part 11, sections 6.1 and 6.2).
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
public enum class ChipAuthMethod {
    /** Chip authentication: a key agreement with the chip's key, then new session keys. */
    CHIP_AUTHENTICATION,

    /** Active authentication: the chip signs a challenge with its key. */
    ACTIVE_AUTHENTICATION,
}

/**
 * How far a chip read got: an estimate of the work done that never goes back, and exact counts.
 *
 * [fraction] is an estimate, not a measure: the SDK weighs each step by its usual time, and
 * corrects the estimate once each file's length is known. It only moves forward: when an estimate
 * was too small the bar slows down, when it was too large the bar jumps ahead. After a lost link
 * and a new start it keeps its value, pauses, and fills again.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fraction The share of the work done, from 0.0 to 1.0. It never goes back, and it is
 *   1.0 only when the read ended with [ChipReadEnding.Complete].
 * @property percent [fraction] in whole percent, rounded down: at most 99 until the read is
 *   complete, then 100.
 * @property filesDone How many application files this attempt has finished, read or not.
 * @property filesPlanned How many application files this attempt plans to read, known once EF.COM
 *   is read; `null` before that.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReadProgress internal constructor(
    public val fraction: Double,
    public val percent: Int,
    public val filesDone: Int,
    public val filesPlanned: Int?,
) {
    /** The percent and the file counts. */
    override fun toString(): String = "ChipReadProgress($percent%, files $filesDone of ${filesPlanned ?: "?"})"
}
