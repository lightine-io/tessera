package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreNFC.NFCISO7816TagProtocol
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * An [ApduTransport] over a Core NFC ISO 7816 tag (`NFCISO7816Tag`), the kind of tag an iPhone
 * reports for a passport chip.
 *
 * It carries bytes only: it does not change, log or keep them. [CoreNfcSession] runs the system's
 * NFC sheet and gives you a connected transport; an app that runs its own `NFCTagReaderSession`
 * can wrap the tag it connected with the public constructor instead.
 *
 * It sends short APDUs only ([maxCommandLength] 261, [supportsExtendedLength] `false`), the form
 * every chip accepts.
 *
 * ## Errors
 *
 * A failed exchange throws [ApduTransportException], whose `error` is:
 * - [NfcTagLost] when the tag left the field or the link broke;
 * - [NfcTimeout] (with no time limit, as Core NFC does not say one) when the chip did not answer
 *   after the reader's retries, or when the session's own time limit passed;
 * - [NfcUnavailable] with reason `CANCELLED_BY_USER` when the person ended the session with the
 *   sheet's Cancel button, `CLOSED` after [close] or after the app ended the session,
 *   `REQUEST_REFUSED` when Core NFC refused the command (it was not sent), `ENDED_BY_SYSTEM`
 *   when the system ended the session on its own, or another reason the system gave for ending
 *   it.
 *
 * Commands go one at a time; calls to [transceive] are serialized. Cancelling the coroutine does
 * not stop a command Core NFC already sent: its answer is dropped.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift, this class is `CoreNfcTransport` in the
 * `Tessera` framework and is experimental too.
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcTransport internal constructor(
    private val channel: Iso7816Channel,
    keepAlive: Any? = null,
) : ApduTransport {
    /**
     * Wraps a tag your own `NFCTagReaderSession` found and connected with `connect(to:)`.
     *
     * The session stays yours: end it when the read is done.
     *
     * @param tag The connected ISO 7816 tag.
     */
    public constructor(tag: NFCISO7816TagProtocol) : this(PlatformIso7816Channel(tag))

    private val lock = Mutex()

    // The reader session the tag came from, held until the transport stops: Core NFC holds its
    // delegate weakly (NFCTagReaderSession.h), so an app that keeps only the transport must not
    // lose the session's callbacks in the middle of a read.
    @Volatile
    private var keepAlive: Any? = keepAlive

    @Volatile
    private var stopReason: NfcError? = null

    @Volatile
    private var inFlight: CompletableDeferred<ByteArray>? = null

    /** Always 261: one short APDU (4 header bytes, 1 length byte, 255 data bytes, 1 expected-length byte). */
    override val maxCommandLength: Int = SHORT_APDU_MAX

    /** Always `false`: the transport sends short APDUs only. */
    override val supportsExtendedLength: Boolean = false

    /**
     * Whether the chip says it supports PACE, as Core NFC reports it from iOS 26.4; `null` on
     * older iOS, where the system does not say.
     *
     * This is what the chip announced when it was found. The SDK reads the chip's own list of
     * protocols before it chooses one.
     */
    public val supportsPace: Boolean? get() = channel.supportsPace

    @Throws(ApduTransportException::class, CancellationException::class)
    override suspend fun transceive(command: ByteArray): ByteArray =
        lock.withLock {
            stopReason?.let { throw ApduTransportException(it) }
            // Completed once: by Core NFC's callback, or by stop() when the session ends first.
            val result = CompletableDeferred<ByteArray>()
            inFlight = result
            try {
                stopReason?.let { throw ApduTransportException(it) }
                channel.send(command) { answer, error ->
                    // A stop that came while the command was in flight names the real cause: the
                    // platform reports only that the session ended.
                    val failure = stopReason ?: error
                    when {
                        failure != null -> result.completeExceptionally(ApduTransportException(failure))
                        answer == null -> result.completeExceptionally(ApduTransportException(NfcTagLost))
                        else -> result.complete(answer)
                    }
                }
                result.await()
            } finally {
                inFlight = null
            }
        }

    /**
     * Stops the transport: after this, [transceive] throws [NfcUnavailable] with reason `CLOSED`.
     * It does not end the reader session. Safe to call more than once.
     */
    public fun close() {
        stop(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
    }

    /** Stops the transport with [reason], unless it is already stopped. */
    internal fun stop(reason: NfcError) {
        if (stopReason == null) stopReason = reason
        keepAlive = null
        inFlight?.completeExceptionally(ApduTransportException(stopReason ?: reason))
    }

    private companion object {
        const val SHORT_APDU_MAX = 261
    }
}
