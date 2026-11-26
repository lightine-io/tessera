@file:OptIn(ExperimentalAtomicApi::class)

package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.internal.flow.FlowReporter
import io.lightine.tessera.emrtd.internal.flow.ProgressTracker
import io.lightine.tessera.emrtd.internal.flow.ResumeCarry
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipKeyCheckDigitMismatch
import io.lightine.tessera.types.errors.ChipUnexpectedAnswer
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcNoAnswer
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmSynthetic
import kotlin.time.TimeSource

/**
 * The platform-neutral part of one chip read through a platform entry point: the states, the one
 * ending, the attempts, the decision to start again after a lost link, and the progress. The
 * Android and iOS entry points feed it platform events and run each attempt on their transport;
 * everything they report to the app goes through it, so both platforms behave the same.
 *
 * Guarantees:
 * - Exactly one ending, through one atomic gate: the first cause wins and later ones do nothing
 *   (each ending method returns `false` then).
 * - No update after the ending; observer calls never overlap and keep their order, whichever
 *   thread calls in. An exception the observer throws is ignored.
 * - A new attempt only after a loss of the link itself ([NfcTagLost] or [NfcTimeout] from the
 *   transport), within [LinkLossPolicy.maxRestarts], and only when the platform allows it; never
 *   after an answered refusal, a secure-messaging error, a malformed file, or a person, app or
 *   system ending.
 * - At most one more try of access per read, inside [read] on the same link, after the first
 *   refusal of the key when no earlier try reached the command that uses the key and the key has
 *   no try counter and looks right (the rule is in [ChipReadEnding.KeyNotAccepted]). It uses no
 *   restart and no new attempt number; the second try starts with
 *   [ChipCommandTimeLimit.BEFORE_ACCESS] again.
 * - A loss of the link before the attempt sent the first command that uses the key (BAC's
 *   EXTERNAL AUTHENTICATE, PACE's first GENERAL AUTHENTICATE; the chip was asked nothing about the
 *   key) uses no restart and is no new attempt: the read reports [ChipReadState.NoAnswerYet] and
 *   the platform tries the same attempt again ([ChipReadAttemptOutcome.NO_ANSWER_YET]), within its
 *   own time bound.
 * - A read that ends while no try of it reached the first command that uses the key, because the
 *   last try was lost before it and no other try may follow, ends with [ChipReadEnding.Failed]
 *   and [NfcNoAnswer], on every platform alike.
 * - Each command gets the time limit of its kind ([ChipCommandTimeLimit]), and the platform is
 *   asked to change the limit only when the kind changes from one command to the next.
 *
 * The calls, in order, for a platform entry point:
 * 1. [start]; when it returns `false` the read has ended (the key was refused), so release nothing
 *    and stop. Then check NFC: [notStarted] when it cannot be used.
 * 2. [waiting] when the reader is on, and again every few seconds with the time waited.
 * 3. [documentFound] when a tag is found; after the settle time, [connecting]; when the link
 *    cannot be set up, [connectionFailed] and wait for the next tag.
 * 4. [read] on the connected transport. [ChipReadAttemptOutcome.ENDED]: release the link, done.
 *    [ChipReadAttemptOutcome.LINK_LOST]: release the link, wait for the document (restart polling),
 *    then go to step 3; when the platform gives up waiting, [documentNotReturned].
 *    [ChipReadAttemptOutcome.NO_ANSWER_YET]: the same, but try again soon, since the document is
 *    likely still there; when the platform's time bound for unanswered tries passes,
 *    [documentNotReturned]. [ChipReadAttemptOutcome.HANDED_OVER]: the platform stopped the
 *    attempt on purpose to get a fresh connection to the same document (its `handedOver` hook);
 *    release the link, ask the platform for the document again, then go to step 3; when it does
 *    not come back within the platform's short wait, [handoverFailed].
 * 5. At any time: [cancel] with [ChipReadEnding.Cancelled.By.APP] (then release the link in the
 *    background) or [ChipReadEnding.Cancelled.By.PERSON]; [endedBySystem]; [fail] for a platform
 *    problem with no better ending.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. Internal: see [InternalTesseraChipApi].
 *
 * @param options The read options; the key is checked in [start].
 * @param linkLoss What to do after a lost link.
 * @param observer Where the updates and the ending go.
 * @param reader The reader that runs each attempt (its telemetry sink receives the step events).
 * @param clearKeyAtEnding Whether the coordinator takes over the access key in [options]: with
 *   `true`, it clears the key at the ending, whatever the ending is, and not before, so every
 *   attempt of the read (a restart or a resume after a lost link) can still use it. The platform
 *   entry points pass `true`; a caller that owns its key passes `false`.
 * @param totalTimeLimitMillis How long a lost link may still lead to a restart, counted from the
 *   first link to the document, not from the start of waiting; past it, the loss ends the read.
 *   Before the first link it does nothing. [NO_TIME_LIMIT] for no limit.
 * @param now The clock for [totalTimeLimitMillis], in milliseconds, never going back.
 * @since 1.0.0
 */
