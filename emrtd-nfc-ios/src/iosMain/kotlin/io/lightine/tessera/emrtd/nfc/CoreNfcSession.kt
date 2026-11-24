package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the iPhone's NFC reader session for one chip read: shows the system NFC sheet, waits for a
 * passport chip, connects to it and gives you a [CoreNfcTransport] for the shared chip code.
 *
 * One session reads one chip. Call [connect], read the chip through the transport, then call
 * [end] (or `end(errorMessage)` to show a failure on the sheet). Always end the session: the
 * system sheet stays up until you do, the person cancels, or the system ends it.
 *
 * ```kotlin
 * val session = CoreNfcSession(alertMessage = "Hold your iPhone near the document.")
 * try {
 *     val transport = session.connect()
 *     // read the chip with transport
 *     session.end()
 * } catch (e: ApduTransportException) {
 *     session.end(errorMessage = "Reading failed.")
 * }
 * ```
 *
 * ## How it looks for chips
 *
 * From iOS 26.4 the session polls ISO 14443 and PACE together and selects only the eMRTD
 * application (`A0000002471001`), so chips that answer only after PACE are found too. Before
 * iOS 26.4 Core NFC cannot combine the two, so the session polls ISO 14443 alone with the
 * select identifiers from your Info.plist: most chips are found this way, but a chip that refuses
 * the application select before PACE is not. When more than one tag is in the field, the tag is
 * not an ISO 7816 tag, or the link to the chip breaks while connecting, the session keeps looking
 * until the person cancels or the time limit passes. Any other failure to connect ends the system
 * session: then [connect] throws, and a later [end] does nothing, so the sheet shows no error
 * message of yours.
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
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcSession internal constructor(
    alertMessage: String,
    private val readingAvailable: () -> Boolean,
    private val openSession: (ReaderSessionEvents) -> ReaderSessionPort,
) {
    /**
     * Creates a session; nothing shows until [connect].
     *
     * @param alertMessage The text the system NFC sheet shows while it looks for the chip.
     */
    public constructor(alertMessage: String) : this(alertMessage, ::platformReadingAvailable, ::PlatformReaderSession)

    private val lock = NSLock()
    private var started = false
    private var ended = false
    private var endedByApp = false
    private var port: ReaderSessionPort? = null
    private var pending: CompletableDeferred<CoreNfcTransport>? = null
    private var connecting = false
    private var transport: CoreNfcTransport? = null
    private var currentAlert: String = alertMessage

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
            target?.setAlertMessage(value)
        }

    /**
     * Shows the system NFC sheet, waits for a passport chip and connects to it.
     *
     * Call it once per session. If the calling coroutine is cancelled while it waits, the session
     * ends.
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
            locked { ended = true }
            throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))
        }
        val session = openSession(events)
        val text =
            locked {
                port = session
                currentAlert
            }
        session.setAlertMessage(text)
        session.begin()
        try {
            return result.await()
        } catch (e: CancellationException) {
            end()
            throw e
        }
    }

    /**
     * Ends the session with success: the system sheet shows its check mark and closes. The
     * transport stops; its [CoreNfcTransport.transceive] throws [NfcUnavailable] `CLOSED`. Safe to
     * call more than once, and after the person or the system ended the session.
     */
    public fun end() {
        endWith(null)
    }

    /**
     * Ends the session with a failure: the system sheet shows [errorMessage] and closes. The
     * transport stops as with [end].
     *
     * @param errorMessage The text the sheet shows, in your app's language.
     */
    public fun end(errorMessage: String) {
        endWith(errorMessage)
    }

    private fun endWith(errorMessage: String?) {
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
        if (errorMessage == null) target.port?.invalidate() else target.port?.invalidate(errorMessage)
    }

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
        val port: ReaderSessionPort?,
        val pending: CompletableDeferred<CoreNfcTransport>?,
        val transport: CoreNfcTransport?,
    )

    private val events =
        object : ReaderSessionEvents {
            override fun onTagsDetected(tags: List<DetectedTag>) = handleTags(tags)

            override fun onInvalidated(error: NfcError) = handleInvalidated(error)
        }

    private fun handleTags(tags: List<DetectedTag>) {
        val session =
            locked {
                if (ended || connecting || transport != null) null else port
            } ?: return
        val tag = tags.singleOrNull()
        val channel = tag?.iso7816
        if (tag == null || channel == null) {
            // More than one tag, or not an ISO 7816 tag: keep looking for the chip. No text on the
            // sheet (the app owns it) and no pause: the header asks for neither.
            session.restartPolling()
            return
        }
        locked { connecting = true }
        tag.connect { error -> handleConnected(session, channel, error) }
    }

    /**
     * What follows `connect(to:)`. Core NFC returns an error there "when there is a communication
     * issue with the tag" (`NFCTagReaderSession.h`): a lost link or a chip that did not answer
     * restarts polling, so the person can hold the document again and [connect] keeps waiting.
     * Any other error fails [connect] and ends the system session, so no session stays open
     * until the system's time limit and blocks the next one.
     */
    private fun handleConnected(
        session: ReaderSessionPort,
        channel: Iso7816Channel,
        error: NfcError?,
    ) {
        val retry = error is NfcTagLost || error is NfcTimeout
        val outcome =
            locked {
                connecting = false
                val waiting = pending
                if (ended || waiting == null) return@locked null
                when {
                    error == null -> {
                        val connected = CoreNfcTransport(channel, keepAlive = session)
                        transport = connected
                        waiting to Result.success(connected)
                    }

                    retry -> {
                        null
                    }

                    else -> {
                        ended = true
                        endedByApp = true
                        takeEnded()
                        waiting to Result.failure(ApduTransportException(error))
                    }
                }
            }
        if (outcome == null) {
            if (retry && locked { !ended }) session.restartPolling()
            return
        }
        val (waiting, result) = outcome
        result.fold({ waiting.complete(it) }, {
            waiting.completeExceptionally(it)
            session.invalidate()
        })
    }

    private fun handleInvalidated(platformError: NfcError) {
        val (error, target) =
            locked {
                // Core NFC reports "user canceled" also when the app itself ended the session.
                val error = if (endedByApp) NfcUnavailable(NfcUnavailable.Reason.CLOSED) else platformError
                ended = true
                error to takeEnded()
            }
        target.transport?.stop(error)
        target.pending?.completeExceptionally(ApduTransportException(error))
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
