package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadReadinessListener
import io.lightine.tessera.emrtd.ChipReadReadinessRegistration
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.HandoffObserver
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Reads a passport or ID card chip on iPhone, from start to end: the SDK owns the system NFC
 * session, its sheet, the link to the chip and every way the read can stop. This is the main way
 * to read a chip on iPhone; [CoreNfcSession] and [CoreNfcTransport] stay as the low-level path,
 * where your app owns the session.
 *
 * The shortest use: build the options, start with an observer, show the updates, and act on the
 * one ending.
 *
 * ```kotlin
 * val read = CoreNfcChipRead.start(options, observer)
 * // later, if the person leaves the screen:
 * read.cancel()
 * ```
 *
 * In Swift: `CoreNfcChipRead.companion.start(options: options, observer: observer)`.
 *
 * ## The key
 *
 * The read takes the access key in `options` over from [start] until its ending: the SDK clears it
 * ([io.lightine.tessera.emrtd.ChipAccessKey.clear]) when the read ends, whatever the ending is,
 * and not earlier, so a restart after a lost link still has it. You do not clear it, and you do not
 * use it for a second read: a new read needs a new key, which also keeps the authentication
 * attempts with it to one read per action of the person. A read started with a key that was already used (the same
 * options again, for example) ends at once, before any reader or sheet, with
 * [ChipReadEnding.KeyRejected] whose error is a
 * [io.lightine.tessera.types.errors.ChipAccessDenied] with reason `KEY_CLEARED`. The
 * low-level [io.lightine.tessera.emrtd.ChipReader.read] does not clear the key: there you own it.
 *
 * ## What the SDK does
 *
 * - Checks the MRZ key's check digits before anything else: a key that does not add up ends with
 *   [ChipReadEnding.KeyRejected] and no sheet is shown (see
 *   [io.lightine.tessera.emrtd.ChipReadOptions.allowCheckDigitMismatch]).
 * - Shows the system sheet and keeps its text current from [CoreNfcSheetTexts] (English by
 *   default). The sheet's text is set on a queue of the read's own, so a slow text change never
 *   holds up the updates, the ending or a cancel. While no document is found, the observer gets
 *   [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] again every 2 seconds, with the
 *   time waited.
 * - While no document is found, asks the system to search again every
 *   [CoreNfcReadSettings.searchRestartMillis] (4 seconds by default), with the sheet still up: on
 *   an iPhone a first contact in a weak position made the system report no document at all and
 *   end the session about 22 seconds later. The call is made on a queue of the read's own, and
 *   never once a document was reported or the read is ending.
 * - While no document was reported in the read, opens a new system session after a dead one, with
 *   the same key, attempt and restarts, at most [CoreNfcReadSettings.maxSessionRenewals] times
 *   (once by default). A dead session is one the system ends with Core NFC's code 202, or with
 *   code 203 ("busy") 2 seconds or more after its begin: on an iPhone a session that ran 18
 *   seconds with no document ended with 203. Optionally also when
 *   [CoreNfcReadSettings.sessionRenewMillis] passed since the session began (off by default). On
 *   an iPhone, a session started before the phone was on the document sometimes never reported
 *   it, and the system ended it 22 to 36 seconds later; a new session then found the document
 *   within 3 seconds in 3 cases of 4. When the last allowed session dies too, the read ends at
 *   once with [ChipReadEnding.EndedBySystem] and [NfcUnavailable.Reason.ENDED_BY_SYSTEM], with 0
 *   attempts: the system never reported the document. The observer gets
 *   [io.lightine.tessera.emrtd.ChipReadState.RenewingSession], then
 *   [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] with the time waited since the
 *   read's first session was on. The sheet closes and opens again with the default texts set
 *   anew. Between the two sessions the read waits for the system's report that the old one ended,
 *   then [CoreNfcReadSettings.waitAfterSystemEndMillis]. The read's telemetry sink gets an
 *   [io.lightine.tessera.emrtd.ChipReadEvent] with step [ChipReadStep.SESSION_RENEW] for each.
 * - When the system refuses to begin a session as busy (Core NFC's code 203 within 2 seconds of
 *   the begin), tries again after
 *   0.5, 1, 2, then every 3 seconds, for [CoreNfcReadSettings.busyRetryMillis] (6 seconds by
 *   default): the first session in [io.lightine.tessera.emrtd.ChipReadState.Starting], a renewed
 *   one in [io.lightine.tessera.emrtd.ChipReadState.RenewingSession].
 * - When the system ends the session on its own, reports Core NFC's raw code to the read's
 *   telemetry sink: an [io.lightine.tessera.emrtd.ChipReadEvent] with step
 *   [ChipReadStep.SESSION_END] and the code in
 *   [io.lightine.tessera.emrtd.ChipReadEvent.systemCode].
 * - When a document is found, waits [CoreNfcReadSettings.settleDelayMillis] before the first
 *   command, connects, and reads. Each command has its own time limit:
 *   [CoreNfcReadSettings.commandTimeoutBeforeAccessMillis] for the commands before the key is
 *   used; from the first command that uses it on (BAC's EXTERNAL AUTHENTICATE, PACE's first
 *   GENERAL AUTHENTICATE), [CoreNfcReadSettings.commandTimeoutMillis] for the commands that make
 *   the chip do public-key work and [CoreNfcReadSettings.readTimeoutMillis] for the others, such
 *   as reading a file.
 * - When the chip does not answer before the key is used (found, but with too little power to
 *   answer), reports [io.lightine.tessera.emrtd.ChipReadState.NoAnswerYet], keeps the sheet up and
 *   asks the system to find the document again, with no restart used, while the person moves it;
 *   the same session rule as for a lost link ends these tries (below). When they end and no try
 *   got past the commands before the key is used, the read ends with [ChipReadEnding.Failed] and
 *   [io.lightine.tessera.types.errors.NfcNoAnswer], and the default sheet text says "No answer
 *   from the chip. Try another position."
 * - When the link is lost (the document moved), follows [CoreNfcReadSettings.linkLoss]: by default
 *   it keeps the sheet up, waits for the document again and goes on, at most twice. It does not
 *   start again, or try again after an unanswered try, when less than 10 seconds of the system
 *   session's 60 are left.
 * - When one connection to the document has lasted [CoreNfcReadSettings.tagTimeLimitMillis] (18
 *   seconds by default) and the read is not finished, stops between two commands, asks the system
 *   to find the document again and goes on from where it stopped on the new connection, opening
 *   the chip again with the same key: the vendor's engineers have stated a limit of 20 seconds per
 *   tag connection. No restart is used and the observer gets no new state; the read's telemetry
 *   sink gets an [io.lightine.tessera.emrtd.ChipReadEvent] with step [ChipReadStep.TAG_HANDOVER].
 * - Ends the system session on every path, exactly once: with success only for
 *   [ChipReadEnding.Complete], with the ending's text as the error message otherwise. The ending
 *   is given to the observer at once; the system takes a moment more to close the sheet.
 * - One read at a time in the app: a [start] while another read is running ends at once with
 *   [ChipReadEnding.NotStarted] and [NfcUnavailable.Reason.READ_IN_PROGRESS]. A [start] right after
 *   a read ended waits in [io.lightine.tessera.emrtd.ChipReadState.Starting] until the previous
 *   system session is released (at most 5 seconds after that read ended), and for
 *   [CoreNfcReadSettings.waitAfterSystemEndMillis] after a session the system ended. [readiness]
 *   tells an app beforehand, and [addReadinessListener] when that changes.
 *
 * ## Endings from the system
 *
 * - The person taps Cancel: [ChipReadEnding.Cancelled] by [ChipReadEnding.Cancelled.By.PERSON].
 * - The session's time limit passes (60 seconds) or the system ends the session (for example the
 *   app goes to the background): [ChipReadEnding.EndedBySystem]. The 60 seconds count from the
 *   current session's begin. Before any document was reported, a dead session (code 202, or 203
 *   2 seconds or more after the begin) leads to a new session while
 *   [CoreNfcReadSettings.maxSessionRenewals] allows; the last one ends the read this way, with
 *   [NfcUnavailable.Reason.ENDED_BY_SYSTEM] whatever the code, and 0 attempts when no document
 *   was ever reported. With the defaults such a read ends after two sessions: on a device about 43
 *   seconds (22.7 + 2 + 18). A renewed session the system keeps refusing as busy ends it this way too, with
 *   [NfcUnavailable.Reason.BUSY]: the read had started.
 * - Before a document was found, and before any new session: NFC missing on the device, the
 *   entitlement or the Info.plist entries missing (`NOT_PERMITTED`, Core NFC's code 2), the device
 *   not eligible (`NOT_ELIGIBLE`, code 7), the person's answer to the app's request to use the
 *   reader still missing (`ACCESS_NOT_ANSWERED`, code 8), the radio off, or the reader busy (after
 *   the tries of [CoreNfcReadSettings.busyRetryMillis]): [ChipReadEnding.NotStarted] with the
 *   reason, and Core NFC's code in [ChipReadEnding.systemCode]. After a document was found, or
 *   after a new session, the radio off or a busy reader is [ChipReadEnding.EndedBySystem].
 * - A document found whose link cannot be set up 3 times in a row (Core NFC's connect fails with
 *   a lost tag or no answer each time): [ChipReadEnding.Failed] with
 *   [io.lightine.tessera.types.errors.NfcNoAnswer] when no try got past the first commands, the
 *   same ending as on Android.
 * - The time limit of the system session passes: [ChipReadEnding.EndedBySystem] with an
 *   [io.lightine.tessera.types.errors.NfcTimeout] whose limit is `SESSION`.
 * - A fault the SDK does not expect ends the read once with [ChipReadEnding.Failed] and
 *   [NfcUnavailable.Reason.UNEXPECTED_FAULT]; it never leaves the read as an exception.
 *
 * The app needs the same entitlement and Info.plist entries as [CoreNfcSession] (see there).
 *
 * The read lets go of the observer when it hands over the ending (with [ObserverThread.MAIN], once
 * the main queue has run that call), even though the read itself lives on until the system closes
 * its session a moment later. Its calls never overlap and come in order, with the one
 * ending last, and by default ([CoreNfcReadSettings.observerOn] is [ObserverThread.MAIN]) they come
 * on the main queue, handed over in the order the SDK made them; none comes before [start] returns.
 * With [ObserverThread.CALLER] they come from the read's own thread. An exception the observer
 * throws is ignored.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcChipRead internal constructor(
    options: ChipReadOptions,
    private val texts: CoreNfcSheetTexts,
    private val settings: CoreNfcReadSettings,
    observer: ChipReadObserver,
    private val env: CoreNfcReadEnvironment,
) : ChipReadHandle {
    private val coordinator =
        ChipReadCoordinator(
            options,
            settings.linkLoss,
            Forwarder(if (settings.observerOn == ObserverThread.MAIN) HandoffObserver(observer, env.mainQueue) else observer),
            settings.telemetry?.let { ChipReader(it) } ?: env.reader,
            clearKeyAtEnding = true,
        )
    private val tags = Channel<SessionTags>(Channel.UNLIMITED)
    private val alerts = AlertWriter()
    private val restarts = SearchRestarter(env.restartWorker()) { coordinator.isEnded }

    // Where a slow sheet text setting is reported: the read's telemetry sink.
    private val sink: TelemetrySink = settings.telemetry ?: TelemetrySinkRegistry.current

    // Guards the sessions, the sheet, the transport and the renewal decisions against the ending
    // and the delegate calls, which may come from any thread.
    private val lock = NSLock()

    // Every system session the read opened, the current one last (TES-346: a read may renew it).
    private val sessions = mutableListOf<ReaderRun>()
    private var current: ReaderRun? = null

    // Where the sheet's texts go: the current session's writer; `null` while the read renews it.
    private var sheet: SheetWriter? = null
    private var transport: CoreNfcTransport? = null

    // Whether any session of the read reported a tag: after that, never a renewal.
    private var tagReported = false
    private var renewalsUsed = 0

    // Planned handovers of the tag in the read (TES-346), under the lock.
    private var handoversMade = 0

    // The system kept refusing a session as busy until the bound: the read ended so.
    @Volatile
    private var refusedOut = false

    @Volatile
    private var job: Job? = null

    @Volatile
    private var documentSeen = false

    // What the system reported when it ended the read's session, once it did.
    @Volatile
    private var sessionEndError: NfcError? = null

    // Completed when the system reports the session's end, for a command that saw it first.
    private val sessionEnd = CompletableDeferred<NfcError>()

    /** `true` once the read's ending is decided. */
    override val isEnded: Boolean get() = coordinator.isEnded

    override fun cancel() {
        if (coordinator.cancel(ChipReadEnding.Cancelled.By.APP)) job?.cancel()
    }

    internal fun begin() {
        if (!coordinator.start()) return
        if (!env.registry.claim(this)) {
            coordinator.notStarted(NfcUnavailable.Reason.READ_IN_PROGRESS)
            return
        }
        job =
            env.scope.launch {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A fault the read does not expect ends it once, named as such, and never
                    // leaves the read's coroutine: on Kotlin/Native an uncaught one ends the process.
                    coordinator.fail(UNEXPECTED_FAULT)
                } finally {
                    stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                    // Every path above ends the read; this only guards against a path that did not.
                    if (!coordinator.isEnded) coordinator.fail(UNEXPECTED_FAULT)
                }
            }
        if (coordinator.isEnded) job?.cancel()
    }

    private suspend fun run() {
        waitForThePreviousSession()
        if (coordinator.isEnded) return
        if (!env.readingAvailable()) {
            coordinator.notStarted(NfcUnavailable.Reason.NOT_SUPPORTED)
            return
        }
        // The time waited for a document counts from the first session that is on, across renewals.
        val clock = WaitClock()
        var session = openSession(renewal = 0, clock, BusyRetry(settings.busyRetryMillis)) ?: return
        var waitingSince = clock.began ?: env.now()
        var failedConnects = 0
        // After a planned handover: until when the read waits for the document before it counts as lost.
        var handoverWaitEnds: Long? = null
        while (!coordinator.isEnded) {
            val next = awaitDocument(session, waitingSince, handoverWaitEnds ?: Long.MAX_VALUE)
            if (next is Next.HandoverMissed) {
                handoverWaitEnds = null
                if (!handoverFailed(session)) return
                waitingSince = env.now()
                continue
            }
            if (next is Next.Renew) {
                session = renew(session, next, clock) ?: return
                waitingSince = clock.began ?: env.now()
                continue
            }
            if (next is Next.Refused) {
                // Refused after the bound for the session's "on" report: tried again the same way.
                val pause = session.busy.nextPause(env.now())
                if (pause == null) {
                    endAfterRefusals(session.renewal)
                    return
                }
                delay(pause)
                session = openSession(session.renewal, clock, session.busy) ?: return
                waitingSince = clock.began ?: env.now()
                continue
            }
            val (tag, channel) = next as Next.Tag
            documentSeen = true
            coordinator.documentFound()
            delay(settings.settleDelayMillis)
            coordinator.connecting()
            val failure = connect(tag)
            val afterHandover = handoverWaitEnds != null
            handoverWaitEnds = null
            if (failure != null && failure.retryable && afterHandover) {
                // The new link after a planned handover could not be set up: a real loss from here,
                // handled once by the lost-link rules; then the read waits for the document again.
                // A failure that is not retryable (the person's Cancel, the system's end) takes the
                // general path below, with its own ending, and spends nothing.
                if (!handoverFailed(session)) return
                restartPolling(session.owned)
                waitingSince = env.now()
                continue
            }
            if (failure != null) {
                if (failure.retryable) {
                    if (++failedConnects < MAX_CONNECT_ATTEMPTS) {
                        coordinator.connectionFailed()
                        restartPolling(session.owned)
                        continue
                    }
                    // The found chip never let the link be set up: no answer, as on Android.
                    coordinator.connectsFailed(failure.error, failure.systemCode?.toInt())
                    return
                }
                endAfterConnect(failure.error, failure.systemCode)
                return
            }
            failedConnects = 0
            // The link starts with the short limit for the commands before the key is used.
            val link =
                CoreNfcTransport(channel, keepAlive = session.owned, commandTimeoutMillis = settings.effectiveTimeoutBeforeAccessMillis)
            if (!locked { if (coordinator.isEnded) false else true.also { transport = link } }) return
            val began = session.began ?: env.now()
            val gate = handoverGate(link, next.at, began)
            val outcome =
                coordinator.read(
                    gate ?: link,
                    readAgainAllowed = timeLeft(began) >= MIN_TIME_FOR_A_NEW_START_MILLIS,
                    systemEnd = { stoppedBy ->
                        // A session end the system reported while the link was lost: that is the
                        // ending. Core NFC can fail the command in flight with "session
                        // invalidated" (CLOSED) before it calls the session's end, after the
                        // person's Cancel or the time limit: wait a moment for that call, which
                        // then ends the read with its cause.
                        sessionEndError
                            ?: if (stoppedBy == CLOSED) withTimeoutOrNull(SESSION_END_WAIT_MILLIS) { sessionEnd.await() } else null
                    },
                    useTimeLimit = { kind -> link.useTimeLimit(settings.timeoutFor(kind)) },
                    handedOver = { gate?.handedOverAt != null },
                )
            stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
            if (outcome == ChipReadAttemptOutcome.ENDED) return
            if (outcome == ChipReadAttemptOutcome.HANDED_OVER) {
                // A fresh connection to the same document, asked for on the read's own terms.
                record(ChipReadEvent.tagHandover(gate?.handedOverAt ?: 0L))
                restartPolling(session.owned)
                waitingSince = env.now()
                handoverWaitEnds = waitingSince + HANDOVER_WAIT_MILLIS
                continue
            }
            // A lost link and a chip that did not answer before access go on the same way: the
            // system is asked to find the document again, while enough of the session is left.
            // The loss was allowed to follow, but too little of the session may be left by now.
            if (timeLeft(began) < MIN_TIME_FOR_A_NEW_START_MILLIS) {
                coordinator.documentNotReturned()
                return
            }
            restartPolling(session.owned)
            waitingSince = env.now()
        }
    }

    private fun timeLeft(began: Long): Long = SESSION_LIMIT_MILLIS - (env.now() - began)

    /**
     * The gate that hands the tag over before [CoreNfcReadSettings.tagTimeLimitMillis] passes on
     * it ([TagHandoverGate]), or `null` when the setting is off or the policy is not
     * [LinkLossPolicy.Resume]. The tag's time counts from its report at [tagReportedAt]. A
     * handover is taken only while fewer than [MAX_TAG_HANDOVERS] were made in the read, the read
     * has not ended, and at least [MIN_TIME_FOR_A_NEW_START_MILLIS] of the session that began at
     * [began] is left, the rule for any new start.
     */
    private fun handoverGate(
        link: CoreNfcTransport,
        tagReportedAt: Long,
        began: Long,
    ): TagHandoverGate? {
        val limit = settings.tagTimeLimitMillis
        if (limit == CoreNfcReadSettings.TAG_TIME_LIMIT_OFF || settings.linkLoss !is LinkLossPolicy.Resume) return null
        return TagHandoverGate(
            link,
            tagMillis = { env.now() - tagReportedAt },
            limitMillis = limit,
            heavyReserveMillis = minOf(settings.commandTimeoutMillis, limit / 2),
        ) {
            locked {
                val allowed =
                    handoversMade < MAX_TAG_HANDOVERS && !coordinator.isEnded &&
                        timeLeft(began) >= MIN_TIME_FOR_A_NEW_START_MILLIS
                if (allowed) handoversMade++
                allowed
            }
        }
    }

    /**
     * The document did not come back after a planned handover, or its new link could not be set
     * up: the lost-link rules from here, unless too little of the session is left for a new start.
     *
     * @return `true` when the read goes on and waits for the document.
     */
    private fun handoverFailed(session: ReaderRun): Boolean {
        val began = session.began ?: env.now()
        if (timeLeft(began) < MIN_TIME_FOR_A_NEW_START_MILLIS) {
            coordinator.documentNotReturned()
            return false
        }
        return coordinator.handoverFailed()
    }

    /**
     * In the Starting state: the previous read's session must be released (at most
     * [RELEASE_WAIT_LIMIT_MILLIS] after that read ended), and the system rested until the time
     * the registry holds: set by the read that saw the system's last end, with its own
     * [CoreNfcReadSettings.waitAfterSystemEndMillis]. The same time [readiness] counts, so a
     * [ChipReadReadiness.NotYet] is the wait a read started now takes.
     */
    private suspend fun waitForThePreviousSession() {
        val deadline = minOf(env.now() + RELEASE_WAIT_LIMIT_MILLIS, env.registry.releaseDeadline() ?: Long.MAX_VALUE)
        while (env.registry.isReleasing() && env.now() < deadline && !coordinator.isEnded) delay(RELEASE_POLL_MILLIS)
        val readyAt = env.registry.readyAt() ?: return
        val wait = readyAt - env.now()
        if (wait > 0) delay(wait)
    }

    /**
     * Opens a system session, sets its first text and begins it, then waits for the system to say
     * it is on ([ReaderSessionEvents.onActive]), at most [ACTIVE_WAIT_MILLIS]. When the system
     * refuses it as busy, tries again with growing pauses for [CoreNfcReadSettings.busyRetryMillis]
     * (TES-346), with no new state for the app; then the read ends: [ChipReadEnding.NotStarted]
     * for the first session, [ChipReadEnding.EndedBySystem] for a renewed one (the read had
     * started). Publishes [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] once the
     * session is on, with the time on [clock].
     *
     * @return The session, or `null` when the read has ended.
     */
    private suspend fun openSession(
        renewal: Int,
        clock: WaitClock,
        busy: BusyRetry,
    ): ReaderRun? {
        while (true) {
            val session = ReaderRun(renewal, busy)
            val writer = SheetWriter(session.owned, env.sheetWorker(), env.now, ::reportSlowText)
            session.writer = writer
            val open =
                locked {
                    if (coordinator.isEnded) {
                        false
                    } else {
                        sessions += session
                        current = session
                        sheet = writer
                        true
                    }
                }
            if (!open) return null
            // The first text goes on the sheet before the session begins, as Core NFC allows; a text
            // change while the session polls is the call that did not return on a device (TES-346).
            alerts.newSheet()
            alerts.show(textFor(coordinator.waitingUpdate(clock.waited(env.now()))))
            writer.awaitIdle(FIRST_TEXT_WAIT_MILLIS)
            if (!session.owned.begin()) return null
            session.began = env.now()
            // The system says the session is on, or ends it. Without either within the bound, the
            // session counts as on: the delegate call is optional in Core NFC's protocol.
            val signal =
                withTimeoutOrNull(ACTIVE_WAIT_MILLIS) {
                    select<Signal?> {
                        session.active.onAwait { null }
                        session.signal.onAwait { it }
                    }
                }
            if (signal !is Signal.Refused) {
                // A session that died at once (code 202) goes on to its renewal with no waiting state.
                if (signal == null) {
                    val now = env.now()
                    coordinator.waiting(now - clock.mark(now))
                }
                return session
            }
            val pause = busy.nextPause(env.now())
            if (pause == null) {
                endAfterRefusals(renewal)
                return null
            }
            delay(pause)
        }
    }

    /** The system kept refusing a session as busy for [CoreNfcReadSettings.busyRetryMillis]. */
    private fun endAfterRefusals(renewal: Int) {
        refusedOut = true
        env.registry.systemEnded(env.now(), settings.waitAfterSystemEndMillis)
        val busy = NfcUnavailable(NfcUnavailable.Reason.BUSY)
        // Only Core NFC's code 203 ("system is busy") leads here.
        val code = CoreNfcErrorCode.SYSTEM_IS_BUSY.toInt()
        if (renewal == 0) coordinator.notStarted(NfcUnavailable.Reason.BUSY, code) else coordinator.endedBySystem(busy, code)
    }

    /**
     * Closes [old] (when the system has not already) and opens a new session, within the same read
     * (TES-346). Between the two: the system's report that [old] ended, at most
     * [RENEW_END_WAIT_MILLIS], then [CoreNfcReadSettings.waitAfterSystemEndMillis] of rest, the
     * same rest a new read takes after a session the system ended.
     *
     * @return The new session, or `null` when the read has ended.
     */
    private suspend fun renew(
        old: ReaderRun,
        renewal: Next.Renew,
        clock: WaitClock,
    ): ReaderRun? {
        val decided = env.now()
        // The texts no longer go to the old sheet: a text set while it closes could hold up its end.
        locked { sheet = null }
        val update = coordinator.renewingSession(renewal.number)
        if (renewal.systemCode == null) {
            // The read ends the old session itself, on the sheet's queue, never here.
            old.writer.end(finalText = null, errorMessage = textFor(update))
            val echoed = withTimeoutOrNull(RENEW_END_WAIT_MILLIS) { old.ended.await() } != null
            if (!echoed) {
                // A call stuck on the sheet's queue: end the session from elsewhere, and go on.
                env.scope.launch { old.writer.endNow() }
            }
        }
        if (coordinator.isEnded) return null
        delay(settings.waitAfterSystemEndMillis)
        val next = openSession(renewal.number, clock, BusyRetry(settings.busyRetryMillis))
        val outcome =
            when {
                next != null -> ChipReadStepOutcome.SUCCEEDED
                refusedOut -> ChipReadStepOutcome.FAILED
                else -> null
            }
        if (outcome != null) record(ChipReadEvent.sessionRenew(renewal.number, renewal.systemCode?.toInt(), outcome, env.now() - decided))
        return next
    }

    /**
     * Waits for one ISO 7816 tag of [session], reporting the time waited every 2 seconds since
     * [since], asking the system to search again every [CoreNfcReadSettings.searchRestartMillis]
     * while no tag was reported, and renewing the session after
     * [CoreNfcReadSettings.sessionRenewMillis] when no tag was reported in the read. After a
     * planned handover, [giveUpAt] ends the wait with [Next.HandoverMissed].
     */
    private suspend fun awaitDocument(
        session: ReaderRun,
        since: Long,
        giveUpAt: Long = Long.MAX_VALUE,
    ): Next {
        val period = settings.searchRestartMillis
        var nextReport = since + ((env.now() - since) / WAITING_REPORT_MILLIS + 1) * WAITING_REPORT_MILLIS
        var search = restarts.searchBegan()
        var nextRestart = if (period > 0) env.now() + period else Long.MAX_VALUE
        val renewOn = settings.sessionRenewMillis > 0 && locked { !tagReported && renewalsUsed < settings.maxSessionRenewals }
        var renewAt = if (renewOn) (session.began ?: env.now()) + settings.sessionRenewMillis else Long.MAX_VALUE
        while (true) {
            val wakeAt = minOf(nextReport, nextRestart, renewAt, giveUpAt)
            val got = awaitNext(session, (wakeAt - env.now()).coerceAtLeast(0))
            if (got == null) {
                val now = env.now()
                if (now >= giveUpAt) return Next.HandoverMissed
                if (now >= nextReport) {
                    coordinator.waiting(now - since)
                    nextReport += WAITING_REPORT_MILLIS
                }
                if (now >= renewAt) {
                    val number = takeEarlyRenewal(session)
                    if (number > 0) return Next.Renew(number, systemCode = null)
                    renewAt = Long.MAX_VALUE
                }
                if (now >= nextRestart) {
                    // Posted, never run here: a call that does not return holds up nothing.
                    restarts.restartWhileSearching(search, session.owned)
                    nextRestart = now + period
                }
                continue
            }
            if (got is Signal.Renew) return Next.Renew(got.number, got.systemCode)
            if (got is Signal.Refused) return Next.Refused
            val reported = got as SessionTags
            if (reported.session !== session) continue
            restarts.tagReported()
            val tag = reported.tags.singleOrNull()
            val channel = tag?.iso7816
            // More than one tag, or not an ISO 7816 tag: keep looking.
            if (tag == null || channel == null) {
                restartPolling(session.owned)
                search = restarts.searchBegan()
                if (period > 0) nextRestart = env.now() + period
                continue
            }
            return Next.Tag(tag, channel, reported.at)
        }
    }

    /**
     * The next tag report or session signal, or `null` when [waitMillis] passed first. One select
     * with a timer of its own, never a receive inside a time limit: a tag reported at the instant
     * the limit passes is then never taken from the channel and lost (TES-346).
     */
    private suspend fun awaitNext(
        session: ReaderRun,
        waitMillis: Long,
    ): Any? =
        coroutineScope {
            val due = CompletableDeferred<Unit>()
            val timer =
                launch {
                    delay(waitMillis)
                    due.complete(Unit)
                }
            try {
                select<Any?> {
                    tags.onReceive { it }
                    session.signal.onAwait { it }
                    due.onAwait { null }
                }
            } finally {
                timer.cancel()
            }
        }

    /**
     * Decides, once, that the read ends [session] itself to open a new one: only while it is the
     * current session, no tag was reported in the read, the read has not ended, the system has not
     * ended the session, and a renewal is left.
     *
     * @return The renewal's number, or 0 when it may not.
     */
    private fun takeEarlyRenewal(session: ReaderRun): Int {
        val number =
            locked {
                val allowed =
                    session === current && !session.renewing && !tagReported && !coordinator.isEnded &&
                        !session.signal.isCompleted && session.owned.isOpen && renewalsUsed < settings.maxSessionRenewals
                if (!allowed) return@locked 0
                session.renewing = true
                ++renewalsUsed
            }
        // No new search of the old session from here on.
        if (number > 0) restarts.tagReported()
        return number
    }

    private suspend fun connect(tag: DetectedTag): ConnectFailure? {
        val done = CompletableDeferred<ConnectFailure?>()
        tag.connect { done.complete(it) }
        return done.await()
    }

    private fun restartPolling(session: OwnedReaderSession) {
        // Tags reported before the restart are no longer valid.
        while (tags.tryReceive().isSuccess) Unit
        restarts.restartNow(session)
    }

    /**
     * Ends the read after a connect that failed for good. A connect can fail because the session is
     * ending (the person tapped Cancel, the system ended it): Core NFC may report that to the
     * connect before it calls the session's end, so those causes get the same ending the session
     * end would give, whichever of the two comes first.
     */
    private fun endAfterConnect(
        error: NfcError,
        systemCode: Long?,
    ) {
        val code = systemCode?.toInt()
        when {
            error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CANCELLED_BY_USER -> {
                coordinator.cancel(ChipReadEnding.Cancelled.By.PERSON)
            }

            // The session's time limit: the system's ending. A chip that did not answer the
            // connect (code 101, limit COMMAND) is retryable and never comes here.
            (error is NfcTimeout && error.limit == NfcTimeout.Limit.SESSION) ||
                (error is NfcUnavailable && error.reason in SYSTEM_REASONS) -> {
                coordinator.endedBySystem(error, code)
            }

            else -> {
                coordinator.fail(error, code)
            }
        }
    }

    private fun stopTransport(reason: NfcError) {
        val link =
            locked {
                val held = transport
                transport = null
                held
            }
        link?.stop(reason)
    }

    /** A tag report of [session]: dropped when it is not the read's current session any more. */
    private fun tagsDetected(
        session: ReaderRun,
        found: List<DetectedTag>,
    ) {
        // Decided with the renewal under one lock: a tag either stops every renewal, or belongs to
        // a session the read is closing and is dropped.
        val take =
            locked {
                if (session !== current || session.renewing) {
                    false
                } else {
                    tagReported = true
                    true
                }
            }
        if (!take) return
        // A usable document counts as found from the moment the system reports it, so a session
        // end right after is the system's ending, never "not started".
        if (found.singleOrNull()?.iso7816 != null) documentSeen = true
        // Before the read can take the tag: no periodic restart is run after this point.
        restarts.tagReported()
        tags.trySend(SessionTags(session, found, env.now()))
    }

    /** What a session's end means for the read. */
    private enum class EndKind { ECHO, RENEW, REFUSED, ENDING }

    /**
     * Whether a system end of [session] with Core NFC's code [systemCode] is the end of a dead
     * session, when no document was reported: code 202 always; code 203 ("busy") when the session
     * ran [REFUSAL_WINDOW_MILLIS] or more since its begin. On a device a session that ran 18
     * seconds with no document ended with 203, while a refused begin ended within about 4
     * milliseconds. The time is what the read can know: the system's "on" report did not tell the
     * two apart on the device.
     */
    private fun isDeadSessionEnd(
        session: ReaderRun,
        systemCode: Long?,
    ): Boolean =
        when (systemCode) {
            CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY -> true
            CoreNfcErrorCode.SYSTEM_IS_BUSY -> session.began?.let { env.now() - it >= REFUSAL_WINDOW_MILLIS } == true
            else -> false
        }

    /**
     * The system ended [session]: the read's ending, unless the SDK ended it, the read renews it
     * (a dead session, see [isDeadSessionEnd], before any tag and while a renewal is left), or the
     * system refused its begin as busy and the read tries again.
     */
    private fun systemEnded(
        session: ReaderRun,
        reported: NfcError,
        systemCode: Long?,
    ) {
        session.owned.systemEnd()
        session.ended.complete(Unit)
        val deadCode = isDeadSessionEnd(session, systemCode)
        var dead = false
        var number = 0
        val kind =
            locked {
                val beforeAnyTag = !tagReported && !coordinator.isEnded
                dead = deadCode && !tagReported
                when {
                    session !== current || session.renewing || session.owned.endedBySdk -> {
                        EndKind.ECHO
                    }

                    beforeAnyTag && dead && renewalsUsed < settings.maxSessionRenewals -> {
                        number = ++renewalsUsed
                        EndKind.RENEW
                    }

                    beforeAnyTag && !dead && reported is NfcUnavailable && reported.reason == NfcUnavailable.Reason.BUSY &&
                        settings.busyRetryMillis > 0 -> {
                        EndKind.REFUSED
                    }

                    else -> {
                        EndKind.ENDING
                    }
                }
            }
        if (kind == EndKind.ECHO) return
        // A dead session's end is the system's ending, whatever its code: never "busy" or "not started".
        val error = if (dead) NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM) else reported
        reportSessionEnd(systemCode, session.began)
        // Not the read's ending: its loop renews the session, or tries the begin again.
        if (kind == EndKind.RENEW) {
            session.signal.complete(Signal.Renew(number, systemCode))
            return
        }
        if (kind == EndKind.REFUSED) {
            session.signal.complete(Signal.Refused)
            return
        }
        sessionEndError = error
        sessionEnd.complete(error)
        restarts.stop()
        if (error is NfcTimeout || (error is NfcUnavailable && error.reason in RESTING_REASONS)) {
            env.registry.systemEnded(env.now(), settings.waitAfterSystemEndMillis)
        }
        val renewed = locked { renewalsUsed > 0 }
        val code = systemCode?.toInt()
        when {
            error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CANCELLED_BY_USER -> {
                coordinator.cancel(ChipReadEnding.Cancelled.By.PERSON)
            }

            error is NfcUnavailable && error.reason in NOT_STARTED_REASONS && !documentSeen && !renewed -> {
                coordinator.notStarted(error.reason, code)
            }

            error is NfcUnavailable && error.reason in SYSTEM_REASONS -> {
                coordinator.endedBySystem(error, code)
            }

            error is NfcTimeout -> {
                coordinator.endedBySystem(error, code)
            }

            // After a renewal the read had started: the system's ending, not "not started".
            error is NfcUnavailable && error.reason in NOT_STARTED_REASONS && !documentSeen -> {
                coordinator.endedBySystem(error, code)
            }

            else -> {
                coordinator.fail(error, code)
            }
        }
        stopTransport(error)
        job?.cancel()
    }

    /** A session of the read was released: the registry hears of it once every session is. */
    private fun sessionReleased() {
        if (locked { sessions.all { it.owned.isReleased } }) env.registry.released(this)
    }

    /**
     * What happens when the ending is decided, before the app hears of it. The sheet's last text and
     * the session's end go to the sheet's queue, after the texts before them; when they are not done
     * [ENDING_WAIT_MILLIS] later (a call stuck), the session is ended from here anyway.
     */
    private fun finish(ending: ChipReadEnding) {
        alerts.stop()
        restarts.stop()
        val (session, writer, unreleased) = locked { Triple(current?.owned, sheet, sessions.any { !it.owned.isReleased }) }
        env.registry.ended(this, unreleased, env.now())
        stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
        if (session != null && writer != null && session.isOpen) {
            val text = textForEnding(ending)
            if (ending is ChipReadEnding.Complete) writer.end(finalText = text, errorMessage = null) else writer.end(null, text)
            if (!writer.isEndingDone) {
                env.scope.launch {
                    delay(ENDING_WAIT_MILLIS)
                    writer.endNow()
                }
            }
        }
        job?.cancel()
    }

    /** A sheet text setting took longer than [SheetWriter.SLOW_SET_MILLIS]: a fact for diagnosis. */
    private fun reportSlowText(
        durationMillis: Long,
        returned: Boolean,
    ) {
        val outcome = if (returned) ChipReadStepOutcome.SUCCEEDED else ChipReadStepOutcome.FAILED
        record(ChipReadEvent.platformStep(ChipReadStep.SHEET_TEXT, outcome, durationMillis))
    }

    /**
     * The system ended a session on its own (not the echo of the SDK's own end): the raw Core NFC
     * code as a fact for diagnosis, with the time since the session began.
     */
    private fun reportSessionEnd(
        systemCode: Long?,
        began: Long?,
    ) {
        record(ChipReadEvent.sessionEnd(systemCode?.toInt(), began?.let { env.now() - it } ?: 0L))
    }

    private fun record(event: ChipReadEvent) {
        try {
            sink.record(event)
        } catch (e: Throwable) {
            // Ignored on purpose: telemetry is never part of the read.
        }
    }

    private fun textForEnding(ending: ChipReadEnding): String =
        try {
            texts.textForEnding(ending)
        } catch (e: Throwable) {
            CoreNfcDefaultSheetTexts.textForEnding(ending)
        }

    private fun textFor(update: ChipReadUpdate): String =
        try {
            texts.textFor(update)
        } catch (e: Throwable) {
            CoreNfcDefaultSheetTexts.textFor(update)
        }

    /**
     * One system session of the read: its own delegate calls, so a late call of a session the read
     * closed is told apart from the current one's.
     */
    private inner class ReaderRun(
        val renewal: Int,
        val busy: BusyRetry,
    ) {
        val events =
            object : ReaderSessionEvents {
                override fun onActive() {
                    active.complete(Unit)
                }

                override fun onTagsDetected(tags: List<DetectedTag>) = tagsDetected(this@ReaderRun, tags)

                override fun onInvalidated(
                    error: NfcError,
                    systemCode: Long?,
                ) = systemEnded(this@ReaderRun, error, systemCode)
            }

        // Keeps the read alive while the system session lives, whatever the app drops.
        val owned = OwnedReaderSession(env.openSession(events, settings.polling), keeper = this@CoreNfcChipRead) { sessionReleased() }
        lateinit var writer: SheetWriter

        // When the session began; `null` until it did.
        @Volatile
        var began: Long? = null

        // The system said the session is on.
        val active = CompletableDeferred<Unit>()

        // A system end that does not end the read: a renewal or a busy refusal.
        val signal = CompletableDeferred<Signal>()

        // The system reported the session's end, whoever asked for it.
        val ended = CompletableDeferred<Unit>()

        // Under the read's lock: the read ends this session to open a new one.
        var renewing = false
    }

    private class SessionTags(
        val session: Any,
        val tags: List<DetectedTag>,
        // When the system reported them: a tag's connection time counts from here.
        val at: Long,
    )

    /** What a system end that does not end the read asks the read's loop to do. */
    private sealed class Signal {
        class Renew(
            val number: Int,
            val systemCode: Long?,
        ) : Signal()

        object Refused : Signal()
    }

    /** What the wait for a document leads to. */
    private sealed class Next {
        class Tag(
            val tag: DetectedTag,
            val channel: Iso7816Channel,
            val at: Long,
        ) : Next() {
            operator fun component1() = tag

            operator fun component2() = channel
        }

        class Renew(
            val number: Int,
            val systemCode: Long?,
        ) : Next()

        object Refused : Next()

        /** No document within the short wait after a planned handover. */
        object HandoverMissed : Next()
    }

    /** The time waited for a document, from the first session of the read that was on. */
    private class WaitClock {
        var began: Long? = null
            private set

        /** The time waited at [now]: 0 before the first session was on. */
        fun waited(now: Long): Long = began?.let { now - it } ?: 0L

        /** A session is on at [now]: the start of the wait, the first time. */
        fun mark(now: Long): Long = began ?: now.also { began = it }
    }

    /** The pauses between tries to begin a session the system refused as busy, within [budgetMillis]. */
    private class BusyRetry(
        private val budgetMillis: Long,
    ) {
        private var since: Long? = null
        private var tries = 0

        /** The pause before the next try, or `null` when the bound has passed. */
        fun nextPause(now: Long): Long? {
            val first = since ?: now.also { since = it }
            val end = first + budgetMillis
            if (budgetMillis <= 0 || now >= end) return null
            val pause = BUSY_RETRY_PAUSES_MILLIS.getOrElse(tries) { BUSY_RETRY_PAUSES_MILLIS.last() }
            tries++
            return minOf(pause, end - now)
        }
    }

    /**
     * Hands the coordinator's calls to the app, after the SDK's own work on each. It lets go of the
     * app's observer at the ending: the read itself stays alive until the system reports the
     * session's end (a moment later), and must not keep the app's observer, and what it holds,
     * alive with it.
     */
    private inner class Forwarder(
        app: ChipReadObserver,
    ) : ChipReadObserver {
        // Only the coordinator's one drainer calls in, one call at a time.
        private var app: ChipReadObserver? = app

        override fun onUpdate(update: ChipReadUpdate) {
            alerts.show(textFor(update))
            app?.onUpdate(update)
        }

        override fun onEnding(ending: ChipReadEnding) {
            val heldApp = app
            app = null
            finish(ending)
            heldApp?.onEnding(ending)
        }
    }

    /**
     * Asks for the sheet's text only when it changes, and at most every [ALERT_INTERVAL_MILLIS]; a
     * text held back is asked for when the interval has passed, unless a newer one replaced it. The
     * [SheetWriter] then sets it on its own queue.
     */
    private inner class AlertWriter {
        private val alertLock = NSLock()
        private var shown: String? = null
        private var shownAt = Long.MIN_VALUE
        private var pending: String? = null
        private var flush: Job? = null
        private var stopped = false

        fun show(text: String) {
            val session = locked { sheet } ?: return
            alertLock.lock()
            try {
                if (stopped || text == pending) return
                if (text == shown) {
                    // Back to the text on the sheet before the held one was set: nothing to change.
                    pending = null
                    return
                }
                val now = env.now()
                if (shownAt == Long.MIN_VALUE || now - shownAt >= ALERT_INTERVAL_MILLIS) {
                    write(session, text, now)
                } else {
                    pending = text
                    if (flush == null) {
                        val wait = shownAt + ALERT_INTERVAL_MILLIS - now
                        flush =
                            env.scope.launch {
                                delay(wait)
                                flushPending(session)
                            }
                    }
                }
            } finally {
                alertLock.unlock()
            }
        }

        /** A new sheet: nothing is on it yet, and a text held for the old one is dropped. */
        fun newSheet() {
            alertLock.lock()
            try {
                shown = null
                shownAt = Long.MIN_VALUE
                pending = null
                flush?.cancel()
                flush = null
            } finally {
                alertLock.unlock()
            }
        }

        private fun flushPending(session: SheetWriter) {
            alertLock.lock()
            try {
                flush = null
                val text = pending ?: return
                if (!stopped) write(session, text, env.now())
            } finally {
                alertLock.unlock()
            }
        }

        // Under [alertLock], so two writes never cross.
        private fun write(
            session: SheetWriter,
            text: String,
            now: Long,
        ) {
            pending = null
            shown = text
            shownAt = now
            session.show(text)
        }

        fun stop() {
            alertLock.lock()
            try {
                stopped = true
                pending = null
                flush?.cancel()
                flush = null
            } finally {
                alertLock.unlock()
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Starts a read. */
    public companion object {
        /**
         * Whether this device can read chips right now, before a read starts: the same question,
         * with the same name and return type, as `NfcChipRead.availability(context)` and
         * `NfcReaderMode.availability(context)` on Android.
         *
         * It covers what Core NFC can tell before a session: whether the device can read tags at
         * all (`NFCReaderSession.readingAvailable`). Core NFC has no call for whether the radio is
         * switched off, whether the app's entitlement and Info.plist entries are right, whether
         * the device is eligible, or whether the person has answered the app's request to use the
         * reader: each of these shows only when a read starts, as [ChipReadEnding.NotStarted] with
         * its reason.
         *
         * @return `null` when reading is available; otherwise [NfcUnavailable.Reason.NOT_SUPPORTED]:
         *   this device cannot read chips.
         * @since 1.0.0
         */
        public fun availability(): NfcUnavailable.Reason? = availability(::platformReadingAvailable)

        internal fun availability(readingAvailable: () -> Boolean): NfcUnavailable.Reason? =
            if (readingAvailable()) null else NfcUnavailable.Reason.NOT_SUPPORTED

        /**
         * Whether a read started now is expected to begin its system session at once: for a
         * "read the chip" button. [ChipReadReadiness.Unavailable] as [availability] reports;
         * [ChipReadReadiness.ReadInProgress] while a read of this app runs;
         * [ChipReadReadiness.NotYet] while a new read would wait in
         * [io.lightine.tessera.emrtd.ChipReadState.Starting]; otherwise [ChipReadReadiness.Ready].
         *
         * The rule, as the SDK applies it: after the system ended a session on its own, or refused
         * one as busy, the SDK notes when it saw that end (the system's report of it), and treats
         * the system as ready [CoreNfcReadSettings.waitAfterSystemEndMillis] of the read that saw
         * it later; after a session the SDK ended itself, as soon as the system reported it closed,
         * and at most 5 seconds after the read ended. [ChipReadReadiness.NotYet.remainingMillis]
         * is the longest wait as the SDK counts it then: while it waits for the system's report
         * that the last session closed, it counts the 5-second bound, and the value turns
         * [ChipReadReadiness.Ready] as soon as the report comes, often sooner (on a device about
         * 2.7 seconds after the read ended, with 5000 reported). It is the SDK's own count, not a promise from
         * the system: a start is always allowed, waits the same time in
         * [io.lightine.tessera.emrtd.ChipReadState.Starting], and tries again when the system
         * still refuses ([CoreNfcReadSettings.busyRetryMillis]).
         *
         * The rest after a system end is the one set by the read that saw that end, with its own
         * [CoreNfcReadSettings.waitAfterSystemEndMillis]; a later read waits until the same time
         * whatever its own setting, so [ChipReadReadiness.NotYet.remainingMillis] is the wait a
         * read started now takes.
         *
         * [ChipReadReadiness.Unavailable] covers only what [availability] covers. Core NFC gives
         * no signal while the app runs that the radio was switched off system-wide: each call
         * asks [availability] afresh, and a radio that is off shows when a read starts, as
         * [ChipReadEnding.NotStarted] with [NfcUnavailable.Reason.DISABLED].
         *
         * @return The readiness now.
         * @since 1.0.0
         */
        public fun readiness(): ChipReadReadiness = CoreNfcReadEnvironment.process.readiness.current()

        /**
         * Registers [listener] to hear when [readiness] changes kind, on the main queue: a read of
         * this app starting ([ChipReadReadiness.ReadInProgress]), ending (then
         * [ChipReadReadiness.NotYet] while a new read would wait), and the wait running out
         * ([ChipReadReadiness.Ready], told with no other event). Not called at registration:
         * register, then ask [readiness]. Core NFC has no signal for the radio being switched on
         * or off, so no call comes for that. The SDK holds the listener until
         * [ChipReadReadinessRegistration.remove].
         *
         * @param listener Called with each new value.
         * @return The registration, to remove the listener.
         * @since 1.0.0
         */
        public fun addReadinessListener(listener: ChipReadReadinessListener): ChipReadReadinessRegistration =
            CoreNfcReadEnvironment.process.readiness.add(listener)

        /**
         * Starts a read with the built-in English sheet texts and the default settings. Returns at
         * once; the read runs in the background.
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param observer Gets every update and exactly one ending.
         * @return The read, to cancel it.
         * @since 1.0.0
         */
        public fun start(
            options: ChipReadOptions,
            observer: ChipReadObserver,
        ): ChipReadHandle = start(options, observer, CoreNfcDefaultSheetTexts, CoreNfcReadSettings())

        /**
         * Starts a read with your sheet texts and the default settings. Returns at once.
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param observer Gets every update and exactly one ending.
         * @param texts The sheet's texts, in your app's language.
         * @return The read, to cancel it.
         * @since 1.0.0
         */
        public fun start(
            options: ChipReadOptions,
            observer: ChipReadObserver,
            texts: CoreNfcSheetTexts,
        ): ChipReadHandle = start(options, observer, texts, CoreNfcReadSettings())

        /**
         * Starts a read. Returns at once; the read runs in the background. The parameters come in
         * the order of the Android start: what to read, then the observer, then the optional
         * texts and settings, settings last.
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param observer Gets every update and exactly one ending.
         * @param texts The sheet's texts, in your app's language; [CoreNfcDefaultSheetTexts] for
         *   the built-in English ones.
         * @param settings What to do after a lost link, and the time limits.
         * @return The read, to cancel it.
         * @since 1.0.0
         */
        public fun start(
            options: ChipReadOptions,
            observer: ChipReadObserver,
            texts: CoreNfcSheetTexts,
            settings: CoreNfcReadSettings,
        ): ChipReadHandle = start(options, observer, texts, settings, CoreNfcReadEnvironment.process)

        internal fun start(
            options: ChipReadOptions,
            observer: ChipReadObserver,
            texts: CoreNfcSheetTexts,
            settings: CoreNfcReadSettings,
            env: CoreNfcReadEnvironment,
        ): CoreNfcChipRead = CoreNfcChipRead(options, texts, settings, observer, env).also { it.begin() }

        /**
         * The system session's time limit: 60 seconds. Core NFC's headers state it only for NDEF
         * and VAS reader sessions (`NFCNDEFReaderSession.h`, `NFCVASReaderSession.h`); for tag
         * reader sessions the figure comes from the vendor's engineers, and devices behave so.
         */
        internal const val SESSION_LIMIT_MILLIS = 60_000L

        /** No new start after a lost link when less than this is left of the session. */
        internal const val MIN_TIME_FOR_A_NEW_START_MILLIS = 10_000L

        /**
         * The most planned handovers of the tag in one read: with 18-second connections, three
         * cover a read up to the session's 60 seconds, where the rule above stops them anyway.
         */
        internal const val MAX_TAG_HANDOVERS = 3

        /**
         * How long the read waits for the document after a planned handover before the loss rules
         * apply: one waiting report's period. A document still on the phone is found again well
         * within it (0.6 seconds in the device-shaped simulation).
         */
        internal const val HANDOVER_WAIT_MILLIS = 2_000L

        /** How often the waiting state repeats with the time waited. */
        internal const val WAITING_REPORT_MILLIS = ChipReadCoordinator.WAITING_REPORT_MILLIS

        /** The longest a new read waits for the previous system session to be released, from that read's ending. */
        internal const val RELEASE_WAIT_LIMIT_MILLIS = 5_000L

        private const val RELEASE_POLL_MILLIS = 25L

        /** The shortest time between two texts on the sheet. */
        internal const val ALERT_INTERVAL_MILLIS = 100L

        /**
         * How long a command that failed with "session invalidated" waits for the system's report of
         * the session's end, which names the cause (the person's Cancel, the time limit). Without
         * it, the read ends as [ChipReadEnding.EndedBySystem] with the closed-link error.
         */
        internal const val SESSION_END_WAIT_MILLIS = 1_000L

        /**
         * How long the ending waits for the sheet's queue to set the last text and end the session;
         * then it ends the session itself, with the ending's error message, or with success and
         * whatever text the sheet shows.
         */
        internal const val ENDING_WAIT_MILLIS = 1_000L

        /** How long the start waits for the first text to be set before the session begins. */
        internal const val FIRST_TEXT_WAIT_MILLIS = 1_000L

        /**
         * How long a begun session waits for the system to say it is on, or to refuse it, before it
         * counts as on: the delegate call is optional in Core NFC's protocol, and a refusal came at
         * once on a device.
         */
        internal const val ACTIVE_WAIT_MILLIS = 1_000L

        /**
         * How long a renewal waits for the system's report that the session the read ended is
         * closed, before it goes on anyway: the same order as [ENDING_WAIT_MILLIS], with room for
         * a slow close.
         */
        internal const val RENEW_END_WAIT_MILLIS = 2_000L

        /**
         * A system end with code 203 ("busy") is a refused begin only this soon after the begin;
         * later it is a dead session's end. On a device refusals came within about 4
         * milliseconds, and a dead session ran 7 to 18 seconds before its 203.
         */
        internal const val REFUSAL_WINDOW_MILLIS = 2_000L

        /** The pauses before each new try to begin a session refused as busy; the last repeats. */
        internal val BUSY_RETRY_PAUSES_MILLIS = listOf(500L, 1_000L, 2_000L, 3_000L)

        private val CLOSED = NfcUnavailable(NfcUnavailable.Reason.CLOSED)

        /** The ending's error for a fault the read did not expect. */
        private val UNEXPECTED_FAULT = NfcUnavailable(NfcUnavailable.Reason.UNEXPECTED_FAULT)

        /** Failed connects in a row (a lost link, no answer) after which the read ends. */
        private const val MAX_CONNECT_ATTEMPTS = ChipReadCoordinator.MAX_CONNECT_ATTEMPTS

        /** Ends after which the next read waits before it begins a session. */
        private val RESTING_REASONS = setOf(NfcUnavailable.Reason.ENDED_BY_SYSTEM, NfcUnavailable.Reason.BUSY)

        /** Session-end reasons that mean the read could not start, before a document was found. */
        private val NOT_STARTED_REASONS =
            setOf(
                NfcUnavailable.Reason.NOT_SUPPORTED,
                NfcUnavailable.Reason.NOT_PERMITTED,
                NfcUnavailable.Reason.NOT_ELIGIBLE,
                NfcUnavailable.Reason.ACCESS_NOT_ANSWERED,
                NfcUnavailable.Reason.DISABLED,
                NfcUnavailable.Reason.BUSY,
            )

        /** Session-end reasons that are the system's ending once a document was found. */
        private val SYSTEM_REASONS =
            setOf(
                NfcUnavailable.Reason.ENDED_BY_SYSTEM,
                NfcUnavailable.Reason.DISABLED,
                NfcUnavailable.Reason.BUSY,
                NfcUnavailable.Reason.CLOSED,
            )
    }
}

/** What a [CoreNfcChipRead] runs on: the platform by default, fakes and a test clock in tests. */
internal class CoreNfcReadEnvironment(
    val readingAvailable: () -> Boolean,
    // Opens the system session, polling as the read's settings say.
    val openSession: (ReaderSessionEvents, CoreNfcPolling) -> ReaderSessionPort,
    val scope: CoroutineScope,
    val now: () -> Long,
    val registry: ChipReadRegistry,
    val reader: ChipReader,
    // Runs a block later on the main queue, first in, first out; tests run it at once or queue it.
    val mainQueue: (() -> Unit) -> Unit = { it() },
    // A new worker for one read's sheet calls; tests run them at once or on a queue of their own.
    val sheetWorker: () -> SerialWorker = { InlineWorker },
    // A new worker for one read's restartPolling calls; the same for tests.
    val restartWorker: () -> SerialWorker = { InlineWorker },
) {
    /** The readiness of a new read in this environment, and its listeners. */
    val readiness: ReadinessWatch by lazy { ReadinessWatch(registry, readingAvailable, now, scope, mainQueue) }

    companion object {
        private val clock = TimeSource.Monotonic.markNow()

        /** The real platform, one registry for the whole process. */
        val process: CoreNfcReadEnvironment by lazy {
            CoreNfcReadEnvironment(
                readingAvailable = ::platformReadingAvailable,
                openSession = ::PlatformReaderSession,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                now = { clock.elapsedNow().inWholeMilliseconds },
                registry = ChipReadRegistry(),
                reader = ChipReader(),
                mainQueue = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
                sheetWorker = ::newSheetQueueWorker,
                restartWorker = ::newSearchQueueWorker,
            )
        }
    }
}

/**
 * The reads of one process: at most one live read, the reads whose system sessions are still being
 * released (and since when), and when the system last ended a session on its own. Tells
 * [onChange] after each change, outside its lock.
 */
internal class ChipReadRegistry {
    private val lock = NSLock()
    private var live: Any? = null
    private val releasing = mutableMapOf<Any, Long>()
    private var lastSystemEnd: Long? = null
    private var readyAt: Long? = null

    /** Called after every change; set once by the readiness watch. */
    @Volatile
    var onChange: () -> Unit = {}

    /** Makes [read] the live read; `false` when another read is live. */
    fun claim(read: Any): Boolean =
        locked {
            if (live != null) {
                false
            } else {
                live = read
                true
            }
        }.also { if (it) onChange() }

    /** [read] has its ending at [now]; when [unreleased], a session of it is still being released. */
    fun ended(
        read: Any,
        unreleased: Boolean,
        now: Long,
    ) {
        locked {
            if (live === read) {
                live = null
                if (unreleased) releasing[read] = now
            }
        }
        onChange()
    }

    /** Every system session of [read] is released. */
    fun released(read: Any) {
        val changed = locked { releasing.remove(read) != null }
        if (changed) onChange()
    }

    fun isReleasing(): Boolean = locked { releasing.isNotEmpty() }

    /** The time after which a new read no longer waits for a session being released; `null` when none is. */
    fun releaseDeadline(): Long? = locked { releasing.values.maxOrNull()?.plus(CoreNfcChipRead.RELEASE_WAIT_LIMIT_MILLIS) }

    /** The system ended a session on its own, or refused one, at [now]; the read that saw it rests [restMillis]. */
    fun systemEnded(
        now: Long,
        restMillis: Long,
    ) {
        locked {
            lastSystemEnd = now
            readyAt = now + restMillis
        }
        onChange()
    }

    fun lastSystemEnd(): Long? = locked { lastSystemEnd }

    /** When a new read may begin a session after the system's last end, as the read that saw it set it; `null` before any. */
    fun readyAt(): Long? = locked { readyAt }

    /** What the readiness is made of, read under one lock. */
    fun snapshot(): Snapshot =
        locked {
            Snapshot(live != null, releasing.values.maxOrNull()?.plus(CoreNfcChipRead.RELEASE_WAIT_LIMIT_MILLIS), readyAt)
        }

    class Snapshot(
        val readInProgress: Boolean,
        val releaseDeadline: Long?,
        val readyAt: Long?,
    )

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
