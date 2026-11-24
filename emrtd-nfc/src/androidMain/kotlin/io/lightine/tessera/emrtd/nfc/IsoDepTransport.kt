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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * An [ApduTransport] over an Android [IsoDep] tag (ISO 14443-4, the layer passport chips use).
 *
 * It carries bytes only: it does not change, log or keep them. Every blocking `IsoDep` call runs
 * on `Dispatchers.IO`, [close] included, so it is safe to use from the main thread.
 *
 * ## Setting up the reader (the app's part)
 *
 * This library declares only the `NFC` permission. The app declares
 * `<uses-feature android:name="android.hardware.nfc" />` (required or optional is its choice).
 * Then, each with its source:
 * - Turn on reader mode with `NfcAdapter.enableReaderMode` and the flags
 *   `FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_SKIP_NDEF_CHECK`. A passport chip
 *   speaks ISO-DEP over NFC-A or NFC-B. Without the last flag the platform runs its own NDEF check
 *   on the card before the app gets it, sending commands the SDK did not ask for (the platform
 *   documentation of `FLAG_READER_SKIP_NDEF_CHECK`).
 * - Consider `FLAG_READER_NO_PLATFORM_SOUNDS`, so the app gives its own feedback when the read
 *   ends instead of the platform's sound when the card is found (the platform documentation).
 * - Consider the extra `EXTRA_READER_PRESENCE_CHECK_DELAY`. By default the platform checks every
 *   125 ms that the tag is still there, pausing the check only during an exchange (the platform's
 *   NFC service source). Some chips drop the link under these checks, and a longer delay is a
 *   known remedy: other readers use values from about 500 to 2,000 ms. This is a judgement for the
 *   app to test with its own documents, not a rule.
 * - Keep reader mode on until the transport is closed. Turning it off, or the activity pausing
 *   (which turns it off), can make the platform refuse every later command (the platform's NFC
 *   service source).
 * - Use the fresh `Tag` of each discovery and pass it to [open]. A `Tag` from an earlier discovery
 *   is out of date, and the platform refuses it.
 * - Do not rely on `NfcAdapter.ignore` to keep a card from being found again: the platform
 *   documentation says it does not work reliably with tags that have a random ID, which passport
 *   chips use.
 * - Close the transport with [close] on every path: success, error and cancellation.
 * - After a result that says the key was wrong, do not read again with the same key: ask the
 *   person to check it. The SDK only uses the MRZ or CAN key the app gives it, sends no write
 *   command, and never retries an authentication by itself.
 *
 * ## Errors
 *
 * A failed exchange throws [ApduTransportException]. Android gives no failure code: it throws an
 * exception type, and the SDK reports what that type says and nothing more.
 * - [NfcTagLost] when Android throws `TagLostException`. Android throws it both when the tag left
 *   the field and when the chip did not answer within the time limit (the platform's NFC service
 *   source marks a response timeout as a lost target), so the SDK cannot tell these apart, and it
 *   never reports [NfcTimeout]. The exception's `cause` says how long the failing call took and the
 *   time limit, so whoever reads it can tell "about the whole limit" from "at once"; the platform's
 *   own exception is the cause of that cause.
 * - [NfcTagLost] too when Android throws `SecurityException`, which it documents as "the tag object
 *   is reused after the tag has left the field", and for any other `IOException` or
 *   `IllegalStateException`, for which Android gives no reason the SDK can rely on.
 * - [NfcUnavailable] with reason `BUSY` when Android refuses to connect because another
 *   technology of the same tag is connected.
 * - [NfcUnavailable] with reason `NOT_SUPPORTED` when Android cannot connect to ISO-DEP on this
 *   tag (`UnsupportedOperationException`), refuses the time limit, or refuses a command as longer
 *   than its limit.
 * - [NfcUnavailable] with reason `CLOSED` when the transport was closed with [close].
 *
 * After the first failure the transport is done: every later [transceive] throws the same error
 * again without sending anything to the tag. To read again, close it and open a new one on the
 * next discovered `Tag`.
 *
 * Commands go one at a time; calls to [transceive] are serialized. Cancelling the coroutine does
 * not interrupt a call already blocked in the platform, and neither does [close]: in the platform
 * the exchange and the close wait for each other, so the blocked call ends when the chip answers
 * or the time limit passes. Cancelling a read can take up to the time limit. No second command is
 * sent while the first is blocked.
 *
 * ## Interruptions
 *
 * - The document moves away: the blocked call fails with [NfcTagLost] and the transport is done.
 * - Your app cancels the coroutine while a command is out: the call still ends in the platform,
 *   its answer is dropped and the transport closes itself, because the chip's secure messaging
 *   state has moved on. The cancellation is thrown; every later [transceive] throws
 *   [NfcUnavailable] `CLOSED`.
 * - Your app calls [close] from another thread while a command is out: the blocked call ends as
 *   above and throws [NfcUnavailable] `CLOSED`; its answer is dropped.
 * - The activity pauses or reader mode is turned off mid-read: the SDK cannot see the activity.
 *   The platform then refuses the next command, and the transport fails fast with that error and
 *   stays done. In `onPause`, cancel the read and call `disableReaderMode`; the read's `finally`
 *   closes the transport. Start a new read only with the `Tag` of a new discovery after
 *   `onResume` turns reader mode on again.
 * - A second card enters the field: reader mode reports it as a new `Tag`. Ignore it while a read
 *   runs; one transport, one `Tag`, one read at a time.
 *
 * A typical read, which closes the transport on every path:
 *
 * ```kotlin
 * override fun onTagDiscovered(tag: Tag) {
 *     readJob = scope.launch {
 *         val result = IsoDepTransport.open(tag).use { transport -> ChipReader().read(transport, options) }
 *         // show result
 *     }
 * }
 *
 * override fun onPause() {
 *     super.onPause()
 *     readJob?.cancel()
 *     NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
 * }
 * ```
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
    private val nanoTime: () -> Long = System::nanoTime,
) : ApduTransport,
    Closeable {
    /**
     * Wraps [isoDep], which may be connected or not. The first [transceive] connects it if needed
     * and sets the time limit, once; the transport never connects again.
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

    private val platformClosed = AtomicBoolean(false)

    // Guarded by [lock]: whether the one connect was tried, and the failure that ended the transport.
    private var connectTried = false
    private var failure: ApduTransportException? = null

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
            // Set on the I/O thread just before the first platform call of this exchange. The
            // blocking call cannot be interrupted, so withContext waits for it to return even when
            // the caller is cancelled; the lock is held until then, and no second command overlaps.
            val platformCalled = AtomicBoolean(false)
            try {
                withContext(ioDispatcher) {
                    if (closed) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                    failure?.let { throw ApduTransportException(it.error, it.cause) }
                    platformCalled.set(true)
                    if (!connectTried) connectOnce()
                    val answer = call("transceive") { channel.transceive(command) }
                    // Closed while the exchange was blocked: the answer is dropped, as close() says.
                    if (closed) throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                    answer
                }
            } catch (e: CancellationException) {
                // Cancelled while a command was out: the chip has answered it (or the time limit
                // passed), and the answer is dropped, so the chip's secure messaging counter has
                // moved past the reader's. Nothing more may be sent: the transport closes itself.
                if (platformCalled.get()) close()
                throw e
            }
        }

    /**
     * Closes the tag connection. After this, [transceive] throws [NfcUnavailable] with reason
     * `CLOSED`, a command blocked in the platform throws it too when it returns, and nothing more
     * is sent. Safe to call more than once and from any thread, the main thread included: it
     * returns at once and hands the platform's close, which talks to the tag and can wait for a
     * blocked exchange up to the time limit, to the I/O dispatcher.
     */
    override fun close() {
        closed = true
        if (!platformClosed.compareAndSet(false, true)) return
        CoroutineScope(ioDispatcher).launch {
            try {
                channel.close()
            } catch (_: IOException) {
                // Nothing is left to release.
            } catch (_: RuntimeException) {
                // The tag already left the field (SecurityException), or the platform state is gone.
            }
        }
    }

    // Must run under [lock] on the I/O dispatcher. Connects only if the tag is not connected yet,
    // then sets the time limit before the first command: the platform resets it on close and when
    // the tag is deactivated, so the value of an earlier connection never carries over.
    private fun connectOnce() {
        connectTried = true
        call("connect") {
            if (!channel.isConnected) channel.connect()
            channel.setTimeout(timeoutMillis)
        }
    }

    // Runs one blocking platform call. On failure it records the typed error, which ends the
    // transport, and throws it with the time the call took.
    private inline fun <T> call(
        operation: String,
        block: () -> T,
    ): T {
        val start = nanoTime()
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw fail(e, operation, start)
        } catch (e: RuntimeException) {
            throw fail(e, operation, start)
        }
    }

    private fun fail(
        e: Exception,
        operation: String,
        start: Long,
    ): ApduTransportException {
        val elapsedMillis = (nanoTime() - start) / NANOS_PER_MILLI
        val cause =
            IsoDepCallFailure(
                "IsoDep.$operation threw ${e.javaClass.simpleName} after $elapsedMillis ms " +
                    "(time limit $timeoutMillis ms)",
                elapsedMillis,
                e,
            )
        val error = if (closed) NfcUnavailable(NfcUnavailable.Reason.CLOSED) else classify(e)
        return ApduTransportException(error, cause).also { failure = it }
    }

    public companion object {
        /**
         * The default time limit for the chip's answer to one command: 5 seconds.
         *
         * The platform's own default for ISO-DEP is 618 ms, and it goes back to that value when the
         * connection is closed (the platform's NFC service source; the platform documentation of
         * `IsoDep.setTimeout` says it is reset on close). ICAO Doc 9303 Part 10 (Appendix B.11) allows a
         * chip up to 5 seconds for one block, so the transport sets this value after it connects and before
         * the first command.
         */
        public const val DEFAULT_TIMEOUT_MILLIS: Int = 5_000

        private const val SHORT_APDU_MAX = 261
        private const val NANOS_PER_MILLI = 1_000_000L

        // Exact texts of the platform's NFC framework (BasicTagTechnology.connect and
        // TransceiveResult.getResponseOrThrow). Only an exact match counts; any other text is an
        // I/O failure with no reason the SDK can rely on.
        private const val ONE_TECHNOLOGY_AT_A_TIME = "Only one TagTechnology can be connected at a time."
        private const val LENGTH_EXCEEDED = "Transceive length exceeds supported maximum"

        /**
         * Opens an [IsoDepTransport] for a tag from reader mode: gets its [IsoDep], connects it and
         * sets the time limit. Close the result with [close] when the read ends.
         *
         * @param tag The tag from the `NfcAdapter.ReaderCallback`.
         * @param timeoutMillis How long to wait for the chip to answer one command, in milliseconds.
         * @return A connected transport.
         * @throws ApduTransportException with [NfcUnavailable] (`NOT_SUPPORTED`) when the tag does not
         *   speak ISO-DEP, or another error from the list in the class description when it cannot
         *   be connected.
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
            nanoTime: () -> Long = System::nanoTime,
        ): IsoDepTransport {
            val transport = IsoDepTransport(channel, timeoutMillis, ioDispatcher, nanoTime)
            transport.lock.withLock {
                withContext(ioDispatcher) { transport.connectOnce() }
            }
            return transport
        }

        internal fun classify(e: Exception): NfcError =
            when (e) {
                is TagLostException -> {
                    NfcTagLost
                }

                is UnsupportedOperationException -> {
                    NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)
                }

                // Only setTimeout throws it: the platform refused the time limit.
                is IllegalArgumentException -> {
                    NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)
                }

                is IOException -> {
                    when (e.message) {
                        ONE_TECHNOLOGY_AT_A_TIME -> NfcUnavailable(NfcUnavailable.Reason.BUSY)
                        LENGTH_EXCEEDED -> NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)
                        else -> NfcTagLost
                    }
                }

                // SecurityException (an out-of-date tag object), IllegalStateException (the platform
                // says the technology is not connected) and anything else from the platform.
                else -> {
                    NfcTagLost
                }
            }
    }
}

/**
 * The cause of a transport failure: which platform call failed, after how long, and the time
 * limit. The platform's own exception is its cause. Holds no document data.
 */
internal class IsoDepCallFailure(
    message: String,
    val elapsedMillis: Long,
    cause: Throwable,
) : IOException(message, cause)
