package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The low-level path for a chip read on iPhone, where you own the session: shows the system NFC
 * sheet, waits for a passport chip, connects to it and gives you a [CoreNfcTransport] for the
 * shared chip code. Most apps should use [CoreNfcChipRead] instead, which owns the whole read: the
 * session, the sheet's texts, a lost link, time limits and the one ending.
 *
 * One session reads one chip. Call [connect], read through the transport, and end the session in
 * `finally`, with success only for a complete read:
 *
 * ```kotlin
 * val session = CoreNfcSession(
 *     alertMessage = "Hold your iPhone near the document.",
 *     failureMessage = "Reading failed. Try again.",
 * )
 * var complete = false
 * try {
 *     val transport = session.connect()
 *     complete = ChipReader().read(transport, options) is ChipReadResult.Complete
 * } finally {
 *     if (complete) session.end() else session.end(errorMessage = "Reading failed. Try again.")
 * }
 * ```
 *
 * ## Interruptions: what the SDK does, what your app does
 *
 * - The document moves away or the link breaks: the next command fails with
 *   [io.lightine.tessera.types.errors.NfcTagLost] and the read stops with what it has. End the
 *   session with your failure text; start a new session to try again.
 * - The person taps Cancel, the app goes to the background, the screen locks, or the system ends
 *   the session for another reason: the system ends it; [connect] or the next command fails with
 *   the reason (`CANCELLED_BY_USER`, `ENDED_BY_SYSTEM`, [io.lightine.tessera.types.errors.NfcTimeout]).
 *   A later [end] does nothing; calling it anyway is safe.
 * - Your app cancels the coroutine: during [connect], the session ends at once with the failure
 *   text; during a command, the transport stops (see [CoreNfcTransport]) and you end the session.
 * - Your app calls [end] from another thread during a command: the command fails with
 *   [NfcUnavailable] `CLOSED` and nothing more is sent.
 * - Your app drops its references mid-read: the SDK keeps the session alive until the system
 *   reports its end, so its callbacks are never lost, but nothing ends it before the system's time
 *   limit. Always end the session.
 * - Start the next read with a new session only after the last one ended. If [connect] fails with
 *   [NfcUnavailable] `BUSY` right after a session ended, wait a moment and try again.
 *
 * The session is ended once, by the system or by the first ending your app or the SDK asks for;
 * every later ending does nothing.
 *
 * ## How it looks for chips
 *
 * From iOS 26.4 the session polls ISO 14443 and PACE together and selects only the eMRTD
 * application (`A0000002471001`), so chips that answer only after PACE are found too. Before
 * iOS 26.4 Core NFC cannot combine the two, so the session polls ISO 14443 alone with the
 * select identifiers from your Info.plist: most chips are found this way, but a chip that refuses
 * the application select before PACE is not. When more than one tag is in the field, the tag is
 * not an ISO 7816 tag, the session keeps looking until the person cancels or the time limit
 * passes. When the link to the chip breaks while connecting, or the chip does not answer, the
 * session looks again; after the third such failure in a row, [connect] throws that error.
 * Any other failure to connect, the session's own time limit included, ends the system session
 * with your failure text: then [connect] throws, and a later [end] does nothing.
 *
 * ## Time limits
 *
 * - Core NFC's headers state a limit of 60 seconds after a reader session begins (in
 *   `NFCNDEFReaderSession.h`; `NFCTagReaderSession.h` does not repeat it). When the limit passes,
 *   the session ends with [io.lightine.tessera.types.errors.NfcTimeout].
 * - An engineer of the vendor has stated, outside the headers, two more limits: a connected tag
 *   gets about 20 seconds, and a new session started right after one ends may be refused for a
 *   short time. The headers do not state either; plan the read to be short, and let the person
 *   try again.
 *
 * ## What your app needs
 *
 * - A paid developer program team (or an enterprise team): the vendor does not offer NFC tag
 *   reading to a free account, so a device build cannot be signed with it.
 * - The entitlement `com.apple.developer.nfc.readersession.formats` with the values `TAG` and
 *   `PACE` (add the "Near Field Communication Tag Reading" capability in Xcode, then add `PACE`).
 *   The `PACE` value is documented in Core NFC's header `NFCTagReaderSession.h`, which says that
 *   PACE polling needs it.
 * - In Info.plist: `NFCReaderUsageDescription`, a text that says why the app reads NFC, and
 *   `com.apple.developer.nfc.readersession.iso7816.select-identifiers`, an array that holds
 *   `A0000002471001` (the eMRTD application). Without the usage description the system ends the
 *   app when it uses the reader: that is not an error [connect] can report.
 * - A device with an NFC reader (iPhone 7 or later). The Simulator has none: there [connect]
 *   fails with [NfcUnavailable] `NOT_SUPPORTED`.
 *
 * ## Errors
 *
 * [connect] throws [ApduTransportException], whose `error` is:
 * - [NfcUnavailable] `NOT_SUPPORTED` when the device cannot read NFC tags; `NOT_PERMITTED` when
 *   the entitlement is missing, the select identifiers in Info.plist hold no valid entry, or the
 *   system does not allow the app to read; `DISABLED` when the radio is off; `BUSY` when Core NFC
 *   is busy for now (Core NFC calls this temporary: wait, then try again); `CANCELLED_BY_USER`
 *   when the person ended the sheet; `CLOSED` when the app called [end]; `ENDED_BY_SYSTEM` when
 *   the system ended the session on its own, for a reason the SDK is not told (the headers name
 *   one case: the app went to the background); `REQUEST_REFUSED` when Core NFC refused a request,
 *   such as a session setting or a command it does not accept;
 * - [io.lightine.tessera.types.errors.NfcTimeout] when the session's own time limit passed
 *   before a chip was connected.
 *
 * Once the session ends, the transport's [CoreNfcTransport.transceive] throws the same error. The
 * transport keeps the system session alive while it runs, so a read goes on even when your app
 * keeps only the transport.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift, this class is `CoreNfcSession` in the
 * `Tessera` framework (call `connect()` with `try await`) and is experimental too.
 *
 * @param alertMessage The text the system NFC sheet shows while it looks for the chip, in your
 *   app's language. Change it later with [alertMessage].
 * @param failureMessage The text the sheet shows when the SDK ends the session with a failure.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcSession internal constructor(
    alertMessage: String,
    private val failureMessage: String,
    private val readingAvailable: () -> Boolean,
    private val openSession: (ReaderSessionEvents) -> ReaderSessionPort,
) {
    /**
     * Creates a session; nothing shows until [connect].
     *
     * @param alertMessage The text the system NFC sheet shows while it looks for the chip.
     * @param failureMessage The text the sheet shows when the SDK itself ends the session with a
     *   failure: a chip that could not be connected, or a [connect] whose coroutine was cancelled.
     *   In your app's language.
     */
    public constructor(alertMessage: String, failureMessage: String) :
        this(alertMessage, failureMessage, ::platformReadingAvailable, ::PlatformReaderSession)

    private val lock = NSLock()
    private var started = false
    private var ended = false
    private var endedByApp = false
    private var port: OwnedReaderSession? = null

    // The system session from begin until the system reports its end; unlike [port] it is not let
    // go when the app ends the session, so the system's end report still reaches it.
    private var owned: OwnedReaderSession? = null
    private var pending: CompletableDeferred<CoreNfcTransport>? = null
    private var connecting = false
    private var transport: CoreNfcTransport? = null
    private var currentAlert: String = alertMessage

    // Failed connects in a row that restarted polling; bounded by [MAX_CONNECT_ATTEMPTS].
    private var failedConnects = 0

    /**
     * The text the system NFC sheet shows. Set it to tell the person how the read goes, for
     * example "Reading the photo". The SDK never writes text on the sheet itself.
     */
    public var alertMessage: String
        get() = locked { currentAlert }
        set(value) {
            val target =
                locked {
                    currentAlert = value
                    if (ended) null else port
                }
            target?.setAlert(value)
        }

    /**
     * Shows the system NFC sheet, waits for a passport chip and connects to it.
     *
     * Call it once per session. If the calling coroutine is cancelled while it waits, the session
     * ends at once with the failure message.
     *
     * @return A transport connected to the chip.
     * @throws ApduTransportException when no chip could be connected; see the class
     *   documentation for each error.
     * @throws IllegalStateException when [connect] was already called on this session.
     */
    @Throws(ApduTransportException::class, CancellationException::class)
    public suspend fun connect(): CoreNfcTransport {
        val result = CompletableDeferred<CoreNfcTransport>()
        val alreadyEnded =
            locked {
                check(!started) { "connect() was already called on this session; start a new session" }
                started = true
                pending = result
                ended
            }
        if (alreadyEnded) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
        if (!readingAvailable()) {
            locked {
                ended = true
                pending = null
            }
            throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))
        }
        // The system session begins at most once and ends exactly once (OwnedReaderSession). It
        // keeps this object alive until the system says the session ended, so its delegate (held
        // weakly by Core NFC) lives as long as the system session does, whatever the app drops.
        val session = OwnedReaderSession(openSession(events), keeper = this)
        val text =
            locked {
                if (ended) return@locked null
                port = session
                owned = session
                currentAlert
            } ?: throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
        session.setAlert(text)
        session.begin()
        try {
            return result.await()
        } catch (e: CancellationException) {
            end(failureMessage)
            throw e
        }
    }

    /**
     * Ends the session with success: the system sheet shows its check mark and closes. Use it only
     * after a complete read. The transport stops; its [CoreNfcTransport.transceive] throws
     * [NfcUnavailable] `CLOSED`. Safe to call more than once, from any thread, and after the person
     * or the system ended the session: only the first ending counts.
     */
    public fun end() {
        endWith(Ending(null))
    }

    /**
     * Ends the session with a failure: the system sheet shows [errorMessage] and closes. The
     * transport stops as with [end]. Use it on every path that is not a complete read.
     *
     * @param errorMessage The text the sheet shows, in your app's language.
     */
    public fun end(errorMessage: String) {
        endWith(Ending(errorMessage))
    }

    private fun endWith(ending: Ending) {
        val target =
            locked {
                if (ended) return@locked null
                ended = true
                endedByApp = true
                takeEnded()
            } ?: return
        val closed = NfcUnavailable(NfcUnavailable.Reason.CLOSED)
        target.transport?.stop(closed)
        target.pending?.completeExceptionally(ApduTransportException(closed))
        target.port?.end(ending.errorMessage)
    }

    /** One kind of ending: success when [errorMessage] is `null`, else a failure with that text. */
    private class Ending(
        val errorMessage: String?,
    )

    /**
     * Hands over what the session still holds and lets go of it, so the system session, its
     * delegate and this object stop keeping each other alive. Call under the lock.
     */
    private fun takeEnded(): Ended {
        val held = Ended(port, pending, transport)
        port = null
        pending = null
        transport = null
        return held
    }

    private class Ended(
        val port: OwnedReaderSession?,
        val pending: CompletableDeferred<CoreNfcTransport>?,
        val transport: CoreNfcTransport?,
    )

    private val events =
        object : ReaderSessionEvents {
            override fun onTagsDetected(tags: List<DetectedTag>) = handleTags(tags)

            override fun onInvalidated(error: NfcError) = handleInvalidated(error)
        }

    private fun handleTags(tags: List<DetectedTag>) {
        val tag = tags.singleOrNull()
        val channel = tag?.iso7816
        // One decision under the lock: an ended session, a connect under way or a connected chip
        // ignore the tags; otherwise the session connects to the one ISO 7816 tag or keeps looking.
        val session =
            locked {
                if (ended || connecting || transport != null) return@locked null
                if (tag != null && channel != null) connecting = true
                port
            } ?: return
        if (tag == null || channel == null) {
            // More than one tag, or not an ISO 7816 tag: keep looking for the chip. No text on the
            // sheet (the app owns it) and no pause: the header asks for neither. Each restart waits
            // for the next discovery, and the session's time limit ends the search.
            session.restartPolling()
            return
        }
        tag.connect { failure -> handleConnected(session, channel, failure) }
    }

    /**
     * What follows `connect(to:)`, decided once under the lock. Success hands the transport to
     * [connect] and clears the wait, so an ending that comes later stops the transport and does not
     * race the result. A lost link or a chip that did not answer restarts polling, so the person can
     * hold the document again, until [MAX_CONNECT_ATTEMPTS] connects in a row have failed. Any
     * other failure, the session's own time limit included, fails [connect] and ends the system
     * session with the failure message, so no session stays open and blocks the next one.
     */
    private fun handleConnected(
        session: OwnedReaderSession,
        channel: Iso7816Channel,
        failure: ConnectFailure?,
    ) {
        val outcome =
            locked {
                connecting = false
                val waiting = pending
                if (ended || waiting == null) return@locked null
                when {
                    failure == null -> {
                        val connected = CoreNfcTransport(channel, keepAlive = session)
                        transport = connected
                        pending = null
                        failedConnects = 0
                        ConnectOutcome.Connected(waiting, connected)
                    }

                    failure.retryable && ++failedConnects < MAX_CONNECT_ATTEMPTS -> {
                        ConnectOutcome.Retry
                    }

                    else -> {
                        ended = true
                        endedByApp = true
                        takeEnded()
                        ConnectOutcome.Failed(waiting, failure.error)
                    }
                }
            } ?: return
        when (outcome) {
            is ConnectOutcome.Connected -> {
                outcome.waiting.complete(outcome.transport)
            }

            ConnectOutcome.Retry -> {
                session.restartPolling()
            }

            is ConnectOutcome.Failed -> {
                outcome.waiting.completeExceptionally(ApduTransportException(outcome.error))
                session.end(failureMessage)
            }
        }
    }

    private sealed class ConnectOutcome {
        class Connected(
            val waiting: CompletableDeferred<CoreNfcTransport>,
            val transport: CoreNfcTransport,
        ) : ConnectOutcome()

        object Retry : ConnectOutcome()

        class Failed(
            val waiting: CompletableDeferred<CoreNfcTransport>,
            val error: NfcError,
        ) : ConnectOutcome()
    }

    private fun handleInvalidated(platformError: NfcError) {
        val session =
            locked {
                val held = owned
                owned = null
                held
            }
        val (error, target) =
            locked {
                // Core NFC reports "user canceled" also when the app itself ended the session.
                val error = if (endedByApp) NfcUnavailable(NfcUnavailable.Reason.CLOSED) else platformError
                ended = true
                error to takeEnded()
            }
        target.transport?.stop(error)
        target.pending?.completeExceptionally(ApduTransportException(error))
        session?.systemEnd()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Facts about NFC on this device. */
    public companion object {
        /** Failed connects in a row (a lost link, no answer) after which [connect] gives up. */
        private const val MAX_CONNECT_ATTEMPTS = 3

        /**
         * Whether this device can read NFC tags at all (`NFCTagReaderSession.readingAvailable`).
         * `false` on the Simulator and on devices without an NFC reader.
         */
        public val isReadingAvailable: Boolean get() = platformReadingAvailable()

        /**
         * The eMRTD application identifier, ICAO Doc 9303 Part 10: the value your Info.plist lists
         * under `com.apple.developer.nfc.readersession.iso7816.select-identifiers`.
         */
        public const val EMRTD_APPLICATION_ID: String = EMRTD_AID_HEX
    }
}

/**
 * The owners of every system session that has begun and not yet ended ([CoreNfcSession] and
 * [CoreNfcChipRead] objects). Core NFC holds its delegate weakly, so without this an app that drops
 * every reference mid-read would leave a system session whose callbacks reach no one, and that
 * nothing ends before the system's time limit. Each entry is removed when the system reports the
 * end, so nothing outlives the system session.
 */
internal object LiveSessions {
    private val lock = NSLock()
    private val owners = mutableSetOf<Any>()

    fun add(owner: Any) {
        lock.lock()
        try {
            owners += owner
        } finally {
            lock.unlock()
        }
    }

    fun remove(owner: Any) {
        lock.lock()
        try {
            owners -= owner
        } finally {
            lock.unlock()
        }
    }

    /** Whether [owner] holds a live system session, for tests. */
    fun contains(owner: Any): Boolean {
        lock.lock()
        try {
            return owner in owners
        } finally {
            lock.unlock()
        }
    }
}
