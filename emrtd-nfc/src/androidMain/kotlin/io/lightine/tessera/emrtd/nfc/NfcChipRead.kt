package io.lightine.tessera.emrtd.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads a passport or ID card chip on Android, from start to end: the SDK owns the link to the
 * chip and every way the read can stop; your app owns the screen. This is the main way to read a
 * chip on Android; [IsoDepTransport] stays the low-level path, where your app owns the link.
 *
 * The read is the platform's reader-mode callback: start it, hand it to [NfcReaderMode.enable],
 * show the updates, and act on the one ending.
 *
 * ```kotlin
 * // In a ViewModel, or anything else that outlives a configuration change:
 * val read = NfcChipRead.start(options, NfcAdapter.getDefaultAdapter(context), observer)
 *
 * // In the read screen, once per read, while the screen is shown (onCreate or onResume):
 * NfcReaderMode.enable(activity, read)?.let { reason ->
 *     read.cancel()   // the read cannot get a document: free it
 *     showWhy(reason) // for example "turn NFC on"
 * }
 *
 * // When the person leaves the read screen for good, or in ViewModel.onCleared:
 * read.cancel()
 * NfcReaderMode.disable(activity)
 * ```
 *
 * ## Your app and the screen
 *
 * - Call [NfcReaderMode.enable] once for each read. The platform turns reader mode off when the
 *   Activity pauses and on again, with the same read, when it resumes: do not call it again in
 *   `onResume`, and never turn reader mode off while a document may be on the phone. After a
 *   configuration change, call it once for the new Activity with the same read.
 * - A pause in the middle of a read (a dialog, another app, the screen locking) drops the link the
 *   way a moved document does: the read reports [io.lightine.tessera.emrtd.ChipReadState.LinkLost]
 *   and goes on when the screen is back and the document is found again, within
 *   [NfcReadSettings.waitForReturnMillis]. Your app may also [cancel] in `onPause`; the SDK does
 *   not watch the Activity.
 * - One read at a time in the app: [start] while another read has not ended ends at once with
 *   [ChipReadEnding.NotStarted] and [NfcUnavailable.Reason.READ_IN_PROGRESS]. A read waiting for a
 *   document waits until [NfcReadSettings.waitForDocumentMillis] passes, which by default is never:
 *   cancel a read your app no longer shows, or no other read can start.
 *
 * ## What the SDK does
 *
 * - Checks the MRZ key's check digits before anything else: a key that does not add up ends with
 *   [ChipReadEnding.KeyRejected] and nothing is sent (see
 *   [io.lightine.tessera.emrtd.ChipReadOptions.allowCheckDigitMismatch]).
 * - Ends with [ChipReadEnding.NotStarted] when the device has no NFC (`NOT_SUPPORTED`) or NFC is
 *   off (`DISABLED`).
 * - While no document is found, the observer gets
 *   [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] again every 2 seconds, with the
 *   time waited. A tag that is not an ISO 14443-4 chip (a transit card, for example) is skipped,
 *   and the read keeps waiting.
 * - When a document is found, waits [NfcReadSettings.settleDelayMillis] before the first command,
 *   connects, and reads. Each command has its own time limit,
 *   [NfcReadSettings.commandTimeoutMillis]. A link that cannot be set up sends the read back to
 *   waiting, with no attempt used; after 3 such failures in a row the read ends.
 * - When the link is lost, follows [NfcReadSettings.linkLoss]: by default it waits for the
 *   document again and goes on, at most twice. The platform hands over a new `Tag` when the
 *   document comes back; when it does not, because the document never left the field, the SDK
 *   tries the earlier `Tag` again every second.
 * - When NFC is switched off during the read, ends with [ChipReadEnding.EndedBySystem] and
 *   [NfcUnavailable.Reason.DISABLED], instead of waiting for a document that cannot come.
 * - Closes the link on every path: the ending goes to the observer first, and the platform's
 *   close, which talks to the tag and can take up to the command time limit, runs in the
 *   background after it. The next link, of this read or a later one, waits for that close (at
 *   most the command time limit), so the two never race on the tag.
 * - Tags found while a command runs, or after the ending, are ignored: never two reads at once.
 *
 * ## Threads
 *
 * [start], [cancel] and [onTagDiscovered] return at once, from any thread. The read runs on the
 * I/O dispatcher. The observer's calls never overlap and come in order; they come from the read's
 * own thread, or from the thread that calls [start] or [cancel] for the updates and the ending
 * those cause. Never from the main thread by promise: hand the values to your UI yourself.
 *
 * The observer is held until the ending.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NfcChipRead internal constructor(
    options: ChipReadOptions,
    private val adapter: NfcAdapter?,
    private val settings: NfcReadSettings,
    observer: ChipReadObserver,
    private val env: NfcReadEnvironment,
) : NfcAdapter.ReaderCallback,
    ChipReadHandle {
    private val coordinator = ChipReadCoordinator(options, settings.linkLoss, Forwarder(observer), env.reader)
    private val scope = CoroutineScope(SupervisorJob() + env.dispatcher)

    // Tags the platform found while the read was willing to take one; only the newest is kept,
    // because the platform refuses an out-of-date Tag. Guarded by [intakeLock] with [intakeOpen].
    private val tags = Channel<Tag>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val intakeLock = Any()
    private var intakeOpen = false

    // The link of the running attempt; closed by whoever takes it out first.
    private val link = AtomicReference<IsoDepTransport?>(null)

    @Volatile
    private var job: Job? = null

    /** `true` once the read's ending is decided. */
    override val isEnded: Boolean get() = coordinator.isEnded

    override fun cancel() {
        if (coordinator.cancel(ChipReadEnding.Cancelled.By.APP)) job?.cancel()
    }

    /**
     * Reader mode found a tag. Returns at once, from the platform's binder thread: the read's own
     * coroutine takes the tag. A tag found while a command runs, or after the ending, is ignored.
     */
    override fun onTagDiscovered(tag: Tag) {
        synchronized(intakeLock) {
            if (intakeOpen && !coordinator.isEnded) tags.trySend(tag)
        }
    }

    internal fun begin() {
        if (!coordinator.start()) return
        if (!env.registry.claim(this)) {
            coordinator.notStarted(NfcUnavailable.Reason.READ_IN_PROGRESS)
            return
        }
        job =
            scope.launch {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A platform fault the read does not expect still gets an ending.
                    coordinator.fail(NfcUnavailable(NfcUnavailable.Reason.REQUEST_REFUSED))
                } finally {
                    setIntake(false)
                    closeLink()
                    // Every path above ends the read; this only guards against a path that did not.
                    if (!coordinator.isEnded) coordinator.fail(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                }
            }
        if (coordinator.isEnded) job?.cancel()
    }

    private class Found(
        val tag: Tag,
        val earlier: Boolean,
    )

    private suspend fun run() {
        val adapter = this.adapter
        if (adapter == null) {
            coordinator.notStarted(NfcUnavailable.Reason.NOT_SUPPORTED)
            return
        }
        if (!env.platform.isEnabled(adapter)) {
            coordinator.notStarted(NfcUnavailable.Reason.DISABLED)
            return
        }
        setIntake(true)
        coordinator.waiting(0)
        val began = env.now()
        var waitingSince = began
        // Before the first link: the app's limit on finding a document. After a lost link: the
        // limit on its return, counted from the loss.
        var deadline: Long? = settings.waitForDocumentMillis?.let { saturatingAdd(began, it) }
        var linkedOnce = false
        var lostTag: Tag? = null
        var failedConnects = 0
        while (!coordinator.isEnded) {
            val found = awaitTag(adapter, waitingSince, deadline, lostTag, linkedOnce) ?: return
            val release = CompletableDeferred<Unit>()
            val connection = env.platform.connectionOf(found.tag, settings.commandTimeoutMillis.toInt()) { release.complete(Unit) }
            if (connection == null) {
                // Not an ISO 14443-4 chip, so no document this read can use: keep waiting.
                continue
            }
            val transport: IsoDepTransport
            if (found.earlier) {
                // The same Tag after a lost link: try the link quietly; the person sees LinkLost
                // until it is up.
                transport = open(connection, release).getOrNull() ?: continue
                if (!holdLink(transport)) return
                coordinator.documentFound()
                delay(settings.settleDelayMillis)
                coordinator.connecting()
            } else {
                coordinator.documentFound()
                delay(settings.settleDelayMillis)
                coordinator.connecting()
                val opened = open(connection, release)
                val error = opened.exceptionOrNull()
                if (error != null) {
                    if (endIfNfcOff(adapter)) return
                    if (error is ApduTransportException && error.error.isLinkLevel() && ++failedConnects < MAX_CONNECT_ATTEMPTS) {
                        // No command was sent: back to waiting, with no attempt used.
                        coordinator.connectionFailed()
                        continue
                    }
                    coordinator.fail((error as? ApduTransportException)?.error ?: NfcTagLost)
                    return
                }
                transport = opened.getOrThrow()
                if (!holdLink(transport)) return
            }
            failedConnects = 0
            linkedOnce = true
            val outcome =
                coordinator.read(transport, readAgainAllowed = true) {
                    if (env.platform.isEnabled(adapter)) null else NfcUnavailable(NfcUnavailable.Reason.DISABLED)
                }
            if (outcome == ChipReadAttemptOutcome.ENDED) return
            closeLink()
            lostTag = found.tag
            waitingSince = env.now()
            deadline = saturatingAdd(waitingSince, settings.waitForReturnMillis)
            setIntake(true)
        }
    }

    /**
     * Waits for a tag: a new discovery, or after a lost link the earlier [lostTag] again, every
     * [SAME_TAG_RETRY_MILLIS] while no new one comes. Every [WAITING_REPORT_MILLIS] it reports the
     * time waited and checks that NFC is still on. `null` when the read ended, here or elsewhere.
     */
    private suspend fun awaitTag(
        adapter: NfcAdapter,
        since: Long,
        deadline: Long?,
        lostTag: Tag?,
        linkedOnce: Boolean,
    ): Found? {
        val start = env.now()
        var nextReport = since + ((start - since) / WAITING_REPORT_MILLIS + 1) * WAITING_REPORT_MILLIS
        val retryAt = if (lostTag != null) start + SAME_TAG_RETRY_MILLIS else Long.MAX_VALUE
        while (!coordinator.isEnded) {
            val now = env.now()
            if (deadline != null && now >= deadline) {
                if (linkedOnce) {
                    coordinator.documentNotReturned()
                } else {
                    coordinator.endedBySystem(NfcTimeout(settings.waitForDocumentMillis))
                }
                return null
            }
            val wake = minOf(nextReport, retryAt, deadline ?: Long.MAX_VALUE)
            val tag = tags.tryReceive().getOrNull() ?: withTimeoutOrNull((wake - now).coerceAtLeast(0)) { tags.receive() }
            if (tag != null) return Found(tag, earlier = false)
            val after = env.now()
            if (after >= nextReport) {
                if (endIfNfcOff(adapter)) return null
                // While the read waits after a lost link, LinkLost stays: the coordinator drops this.
                coordinator.waiting(after - since)
                while (nextReport <= after) nextReport += WAITING_REPORT_MILLIS
            }
            if (lostTag != null && after >= retryAt) return Found(lostTag, earlier = true)
        }
        return null
    }

    /** Opens the link once every earlier link has been released, or the wait for that ran out. */
    private suspend fun open(
        connection: IsoDepConnection,
        release: CompletableDeferred<Unit>,
    ): Result<IsoDepTransport> {
        env.registry.awaitReleased(settings.commandTimeoutMillis)
        env.registry.track(release)
        return try {
            Result.success(connection.open())
        } catch (e: ApduTransportException) {
            Result.failure(e)
        }
    }

    /**
     * Makes [transport] the read's link and stops taking tags until the link is lost; `false`
     * (with the link closed) when the read ended meanwhile.
     */
    private fun holdLink(transport: IsoDepTransport): Boolean {
        setIntake(false)
        link.set(transport)
        if (coordinator.isEnded) {
            closeLink()
            return false
        }
        return true
    }

    private fun setIntake(open: Boolean) {
        synchronized(intakeLock) {
            intakeOpen = open
            // A tag taken in while a link was up is either the document of that link or another
            // one near the phone: never one to read after this link.
            if (!open) while (tags.tryReceive().isSuccess) Unit
        }
    }

    /** Ends the read when NFC was switched off; `true` when the read has ended, by this or before. */
    private fun endIfNfcOff(adapter: NfcAdapter): Boolean {
        if (!env.platform.isEnabled(adapter)) coordinator.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.DISABLED))
        return coordinator.isEnded
    }

    /** Releases the link in the background: the platform's close talks to the tag. */
    private fun closeLink() {
        link.getAndSet(null)?.close()
    }

    /** What happens when the ending is decided, before the app hears of it. */
    private fun finish() {
        setIntake(false)
        env.registry.ended(this)
        closeLink()
        job?.cancel()
    }

    /** Hands the coordinator's calls to the app, after the SDK's own work on the ending. */
    private inner class Forwarder(
        private val app: ChipReadObserver,
    ) : ChipReadObserver {
        override fun onUpdate(update: ChipReadUpdate) = app.onUpdate(update)

        override fun onEnding(ending: ChipReadEnding) {
            finish()
            app.onEnding(ending)
        }
    }

    /** Starts a read. */
    public companion object {
        /**
         * Starts a read with the default settings. Returns at once; the read waits for reader mode
         * to hand it a document: pass it to [NfcReaderMode.enable].
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param adapter The device's NFC adapter, from `NfcAdapter.getDefaultAdapter(context)`;
         *   `null` (no NFC on the device) ends the read with [ChipReadEnding.NotStarted].
         * @param observer Gets every update and exactly one ending.
         * @return The read: the reader-mode callback, and the handle to cancel it.
         */
        @JvmStatic
        public fun start(
            options: ChipReadOptions,
            adapter: NfcAdapter?,
            observer: ChipReadObserver,
        ): NfcChipRead = start(options, adapter, NfcReadSettings(), observer)

        /**
         * Starts a read. Returns at once; the read waits for reader mode to hand it a document:
         * pass it to [NfcReaderMode.enable].
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param adapter The device's NFC adapter, from `NfcAdapter.getDefaultAdapter(context)`;
         *   `null` (no NFC on the device) ends the read with [ChipReadEnding.NotStarted].
         * @param settings What to do after a lost link, and the time limits.
         * @param observer Gets every update and exactly one ending.
         * @return The read: the reader-mode callback, and the handle to cancel it.
         */
        @JvmStatic
        public fun start(
            options: ChipReadOptions,
            adapter: NfcAdapter?,
            settings: NfcReadSettings,
            observer: ChipReadObserver,
        ): NfcChipRead = start(options, adapter, settings, observer, NfcReadEnvironment.process)

        internal fun start(
            options: ChipReadOptions,
            adapter: NfcAdapter?,
            settings: NfcReadSettings,
            observer: ChipReadObserver,
            env: NfcReadEnvironment,
        ): NfcChipRead = NfcChipRead(options, adapter, settings, observer, env).also { it.begin() }

        /** How often the waiting state repeats with the time waited, as on iPhone. */
        internal const val WAITING_REPORT_MILLIS = 2_000L

        /** After a lost link with no new discovery, how often the earlier Tag is tried again. */
        internal const val SAME_TAG_RETRY_MILLIS = 1_000L

        /** Failed connects in a row after which the read ends, as on iPhone. */
        internal const val MAX_CONNECT_ATTEMPTS = 3

        /**
         * [time] plus [millis], at most [Long.MAX_VALUE]: a wait the app sets to a very large value
         * (for example `Long.MAX_VALUE` for "no limit") must not wrap round to a deadline in the past.
         */
        internal fun saturatingAdd(
            time: Long,
            millis: Long,
        ): Long = if (millis > Long.MAX_VALUE - time) Long.MAX_VALUE else time + millis

        /** A failure of the link itself, not a refusal: the connect may work on the next tag. */
        private fun NfcError.isLinkLevel(): Boolean = this is NfcTagLost || this is NfcTimeout
    }
}

