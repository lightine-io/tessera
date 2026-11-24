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
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One chip read on Android, owned by the SDK: it waits for a document on the phone's reader,
 * connects to the chip, reads and checks what [options] ask, and ends with exactly one
 * [ChipReadEnding] to [observer].
 *
 * Hand the read to reader mode: the read is the callback, and [NfcReaderMode] turns the reader on
 * with it. Your app keeps only the lifecycle: reader mode on when the read screen is resumed, off
 * when it is left, and [cancel] when the person gives up. Nothing is left open on any path: the
 * read closes the link to the chip in the background, on every ending.
 *
 * How it runs:
 * - The work never runs on the caller's thread and never on the main thread. [onTagDiscovered]
 *   returns at once; updates and the ending come from the read's own I/O dispatcher, so hand them to
 *   your UI thread yourself and return quickly.
 * - One read is live per process. A second read started while one is live ends at once with
 *   [ChipReadEnding.NotStarted] (`READ_IN_PROGRESS`) and touches no radio.
 * - A tag that arrives while a read runs, or after the ending, is ignored: never two reads at
 *   once, and never an out-of-date `Tag`, which the platform refuses commands on.
 * - When the link is lost, the read closes it and waits for the document to be found again, up to
 *   [NfcReadSettings.waitForReturnMillis], then continues as [settings][NfcReadSettings.linkLoss]
 *   say. A document that does not come back ends the read.
 * - When NFC is switched off mid-read, the read says so through the system ending instead of
 *   waiting for a document that cannot come back.
 *
 * For an app that wants to own the link itself - its own reader-mode wiring, one attempt, no
 * restarts - [IsoDepTransport] stays the low-level path: it carries command bytes, and you own
 * the link.
 *
 * A typical read:
 *
 * ```kotlin
 * val options = ChipReadOptions(ChipAccessKey.Can(canFromTheDocument), setOf(1, 2))
 * val read = NfcChipRead(
 *     options,
 *     object : ChipReadObserver {
 *         override fun onUpdate(update: ChipReadUpdate) {
 *             show(update.state)     // a hint such as "hold the document against the phone"
 *         }
 *
 *         override fun onEnding(ending: ChipReadEnding) {
 *             handle(ending)        // show the read, or why it ended
 *         }
 *     },
 *     NfcAdapter.getDefaultAdapter(this),
 * )
 * NfcReaderMode.enable(this, read) ?: showReaderReady()
 * ```
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @param options The key, the data groups, the roots and the check time; the key is checked before
 *   any radio activity.
 * @param observer Where the updates and the one ending go, never document data in the updates.
 * @param adapter The process's NFC adapter, from `NfcAdapter.getDefaultAdapter(context)`, or `null`
 *   when the device has none: the read then ends with [ChipReadEnding.NotStarted]
 *   (`NOT_SUPPORTED`).
 * @param settings What the read may do beyond the options: the link-loss policy and the platform's
 *   timings.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NfcChipRead internal constructor(
    private val options: ChipReadOptions,
    private val observer: ChipReadObserver,
    private val adapter: NfcAdapter?,
    private val settings: NfcReadSettings,
    private val io: CoroutineDispatcher,
    private val platform: NfcChipPlatform,
) : NfcAdapter.ReaderCallback,
    ChipReadHandle {
    /**
     * Reads the chip behind [adapter] as [options] say, with [settings]' timings and link-loss
     * policy.
     */
    public constructor(
        options: ChipReadOptions,
        observer: ChipReadObserver,
        adapter: NfcAdapter?,
        settings: NfcReadSettings = NfcReadSettings(),
    ) : this(options, observer, adapter, settings, Dispatchers.IO, PlatformNfcChip)

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val coordinator = ChipReadCoordinator(options, settings.linkLoss, observer)
    private val tagIntake = Channel<Tag>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val intakeOpen = AtomicBoolean(false)
    private val liveReleased = AtomicBoolean(false)

    // The one live read per process: claimed here, released exactly once on the way out.
    private val ownsLiveRead: Boolean = LIVE_READ.compareAndSet(false, true)

    init {
        scope.launch {
            if (!ownsLiveRead) {
                // Another read is live in this process: end at once, with no radio activity.
                coordinator.notStarted(NfcUnavailable.Reason.READ_IN_PROGRESS)
                return@launch
            }
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing may end without an ending: an unexpected platform fault still gets one.
                coordinator.fail(NfcUnavailable(NfcUnavailable.Reason.REQUEST_REFUSED))
            }
        }
    }

    /** `true` once the ending is decided, just before or as the observer hears it. */
    override val isEnded: Boolean
        get() = coordinator.isEnded

    /**
     * Stops the read. The cancelled ending goes to the observer at once, on the thread that calls
     * this; the link to the chip is released in the background after it, so a command blocked in
     * the platform does not hold the caller. Safe from any thread and safe to call more than
     * once; a read that already ended stays as it ended.
     */
    override fun cancel() {
        coordinator.cancel(ChipReadEnding.Cancelled.By.APP)
        releaseLive()
        scope.cancel()
    }

    /**
     * Reader mode found a tag. Returns at once: the read's own coroutine takes the tag and does all
     * the work, so this never blocks and never runs on the read's dispatcher. A tag that arrives
     * while a read runs, or after the ending, is ignored.
     */
    override fun onTagDiscovered(tag: Tag) {
        if (coordinator.isEnded || !intakeOpen.get()) return
        tagIntake.trySend(tag)
    }

    // --- The read -----------------------------------------------------------------------------

    /** Which document wait is running: the ticks and the time bound differ per kind. */
    private enum class WaitKind {
        /** The first wait for a document: ticks, bounded by the app's document limit. */
        FIRST_DOCUMENT,

        /** A later wait after a tag that could not be connected: ticks, no bound. */
        NEXT_TAG,

        /** The wait for the document to come back after a lost link: no ticks, bounded by the return limit. */
        RETURNING_DOCUMENT,
    }

    /** The open link of the current attempt, with the signal that says when the platform let go. */
    private class OpenLink(
        val transport: IsoDepTransport,
        val release: CompletableDeferred<Unit>,
    )

    private suspend fun run() {
        var open: OpenLink? = null
        try {
            if (!coordinator.start()) return
            val adapter = this.adapter
            if (adapter == null) {
                coordinator.notStarted(NfcUnavailable.Reason.NOT_SUPPORTED)
                return
            }
            if (!platform.isEnabled(adapter)) {
                coordinator.notStarted(NfcUnavailable.Reason.DISABLED)
                return
            }
            var waited = 0L
            var kind = WaitKind.FIRST_DOCUMENT
            while (true) {
                val bound =
                    when (kind) {
                        WaitKind.FIRST_DOCUMENT -> settings.waitForDocumentMillis
                        WaitKind.NEXT_TAG -> null
                        WaitKind.RETURNING_DOCUMENT -> settings.waitForReturnMillis
                    }
                if (kind == WaitKind.FIRST_DOCUMENT) coordinator.waiting(0)
                val ticks: Job? =
                    if (kind == WaitKind.RETURNING_DOCUMENT) {
                        null
                    } else {
                        // While the read waits for a document, tell the app every 2 seconds how
                        // long it has waited, so it can change its hint after a while.
                        scope.launch {
                            while (true) {
                                delay(WAITING_TICK_MILLIS)
                                waited += WAITING_TICK_MILLIS
                                if (intakeOpen.get()) coordinator.waiting(waited)
                            }
                        }
                    }
                val tag: Tag?
                try {
                    tag = receiveTag(bound)
                } finally {
                    ticks?.cancelAndJoin()
                }
                if (tag == null) {
                    if (kind == WaitKind.FIRST_DOCUMENT) {
                        // The app gave the first wait a limit and no document came: a system-style
                        // timeout.
                        coordinator.endedBySystem(NfcTimeout(bound))
                    } else {
                        coordinator.documentNotReturned()
                    }
                    return
                }
                if (coordinator.isEnded) return

                val release = CompletableDeferred<Unit>()
                val connection = platform.connectionOf(tag, settings.timeoutMillis) { release.complete(Unit) }
                if (connection == null) {
                    // A tag that speaks no ISO-DEP is no document this read can use.
                    coordinator.fail(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))
                    reportNfcOff(adapter)
                    return
                }
                coordinator.documentFound()
                // Frames are often lost in the first moment after the tag is found, while the hand
                // that holds the document settles: give it the settle time before the first command.
                delay(settings.settleDelayMillis)
                if (coordinator.isEnded) return
                coordinator.connecting()
                awaitEarlierClose()
                val transport = openTransport(connection)
                if (transport == null) {
                    // The link could not be set up, before any command: back to waiting, with no
                    // attempt used.
                    coordinator.connectionFailed()
                    reportNfcOff(adapter)
                    if (coordinator.isEnded) return
                    if (kind == WaitKind.FIRST_DOCUMENT) kind = WaitKind.NEXT_TAG
                    continue
                }
                // The next connect, of this read or a later one, waits for this link's release.
                pendingRelease.set(release)
                open = OpenLink(transport, release)
                when (coordinator.read(transport, readAgainAllowed = true)) {
                    ChipReadAttemptOutcome.ENDED -> {
                        return
                    }

                    ChipReadAttemptOutcome.LINK_LOST -> {
                        closeLink(open)
                        open = null
                        reportNfcOff(adapter)
                        if (coordinator.isEnded) return
                        kind = WaitKind.RETURNING_DOCUMENT
                    }
                }
            }
        } finally {
            open?.let(::closeLink)
            releaseLive()
        }
    }

    /**
     * Takes the next discovery, or `null` when [boundMillis] passed. Only a discovery that comes
     * while this wait is open is used: one that arrived during a read, or in an earlier wait, is
     * dropped, because the platform refuses commands on an out-of-date `Tag`.
     */
    private suspend fun receiveTag(boundMillis: Long?): Tag? {
        while (tagIntake.tryReceive().isSuccess) {
            // An out-of-date discovery: dropped.
        }
        intakeOpen.set(true)
        try {
            return if (boundMillis == null) {
                tagIntake.receive()
            } else {
                withTimeoutOrNull(boundMillis) { tagIntake.receive() }
            }
        } finally {
            intakeOpen.set(false)
        }
    }

    /** Opens the connection the platform built for a tag; `null` when the link could not be set up. */
    private suspend fun openTransport(connection: IsoDepConnection): IsoDepTransport? =
        try {
            connection.open()
        } catch (e: ApduTransportException) {
            null
        }

    /**
     * Waits for the release of the link a read just closed, bounded by the command time limit: the
     * platform's close talks to the tag and can take up to that limit, and a connect raced against
     * it can fail. When the bound passes, the connect goes ahead anyway.
     */
    private suspend fun awaitEarlierClose() {
        val earlier = pendingRelease.getAndSet(null) ?: return
        withTimeoutOrNull(settings.timeoutMillis.toLong()) { earlier.await() }
    }

    /** Releases the link in the background: the platform close talks to the tag, this returns at once. */
    private fun closeLink(link: OpenLink) {
        link.transport.close()
    }

    /**
     * After a link problem, when NFC was switched off: report the system ending, so a read that
     * could otherwise wait for the next document does not wait for one that cannot come back. The
     * coordinator's one-ending gate keeps whichever cause came first.
     */
    private fun reportNfcOff(adapter: NfcAdapter) {
        if (!platform.isEnabled(adapter)) {
            coordinator.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.DISABLED))
        }
    }

    /** Frees the process's live slot exactly once, and only when this read took it. */
    private fun releaseLive() {
        if (ownsLiveRead && liveReleased.compareAndSet(false, true)) LIVE_READ.set(false)
    }

    private companion object {
        /** How often the read tells the app how long it has waited for a document. */
        private const val WAITING_TICK_MILLIS = 2_000L

        /** The one live read per process: a second read ends at once while one is live. */
        private val LIVE_READ = AtomicBoolean(false)

        /**
         * The release of the most recent open link, so the next connect can wait for it. A link
         * registers its own release when it opens; the release completes when the platform let go.
         */
        private val pendingRelease = AtomicReference<CompletableDeferred<Unit>?>(null)
    }
}

