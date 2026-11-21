package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
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
 * system sheet stays up until you do, the person cancels, or the system's time limit passes.
 *
 * ```kotlin
 * val session = CoreNfcSession(alertMessage = "Hold the top of your iPhone on the document.")
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
 * the application select before PACE is not. When more than one tag is in the field, or the tag is
 * not an ISO 7816 tag, the session keeps looking.
 *
 * ## What your app needs
 *
 * - A paid Apple Developer Program (or Enterprise) team: Apple does not offer NFC tag reading to a
 *   free account, so a device build cannot be signed with it.
 * - The entitlement `com.apple.developer.nfc.readersession.formats` with the values `TAG` and
 *   `PACE` (add the "Near Field Communication Tag Reading" capability in Xcode, then add `PACE`).
 * - In Info.plist: `NFCReaderUsageDescription`, a text that says why the app reads NFC, and
 *   `com.apple.developer.nfc.readersession.iso7816.select-identifiers`, an array that holds
 *   `A0000002471001` (the eMRTD application).
 * - A device with an NFC reader (iPhone 7 or later). The Simulator has none: there [connect]
 *   fails with [NfcUnavailable] `NOT_SUPPORTED`.
 *
 * ## Errors
 *
 * [connect] throws [ApduTransportException], whose `error` is:
 * - [NfcUnavailable] `NOT_SUPPORTED` when the device cannot read NFC tags, `NOT_PERMITTED` when
 *   the entitlement or the usage description is missing or the system does not allow the app to
 *   read, `DISABLED` when the radio is off, `BUSY` when the system reader is busy,
 *   `CANCELLED_BY_USER` when the person ended the sheet, and `CLOSED` when the app called [end];
 * - [io.lightine.tessera.types.errors.NfcTimeout] when the session's own time limit (about 60
 *   seconds) passed before a chip was found;
 * - [io.lightine.tessera.types.errors.NfcTagLost] when connecting to the chip failed.
 *
 * Once the session ends, the transport's [CoreNfcTransport.transceive] throws the same error.
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
                Ended(port, pending, transport)
            } ?: return
        val closed = NfcUnavailable(NfcUnavailable.Reason.CLOSED)
        target.transport?.stop(closed)
        target.pending?.completeExceptionally(ApduTransportException(closed))
        if (errorMessage == null) target.port?.invalidate() else target.port?.invalidate(errorMessage)
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
            // More than one tag, or not an ISO 7816 tag: keep looking for the chip.
            session.restartPolling()
            return
        }
        locked { connecting = true }
        tag.connect { error ->
            val outcome =
                locked {
                    connecting = false
                    val waiting = pending
                    if (ended || waiting == null) return@locked null
                    if (error == null) {
                        val connected = CoreNfcTransport(channel)
                        transport = connected
                        waiting to Result.success(connected)
                    } else {
                        waiting to Result.failure(ApduTransportException(error))
                    }
                } ?: return@connect
            val (waiting, result) = outcome
            result.fold({ waiting.complete(it) }, { waiting.completeExceptionally(it) })
        }
    }

    private fun handleInvalidated(platformError: NfcError) {
        val (error, target) =
            locked {
                // Core NFC reports "user canceled" also when the app itself ended the session.
                val error = if (endedByApp) NfcUnavailable(NfcUnavailable.Reason.CLOSED) else platformError
                ended = true
                error to Ended(port, pending, transport)
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