/** What an [NfcChipRead] runs on: the platform by default, fakes and a test clock in tests. */
internal class NfcReadEnvironment(
    val platform: NfcChipPlatform,
    val dispatcher: CoroutineDispatcher,
    val now: () -> Long,
    val registry: NfcReadRegistry,
    val reader: ChipReader = ChipReader(),
) {
    companion object {
        /** The real platform, one registry for the whole process. */
        val process: NfcReadEnvironment by lazy {
            NfcReadEnvironment(
                platform = PlatformNfcChip,
                dispatcher = Dispatchers.IO,
                now = { System.nanoTime() / NANOS_PER_MILLI },
                registry = NfcReadRegistry(),
            )
        }

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The reads of one process: at most one live read, and the links whose platform close has not
 * finished, so a new link never races a close on the tag.
 */
internal class NfcReadRegistry {
    private val live = AtomicReference<Any?>(null)
    private val releasing: MutableSet<CompletableDeferred<Unit>> = ConcurrentHashMap.newKeySet()

    /** Makes [read] the live read; `false` when another read is live. */
    fun claim(read: Any): Boolean = live.compareAndSet(null, read)

    /** [read] has its ending: another read may start. */
    fun ended(read: Any) {
        live.compareAndSet(read, null)
    }

    /** A link is about to open; [release] completes when the platform let go of it. */
    fun track(release: CompletableDeferred<Unit>) {
        releasing += release
        release.invokeOnCompletion { releasing -= release }
    }

    /** Waits until every tracked link was released, or [limitMillis] passed. */
    suspend fun awaitReleased(limitMillis: Long) {
        withTimeoutOrNull(limitMillis) { releasing.toList().forEach { it.await() } }
    }
}

/**
 * The part of the Android NFC platform that [NfcChipRead] uses, so its flow can be tested on the
 * JVM with a fake and no device. The real implementation talks to `NfcAdapter` and `IsoDep`.
 */
internal interface NfcChipPlatform {
    /** Whether [adapter] is on. */
    fun isEnabled(adapter: NfcAdapter): Boolean

    /**
     * The ISO-DEP connection of [tag], or `null` when the tag speaks none. Nothing is sent to the
     * tag here. [onReleased] runs once, after the platform's close of the link returned.
     *
     * @param timeoutMillis How long to wait for the chip's answer to one command.
     */
    fun connectionOf(
        tag: Tag,
        timeoutMillis: Int,
        onReleased: () -> Unit,
    ): IsoDepConnection?
}

/** One tag's ISO-DEP connection: opened when the read is ready to send, never on the caller's thread. */
internal fun interface IsoDepConnection {
    /**
     * A new transport on the tag, connected and with the time limit set. When it throws, the
     * tag's link was closed (and its release reported) first.
     */
    suspend fun open(): IsoDepTransport
}

/** A connection over any channel, the wiring the platform and the tests build alike. */
internal fun connectionOver(
    channel: IsoDepChannel,
    timeoutMillis: Int,
    io: CoroutineDispatcher,
    onReleased: () -> Unit,
): IsoDepConnection = IsoDepConnection { IsoDepTransport.open(ReleaseSignallingChannel(channel, onReleased), timeoutMillis, io) }

/** The platform itself: `NfcAdapter` for the state, `IsoDep` for the tag's link. */
internal object PlatformNfcChip : NfcChipPlatform {
    override fun isEnabled(adapter: NfcAdapter): Boolean = adapter.isEnabled

    override fun connectionOf(
        tag: Tag,
        timeoutMillis: Int,
        onReleased: () -> Unit,
    ): IsoDepConnection? {
        val isoDep = IsoDep.get(tag) ?: return null
        return connectionOver(PlatformIsoDepChannel(isoDep), timeoutMillis, Dispatchers.IO, onReleased)
    }
}