@InternalTesseraChipApi
@ExperimentalTesseraChipApi
public class ChipReadCoordinator private constructor(
    private val options: ChipReadOptions,
    private val linkLoss: LinkLossPolicy,
    private val observer: ChipReadObserver,
    private val reader: ChipReader,
    private val clearKeyAtEnding: Boolean,
    private val totalTimeLimitMillis: Long,
    private val now: () -> Long,
) {
    // When the first link to a document was made (the first documentFound(), or read() when the
    // caller never announced one): the start of the total time limit. -1 until then, so the wait
    // for a document is not counted.
    private val firstLinkAt = AtomicLong(-1)

    private fun markFirstLink() {
        firstLinkAt.compareAndSet(-1, now())
    }

    /**
     * Creates a coordinator: `ChipReadCoordinator(options, linkLoss, observer)`. Kotlin only: on the
     * JVM this and every member are synthetic, so Java code cannot call them (the opt-in marker
     * does not reach Java), and Objective-C and Swift do not see the class.
     */
    @InternalTesseraChipApi
    public companion object {
        /** See the class parameters. */
        @JvmSynthetic
        public operator fun invoke(
            options: ChipReadOptions,
            linkLoss: LinkLossPolicy,
            observer: ChipReadObserver,
            reader: ChipReader = ChipReader(),
            clearKeyAtEnding: Boolean = false,
            totalTimeLimitMillis: Long = NO_TIME_LIMIT,
            now: () -> Long = monotonicMillis(),
        ): ChipReadCoordinator = ChipReadCoordinator(options, linkLoss, observer, reader, clearKeyAtEnding, totalTimeLimitMillis, now)

        /** The value of `totalTimeLimitMillis` that means no limit. */
        public const val NO_TIME_LIMIT: Long = Long.MAX_VALUE

        private fun monotonicMillis(): () -> Long {
            val mark = TimeSource.Monotonic.markNow()
            return { mark.elapsedNow().inWholeMilliseconds }
        }

        /** How often a platform reports [ChipReadState.WaitingForDocument] again: every 2 seconds, on both platforms. */
        public const val WAITING_REPORT_MILLIS: Long = 2_000

        /** Failed connects in a row (a lost link, no answer, before any command) after which a platform ends the read. */
        public const val MAX_CONNECT_ATTEMPTS: Int = 3

        /** The default settle time after a document is found, before the first command, on both platforms. */
        public const val DEFAULT_SETTLE_DELAY_MILLIS: Long = 400

        /**
         * The default time limit for one command before access, on both platforms: 1500
         * milliseconds. These commands, before the key is used (selecting and reading
         * EF.CardAccess, selecting the application, BAC's GET CHALLENGE or PACE's MSE:Set AT), need
         * no public-key work on the chip and normally answer in milliseconds.
         */
        public const val DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS: Long = 1_500

        /**
         * The default time limit for one ordinary command after access
         * ([ChipCommandTimeLimit.READ]), on both platforms: 2000 milliseconds. These commands
         * (selecting a file, reading up to 256 bytes of it, under secure messaging or not) need no
         * public-key work on the chip: on a device a 223-byte protected read took 45 to 65
         * milliseconds. ISO/IEC 14443-4 lets a chip take up to about 4.95 seconds for one frame, so
         * a legal chip can be slower than this; none seen so far was for these commands.
         */
        public const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 2_000

        /**
         * The kind of [command] after access: [ChipCommandTimeLimit.LONG] for the commands that make
         * the chip do public-key or key-agreement work (EXTERNAL, GENERAL and INTERNAL
         * AUTHENTICATE, MSE:Set KAT, PERFORM SECURITY OPERATION) and for any extended-length
         * command (its answer can be up to 64 KB), [ChipCommandTimeLimit.READ] for every other one.
         * The header bytes are plain under secure messaging too (Doc 9303 Part 11, 9.8.1).
         */
        @JvmSynthetic
        internal fun timeLimitFor(command: ByteArray): ChipCommandTimeLimit {
            if (command.size < 4) return ChipCommandTimeLimit.READ
            val ins = command[1].toInt() and 0xFF
            val p2 = command[3].toInt() and 0xFF
            // Lc or Le of 0 in byte 5, with more bytes after it, marks the extended-length form.
            val extended = command.size > 5 && command[4] == 0.toByte()
            return when {
                extended -> ChipCommandTimeLimit.LONG
                ins == INS_EXTERNAL_AUTHENTICATE -> ChipCommandTimeLimit.LONG
                ins == INS_GENERAL_AUTHENTICATE -> ChipCommandTimeLimit.LONG
                ins == INS_INTERNAL_AUTHENTICATE -> ChipCommandTimeLimit.LONG
                ins == INS_MSE && p2 == MSE_P2_KAT -> ChipCommandTimeLimit.LONG
                ins == INS_PSO -> ChipCommandTimeLimit.LONG
                else -> ChipCommandTimeLimit.READ
            }
        }

        private const val INS_EXTERNAL_AUTHENTICATE = 0x82
        private const val INS_GENERAL_AUTHENTICATE = 0x86
        private const val INS_INTERNAL_AUTHENTICATE = 0x88
        private const val INS_MSE = 0x22
        private const val INS_PSO = 0x2A
        private const val MSE_P2_KAT = 0xA6

        private val SYSTEM_REASONS = setOf(NfcUnavailable.Reason.ENDED_BY_SYSTEM, NfcUnavailable.Reason.DISABLED)

        /** The chip answered about the key: never tried again with it. */
        private val KEY_REFUSALS =
            setOf(
                ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                ChipAccessDenied.Reason.CHIP_REFUSED,
                ChipAccessDenied.Reason.KEY_BLOCKED,
                ChipAccessDenied.Reason.KEY_SUSPENDED,
                ChipAccessDenied.Reason.KEY_DEACTIVATED,
                ChipAccessDenied.Reason.KEY_TRIES_LIMITED,
            )

        /** The refusals after which the read tries access once more on the same link (see tryAccessAgain). */
        private val RETRIED_REFUSALS = setOf(ChipAccessDenied.Reason.KEY_NOT_MATCHED, ChipAccessDenied.Reason.CHIP_REFUSED)

        private val RETRIED_PROTOCOLS = setOf(ChipAccessProtocol.BAC, ChipAccessProtocol.PACE)

        /** A loss of the link itself, with no status word: the only kind a read starts again after. */
        private fun isLinkLoss(error: ChipError): Boolean = error is NfcTagLost || error is NfcTimeout

        /** The link was closed under the read, by the platform. */
        private fun isClosed(error: ChipError): Boolean = error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CLOSED
    }

    private sealed class Item {
        class Update(
            val update: ChipReadUpdate,
        ) : Item()

        class End(
            val ending: ChipReadEnding,
        ) : Item()
    }

    private val ended = AtomicBoolean(false)
    private val queue = AtomicReference<List<Item>>(emptyList())
    private val draining = AtomicBoolean(false)

    // Touched only by the thread that holds `draining`.
    private var endingDelivered = false

    // The tracker is used by the attempt's own context only; other threads read lastProgress.
    private val tracker = ProgressTracker()
    private val lastProgress = AtomicReference(tracker.snapshot())
    private val attemptsStarted = AtomicInt(0)
    private val restartsUsed = AtomicInt(0)

    // The file bytes the carry held at the last lost link (0 before the first): a restart is
    // free when the carry now holds more (see takeRestart).
    private val bytesAtLastLoss = AtomicLong(0)
    private val waitingAfterLoss = AtomicBoolean(false)
    private val lastLinkLost = AtomicReference<ChipReadState.LinkLost?>(null)
    private val lastLoss = AtomicReference<ChipError?>(null)
    private val lastAccessResult = AtomicReference<ChipReadResult.Partial?>(null)
    private val lastReadState = AtomicReference<ChipReadState?>(null)
    private val waitedMillis = AtomicReference(0L)
    private val carry = AtomicReference<ResumeCarry?>(null)

    // Unanswered tries before access (see NoAnswerYet): the tries in a row, the state to show again,
    // and whether the next read() repeats the current attempt instead of starting a new one.
    private val noAnswerTries = AtomicInt(0)
    private val lastNoAnswer = AtomicReference<ChipReadState.NoAnswerYet?>(null)
    private val repeatAttempt = AtomicBoolean(false)

    // Whether any try of the read reached the first command that uses the key.
    private val accessEverReached = AtomicBoolean(false)

    // Whether the read used its one more try of access after a refusal (see tryAccessAgain).
    private val accessRetried = AtomicBoolean(false)

    // A planned handover (see HANDED_OVER): the updates held back until the read is past where it
    // was, and whether the repeats of the current attempt continue from what it read.
    private val handover = AtomicReference<HandoverMark?>(null)
    private val resumeAfterHandover = AtomicBoolean(false)

    /** Where the read was when the platform handed over: its progress and the checks it had shown. */
    private class HandoverMark(
        val progress: ChipReadProgress,
        val authShown: Set<ChipAuthMethod>,
    )

    /** `true` while a planned handover is under way: nothing is published until the read is past it. */
    private val handingOver: Boolean get() = handover.load() != null

    /** `true` while the read shows [ChipReadState.NoAnswerYet]: the steps of each new try are not reported. */
    private val noAnswer: Boolean get() = lastNoAnswer.load() != null

    /** `true` once the ending is decided. */
    @get:JvmSynthetic
    public val isEnded: Boolean
        get() = ended.load()

    /**
     * The attempts the read can make when every restart is used: 1 plus [LinkLossPolicy.maxRestarts].
     * Under [LinkLossPolicy.Resume] a restart after an attempt that kept more bytes uses no restart
     * (see [takeRestart]), so the attempt number can pass this; the updates then carry the attempt
     * number as the most.
     */
    @get:JvmSynthetic
    public val maxAttempts: Int = 1 + linkLoss.maxRestarts

    /**
     * Starts the read: [ChipReadState.Starting], then the key check, before any radio activity or
     * system sheet. The read ends with [ChipReadEnding.KeyRejected] when the key was already used
     * (cleared, for example by an earlier read: [ChipAccessDenied] with
     * [ChipAccessDenied.Reason.KEY_CLEARED]), or when an MRZ key's check digits do not match,
     * unless [ChipReadOptions.allowCheckDigitMismatch] allows that.
     *
     * @return `false` when the read has ended (the key was refused, or it had ended already).
     */
    @JvmSynthetic
    public fun start(): Boolean {
        publish(ChipReadState.Starting)
        val key = options.accessKey
        when {
            key.isCleared -> {
                end(
                    ChipReadEnding.KeyRejected(
                        ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_CLEARED),
                        0,
                        lastProgress.load(),
                    ),
                )
            }

            key is ChipAccessKey.Mrz && key.checkDigitFindings.isNotEmpty() && !options.allowCheckDigitMismatch -> {
                end(ChipReadEnding.KeyRejected(ChipKeyCheckDigitMismatch(key.checkDigitFindings), 0, lastProgress.load()))
            }
        }
        return !isEnded
    }

    /**
     * The reader is on and waits for a document: [ChipReadState.WaitingForDocument] with
     * [waitedMillis]. Call it when polling starts and again every few seconds. While the read
     * waits after a lost link it does nothing: [ChipReadState.LinkLost] stays until the document is
     * found again.
     *
     * @param waitedMillis How long the reader has waited so far, in milliseconds.
     */
    @JvmSynthetic
    public fun waiting(waitedMillis: Long) {
        if (waitingAfterLoss.load() || noAnswer || handingOver) return
        this.waitedMillis.store(waitedMillis)
        publish(ChipReadState.WaitingForDocument(waitedMillis))
    }

    /**
     * The update [waiting] would publish now for [waitedMillis], without publishing it: for a
     * platform that shows its text before the reader is on.
     *
     * @param waitedMillis How long the reader has waited so far, in milliseconds.
     * @return The update.
     */
    @JvmSynthetic
    public fun waitingUpdate(waitedMillis: Long): ChipReadUpdate = updateOf(ChipReadState.WaitingForDocument(waitedMillis))

    /**
     * The platform closes its reader session and opens a new one while no document was reported:
     * [ChipReadState.RenewingSession]. No attempt or restart is used; call [waiting] when the new
     * session is on.
     *
     * @param renewal Which new session of the read this is, from 1.
     * @return The update, published unless the read has ended.
     */
    @JvmSynthetic
    public fun renewingSession(renewal: Int): ChipReadUpdate {
        val state = ChipReadState.RenewingSession(renewal)
        publish(state)
        return updateOf(state)
    }

    /**
     * A document is found and nothing was sent yet: [ChipReadState.DocumentFound]. While the read
     * shows [ChipReadState.NoAnswerYet], or hands over ([ChipReadAttemptOutcome.HANDED_OVER]), it
     * does nothing: the state before stays.
     */
    @JvmSynthetic
    public fun documentFound() {
        markFirstLink()
        if (noAnswer || handingOver) return
        publish(ChipReadState.DocumentFound)
    }

    /**
     * The link to the chip is being set up: [ChipReadState.Connecting]. While the read shows
     * [ChipReadState.NoAnswerYet], or hands over, it does nothing.
     */
    @JvmSynthetic
    public fun connecting() {
        if (noAnswer || handingOver) return
        publish(ChipReadState.Connecting)
    }

    /**
     * The link could not be set up, before any command: back to waiting, with no restart used
     * ([ChipReadState.WaitingForDocument], [ChipReadState.LinkLost] again after a lost link, or
     * [ChipReadState.NoAnswerYet] again after unanswered tries).
     */
    @JvmSynthetic
    public fun connectionFailed() {
        val lost = lastLinkLost.load()
        val silent = lastNoAnswer.load()
        if (silent != null) {
            publish(silent)
        } else if (waitingAfterLoss.load() && lost != null) {
            publish(lost)
        } else {
            publish(ChipReadState.WaitingForDocument(waitedMillis.load()))
        }
    }

    /**
     * Runs one attempt on [transport], already connected, and decides what follows. Call it from
     * the context that may block on the transport (never the main thread).
     *
     * When the attempt ends with a lost link that the policy allows to follow, the read publishes
     * [ChipReadState.LinkLost] and returns [ChipReadAttemptOutcome.LINK_LOST]: release this link and
     * wait for the document. Otherwise the ending is delivered (unless another cause ended the read
     * first) and the result is [ChipReadAttemptOutcome.ENDED].
     *
     * When the attempt ends with a lost link before it sent the first command that uses the key,
     * and the platform allows another try, the read publishes [ChipReadState.NoAnswerYet] and
     * returns [ChipReadAttemptOutcome.NO_ANSWER_YET]: no restart is used, and the next call repeats
     * this attempt. When the platform allows no other try and no try of the read reached that
     * command, the read ends with [ChipReadEnding.Failed] and [NfcNoAnswer].
     *
     * When the attempt stopped with a lost link that the platform made on purpose ([handedOver]
     * answers `true`), under [LinkLossPolicy.Resume], the read returns
     * [ChipReadAttemptOutcome.HANDED_OVER]: no restart is used, no [ChipReadState.LinkLost] is
     * published, and the next call repeats this attempt and continues from what it read, as a
     * resume does (EF.SOD compared byte for byte, a cut-off data group continued from its byte
     * offset, access run again with the same key). Until that next try is past where this one
     * stopped, the read publishes nothing: no [ChipReadState.DocumentFound],
     * [ChipReadState.Connecting] or [ChipReadState.OpeningAccess], no file it had already done,
     * no [ChipReadState.AuthenticatingChip] it had already shown, so the progress and the file
     * count never go back.
     *
     * When the chip refuses the key ([ChipAccessDenied.Reason.KEY_NOT_MATCHED] or
     * [ChipAccessDenied.Reason.CHIP_REFUSED]) and this is the first refusal in the read, before any
     * try reached the command that uses the key, with an MRZ key whose check digits match or a card
     * access number, the read runs the flow once more on [transport] before it decides: the
     * application is selected again and the access protocol runs again with fresh random values.
     * What that second run ends with is what follows.
     *
     * Cancelling the calling coroutine ends the read with [ChipReadEnding.Cancelled.By.APP] when it
     * has not ended yet, then rethrows.
     *
     * @param transport The connected link to the chip.
     * @param readAgainAllowed `false` when the platform cannot start again after this attempt (for
     *   example too little session time left); the read then ends at a lost link.
     * @param systemEnd Asked once, on the read's own context, when the attempt stopped on a lost
     *   link ([NfcTagLost], [NfcTimeout]) or on a link that closed while the read had not ended
     *   ([NfcUnavailable.Reason.CLOSED]: the platform invalidated it, for example), with that
     *   error, before the read decides to start again or to end. It returns why the system ended
     *   the read, such as NFC switched off, or `null` when the platform sees no such cause. A
     *   non-null answer ends the read, never with a new attempt: [ChipReadEnding.Cancelled] by
     *   [ChipReadEnding.Cancelled.By.PERSON] for [NfcUnavailable.Reason.CANCELLED_BY_USER],
     *   [ChipReadEnding.EndedBySystem] with that reason otherwise. It may wait briefly for the
     *   platform's own report (a closed link is often reported before the session's end); an
     *   exception it throws counts as `null`. A closed link with no answer ends the read as
     *   [ChipReadEnding.EndedBySystem] with the closed-link error, never as an app cancel: an app
     *   cancel ends the read before its link closes.
     * @param useTimeLimit Called right before a command whose kind ([ChipCommandTimeLimit]) differs
     *   from the one before it in this attempt: the platform sets that kind's time limit on
     *   [transport] here. The attempt starts with [ChipCommandTimeLimit.BEFORE_ACCESS], so the
     *   platform opens the link with that limit and is not called for it. Up to the first command
     *   that uses the key (BAC's EXTERNAL AUTHENTICATE, PACE's first GENERAL AUTHENTICATE; for a
     *   plain chip that needs no access protocol, the first command after EF.COM) every command is
     *   [ChipCommandTimeLimit.BEFORE_ACCESS]; a loss of the link before that command is a
     *   [ChipReadAttemptOutcome.NO_ANSWER_YET]. From it on, each command is
     *   [ChipCommandTimeLimit.LONG] or [ChipCommandTimeLimit.READ]. An exception it throws stops
     *   the attempt like a failed command.
     * @param handedOver Asked once, when the attempt stopped with a lost link ([NfcTagLost] or
     *   [NfcTimeout]) and the platform named no system end: `true` when the platform stopped the
     *   attempt itself, between two commands, to get a fresh connection to the same document (on
     *   iPhone, before the system's limit on one tag connection). Honoured only under
     *   [LinkLossPolicy.Resume]; the platform never hands over under another policy. An exception
     *   it throws counts as `false`.
     */
    @JvmSynthetic
    @Throws(CancellationException::class)
    public suspend fun read(
        transport: ApduTransport,
        readAgainAllowed: Boolean = true,
        systemEnd: suspend (stoppedBy: NfcError) -> NfcError? = { null },
        useTimeLimit: suspend (ChipCommandTimeLimit) -> Unit = {},
        handedOver: () -> Boolean = { false },
    ): ChipReadAttemptOutcome {
        if (isEnded) return ChipReadAttemptOutcome.ENDED
        markFirstLink()
        val repeat = repeatAttempt.exchange(false)
        // A new attempt number starts fresh; the repeats after a handover continue from its carry.
        if (!repeat) resumeAfterHandover.store(false)
        val attempt = if (repeat) attemptsStarted.load() else attemptsStarted.incrementAndFetch()
        waitingAfterLoss.store(false)
        // While unanswered tries go on, the opening steps of each try (OpeningAccess, with or
        // without the protocol) are not news: the chip may answer the plain commands and still go
        // silent before access. The last one is held back and shown once access is reached.
        var heldOpening: ChipReadState? = null
        // The checks this try showed, so a try after a planned handover does not show them again.
        val authShown = HashSet<ChipAuthMethod>()
        val reporter =
            FlowReporter(tracker) { state, progress ->
                lastReadState.store(state)
                if (state is ChipReadState.AuthenticatingChip) authShown += state.method
                if (holdForHandover(state, progress)) return@FlowReporter
                lastProgress.store(progress)
                if (noAnswer && state is ChipReadState.OpeningAccess) {
                    heldOpening = state
                } else {
                    lastNoAnswer.store(null)
                    publish(state)
                }
            }
        val resume = linkLoss is LinkLossPolicy.Resume
        var accessReached = false
        // Whether an earlier try of the read reached the first command that uses the key.
        val accessBefore = accessEverReached.load()
        val tryCarry = if (resume && (attempt > 1 || resumeAfterHandover.load())) carry.load() else null
        var gate = EndedGate(transport, { accessReached }, useTimeLimit, ChipCommandTimeLimit.BEFORE_ACCESS)

        // One run of the flow on this link; `null` when it ended the read with a fault.
        suspend fun runFlow(): Pair<ChipReadResult, ResumeCarry>? =
            try {
                reader.readAttempt(gate, options, reporter, attempt, tryCarry) {
                    accessReached = true
                    accessEverReached.store(true)
                    if (noAnswer) {
                        lastNoAnswer.store(null)
                        heldOpening?.let(::publish)
                    }
                }
            } catch (e: CancellationException) {
                end(ChipReadEnding.Cancelled(ChipReadEnding.Cancelled.By.APP, attempt, lastProgress.load()))
                throw e
            } catch (e: Throwable) {
                // The flow turns every fault into a typed result; this is the backstop for a fault
                // outside it (an Error, for example): a typed ending that says the chip's answer could
                // not be used, never "the app cancelled". Not rethrown: the ending is the report.
                end(stoppedBy(unexpectedFault(), null))
                null
            }

        var (result, next) = runFlow() ?: return ChipReadAttemptOutcome.ENDED
        // The first refusal of access in the read, with a key that has no try counter: one more try
        // on the same link, from the selection of the application on (see ChipReadEnding.KeyNotAccepted).
        if (!isEnded && !accessBefore && tryAccessAgain(result)) {
            accessReached = false
            gate = EndedGate(transport, { accessReached }, useTimeLimit, gate.limit)
            val again = runFlow() ?: return ChipReadAttemptOutcome.ENDED
            result = again.first
            next = again.second
        }
        if (resume) carry.store(next)
        if (result is ChipReadResult.Partial) lastAccessResult.store(result)
        if (isEnded) return ChipReadAttemptOutcome.ENDED
        val error =
            when (result) {
                is ChipReadResult.Complete -> {
                    end(ChipReadEnding.Complete(result.read, attempt, tracker.complete()))
                    return ChipReadAttemptOutcome.ENDED
                }

                is ChipReadResult.Partial -> {
                    result.stoppedBy
                }

                is ChipReadResult.Failed -> {
                    result.error
                }
            }
        // The platform is asked only about a loss of the link, or a link that closed under a read
        // that had not ended: a person's cancel or a refusal keeps its own meaning.
        if (error is NfcError && (isLinkLoss(error) || isClosed(error))) {
            val system =
                try {
                    systemEnd(error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (isEnded) return ChipReadAttemptOutcome.ENDED
            if (system != null) {
                end(endingForSystem(system, attempt))
                return ChipReadAttemptOutcome.ENDED
            }
        }
        // Stopped by the platform on purpose, to get a fresh connection to the same document: no
        // restart, no LinkLost; the next try repeats this attempt and continues from its carry.
        if (isLinkLoss(error) && resume && askHandedOver(handedOver)) {
            if (accessReached) endNoAnswer()
            lastLoss.store(error)
            val before = handover.load()
            handover.store(HandoverMark(lastProgress.load(), authShown + before?.authShown.orEmpty()))
            resumeAfterHandover.store(true)
            repeatAttempt.store(true)
            return ChipReadAttemptOutcome.HANDED_OVER
        }
        // Lost before the first command that uses the key: the chip was asked nothing about it, so
        // the try costs no restart and repeats this attempt.
        if (isLinkLoss(error) && readAgainAllowed && !accessReached && result is ChipReadResult.Failed) {
            lastLoss.store(error)
            repeatAttempt.store(true)
            val silent = ChipReadState.NoAnswerYet(noAnswerTries.incrementAndFetch())
            lastNoAnswer.store(silent)
            handover.store(null)
            publish(silent)
            return ChipReadAttemptOutcome.NO_ANSWER_YET
        }
        // Lost before that command with no other try allowed, and no try reached it: the chip never
        // answered the first commands, which the app can tell from a link lost later.
        if (isLinkLoss(error) && !accessReached && !accessEverReached.load() && result is ChipReadResult.Failed) {
            endNoAnswer()
            end(stoppedBy(NfcNoAnswer, null))
            return ChipReadAttemptOutcome.ENDED
        }
        endNoAnswer()
        val usedBefore = if (isLinkLoss(error) && readAgainAllowed) takeRestart() else -1
        if (usedBefore >= 0) {
            lastLoss.store(error)
            val lost = ChipReadState.LinkLost(linkLoss.maxRestarts - usedBefore)
            lastLinkLost.store(lost)
            waitingAfterLoss.store(true)
            handover.store(null)
            publish(lost)
            // Another cause may have ended the read since the last check (a cancel, or another
            // thread's attempt): then LinkLost was not published, and the platform must not wait.
            return if (isEnded) ChipReadAttemptOutcome.ENDED else ChipReadAttemptOutcome.LINK_LOST
        }
        end(endingFor(result, error))
        return ChipReadAttemptOutcome.ENDED
    }

    /**
     * Whether the read tries access once more on the same link after [result]: a refusal of the
     * key by the chip ([ChipAccessDenied.Reason.KEY_NOT_MATCHED] or
     * [ChipAccessDenied.Reason.CHIP_REFUSED], during BAC or PACE), with a key that has no try
     * counter and looks right (an MRZ key whose check digits match, or a card access number), and
     * only the first time in the read. Takes the one retry of the read when it answers `true`.
     */
    private fun tryAccessAgain(result: ChipReadResult): Boolean {
        val error = (result as? ChipReadResult.Failed)?.error as? ChipAccessDenied ?: return false
        if (error.reason !in RETRIED_REFUSALS || error.protocol !in RETRIED_PROTOCOLS) return false
        val key = options.accessKey
        if (key.isCleared) return false
        val noCounter =
            when (key) {
                // A key the check-digit gate let through only by the app's choice does not look right.
                is ChipAccessKey.Mrz -> key.checkDigitFindings.isEmpty()

                is ChipAccessKey.Can -> true
            }
        return noCounter && accessRetried.compareAndSet(false, true)
    }

    private fun askHandedOver(handedOver: () -> Boolean): Boolean =
        try {
            handedOver()
        } catch (e: Exception) {
            false
        }

    /**
     * Whether [state] is held back during a planned handover: the steps the new try repeats
     * (opening access, files done before, a check shown before). The first state past them ends
     * the handover and goes out.
     */
    private fun holdForHandover(
        state: ChipReadState,
        progress: ChipReadProgress,
    ): Boolean {
        val mark = handover.load() ?: return false
        val hold =
            when (state) {
                is ChipReadState.OpeningAccess -> {
                    true
                }

                is ChipReadState.ReadingFile -> {
                    state.file.inMasterFile || state.file.dataGroup == null || progress.filesDone < mark.progress.filesDone
                }

                is ChipReadState.AuthenticatingChip -> {
                    state.method in mark.authShown
                }

                else -> {
                    false
                }
            }
        if (!hold) handover.compareAndSet(mark, null)
        return hold
    }

    /**
     * The document did not come back within the platform's short wait after a planned handover
     * ([ChipReadAttemptOutcome.HANDED_OVER]), or its new link could not be set up: from here the
     * lost-link rules apply, as after any loss. When a restart is left, the read uses it, publishes
     * [ChipReadState.LinkLost] and waits for the document; the next [read] is a new attempt that
     * continues from what was read. Otherwise the read ends as [documentNotReturned] does.
     *
     * @return `true` when the read goes on and waits for the document.
     */
    @JvmSynthetic
    public fun handoverFailed(): Boolean {
        if (isEnded) return false
        handover.store(null)
        // The next try is a new attempt, as after any lost link.
        repeatAttempt.store(false)
        val usedBefore = takeRestart()
        if (usedBefore < 0) {
            documentNotReturned()
            return false
        }
        val lost = ChipReadState.LinkLost(linkLoss.maxRestarts - usedBefore)
        lastLinkLost.store(lost)
        waitingAfterLoss.store(true)
        publish(lost)
        return !isEnded
    }

    /**
     * The attempt reached access and ended, or ended otherwise: unanswered tries in a row are over.
     * A chip that answers some plain commands and then goes silent before access keeps counting.
     */
    private fun endNoAnswer() {
        lastNoAnswer.store(null)
        noAnswerTries.store(0)
    }

    /** The data group bytes the carry holds, finished and cut off: what a resume keeps. */
    private fun carriedBytes(): Long {
        val held = carry.load() ?: return 0
        return held.files.sumOf { it.size.toLong() } + (held.unfinished?.second?.size ?: 0)
    }

    /**
     * Decides the restart after a lost link: the restarts used before this one, or -1 when the read
     * ends. Under [LinkLossPolicy.Resume], a loss after an attempt that kept more bytes than the
     * attempts before it (the carry grew since the last loss) uses no restart: the read is
     * getting somewhere. Every other loss takes one restart from the budget with one
     * compare-and-set, so no misuse can buy an uncounted attempt. Past the total time limit no
     * restart is given at all.
     */
    private fun takeRestart(): Int {
        val bytes = carriedBytes()
        val grew = bytes > bytesAtLastLoss.exchange(bytes)
        val first = firstLinkAt.load()
        if (first >= 0 && now() - first >= totalTimeLimitMillis) return -1
        if (grew && linkLoss is LinkLossPolicy.Resume) return restartsUsed.load()
        while (true) {
            val used = restartsUsed.load()
            if (used >= linkLoss.maxRestarts) return -1
            if (restartsUsed.compareAndSet(used, used + 1)) return used
        }
    }

    /** The error for a fault the read did not expect: about the chip's answer, after access or before it. */
    private fun unexpectedFault(): ChipError =
        if (lastAccessResult.load() != null) {
            ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNUSABLE_FILE_ANSWER)
        } else {
            ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER)
        }

    /**
     * The attempt's transport, closed by the read's ending: once the read has ended, from any cause
     * and on any thread, no further command reaches the chip, whatever the platform entry point
     * does with the running attempt. It also asks the platform for each command's time limit, only
     * when the kind changes ([accessReached] says whether the attempt is past the commands before
     * access).
     */
    private inner class EndedGate(
        private val base: ApduTransport,
        private val accessReached: () -> Boolean,
        private val useTimeLimit: suspend (ChipCommandTimeLimit) -> Unit,
        initial: ChipCommandTimeLimit,
    ) : ApduTransport by base {
        // The kind of limit the link has now. The attempt's commands go one at a time, so this
        // needs no lock.
        var limit = initial
            private set

        override suspend fun transceive(command: ByteArray): ByteArray {
            if (isEnded) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
            val wanted = if (accessReached()) timeLimitFor(command) else ChipCommandTimeLimit.BEFORE_ACCESS
            if (wanted != limit) {
                useTimeLimit(wanted)
                limit = wanted
            }
            return base.transceive(command)
        }
    }

    /**
     * The document did not come back after a lost link within the platform's wait, or its chip did
     * not answer within the platform's time bound for unanswered tries: the read ends with
     * [ChipReadEnding.Partial] when any attempt was granted access, [ChipReadEnding.Failed]
     * otherwise, stopped by the last loss. When the read shows [ChipReadState.NoAnswerYet] and no
     * try of it reached the first command that uses the key, the error is [NfcNoAnswer] instead of
     * the loss.
     *
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun documentNotReturned(): Boolean {
        // The time for unanswered tries passed, and no try of the read reached access: the chip
        // never answered the first commands, which the app can tell from a link lost later.
        val loss = if (noAnswer && !accessEverReached.load()) NfcNoAnswer else lastLoss.load() ?: NfcTagLost
        return end(stoppedBy(loss, null))
    }

    /**
     * The person or the app cancelled. For the app, [ChipReadState.Stopping] comes first; release
     * the link in the background after this call.
     *
     * @param by Who cancelled.
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun cancel(by: ChipReadEnding.Cancelled.By): Boolean {
        val stopping = if (by == ChipReadEnding.Cancelled.By.APP) ChipReadState.Stopping else null
        return end(ChipReadEnding.Cancelled(by, attemptsStarted.load(), lastProgress.load()), stopping)
    }

    /**
     * The system ended the read: [ChipReadEnding.EndedBySystem].
     *
     * @param reason What the platform reported.
     * @param systemCode The platform's own code for it, when it gave one: [ChipReadEnding.systemCode].
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun endedBySystem(
        reason: NfcError,
        systemCode: Int? = null,
    ): Boolean = end(ChipReadEnding.EndedBySystem(reason, attemptsStarted.load(), lastProgress.load(), systemCode))

    /**
     * The read could not start: [ChipReadEnding.NotStarted].
     *
     * @param reason Why.
     * @param systemCode The platform's own code for it, when it gave one: [ChipReadEnding.systemCode].
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun notStarted(
        reason: NfcUnavailable.Reason,
        systemCode: Int? = null,
    ): Boolean = end(ChipReadEnding.NotStarted(reason, lastProgress.load(), systemCode))

    /**
     * A platform problem with no better ending (for example a tag that is not an ISO 14443-4 chip):
     * [ChipReadEnding.Partial] when an attempt was granted access, else [ChipReadEnding.Failed].
     *
     * @param error What happened.
     * @param systemCode The platform's own code for it, when it gave one: [ChipReadEnding.systemCode].
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun fail(
        error: ChipError,
        systemCode: Int? = null,
    ): Boolean = end(stoppedBy(error, null, systemCode))

    /**
     * The link to a found document could not be set up [MAX_CONNECT_ATTEMPTS] times in a row, each
     * time with a loss of the link itself ([last]): the read ends. When no try of the read got past
     * the first commands, the chip never answered, and the error is [NfcNoAnswer], the same on
     * every platform; otherwise it is [last]. [ChipReadEnding.Partial] when an attempt was granted
     * access, else [ChipReadEnding.Failed].
     *
     * @param last The error of the last failed connect.
     * @param systemCode The platform's own code of that failure, when it gave one.
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun connectsFailed(
        last: NfcError,
        systemCode: Int? = null,
    ): Boolean = end(stoppedBy(if (accessEverReached.load()) last else NfcNoAnswer, null, systemCode))

    private fun stoppedBy(
        error: ChipError,
        failed: ChipReadResult.Failed?,
        systemCode: Int? = null,
    ): ChipReadEnding {
        val attempts = attemptsStarted.load()
        val progress = lastProgress.load()
        val accessed = lastAccessResult.load()
        return if (accessed != null) {
            ChipReadEnding.Partial(accessed.read, error, lastReadState.load(), attempts, progress, systemCode)
        } else {
            ChipReadEnding.Failed(error, failed?.access, attempts, progress, systemCode)
        }
    }

    private fun endingFor(
        result: ChipReadResult,
        error: ChipError,
    ): ChipReadEnding {
        val attempts = attemptsStarted.load()
        val progress = lastProgress.load()
        return when {
            error is ChipKeyCheckDigitMismatch -> {
                ChipReadEnding.KeyRejected(error, attempts, progress)
            }

            error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CANCELLED_BY_USER -> {
                ChipReadEnding.Cancelled(ChipReadEnding.Cancelled.By.PERSON, attempts, progress)
            }

            // Closed while the read had not ended, and the platform named no cause: the system's
            // doing. Never "cancelled by the app": an app cancel ends the read before the link closes.
            isClosed(error) -> {
                ChipReadEnding.EndedBySystem(error as NfcError, attempts, progress)
            }

            error is NfcUnavailable && error.reason in SYSTEM_REASONS -> {
                ChipReadEnding.EndedBySystem(error, attempts, progress)
            }

            result is ChipReadResult.Failed && error is ChipAccessDenied && error.reason in KEY_REFUSALS -> {
                ChipReadEnding.KeyNotAccepted(error, attempts, progress)
            }

            result is ChipReadResult.Partial -> {
                ChipReadEnding.Partial(result.read, error, lastReadState.load(), attempts, progress)
            }

            else -> {
                stoppedBy(error, result as? ChipReadResult.Failed)
            }
        }
    }

    /** The ending for a cause the platform reported after an attempt stopped. */
    private fun endingForSystem(
        system: NfcError,
        attempt: Int,
    ): ChipReadEnding =
        if (system is NfcUnavailable && system.reason == NfcUnavailable.Reason.CANCELLED_BY_USER) {
            ChipReadEnding.Cancelled(ChipReadEnding.Cancelled.By.PERSON, attempt, lastProgress.load())
        } else {
            ChipReadEnding.EndedBySystem(system, attempt, lastProgress.load())
        }

    // The attempt an update belongs to (see ChipReadUpdate.attempt): LinkLost belongs to the
    // attempt that lost the link, however often it is published; every state after a loss belongs
    // to the attempt that starts when the document is back.
    private fun attemptOf(state: ChipReadState?): Int {
        val started = attemptsStarted.load()
        val next = if (waitingAfterLoss.load() && state !is ChipReadState.LinkLost) 1 else 0
        return (started + next).coerceAtLeast(1)
    }

    private fun updateOf(state: ChipReadState): ChipReadUpdate =
        attemptOf(state).let {
            ChipReadUpdate(state, lastProgress.load(), it, maxOf(maxAttempts, it))
        }

    private fun publish(state: ChipReadState) {
        if (isEnded) return
        post(listOf(Item.Update(updateOf(state))))
    }

    /** The gate: only the first ending passes; [before] goes out right before of it. */
    private fun end(
        ending: ChipReadEnding,
        before: ChipReadState? = null,
    ): Boolean {
        if (!ended.compareAndSet(false, true)) return false
        // The one gate every ending passes: the key is wiped here, before the app hears of it.
        if (clearKeyAtEnding) options.accessKey.clear()
        val items = ArrayList<Item>(2)
        if (before !=
            null
        ) {
            items += Item.Update(attemptOf(before).let { ChipReadUpdate(before, lastProgress.load(), it, maxOf(maxAttempts, it)) })
        }
        items += Item.End(ending)
        post(items)
        return true
    }

    // A lock-free queue with one drainer at a time: calls to the observer never overlap and keep
    // the order they were posted in, from any thread, with no lock in common code.
    private fun post(items: List<Item>) {
        while (true) {
            val old = queue.load()
            if (queue.compareAndSet(old, old + items)) break
        }
        drain()
    }

    private fun drain() {
        while (true) {
            if (!draining.compareAndSet(false, true)) return
            while (true) {
                val batch = queue.exchange(emptyList())
                if (batch.isEmpty()) break
                batch.forEach(::deliver)
            }
            draining.store(false)
            if (queue.load().isEmpty()) return
        }
    }

    private fun deliver(item: Item) {
        if (endingDelivered) return
        try {
            when (item) {
                is Item.Update -> observer.onUpdate(item.update)
                is Item.End -> observer.onEnding(item.ending)
            }
        } catch (e: Throwable) {
            // Ignored on purpose: the observer is never part of the read.
        }
        if (item is Item.End) endingDelivered = true
    }
}

