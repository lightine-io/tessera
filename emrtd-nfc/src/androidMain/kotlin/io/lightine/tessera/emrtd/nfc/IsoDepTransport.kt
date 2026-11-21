package io.lightine.tessera.emrtd.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * An [ApduTransport] over an Android [IsoDep] tag (ISO 14443-4, the layer passport chips use).
 *
 * It carries bytes only: it does not change, log or keep them. Every blocking `IsoDep` call runs
 * on `Dispatchers.IO`, so it is safe to use from the main thread's coroutines.
 *
 * ## Setting up the reader (the app's part)
 *
 * This library declares only the `NFC` permission. The app declares
 * `<uses-feature android:name="android.hardware.nfc" />` (required or optional is its choice) and
 * turns on reader mode with `NfcAdapter.enableReaderMode`, using the flags
 * `FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_SKIP_NDEF_CHECK`. A passport chip speaks
 * ISO-DEP over NFC-A or NFC-B, and the NDEF check would send commands to the chip that the SDK did
 * not ask for. In the reader-mode callback, pass the `Tag` to [open].
 *
 * ## Errors
 *
 * A failed exchange throws [ApduTransportException], whose `error` is:
 * - [NfcTagLost] when the tag left the field (`TagLostException`, or a tag object used after the
 *   tag left), and for any other I/O failure that is not a timeout;
 * - [NfcTimeout] when Android reports that the chip did not answer within the time limit (an
 *   `IOException` whose message says so);
 * - [NfcUnavailable] with reason `CLOSED` when the transport was closed with [close].
 *
 * Android gives no failure code, so the kind of a plain `IOException` is read from its message,
 * and an unknown failure is reported as [NfcTagLost], the most common cause.
 *
 * Commands go one at a time; calls to [transceive] are serialized. Cancelling the coroutine does
 * not interrupt a call already blocked in the platform: it ends when the chip answers, the time
 * limit passes, or [close] is called.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class IsoDepTransport internal constructor(
    private val channel: IsoDepChannel,
    private val timeoutMillis: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : ApduTransport,
    Closeable {
    /**
     * Wraps [isoDep], which may be connected or not; [transceive] connects it on first use.
     *
     * @param isoDep The tag's ISO-DEP technology, from `IsoDep.get(tag)`.
     * @param timeoutMillis How long to wait for the chip to answer one command, in milliseconds.
     *   The default allows for the slow commands of a chip.
     * @throws IllegalArgumentException when [timeoutMillis] is not positive.
     */
    @JvmOverloads
    public constructor(
        isoDep: IsoDep,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ) : this(PlatformIsoDepChannel(isoDep), timeoutMillis, Dispatchers.IO)

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive, was $timeoutMillis" }
    }

    private val lock = Mutex()

    @Volatile
    private var closed = false

    // Both are reported by the platform without radio traffic and read once. A tag that already left
    // the field makes the platform throw; the answer is then the safe short-APDU limit.
    override val supportsExtendedLength: Boolean =
        try {
            channel.isExtendedLengthApduSupported
        } catch (_: RuntimeException) {
            false
        }

    override val maxCommandLength: Int =
        try {
            val platformMax = channel.maxTransceiveLength
            if (supportsExtendedLength) platformMax else minOf(platformMax, SHORT_APDU_MAX)
        } catch (_: RuntimeException) {
            SHORT_APDU_MAX
        }

    override suspend fun transceive(command: ByteArray): ByteArray =
        lock.withLock {
            withContext(ioDispatcher) {
                if (closed) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                try {
                    if (!channel.isConnected) {
                        channel.connect()
                        // The platform resets the time limit on close, so set it after every connect.
                        channel.setTimeout(timeoutMillis)
                    }
                    channel.transceive(command)
                } catch (e: IOException) {
                    throw ApduTransportException(classify(e), e)
                } catch (e: SecurityException) {
                    // The platform's way to say the tag object is used after the tag left the field.
                    throw ApduTransportException(NfcTagLost, e)
                }
            }
        }

    /**
     * Closes the tag connection. A call blocked in [transceive] on another thread ends with an
     * error. After this, [transceive] throws [NfcUnavailable]. Safe to call more than once.
     */
    override fun close() {
        closed = true
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing is left to release.
        } catch (_: SecurityException) {
            // The tag already left the field.
        }
    }

    private fun classify(e: IOException): NfcError =
        when {
            e is TagLostException -> NfcTagLost
            closed -> NfcUnavailable(NfcUnavailable.Reason.CLOSED)
            isTimeoutMessage(e.message) -> NfcTimeout(timeoutMillis.toLong())
            else -> NfcTagLost
        }

    private fun isTimeoutMessage(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "timed out" in text || "timeout" in text
    }

    public companion object {
        /** The default time limit for the chip's answer to one command: 5 seconds. */
        public const val DEFAULT_TIMEOUT_MILLIS: Int = 5_000

        private const val SHORT_APDU_MAX = 261

        /**
         * Opens an [IsoDepTransport] for a tag from reader mode: gets its [IsoDep], connects it and
         * sets the time limit. Close the result with [close] when the read ends.
         *
         * @param tag The tag from the `NfcAdapter.ReaderCallback`.
         * @param timeoutMillis How long to wait for the chip to answer one command, in milliseconds.
         * @return A connected transport.
         * @throws ApduTransportException with [NfcUnavailable] (`NOT_SUPPORTED`) when the tag does not
         *   speak ISO-DEP, or [NfcTagLost] or [NfcTimeout] when it cannot be connected.
         * @throws IllegalArgumentException when [timeoutMillis] is not positive.
         */
        @JvmStatic
        @JvmOverloads
        public suspend fun open(
            tag: Tag,
            timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        ): IsoDepTransport {
            val isoDep =
                IsoDep.get(tag)
                    ?: throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))
            return open(PlatformIsoDepChannel(isoDep), timeoutMillis, Dispatchers.IO)
        }

        internal suspend fun open(
            channel: IsoDepChannel,
            timeoutMillis: Int,
            ioDispatcher: CoroutineDispatcher,
        ): IsoDepTransport {
            val transport = IsoDepTransport(channel, timeoutMillis, ioDispatcher)
            withContext(ioDispatcher) {
                try {
                    channel.connect()
                    channel.setTimeout(timeoutMillis)
                } catch (e: IOException) {
                    throw ApduTransportException(transport.classify(e), e)
                } catch (e: SecurityException) {
                    throw ApduTransportException(NfcTagLost, e)
                }
            }
            return transport
        }
    }
}
