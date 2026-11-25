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
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmSynthetic

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
) {
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
        ): ChipReadCoordinator = ChipReadCoordinator(options, linkLoss, observer, reader, clearKeyAtEnding)

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
    private val waitingAfterLoss = AtomicBoolean(false)
    private val lastLinkLost = AtomicReference<ChipReadState.LinkLost?>(null)
    private val lastLoss = AtomicReference<ChipError?>(null)
    private val lastAccessResult = AtomicReference<ChipReadResult.Partial?>(null)
    private val lastReadState = AtomicReference<ChipReadState?>(null)
    private val waitedMillis = AtomicReference(0L)
    private val carry = AtomicReference<ResumeCarry?>(null)

    /** `true` once the ending is decided. */
    @get:JvmSynthetic
    public val isEnded: Boolean
        get() = ended.load()

    /** The most attempts this read can make: 1 plus [LinkLossPolicy.maxRestarts]. */
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
                end(ChipReadEnding.KeyRejected(ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_CLEARED), 0, lastProgress.load()))
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
        if (waitingAfterLoss.load()) return
        this.waitedMillis.store(waitedMillis)
        publish(ChipReadState.WaitingForDocument(waitedMillis))
    }

    /** A document is found and nothing was sent yet: [ChipReadState.DocumentFound]. */
    @JvmSynthetic
    public fun documentFound() {
        publish(ChipReadState.DocumentFound)
    }

    /** The link to the chip is being set up: [ChipReadState.Connecting]. */
    @JvmSynthetic
    public fun connecting() {
        publish(ChipReadState.Connecting)
    }

    /**
     * The link could not be set up, before any command: back to waiting, with no restart used
     * ([ChipReadState.WaitingForDocument], or [ChipReadState.LinkLost] again after a lost link).
     */
    @JvmSynthetic
    public fun connectionFailed() {
        val lost = lastLinkLost.load()
        if (waitingAfterLoss.load() && lost != null) {
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
     */
    @JvmSynthetic
    @Throws(CancellationException::class)
    public suspend fun read(
        transport: ApduTransport,
        readAgainAllowed: Boolean = true,
        systemEnd: suspend (stoppedBy: NfcError) -> NfcError? = { null },
    ): ChipReadAttemptOutcome {
        if (isEnded) return ChipReadAttemptOutcome.ENDED
        val attempt = attemptsStarted.incrementAndFetch()
        waitingAfterLoss.store(false)
        val reporter =
            FlowReporter(tracker) { state, progress ->
                lastProgress.store(progress)
                lastReadState.store(state)
                publish(state)
            }
        val resume = linkLoss is LinkLossPolicy.Resume
        val (result, next) =
            try {
                reader.readAttempt(EndedGate(transport), options, reporter, attempt, if (resume && attempt > 1) carry.load() else null)
            } catch (e: CancellationException) {
                end(ChipReadEnding.Cancelled(ChipReadEnding.Cancelled.By.APP, attempt, lastProgress.load()))
                throw e
            } catch (e: Throwable) {
                // The flow turns every fault into a typed result; this is the backstop for a fault
                // outside it (an Error, for example): a typed ending that says the chip's answer could
                // not be used, never "the app cancelled". Not rethrown: the ending is the report.
                end(stoppedBy(unexpectedFault(), null))
                return ChipReadAttemptOutcome.ENDED
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
        val usedBefore = if (isLinkLoss(error) && readAgainAllowed) takeRestart() else -1
        if (usedBefore >= 0) {
            lastLoss.store(error)
            val lost = ChipReadState.LinkLost(linkLoss.maxRestarts - usedBefore)
            lastLinkLost.store(lost)
            waitingAfterLoss.store(true)
            publish(lost)
            return ChipReadAttemptOutcome.LINK_LOST
        }
        end(endingFor(result, error))
        return ChipReadAttemptOutcome.ENDED
    }

    /**
     * Takes one restart from the budget with one compare-and-set, so no misuse can buy an uncounted
     * attempt: the restarts used before this one, or -1 when none is left.
     */
    private fun takeRestart(): Int {
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
     * does with the running attempt.
     */
    private inner class EndedGate(
        private val base: ApduTransport,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            if (isEnded) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
            return base.transceive(command)
        }
    }

    /**
     * The document did not come back after a lost link within the platform's wait: the read ends
     * with [ChipReadEnding.Partial] when any attempt was granted access, [ChipReadEnding.Failed]
     * otherwise, stopped by the loss.
     *
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun documentNotReturned(): Boolean {
        val loss = lastLoss.load() ?: NfcTagLost
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
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun endedBySystem(reason: NfcError): Boolean =
        end(ChipReadEnding.EndedBySystem(reason, attemptsStarted.load(), lastProgress.load()))

    /**
     * The read could not start: [ChipReadEnding.NotStarted].
     *
     * @param reason Why.
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun notStarted(reason: NfcUnavailable.Reason): Boolean = end(ChipReadEnding.NotStarted(reason, lastProgress.load()))

    /**
     * A platform problem with no better ending (for example a tag that is not an ISO 14443-4 chip):
     * [ChipReadEnding.Partial] when an attempt was granted access, else [ChipReadEnding.Failed].
     *
     * @param error What happened.
     * @return `true` when this call ended the read.
     */
    @JvmSynthetic
    public fun fail(error: ChipError): Boolean = end(stoppedBy(error, null))

    private fun stoppedBy(
        error: ChipError,
        failed: ChipReadResult.Failed?,
    ): ChipReadEnding {
        val attempts = attemptsStarted.load()
        val progress = lastProgress.load()
        val accessed = lastAccessResult.load()
        return if (accessed != null) {
            ChipReadEnding.Partial(accessed.read, error, lastReadState.load(), attempts, progress)
        } else {
            ChipReadEnding.Failed(error, failed?.access, attempts, progress)
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

    private fun publish(state: ChipReadState) {
        if (isEnded) return
        post(listOf(Item.Update(ChipReadUpdate(state, lastProgress.load(), attemptOf(state), maxAttempts))))
    }

    /** The gate: only the first ending passes; [before] goes out just ahead of it. */
    private fun end(
        ending: ChipReadEnding,
        before: ChipReadState? = null,
    ): Boolean {
        if (!ended.compareAndSet(false, true)) return false
        // The one gate every ending passes: the key is wiped here, before the app hears of it.
        if (clearKeyAtEnding) options.accessKey.clear()
        val items = ArrayList<Item>(2)
        if (before != null) items += Item.Update(ChipReadUpdate(before, lastProgress.load(), attemptOf(before), maxAttempts))
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
}
