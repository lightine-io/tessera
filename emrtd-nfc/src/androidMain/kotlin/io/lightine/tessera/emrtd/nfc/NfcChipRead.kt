package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipReadAttemptOutcome
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadReadinessListener
import io.lightine.tessera.emrtd.ChipReadReadinessRegistration
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.HandoffObserver
import io.lightine.tessera.emrtd.ObserverThread
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
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.JvmOverloads

/**
 * Reads a passport or ID card chip on Android, from start to end: the SDK owns the link to the
 * chip and every way the read can stop; your app owns the screen. This is the main way to read a
 * chip on Android; [IsoDepTransport] stays the low-level path, where your app owns the link.
 *
 * ## The shortest read
 *
 * Start it with the screen that shows it. The read turns reader mode on for that screen, runs, and
 * turns reader mode off at its ending, on every ending:
 *
 * ```kotlin
 * val read = NfcChipRead.start(activity, options, observer)
 * // later, if the person leaves the screen: read.cancel()
 * ```
 *
 * [start] with an Activity checks that NFC can be used, turns on reader mode with the SDK's flags
 * and [NfcReadSettings.presenceCheckDelayMillis], and ends the read as [ChipReadEnding.NotStarted]
 * when it cannot: NFC missing or off, the Activity finishing or destroyed
 * ([NfcUnavailable.Reason.ACTIVITY_DESTROYED]), or reader mode refused. It never turns reader
 * mode off and on again while a document may be on the phone: it is switched on once, here, and off
 * once, at the ending, before the observer hears the ending. Call it on the main thread, with the
 * Activity that shows the read; the observer is called on the main thread by default
 * ([NfcReadSettings.observerOn]). The SDK cannot ask an Activity whether it is resumed (the platform
 * has no public call for it): the platform keeps the reader-mode request and applies it when the
 * Activity resumes, so a read started from `onCreate` waits for that.
 *
 * **Configuration change.** The read is for one Activity. The platform turns reader mode off and
 * on with that Activity's pause and resume, and a pause in the middle of a read drops the link the
 * way a moved document does (see below). When the Activity is destroyed, for example by a rotation,
 * the read ends at once as [ChipReadEnding.EndedBySystem] with
 * [NfcUnavailable.Reason.ACTIVITY_DESTROYED], because reader mode ended with the Activity and a new
 * Activity cannot take the read over without turning reader mode off and on. That is the safest
 * behaviour to promise: no read is left running without a screen, and the key is cleared. To read
 * through a rotation, keep the read screen out of it (`android:configChanges` for the screen's
 * orientation and size, or a fixed orientation), or use the lower-level form below. The read holds
 * the Activity weakly, and lets it go at the ending, so a finished read never keeps an Activity
 * alive; do not keep the read in a ViewModel past the screen.
 *
 * ## The lower-level form
 *
 * Your app can run reader mode itself: start the read with an adapter, hand it to
 * [NfcReaderMode.enable], show the updates, and act on the one ending.
 *
 * ```kotlin
 * // In a ViewModel, or anything else that outlives a configuration change:
 * val read = NfcChipRead.start(options, NfcAdapter.getDefaultAdapter(context), observer)
 *
 * // In the read screen, once per read, while the screen is shown (onCreate or onResume):
 * NfcReaderMode.enable(activity, read)?.let { reason ->
 *     // The read has already ended with NotStarted(reason): it cannot get a document.
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
 * - [NfcReaderMode.enable] replaces any reader mode your app had turned on for the same Activity,
 *   as the Activity-owning [start] does: see [NfcReaderMode].
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
 *   document waits until [NfcReadSettings.waitForDocumentMillis] passes, which by default is never ([NfcReadSettings.NO_LIMIT]):
 *   cancel a read your app no longer shows, or no other read can start.
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
 *   [ChipReadEnding.KeyRejected] and nothing is sent (see
 *   [io.lightine.tessera.emrtd.ChipReadOptions.allowCheckDigitMismatch]).
 * - Ends with [ChipReadEnding.NotStarted] when the device has no NFC (`NOT_SUPPORTED`), NFC is
 *   off (`DISABLED`), or NFC is on but its option for reading tags is off (`TAG_READING_DISABLED`,
 *   known from Android 15, API level 35, on).
 * - When no document is found within [NfcReadSettings.waitForDocumentMillis] (no limit by
 *   default), ends with [ChipReadEnding.Failed] and an
 *   [io.lightine.tessera.types.errors.NfcTimeout] with limit `WAIT_FOR_DOCUMENT`, 0 attempts: the
 *   app set that limit, so it is not [ChipReadEnding.EndedBySystem].
 * - While no document is found, the observer gets
 *   [io.lightine.tessera.emrtd.ChipReadState.WaitingForDocument] again every 2 seconds, with the
 *   time waited. A tag that is not an ISO 14443-4 chip (a transit card, for example) is skipped,
 *   and the read keeps waiting.
 * - When a document is found, waits [NfcReadSettings.settleDelayMillis] before the first command,
 *   connects, and reads. Each command has its own time limit:
 *   [NfcReadSettings.commandTimeoutBeforeAccessMillis] for the commands before the key is used;
 *   from the first command that uses it on (BAC's EXTERNAL AUTHENTICATE, PACE's first GENERAL
 *   AUTHENTICATE), [NfcReadSettings.commandTimeoutMillis] for the commands that make the chip do
 *   public-key work and [NfcReadSettings.readTimeoutMillis] for the others, such as reading a
 *   file. The platform's time limit is changed only when the next command needs another. A link that
 *   cannot be set up sends the read back to waiting, with no attempt used; after 3 such failures
 *   in a row the read ends with [ChipReadEnding.Failed] and
 *   [io.lightine.tessera.types.errors.NfcNoAnswer] when no try got past the first commands, the
 *   same ending as on iPhone.
 * - When the chip does not answer before the key is used (found, but with too little power to
 *   answer), reports [io.lightine.tessera.emrtd.ChipReadState.NoAnswerYet] and tries the same
 *   `Tag` again after a short pause, with no restart used, while the person moves the document:
 *   about one try every 2 seconds, for at most [NfcReadSettings.waitForReturnMillis] from the
 *   first unanswered try; then the read ends with [ChipReadEnding.Failed] and, when no try got past
 *   those commands, [io.lightine.tessera.types.errors.NfcNoAnswer].
 * - A `Tag` the platform can no longer connect to is let go at once and never called again: each
 *   call on it would put off the platform's own check that the tag is gone, and with it the search
 *   for the document. The read then waits for the platform to find the document again, within the
 *   same time bound, and reports the state it holds (`NoAnswerYet` or `LinkLost`) again every 2
 *   seconds.
 * - When the link is lost, follows [NfcReadSettings.linkLoss]: by default it waits for the
 *   document again and goes on, at most twice. The platform hands over a new `Tag` when the
 *   document comes back; when it does not, because the document never left the field, the SDK
 *   tries the earlier `Tag` again every second, until the platform can no longer connect to it.
 *   The Activity-owning form does not try while its Activity is paused (it has no reader mode
 *   then); the wait for the return keeps counting.
 * - When NFC is switched off during the read, ends with [ChipReadEnding.EndedBySystem] and
 *   [NfcUnavailable.Reason.DISABLED] (or `TAG_READING_DISABLED` for the option for reading tags),
 *   instead of waiting for a document that cannot come: at once when a command or a connect
 *   fails, and within 2 seconds while the read waits for a document.
 * - A fault the SDK does not expect (an exception from the platform's NFC code with no meaning
 *   the SDK knows) ends the read with [ChipReadEnding.Failed] and
 *   [NfcUnavailable.Reason.UNEXPECTED_FAULT]; it never reaches the app as an exception.
 * - Closes the link on every path, and the observer is never blocked by it: the read starts the
 *   close right before it hands the ending to the observer, and the platform's close, which talks
 *   to the tag and can take up to the command time limit, runs in the background. The next
 *   link, of this read or a later one, waits for that close (at most the command time limit), so
 *   the two never race on the tag.
 * - Tags found while a command runs, or after the ending, are ignored: never two reads at once.
 *
 * ## Threads
 *
 * [start], [cancel] and [onTagDiscovered] return at once, from any thread. The read runs on the
 * I/O dispatcher. The observer's calls never overlap and come in order, with the one ending last,
 * and by default ([NfcReadSettings.observerOn] is [ObserverThread.MAIN]) they come on the main
 * thread, handed over through the main looper in the order the SDK made them; none comes before
 * [start] returns. With [ObserverThread.CALLER] they come from the read's own thread, or from the
 * thread that calls [start] or [cancel] for the updates and the ending those cause: then
 * [io.lightine.tessera.emrtd.ChipReadState.Starting], and the ending of a read that cannot start,
 * can come before [start] returns. One exception to the thread: a read that owns the Activity's
 * reader mode (`start(activity, ...)`) hands over its ending on the main thread with either
 * value, because it turns reader mode off there first. An exception the observer throws is
 * ignored, so it never reaches the main thread.
 *
 * The read lets go of the observer when it hands over the ending (with [ObserverThread.MAIN], once
 * the main thread has run that call).
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
    private val coordinator =
        ChipReadCoordinator(
            options,
            settings.linkLoss,
            Forwarder(if (settings.observerOn == ObserverThread.MAIN) HandoffObserver(observer, env.main::post) else observer),
            settings.telemetry?.let { ChipReader(it) } ?: env.reader,
            clearKeyAtEnding = true,
        )
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

    // The screen of an Activity-owning read; null for the callback form. Set once, before begin.
    @Volatile
    private var hold: ActivityHold? = null

    /** `true` once the read's ending is decided. */
    override val isEnded: Boolean get() = coordinator.isEnded

    /** The screen this read holds, for tests: `null` for the callback form, and after the ending. */
    internal val heldActivity: Activity? get() = hold?.activity

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
        launchRun()
    }

    /** The Activity-owning start: the same first steps, then reader mode on the main thread, then the run. */
    internal fun beginOwned(owner: ActivityHold) {
        hold = owner
        if (!coordinator.start()) return
        if (!env.registry.claim(this)) {
            coordinator.notStarted(NfcUnavailable.Reason.READ_IN_PROGRESS)
            return
        }
        env.main.runNow { prepareOwned(owner) }
    }

    /** On the main thread: the screen must be shown, then reader mode goes on once, then the read runs. */
    private fun prepareOwned(owner: ActivityHold) {
        if (coordinator.isEnded) return
        val activity = owner.activity
        if (activity == null || !env.activities.isUsable(activity)) {
            coordinator.notStarted(NfcUnavailable.Reason.ACTIVITY_DESTROYED)
            return
        }
        owner.watch()
        val refused = env.activities.enable(activity, this, settings)
        if (refused != null) {
            coordinator.notStarted(refused)
            return
        }
        owner.enabled = true
        launchRun()
    }

    /** The Activity was destroyed: reader mode ended with it, so the read ends. */
    internal fun activityDestroyed() {
        coordinator.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.ACTIVITY_DESTROYED))
    }

    private fun launchRun() {
        job =
            scope.launch {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A platform fault the read does not expect still gets an ending, named as such.
                    coordinator.fail(UNEXPECTED_FAULT)
                } finally {
                    setIntake(false)
                    closeLink()
                    // Every path above ends the read; this only guards against a path that did not.
                    if (!coordinator.isEnded) coordinator.fail(UNEXPECTED_FAULT)
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
        if (env.platform.isTagReadingOff(adapter)) {
            coordinator.notStarted(NfcUnavailable.Reason.TAG_READING_DISABLED)
            return
        }
        setIntake(true)
        coordinator.waiting(0)
        val began = env.now()
        var waitingSince = began
        // Before the first link: the app's limit on finding a document. After a lost link: the
        // limit on its return, counted from the loss.
        var deadline: Long? =
            if (settings.waitForDocumentMillis != NfcReadSettings.NO_LIMIT) saturatingAdd(began, settings.waitForDocumentMillis) else null
        var linkedOnce = false
        var lostTag: Tag? = null
        var retryMillis = SAME_TAG_RETRY_MILLIS
        // When the first of the unanswered tries in a row ended; null while the chip answers.
        var silentSince: Long? = null
        var failedConnects = 0
        while (!coordinator.isEnded) {
            val found = awaitTag(adapter, waitingSince, deadline, lostTag, linkedOnce, retryMillis) ?: return
            // A new discovery makes the earlier Tag out of date: only the new one is tried from now on.
            if (!found.earlier) lostTag = null
            val release = CompletableDeferred<Unit>()
            // The link starts with the short limit for the commands before the key is used.
            val connection = env.platform.connectionOf(found.tag, settings.effectiveTimeoutBeforeAccessMillis) { release.complete(Unit) }
            if (connection == null) {
                // Not an ISO 14443-4 chip, so no document this read can use: keep waiting.
                continue
            }
            val transport: IsoDepTransport
            if (found.earlier) {
                // The same Tag after a lost link or an unanswered try: try the link quietly; the
                // person sees LinkLost or NoAnswerYet until it is up.
                val reopened = open(connection, release).getOrNull()
                if (reopened == null) {
                    // The platform can no longer connect to this Tag: it has lost it. Let go of it
                    // at once. Every call on it postpones the platform's presence check, the only
                    // thing that makes it notice the tag is gone and search again; so nothing more
                    // is called on it, and the read waits for a new discovery (TES-346).
                    lostTag = null
                    continue
                }
                transport = reopened
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
                    val linkError = (error as? ApduTransportException)?.error
                    if (linkError != null && linkError.isLinkLevel()) {
                        if (++failedConnects < MAX_CONNECT_ATTEMPTS) {
                            // No command was sent: back to waiting, with no attempt used.
                            coordinator.connectionFailed()
                            continue
                        }
                        // The found chip never let the link be set up: no answer, as on iPhone.
                        coordinator.connectsFailed(linkError)
                        return
                    }
                    coordinator.fail(linkError ?: NfcTagLost)
                    return
                }
                transport = opened.getOrThrow()
                if (!holdLink(transport)) return
            }
            failedConnects = 0
            linkedOnce = true
            val outcome =
                coordinator.read(
                    transport,
                    readAgainAllowed = true,
                    systemEnd = { nfcOff(adapter)?.let(::NfcUnavailable) },
                    useTimeLimit = { kind -> transport.useTimeLimit(settings.timeoutFor(kind)) },
                )
            if (outcome == ChipReadAttemptOutcome.ENDED) return
            closeLink()
            lostTag = found.tag
            waitingSince = env.now()
            if (outcome == ChipReadAttemptOutcome.NO_ANSWER_YET) {
                // The document is most likely still there: try again soon, bounded from the first
                // unanswered try, so a person who keeps moving it does not keep the read alive.
                val since = silentSince ?: waitingSince.also { silentSince = it }
                deadline = saturatingAdd(since, settings.waitForReturnMillis)
                retryMillis = NO_ANSWER_RETRY_MILLIS
            } else {
                silentSince = null
                deadline = saturatingAdd(waitingSince, settings.waitForReturnMillis)
                retryMillis = SAME_TAG_RETRY_MILLIS
            }
            setIntake(true)
        }
    }

    /**
     * Waits for a tag: a new discovery, or after a lost link the earlier [lostTag] again, every
     * [retryMillis] while no new one comes. Every [WAITING_REPORT_MILLIS] it reports the
     * time waited (or, after a link with no [lostTag] left to try, the state the read holds again)
     * and checks that NFC is still on. `null` when the read ended, here or elsewhere.
     */
    private suspend fun awaitTag(
        adapter: NfcAdapter,
        since: Long,
        deadline: Long?,
        lostTag: Tag?,
        linkedOnce: Boolean,
        retryMillis: Long,
    ): Found? {
        val start = env.now()
        var nextReport = since + ((start - since) / WAITING_REPORT_MILLIS + 1) * WAITING_REPORT_MILLIS
        var retryAt = if (lostTag != null) start + retryMillis else Long.MAX_VALUE
        while (!coordinator.isEnded) {
            val now = env.now()
            if (deadline != null && now >= deadline) {
                if (linkedOnce) {
                    coordinator.documentNotReturned()
                } else {
                    // The app's own limit passed: neither the system nor the person ended the read.
                    coordinator.fail(NfcTimeout(settings.waitForDocumentMillis, NfcTimeout.Limit.WAIT_FOR_DOCUMENT))
                }
                return null
            }
            val wake = minOf(nextReport, retryAt, deadline ?: Long.MAX_VALUE)
            val tag = tags.tryReceive().getOrNull() ?: withTimeoutOrNull((wake - now).coerceAtLeast(0)) { tags.receive() }
            if (tag != null) return Found(tag, earlier = false)
            val after = env.now()
            if (after >= nextReport) {
                if (endIfNfcOff(adapter)) return null
                if (linkedOnce && lostTag == null) {
                    // The earlier Tag was let go, so no try comes until the platform finds the
                    // document again: the state the read holds (NoAnswerYet or LinkLost) is
                    // reported again, so the app is never left without news.
                    coordinator.connectionFailed()
                } else {
                    // While the read waits after a lost link, LinkLost stays: the coordinator drops this.
                    coordinator.waiting(after - since)
                }
                while (nextReport <= after) nextReport += WAITING_REPORT_MILLIS
            }
            if (lostTag != null && after >= retryAt) {
                // A paused screen has no reader mode, so the earlier Tag cannot answer: no retry
                // until it resumes. The wait for the document's return goes on counting.
                if (hold?.paused != true) return Found(lostTag, earlier = true)
                retryAt = after + retryMillis
            }
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

    /** Why NFC cannot read tags now: switched off, or its option for reading tags is off; `null` when it can. */
    private fun nfcOff(adapter: NfcAdapter): NfcUnavailable.Reason? =
        when {
            !env.platform.isEnabled(adapter) -> NfcUnavailable.Reason.DISABLED
            env.platform.isTagReadingOff(adapter) -> NfcUnavailable.Reason.TAG_READING_DISABLED
            else -> null
        }

    /** Ends the read when NFC was switched off; `true` when the read has ended, by this or before. */
    private fun endIfNfcOff(adapter: NfcAdapter): Boolean {
        nfcOff(adapter)?.let { coordinator.endedBySystem(NfcUnavailable(it)) }
        return coordinator.isEnded
    }

    /**
     * The callback form: [NfcReaderMode.enable] could not turn reader mode on for this read, so it
     * cannot get a document. It ends as not started, as the Activity-owning start does.
     */
    internal fun readerModeRefused(reason: NfcUnavailable.Reason) {
        if (coordinator.notStarted(reason)) job?.cancel()
    }

    /** Releases the link in the background: the platform's close talks to the tag. */
    private fun closeLink() {
        link.getAndSet(null)?.close()
    }

    /**
     * What happens when the ending is decided, before the app hears of it. [then] hands the ending to
     * the app. An Activity-owning read first turns reader mode off, on the main thread, and only
     * then lets another read start and the app hear the ending, so an app that starts a new read in
     * its ending does not have it switched off behind its back.
     */
    private fun finish(then: () -> Unit) {
        setIntake(false)
        val owner = hold
        if (owner == null) env.registry.ended(this)
        closeLink()
        job?.cancel()
        if (owner == null) {
            then()
        } else {
            owner.release {
                env.registry.ended(this)
                then()
            }
        }
    }

    /**
     * Hands the coordinator's calls to the app, after the SDK's own work on the ending, and lets go
     * of the app's observer at the ending, so a read the app still holds keeps nothing of it.
     */
    private inner class Forwarder(
        app: ChipReadObserver,
    ) : ChipReadObserver {
        // Only the coordinator's one drainer calls in, one call at a time.
        private var app: ChipReadObserver? = app

        override fun onUpdate(update: ChipReadUpdate) {
            app?.onUpdate(update)
        }

        override fun onEnding(ending: ChipReadEnding) {
            val heldApp = app ?: return
            app = null
            finish { heldApp.onEnding(ending) }
        }
    }

    /**
     * The screen an Activity-owning read belongs to. It holds the Activity weakly and lets go of it
     * at the ending, watches for its destruction, and remembers whether this read turned reader
     * mode on, so only what it switched on is switched off.
     */
    internal inner class ActivityHold(
        activity: Activity,
    ) {
        private val ref = WeakReference(activity)
        private val application: Application? = activity.application
        private var watching = false

        /** Whether this read turned reader mode on. Touched on the main thread only. */
        var enabled = false

        /** Whether the Activity is paused (no reader mode until it resumes), as its callbacks report. */
        @Volatile
        var paused = false

        /** The Activity, or `null` once it is gone or the read let go of it. */
        val activity: Activity? get() = ref.get()

        private val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === ref.get()) activityDestroyed()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (activity === ref.get()) paused = false
                }

                override fun onActivityPaused(activity: Activity) {
                    if (activity === ref.get()) paused = true
                }

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            }

        /** Starts watching for the Activity's destruction. Main thread. */
        fun watch() {
            if (watching) return
            watching = true
            application?.registerActivityLifecycleCallbacks(callbacks)
        }

        /** Stops watching, turns reader mode off (when this read turned it on) and lets go of the Activity; then [after]. */
        fun release(after: () -> Unit) {
            env.main.runNow {
                if (watching) {
                    watching = false
                    application?.unregisterActivityLifecycleCallbacks(callbacks)
                }
                val held = ref.get()
                // A destroyed or finishing Activity has no reader mode left to turn off, and the
                // platform's call on it can throw: the ending may come from another thread after
                // the Activity went away.
                if (enabled && held != null && env.activities.isUsable(held)) env.activities.disable(held)
                enabled = false
                ref.clear()
                after()
            }
        }
    }

    /** Starts a read. */
    public companion object {
        /**
         * Whether NFC can be used right now, before a read starts: the same question, with the
         * same answer, as [NfcReaderMode.availability], and as `CoreNfcChipRead.availability()`
         * on iPhone.
         *
         * @param context Any context of the app.
         * @return `null` when NFC is usable; otherwise why it is not: `NOT_SUPPORTED`, `DISABLED`,
         *   or `TAG_READING_DISABLED` (from Android 15, API level 35, on).
         * @since 1.0.0
         */
        @JvmStatic
        public fun availability(context: Context): NfcUnavailable.Reason? = NfcReaderMode.availability(context)

        /**
         * Whether a read started now is expected to be accepted at once: for a "read the chip"
         * button. The same question and type as `CoreNfcChipRead.readiness()` on iPhone.
         * [ChipReadReadiness.Unavailable] as [availability] reports it (no NFC reader, NFC off,
         * or its option for reading tags off); else [ChipReadReadiness.ReadInProgress] while a
         * read of this app runs (a start now ends with `NotStarted(READ_IN_PROGRESS)`); else
         * [ChipReadReadiness.Ready]. Android never reports [ChipReadReadiness.NotYet]: it has no
         * wait between reads that an app would see (a new read's first link waits for the last
         * link's close by itself, at most the command time limit, while it already waits for a
         * document).
         *
         * Each call asks the platform afresh. Cheap enough for a screen's `onResume`.
         *
         * @param context Any context of the app; only its application context is used.
         * @return The readiness now.
         * @since 1.0.0
         */
        @JvmStatic
        public fun readiness(context: Context): ChipReadReadiness = NfcReadEnvironment.process.readiness.current(context)

        /**
         * Registers [listener] to hear when [readiness] changes kind, on the main thread: when the
         * person switches NFC on or off (the platform's `ACTION_ADAPTER_STATE_CHANGED` broadcast),
         * and when a read of this app starts or ends. Not called at registration: register, then
         * ask [readiness]. A change of the option for reading tags has no broadcast: it shows at
         * the next of these changes, and in [readiness] when asked.
         *
         * The SDK registers one broadcast receiver, with the application context of [context],
         * while at least one listener is registered, and unregisters it with the last one; it
         * keeps no Activity. From Android 13 (API level 33) on it is registered as not exported:
         * the broadcast comes from the system, never from another app. From Android 14 the
         * system may hold broadcasts back while the app is in the cached state and deliver them
         * when it returns, so ask [readiness] again in `onResume`. The SDK holds the listener,
         * and what it refers to, until [ChipReadReadinessRegistration.remove]: remove it when the
         * screen goes away.
         *
         * @param context Any context of the app; only its application context is kept.
         * @param listener Called with each new value.
         * @return The registration, to remove the listener.
         * @since 1.0.0
         */
        @JvmStatic
        public fun addReadinessListener(
            context: Context,
            listener: ChipReadReadinessListener,
        ): ChipReadReadinessRegistration = NfcReadEnvironment.process.readiness.add(context, listener)

        /**
         * Starts a read whose reader mode your app runs. Returns at once; the read waits for reader
         * mode to hand it a document: pass it to [NfcReaderMode.enable]. The parameters come in the
         * order of the Activity-owning start: what to read, then the observer, then the settings.
         *
         * @param options The key, the data groups, the roots and the check time.
         * @param adapter The device's NFC adapter, from `NfcAdapter.getDefaultAdapter(context)`;
         *   `null` (no NFC on the device) ends the read with [ChipReadEnding.NotStarted].
         * @param observer Gets every update and exactly one ending.
         * @param settings What to do after a lost link, and the time limits.
         * @return The read: the reader-mode callback, and the handle to cancel it.
         * @since 1.0.0
         */
        @JvmStatic
        @JvmOverloads
        public fun start(
            options: ChipReadOptions,
            adapter: NfcAdapter?,
            observer: ChipReadObserver,
            settings: NfcReadSettings = NfcReadSettings(),
        ): NfcChipRead = start(options, adapter, observer, settings, NfcReadEnvironment.process)

        internal fun start(
            options: ChipReadOptions,
            adapter: NfcAdapter?,
            observer: ChipReadObserver,
            settings: NfcReadSettings,
            env: NfcReadEnvironment,
        ): NfcChipRead = NfcChipRead(options, adapter, settings, observer, env).also { it.begin() }

        /**
         * Starts a read that owns reader mode: the high-level way to read a chip on Android. Returns at
         * once. It checks that NFC can be used, turns reader mode on for [activity] with the SDK's
         * flags and [NfcReadSettings.presenceCheckDelayMillis], runs the read, and turns reader
         * mode off at the ending, on every ending and before the observer hears it. Reader mode is
         * never switched while a document may be on the phone: once on at the start, once off at the
         * end.
         *
         * Call it on the main thread, with the Activity that shows the read, from a person's
         * action (for example a button), not from `onCreate`.
         *
         * **A rotation ends the read.** The read is for this one Activity. When the Activity is
         * destroyed and made again (a rotation or any other configuration change), the read ends at
         * once as [ChipReadEnding.EndedBySystem] with [NfcUnavailable.Reason.ACTIVITY_DESTROYED] and
         * the key is cleared. Starting it again needs a new key and spends another authentication
         * attempt with the chip. Two ways to avoid it: declare `android:configChanges` for the
         * orientation and size of the read screen, so the Activity is not destroyed, or lock the
         * orientation while the read runs. A read that was not started from a person's action but
         * from `onCreate` also starts again after every rotation, so start it from a button.
         *
         * When the read cannot start it ends at once as [ChipReadEnding.NotStarted] with a typed reason: [NfcUnavailable.Reason.ACTIVITY_DESTROYED]
         * for an Activity that is finishing or destroyed, `NOT_SUPPORTED`, `DISABLED`,
         * `NOT_PERMITTED` or `REQUEST_REFUSED` as [NfcReaderMode.enable] reports them, and
         * `READ_IN_PROGRESS` for a second read at once. The SDK does not ask whether [activity] is
         * resumed: the platform applies reader mode when it resumes. See the class documentation
         * for a pause in the middle of a read. The read holds [activity] weakly and lets it go at
         * the ending.
         *
         * By default ([NfcReadSettings.observerOn] is [ObserverThread.MAIN]) the observer is
         * called on the main thread and hears nothing before this call returns. With
         * [ObserverThread.CALLER] the updates come from the read's own thread, and
         * [io.lightine.tessera.emrtd.ChipReadState.Starting], and the ending of a read that cannot
         * start, can come before this call returns; the ending still comes on the main thread,
         * where the read turns reader mode off first.
         *
         * @param activity The read screen, shown now.
         * @param options The key, the data groups, the roots and the check time. The read takes the
         *   key over and clears it at the ending.
         * @param observer Gets every update and exactly one ending.
         * @param settings What to do after a lost link, the time limits, reader mode and the
         *   observer's thread.
         * **Your app's own reader mode.** An Activity has one reader mode: this start replaces any
         * reader mode your app turned on for [activity] (its callback hears no tag during the
         * read), and the ending turns reader mode off for [activity]. The platform has no call
         * that tells whether an Activity already has reader mode on, so the SDK cannot report it:
         * turn your own reader mode off before the read, and on again after its ending if you need it.
         *
         * @return The read, to cancel it. It is also the reader-mode callback, already registered:
         *   do not pass it to [NfcReaderMode.enable].
         * @since 1.0.0
         */
        @JvmStatic
        @JvmOverloads
        public fun start(
            activity: Activity,
            options: ChipReadOptions,
            observer: ChipReadObserver,
            settings: NfcReadSettings = NfcReadSettings(),
        ): NfcChipRead = start(activity, options, observer, settings, NfcReadEnvironment.process)

        internal fun start(
            activity: Activity,
            options: ChipReadOptions,
            observer: ChipReadObserver,
            settings: NfcReadSettings,
            env: NfcReadEnvironment,
        ): NfcChipRead {
            val read = NfcChipRead(options, env.activities.adapter(activity), settings, observer, env)
            read.beginOwned(read.ActivityHold(activity))
            return read
        }

        /** How often the waiting state repeats with the time waited: the shared value. */
        internal const val WAITING_REPORT_MILLIS = ChipReadCoordinator.WAITING_REPORT_MILLIS

        /** After a lost link with no new discovery, how often the earlier Tag is tried again. */
        internal const val SAME_TAG_RETRY_MILLIS = 1_000L

        /**
         * After a try the chip did not answer before access, how soon the same Tag is tried again.
         * With the default settle time (400) and time limit before access (1500), a person moving
         * the document gets about one try every 2 seconds.
         */
        internal const val NO_ANSWER_RETRY_MILLIS = 300L

        /** The ending's error for a fault the read did not expect. */
        private val UNEXPECTED_FAULT = NfcUnavailable(NfcUnavailable.Reason.UNEXPECTED_FAULT)

        /** Failed connects in a row after which the read ends: the shared value. */
        internal const val MAX_CONNECT_ATTEMPTS = ChipReadCoordinator.MAX_CONNECT_ATTEMPTS

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
    val main: MainThread = InlineMainThread,
    val activities: ActivityPlatform = PlatformActivities,
    val state: NfcStateSource = PlatformNfcState,
) {
    /** The readiness of a new read in this environment, and its listeners. */
    val readiness: NfcReadinessWatch by lazy { NfcReadinessWatch(registry, state, main) }

    companion object {
        /** The real platform, one registry for the whole process. */
        val process: NfcReadEnvironment by lazy {
            NfcReadEnvironment(
                platform = PlatformNfcChip,
                dispatcher = Dispatchers.IO,
                now = { System.nanoTime() / NANOS_PER_MILLI },
                registry = NfcReadRegistry(),
                main = AndroidMainThread,
            )
        }

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The main thread, as the Activity-owning read and the observer hand-over use it. */
internal interface MainThread {
    /** `true` on the main thread. */
    fun isCurrent(): Boolean

    /** Runs [block] later on the main thread, in the order of the calls; never before this returns. */
    fun post(block: () -> Unit)

    /** Runs [block] now when already on the main thread, otherwise posts it. */
    fun runNow(block: () -> Unit) {
        if (isCurrent()) block() else post(block)
    }
}

/** The platform's main looper. */
internal object AndroidMainThread : MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun isCurrent(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    override fun post(block: () -> Unit) {
        handler.post(block)
    }
}

/** For tests that do not look at threads: everything runs on the calling thread, at once. */
internal object InlineMainThread : MainThread {
    override fun isCurrent(): Boolean = true

    override fun post(block: () -> Unit) = block()
}

/** What the Activity-owning read asks of the platform about a screen, so its flow can be tested with a fake. */
internal interface ActivityPlatform {
    /** The device's NFC adapter, or `null` without NFC. */
    fun adapter(activity: Activity): NfcAdapter?

    /**
     * Whether reader mode can still be turned on for [activity]: it is not finishing or destroyed.
     * The Activity's resumed state is not asked: the platform has no public way to read it, and it
     * holds a reader-mode request made before the Activity resumes (for example in `onCreate`)
     * until it does.
     */
    fun isUsable(activity: Activity): Boolean

    /** Turns reader mode on for [activity]; `null` when it is on, otherwise why not. */
    fun enable(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        settings: NfcReadSettings,
    ): NfcUnavailable.Reason?

    /** Turns reader mode off for [activity]. */
    fun disable(activity: Activity)
}

/** The real platform. */
internal object PlatformActivities : ActivityPlatform {
    override fun adapter(activity: Activity): NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    override fun isUsable(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed

    override fun enable(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        settings: NfcReadSettings,
    ): NfcUnavailable.Reason? = NfcReaderMode.turnOn(activity, callback, settings.presenceCheckDelayMillis, settings.platformSounds)

    override fun disable(activity: Activity) = NfcReaderMode.disable(activity)
}

/**
 * The reads of one process: at most one live read, and the links whose platform close has not
 * finished, so a new link never races a close on the tag.
 */
internal class NfcReadRegistry {
    private val live = AtomicReference<Any?>(null)

    /** Called after a read became live or ended; set once by the readiness watch. */
    @Volatile
    var onChange: () -> Unit = {}

    // Collections.newSetFromMap, not ConcurrentHashMap.newKeySet(): the latter is API 24 and minSdk is 23.
    private val releasing: MutableSet<CompletableDeferred<Unit>> =
        Collections.newSetFromMap(ConcurrentHashMap<CompletableDeferred<Unit>, Boolean>())

    /** Makes [read] the live read; `false` when another read is live. */
    fun claim(read: Any): Boolean = live.compareAndSet(null, read).also { if (it) onChange() }

    /** [read] has its ending: another read may start. */
    fun ended(read: Any) {
        if (live.compareAndSet(read, null)) onChange()
    }

    /** Whether a read is live. */
    fun isLive(): Boolean = live.get() != null

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

    /** Whether NFC's option for reading tags is known to be off, with NFC on: see [NfcReaderMode.isTagReadingOff]. */
    fun isTagReadingOff(adapter: NfcAdapter): Boolean

    /**
     * The ISO-DEP connection of [tag], or `null` when the tag speaks none. Nothing is sent to the
     * tag here. [onReleased] runs once, after the platform's close of the link returned.
     *
     * @param timeoutMillis How long to wait for the chip's answer to one command.
     */
    fun connectionOf(
        tag: Tag,
        timeoutMillis: Long,
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
    timeoutMillis: Long,
    io: CoroutineDispatcher,
    onReleased: () -> Unit,
): IsoDepConnection = IsoDepConnection { IsoDepTransport.open(ReleaseSignallingChannel(channel, onReleased), timeoutMillis, io) }

/** The platform itself: `NfcAdapter` for the state, `IsoDep` for the tag's link. */
internal object PlatformNfcChip : NfcChipPlatform {
    override fun isEnabled(adapter: NfcAdapter): Boolean = adapter.isEnabled

    override fun isTagReadingOff(adapter: NfcAdapter): Boolean = NfcReaderMode.isTagReadingOff(adapter)

    override fun connectionOf(
        tag: Tag,
        timeoutMillis: Long,
        onReleased: () -> Unit,
    ): IsoDepConnection? {
        val isoDep = IsoDep.get(tag) ?: return null
        return connectionOver(PlatformIsoDepChannel(isoDep), timeoutMillis, Dispatchers.IO, onReleased)
    }
}
