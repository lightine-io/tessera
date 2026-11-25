package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmOverloads

/**
 * Sends command bytes to a chip and returns its answer: the one seam between the shared chip code
 * and a platform's NFC reader.
 *
 * A platform module implements it over its NFC API (Android `IsoDep`, iOS `NFCISO7816Tag`), and
 * an app can implement it over any other reader, such as a USB card reader on a desktop. Only
 * bytes cross it: the SDK builds every command, including secure messaging, and reads every answer.
 *
 * An implementation must not change, log, or keep the bytes it carries: they hold document data.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface ApduTransport {
    /**
     * The longest command, in bytes, that the transport can send in one exchange: the whole
     * command APDU, header included.
     *
     * The SDK never sends a longer command. A reader that only sends short APDUs reports 261
     * (4 header bytes, 1 length byte, 255 data bytes, 1 expected-length byte).
     */
    public val maxCommandLength: Int

    /**
     * Whether the transport can carry extended-length APDUs (up to 65,535 data bytes in a command
     * and 65,536 in an answer).
     *
     * This says what the reader and its platform can carry, not what the chip accepts: the SDK
     * reads the chip's own statement before it uses extended length.
     */
    public val supportsExtendedLength: Boolean

    /**
     * Which file the chip has selected when the read starts, when the platform knows it.
     *
     * The default, [ChipStartSelection.UNKNOWN], keeps the reader's general start: it tries the
     * master-file files first and selects the master file only when the chip refuses them. A
     * transport whose platform has already selected the eMRTD application before handing over the
     * chip (Core NFC on iPhone does, from the app's list of application identifiers) reports
     * [ChipStartSelection.EMRTD_APPLICATION]: the reader then selects the master file first and
     * skips a command that is bound to fail, so fewer plain commands go out before access.
     *
     * Report only what the platform states; when in doubt, keep the default.
     *
     * @since 1.0.0
     */
    public val startSelection: ChipStartSelection
        get() = ChipStartSelection.UNKNOWN

    /**
     * Sends one command APDU and returns the chip's whole answer, status word included.
     *
     * The answer is returned as received, never changed. A transport that cannot reach the chip
     * throws [ApduTransportException] with the [NfcError] that says why; it never returns an empty
     * array instead.
     *
     * @param command The command APDU, at most [maxCommandLength] bytes.
     * @return The response APDU: the response data followed by the two status-word bytes.
     * @throws ApduTransportException when the connection to the chip fails.
     */
    @Throws(ApduTransportException::class, CancellationException::class)
    public suspend fun transceive(command: ByteArray): ByteArray
}

/**
 * Which file a chip has selected when a read starts, as an [ApduTransport] reports it in
 * [ApduTransport.startSelection].
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipStartSelection {
    /** The transport does not know. The reader works from either start. */
    UNKNOWN,

    /** The platform has already selected the eMRTD application, so the master file is not current. */
    EMRTD_APPLICATION,
}

/**
 * Thrown by an [ApduTransport] when it cannot send a command to the chip or get its answer.
 *
 * The message is the error's [NfcError.description], which carries no document values.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property error Why the exchange failed.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ApduTransportException
    @JvmOverloads
    constructor(
        public val error: NfcError,
        cause: Throwable? = null,
    ) : Exception(error.description, cause)