/**
 * What a [ChipReadCoordinator.read] attempt leads to.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. Internal: see [InternalTesseraChipApi].
 *
 * @since 1.0.0
 */
@InternalTesseraChipApi
@ExperimentalTesseraChipApi
public enum class ChipReadAttemptOutcome {
    /** The read has ended: release the link. */
    ENDED,

    /** The link was lost and the read goes on: release the link and wait for the document. */
    LINK_LOST,

    /**
     * The chip did not answer before the attempt sent the first command that uses the key, and the
     * read goes on with no restart used ([ChipReadState.NoAnswerYet]): release the link and try the
     * same document again soon, within the platform's time bound for such tries; when it passes,
     * call [ChipReadCoordinator.documentNotReturned].
     */
    NO_ANSWER_YET,

    /**
     * The platform stopped the attempt on purpose, between two commands, to get a fresh connection
     * to the same document (the `handedOver` hook of [ChipReadCoordinator.read]): no restart is
     * used and the app sees no new state. Release the link, ask for the document again, and call
     * [ChipReadCoordinator.read] on the new link: it continues from what was read. When the
     * document does not come back within the platform's short wait, call
     * [ChipReadCoordinator.handoverFailed].
     */
    HANDED_OVER,
}

/**
 * The kind of a command for its time limit, which a platform entry point maps to its settings: see
 * the `useTimeLimit` hook of [ChipReadCoordinator.read].
 *
 * Experimental: see [ExperimentalTesseraChipApi]. Internal: see [InternalTesseraChipApi].
 *
 * @since 1.0.0
 */
@InternalTesseraChipApi
@ExperimentalTesseraChipApi
public enum class ChipCommandTimeLimit {
    /**
     * The commands before the key is used: selecting and reading the first files, BAC's GET
     * CHALLENGE, PACE's MSE:Set AT. They need no public-key work on the chip.
     */
    BEFORE_ACCESS,

    /**
     * An ordinary command after access: selecting and reading files with short APDUs, under secure
     * messaging or not, and the other commands with no public-key work on the chip.
     */
    READ,

    /**
     * A command that makes the chip do public-key or key-agreement work: the steps of BAC and PACE
     * from the first that uses the key, chip authentication, active authentication. Also any
     * extended-length command, whose answer can be up to 64 KB.
     */
    LONG,
}