/**
 * The part of the Android NFC platform that [NfcChipRead] uses, so its flow can be tested on the
 * JVM with a fake and no device. The real implementation talks to `NfcAdapter` and `IsoDep`.
 */
internal interface NfcChipPlatform {
    /** Whether [adapter] is on and ready to find tags. */
    public fun isEnabled(adapter: NfcAdapter): Boolean

    /**
     * The ISO-DEP connection of a fresh [tag], or `null` when the tag speaks none. Nothing is sent
     * to the tag here. [onReleased] runs once, after the transport was closed and the platform let
     * go of the tag.
     *
     * @param timeoutMillis How long to wait for the chip's answer to one command.
     */
    public fun connectionOf(
        tag: Tag,
        timeoutMillis: Int,
        onReleased: () -> Unit,
    ): IsoDepConnection?
}

/**
 * One tag's ISO-DEP connection: opened once, when the read is ready to send, never on the caller's
 * thread. A new one for every discovery; never reused.
 */
internal fun interface IsoDepConnection {
    /** A new transport on the tag, connected and with the time limit set. */
    public suspend fun open(): IsoDepTransport
}

/**
 * A connection over any channel, the wiring the platform and the tests build alike: the transport
 * tells [onReleased] when the platform let go of the tag.
 */
internal fun connectionOver(
    channel: IsoDepChannel,
    timeoutMillis: Int,
    io: CoroutineDispatcher,
    onReleased: () -> Unit,
): IsoDepConnection =
    object : IsoDepConnection {
        override suspend fun open(): IsoDepTransport =
            IsoDepTransport.open(ReleaseSignallingChannel(channel, onReleased), timeoutMillis, io)
    }

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
