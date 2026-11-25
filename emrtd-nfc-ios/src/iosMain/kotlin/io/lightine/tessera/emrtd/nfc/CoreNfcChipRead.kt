package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.HandoffObserver
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * use it for a second read: a new read needs a new key, which also keeps to one authentication
 * attempt per action of the person. A read started with a key that was already used (the same
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
 *   default). While no document is found, the observer gets
 *   [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] again every 2 seconds, with the
 *   time waited.
 * - When a document is found, waits [CoreNfcReadSettings.settleDelayMillis] before the first
 *   command, connects, and reads. Each command has its own time limit,
 *   [CoreNfcReadSettings.commandTimeoutMillis].
 * - When the link is lost (the document moved), follows [CoreNfcReadSettings.linkLoss]: by default
 *   it keeps the sheet up, waits for the document again and goes on, at most twice. It does not
 *   start again when less than 10 seconds of the system session's 60 are left.
 * - Ends the system session on every path, exactly once: with success only for
 *   [ChipReadEnding.Complete], with the ending's text as the error message otherwise. The ending
 *   is given to the observer at once; the system takes a moment more to close the sheet.
 * - One read at a time in the app: a [start] while another read is running ends at once with
 *   [ChipReadEnding.NotStarted] and [NfcUnavailable.Reason.READ_IN_PROGRESS]. A [start] right after
 *   a read ended waits in [io.lightine.tessera.emrtd.ChipReadState.Starting] until the previous
 *   system session is released (at most 5 seconds), and for
 *   [CoreNfcReadSettings.waitAfterSystemEndMillis] after a session the system ended.
 *
 * ## Endings from the system
 *
 * - The person taps Cancel: [ChipReadEnding.Cancelled] by [ChipReadEnding.Cancelled.By.PERSON].
 * - The session's time limit passes (60 seconds) or the system ends the session (for example the
 *   app goes to the background): [ChipReadEnding.EndedBySystem].
 * - Before a document was found: NFC missing on the device, the entitlement or the Info.plist
 *   entries missing, the radio off, or the reader busy: [ChipReadEnding.NotStarted] with the
 *   reason. After a document was found, the radio off or a busy reader is
 *   [ChipReadEnding.EndedBySystem].
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
    private val tags = Channel<List<DetectedTag>>(Channel.UNLIMITED)
    private val alerts = AlertWriter()

    // Guards [owned] and [transport] against the ending, which may come from any thread.
    private val lock = NSLock()
    private var owned: OwnedReaderSession? = null
    private var transport: CoreNfcTransport? = null

    @Volatile
    private var job: Job? = null

    @Volatile
    private var documentSeen = false

    // What the system reported when it ended the session, once it did.
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
                } finally {
                    stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                    // Every path above ends the read; this only guards against a path that did not.
                    if (!coordinator.isEnded) coordinator.fail(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
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
        val session = OwnedReaderSession(env.openSession(events), keeper = this) { env.registry.released(this) }
        if (!locked { if (coordinator.isEnded) false else true.also { owned = session } }) return
        coordinator.waiting(0)
        if (!session.begin()) return
        val began = env.now()
        var waitingSince = began
        var failedConnects = 0
        while (!coordinator.isEnded) {
            val (tag, channel) = awaitDocument(session, waitingSince)
            documentSeen = true
            coordinator.documentFound()
            delay(settings.settleDelayMillis)
            coordinator.connecting()
            val failure = connect(tag)
            if (failure != null) {
                if (failure.retryable && ++failedConnects < MAX_CONNECT_ATTEMPTS) {
                    coordinator.connectionFailed()
                    restartPolling(session)
                    continue
                }
                endAfterConnect(failure.error)
                return
            }
            failedConnects = 0
            val link = CoreNfcTransport(channel, keepAlive = session, commandTimeoutMillis = settings.commandTimeoutMillis)
            if (!locked { if (coordinator.isEnded) false else true.also { transport = link } }) return
            val outcome =
                coordinator.read(link, readAgainAllowed = timeLeft(began) >= MIN_TIME_FOR_A_NEW_START_MILLIS) { stoppedBy ->
                    // A session end the system reported while the link was lost: that is the ending.
                    // Core NFC can fail the command in flight with "session invalidated" (CLOSED)
                    // before it calls the session's end, after the person's Cancel or the time
                    // limit: wait a moment for that call, which then ends the read with its cause.
                    sessionEndError ?: if (stoppedBy == CLOSED) withTimeoutOrNull(SESSION_END_WAIT_MILLIS) { sessionEnd.await() } else null
                }
            stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
            if (outcome == ChipReadAttemptOutcome.ENDED) return
            // The loss was allowed to follow, but too little of the session may be left by now.
            if (timeLeft(began) < MIN_TIME_FOR_A_NEW_START_MILLIS) {
                coordinator.documentNotReturned()
                return
            }
            restartPolling(session)
            waitingSince = env.now()
        }
    }

    private fun timeLeft(began: Long): Long = SESSION_LIMIT_MILLIS - (env.now() - began)

    /** In the Starting state: the previous read's session must be released, and the system rested. */
    private suspend fun waitForThePreviousSession() {
        val deadline = env.now() + RELEASE_WAIT_LIMIT_MILLIS
        while (env.registry.isReleasing() && env.now() < deadline && !coordinator.isEnded) delay(RELEASE_POLL_MILLIS)
        val systemEnd = env.registry.lastSystemEnd() ?: return
        val wait = systemEnd + settings.waitAfterSystemEndMillis - env.now()
        if (wait > 0) delay(wait)
    }

    /** Waits for one ISO 7816 tag, reporting the time waited every 2 seconds. */
    private suspend fun awaitDocument(
        session: OwnedReaderSession,
        since: Long,
    ): Pair<DetectedTag, Iso7816Channel> {
        var nextReport = since + WAITING_REPORT_MILLIS
        while (true) {
            val found = withTimeoutOrNull((nextReport - env.now()).coerceAtLeast(0)) { tags.receive() }
            if (found == null) {
                coordinator.waiting(env.now() - since)
                nextReport += WAITING_REPORT_MILLIS
                continue
            }
            val tag = found.singleOrNull()
            val channel = tag?.iso7816
            // More than one tag, or not an ISO 7816 tag: keep looking.
            if (tag == null || channel == null) {
                restartPolling(session)
                continue
            }
            return tag to channel
        }
    }

    private suspend fun connect(tag: DetectedTag): ConnectFailure? {
        val done = CompletableDeferred<ConnectFailure?>()
        tag.connect { done.complete(it) }
        return done.await()
    }

    private fun restartPolling(session: OwnedReaderSession) {
        // Tags reported before the restart are no longer valid.
        while (tags.tryReceive().isSuccess) Unit
        session.restartPolling()
    }

    /**
     * Ends the read after a connect that failed for good. A connect can fail because the session is
     * ending (the person tapped Cancel, the system ended it): Core NFC may report that to the
     * connect before it calls the session's end, so those causes get the same ending the session
     * end would give, whichever of the two comes first.
     */
    private fun endAfterConnect(error: NfcError) {
        when {
            error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CANCELLED_BY_USER -> {
                coordinator.cancel(ChipReadEnding.Cancelled.By.PERSON)
            }

            error is NfcTimeout || (error is NfcUnavailable && error.reason in SYSTEM_REASONS) -> {
                coordinator.endedBySystem(error)
            }

            else -> {
                coordinator.fail(error)
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

    private val events =
        object : ReaderSessionEvents {
            override fun onTagsDetected(tags: List<DetectedTag>) {
                // A usable document counts as found from the moment the system reports it, so a
                // session end right after is the system's ending, never "not started".
                if (tags.singleOrNull()?.iso7816 != null) documentSeen = true
                this@CoreNfcChipRead.tags.trySend(tags)
            }

            override fun onInvalidated(error: NfcError) = systemEnded(error)
        }

    /** The system ended the session: the ending, unless the SDK ended it. */
    private fun systemEnded(error: NfcError) {
        sessionEndError = error
        sessionEnd.complete(error)
        val session = locked { owned } ?: return
        session.systemEnd()
        if (session.endedBySdk) return
        if (error is NfcTimeout || (error is NfcUnavailable && error.reason in RESTING_REASONS)) {
            env.registry.systemEnded(env.now())
        }
        when {
            error is NfcUnavailable && error.reason == NfcUnavailable.Reason.CANCELLED_BY_USER -> {
                coordinator.cancel(ChipReadEnding.Cancelled.By.PERSON)
            }

            error is NfcUnavailable && error.reason in NOT_STARTED_REASONS && !documentSeen -> {
                coordinator.notStarted(error.reason)
            }

            error is NfcUnavailable && error.reason in SYSTEM_REASONS -> {
                coordinator.endedBySystem(error)
            }

            error is NfcTimeout -> {
                coordinator.endedBySystem(error)
            }

            else -> {
                coordinator.fail(error)
            }
        }
        stopTransport(error)
        job?.cancel()
    }

    /** What happens when the ending is decided, before the app hears of it. */
    private fun finish(ending: ChipReadEnding) {
        alerts.stop()
        val session = locked { owned }
        env.registry.ended(this, session)
        stopTransport(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
        if (session != null && session.isOpen) {
            val text = textForEnding(ending)
            if (ending is ChipReadEnding.Complete) {
                session.setAlert(text)
                session.end(null)
            } else {
                session.end(text)
            }
        }
        job?.cancel()
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
     * Sets the sheet's text only when it changes, and at most every [ALERT_INTERVAL_MILLIS]; a text
     * held back is set when the interval has passed, unless a newer one replaced it.
     */
    private inner class AlertWriter {
        private val alertLock = NSLock()
        private var shown: String? = null
        private var shownAt = Long.MIN_VALUE
        private var pending: String? = null
        private var flush: Job? = null
        private var stopped = false

        fun show(text: String) {
            val session = locked { owned } ?: return
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

        private fun flushPending(session: OwnedReaderSession) {
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
            session: OwnedReaderSession,
            text: String,
            now: Long,
        ) {
            pending = null
            shown = text
            shownAt = now
            session.setAlert(text)
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
         * @return `null` when reading is available; otherwise [NfcUnavailable.Reason.NOT_SUPPORTED]:
         *   this device cannot read chips. A missing entitlement or Info.plist entry, or a reader
         *   that is busy, shows only when a read starts, as [ChipReadEnding.NotStarted].
         * @since 1.0.0
         */
        public fun availability(): NfcUnavailable.Reason? = availability(::platformReadingAvailable)

        internal fun availability(readingAvailable: () -> Boolean): NfcUnavailable.Reason? =
            if (readingAvailable()) null else NfcUnavailable.Reason.NOT_SUPPORTED

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

        /** The system session's time limit as Core NFC's headers state it: 60 seconds. */
        internal const val SESSION_LIMIT_MILLIS = 60_000L

        /** No new start after a lost link when less than this is left of the session. */
        internal const val MIN_TIME_FOR_A_NEW_START_MILLIS = 10_000L

        /** How often the waiting state repeats with the time waited. */
        internal const val WAITING_REPORT_MILLIS = 2_000L

        /** The longest a new read waits for the previous system session to be released. */
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

        private val CLOSED = NfcUnavailable(NfcUnavailable.Reason.CLOSED)

        /** Failed connects in a row (a lost link, no answer) after which the read ends. */
        private const val MAX_CONNECT_ATTEMPTS = 3

        /** Ends after which the next read waits before it begins a session. */
        private val RESTING_REASONS = setOf(NfcUnavailable.Reason.ENDED_BY_SYSTEM, NfcUnavailable.Reason.BUSY)

        /** Session-end reasons that mean the read could not start, before a document was found. */
        private val NOT_STARTED_REASONS =
            setOf(
                NfcUnavailable.Reason.NOT_SUPPORTED,
                NfcUnavailable.Reason.NOT_PERMITTED,
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
    val openSession: (ReaderSessionEvents) -> ReaderSessionPort,
    val scope: CoroutineScope,
    val now: () -> Long,
    val registry: ChipReadRegistry,
    val reader: ChipReader,
    // Runs a block later on the main queue, first in, first out; tests run it at once or queue it.
    val mainQueue: (() -> Unit) -> Unit = { it() },
) {
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
            )
        }
    }
}

/**
 * The reads of one process: at most one live read, the reads whose system session is still being
 * released, and when the system last ended a session on its own.
 */
internal class ChipReadRegistry {
    private val lock = NSLock()
    private var live: Any? = null
    private val releasing = mutableSetOf<Any>()
    private var lastSystemEnd: Long? = null

    /** Makes [read] the live read; `false` when another read is live. */
    fun claim(read: Any): Boolean =
        locked {
            if (live != null) {
                false
            } else {
                live = read
                true
            }
        }

    /** [read] has its ending; its [session], when it has one, may still be being released. */
    fun ended(
        read: Any,
        session: OwnedReaderSession?,
    ) = locked {
        if (live === read) {
            live = null
            if (session != null && !session.isReleased) releasing += read
        }
    }

    /** The system session of [read] is released. */
    fun released(read: Any) = locked { releasing -= read }

    fun isReleasing(): Boolean = locked { releasing.isNotEmpty() }

    /** The system ended a session on its own, or refused one, at [now]. */
    fun systemEnded(now: Long) = locked { lastSystemEnd = now }

    fun lastSystemEnd(): Long? = locked { lastSystemEnd }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
