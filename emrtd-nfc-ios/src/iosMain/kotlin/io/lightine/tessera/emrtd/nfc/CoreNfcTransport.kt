package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipStartSelection
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreNFC.NFCISO7816TagProtocol
import platform.Foundation.NSLock
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
 *   after the reader's retries, or when the session's own time limit passed; with the time limit
 *   in milliseconds when a transport from [CoreNfcChipRead] waited longer than
 *   [CoreNfcReadSettings.commandTimeoutMillis] for one answer (that transport then stops);
 * - [NfcUnavailable] with reason `CANCELLED_BY_USER` when the person ended the session with the
 *   sheet's Cancel button, `CLOSED` after [close] or after the app ended the session,
 *   `REQUEST_REFUSED` when Core NFC refused the command (it was not sent), `ENDED_BY_SYSTEM`
 *   when the system ended the session on its own, or another reason the system gave for ending
 *   it.
 *
 * Commands go one at a time; calls to [transceive] are serialized. Core NFC cannot take back a
 * command it was given, so cancelling the coroutine while a command is out stops the transport:
 * the answer is dropped and every later [transceive] throws [NfcUnavailable] `CLOSED` without
 * sending anything. The chip's secure messaging state has moved on by then, so a read cannot go
 * on after it anyway: end the session and start a new read.
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
    // A time limit of the SDK's own for one command, in milliseconds; `null` for none. Used by
    // CoreNfcChipRead: Core NFC has been seen to wait far longer than a chip may take.
    commandTimeoutMillis: Long? = null,
) : ApduTransport {
    /**
     * Wraps a tag your own `NFCTagReaderSession` found and connected with `connect(to:)`.
     *
     * The session stays yours: end it when the read is done.
     *
     * @param tag The connected ISO 7816 tag.
     */
    public constructor(tag: NFCISO7816TagProtocol) : this(PlatformIso7816Channel(tag))

    // Serializes calls to [transceive]: one command out at a time.
    private val lock = Mutex()

    // The limit for the next commands (see useTimeLimit); read when each command goes out.
    @Volatile
    private var commandTimeoutMillis: Long? = commandTimeoutMillis

    /**
     * Sets the SDK's own time limit for the commands that follow, from the next command on: the
     * read uses a short one for the commands before the key is used, then one per kind of command
     * (see [CoreNfcReadSettings.readTimeoutMillis]). The
     * limit is the SDK's own wait (Core NFC has no time limit to set), so it can change at any
     * time; it waits for a command that is out, as commands go one at a time.
     */
    internal suspend fun useTimeLimit(millis: Long) {
        require(millis > 0) { "millis must be positive" }
        lock.withLock { commandTimeoutMillis = millis }
    }

    // Guards [stopReason], [inFlight] and the decision to send, so a stop either comes before a
    // command is handed to Core NFC (and nothing is sent) or finds it in flight (and ends it).
    private val state = NSLock()

    // The reader session the tag came from, held until the transport stops: Core NFC holds its
    // delegate weakly (NFCTagReaderSession.h), so an app that keeps only the transport must not
    // lose the session's callbacks in the middle of a read.
    private var keepAlive: Any? = keepAlive

    // Read without [state] by the command's completion, which may run on Core NFC's queue.
    @Volatile
    private var stopReason: NfcError? = null

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

    /**
     * [ChipStartSelection.EMRTD_APPLICATION] when Core NFC reports that it selected the eMRTD
     * application when it found the tag (`initialSelectedAID`, from the select identifiers in your
     * Info.plist or the session's configuration); [ChipStartSelection.UNKNOWN] otherwise.
     */
    override val startSelection: ChipStartSelection
        get() =
            if (channel.initialSelectedAid?.equals(EMRTD_AID_HEX, ignoreCase = true) == true) {
                ChipStartSelection.EMRTD_APPLICATION
            } else {
                ChipStartSelection.UNKNOWN
            }

    @Throws(ApduTransportException::class, CancellationException::class)
    override suspend fun transceive(command: ByteArray): ByteArray =
        lock.withLock {
            // Completed once: by Core NFC's callback, or by stop() when the session ends first.
            val result = CompletableDeferred<ByteArray>()
            locked {
                stopReason?.let { throw ApduTransportException(it) }
                inFlight = result
                // Handed over under the lock, so no stop can slip between the check and the send.
                // Core NFC calls back later on its own queue; the callback takes no lock.
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
            }
            try {
                val limit = commandTimeoutMillis
                if (limit == null) {
                    result.await()
                } else {
                    // Waits without holding any thread. On expiry the command counts as lost: the
                    // transport stops, so nothing more is sent on it and a late answer is dropped.
                    withTimeoutOrNull(limit) { result.await() } ?: run {
                        val timeout = NfcTimeout(limit)
                        stop(timeout)
                        throw ApduTransportException(timeout)
                    }
                }
            } catch (e: CancellationException) {
                // The command is still out and Core NFC cannot take it back: its answer would be
                // lost, and the chip's secure messaging counter has moved past it. Nothing more
                // may be sent, so the transport stops; the next command fails at once.
                stop(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                throw e
            } finally {
                locked { if (inFlight === result) inFlight = null }
            }
        }

    /**
     * Stops the transport: after this, [transceive] throws [NfcUnavailable] with reason `CLOSED`,
     * and a command still out ends with that error at once. It does not end the reader session:
     * end it with [CoreNfcSession.end] (or your own session's invalidate call). Safe to call more
     * than once and from any thread.
     */
    public fun close() {
        stop(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
    }

    /** Stops the transport with [reason], unless it is already stopped; the first reason stays. */
    internal fun stop(reason: NfcError) {
        val (first, pending) =
            locked {
                if (stopReason == null) stopReason = reason
                keepAlive = null
                val held = inFlight
                inFlight = null
                stopReason!! to held
            }
        pending?.completeExceptionally(ApduTransportException(first))
    }

    private inline fun <T> locked(block: () -> T): T {
        state.lock()
        try {
            return block()
        } finally {
            state.unlock()
        }
    }

    private companion object {
        const val SHORT_APDU_MAX = 261
    }
}
