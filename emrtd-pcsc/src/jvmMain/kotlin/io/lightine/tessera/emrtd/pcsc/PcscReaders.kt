package io.lightine.tessera.emrtd.pcsc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import java.security.ProviderException
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals
import javax.smartcardio.TerminalFactory

/**
 * Lists PC/SC readers and opens a card session in a selected reader.
 *
 * The default JDK [TerminalFactory] may provide no terminals on a host that has no usable PC/SC
 * service. Reader listing then returns an empty list. Operations that need a reader wrap failures
 * in [ApduTransportException].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public object PcscReaders {
    /**
     * Returns the names of the PC/SC readers exposed by the default JDK terminal factory.
     *
     * An empty list means the host currently exposes no readers. PC/SC failures are wrapped in
     * [ApduTransportException] with the best-fitting [NfcUnavailable] reason.
     *
     * @return Reader names in the order reported by the JDK.
     * @throws ApduTransportException if the reader list cannot be obtained.
     * @since 1.0.0
     */
    @JvmStatic
    @Throws(ApduTransportException::class)
    public fun listReaders(): List<String> =
        try {
            listReaders(TerminalFactory.getDefault().terminals())
        } catch (failure: CardException) {
            throw failure.toTransportException(CardOperation.READER_DISCOVERY)
        } catch (failure: SecurityException) {
            throw failure.toTransportException()
        } catch (failure: ProviderException) {
            throw failure.toTransportException()
        }

    /**
     * Waits for a card in the named reader, then connects with any available protocol and uses its
     * basic channel.
     *
     * The default wait is 30 seconds. A value of zero waits indefinitely, as specified by
     * `CardTerminal.waitForCardPresent`. A timeout is reported as [NfcTimeout]. Extended-length
     * support is disabled by default because PC/SC reader and driver support varies.
     *
     * @param readerName The exact name returned by [listReaders].
     * @param waitTimeoutMillis Maximum wait in milliseconds; zero waits indefinitely.
     * @param supportsExtendedLength Set to true only if the selected reader path is known to support
     *   extended-length APDUs.
     * @return A transport for the connected card's basic channel. Close it when the session ends.
     * @throws ApduTransportException if the reader is missing, the wait times out, or PC/SC cannot
     *   connect to the card.
     * @throws IllegalArgumentException if [waitTimeoutMillis] is negative.
     * @since 1.0.0
     */
    @JvmStatic
    @JvmOverloads
    @Throws(ApduTransportException::class, IllegalArgumentException::class)
    public fun connect(
        readerName: String,
        waitTimeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MILLIS,
        supportsExtendedLength: Boolean = false,
    ): SmartCardTransport {
        require(waitTimeoutMillis >= 0) { "waitTimeoutMillis must not be negative" }
        return try {
            connect(
                TerminalFactory.getDefault().terminals(),
                readerName,
                waitTimeoutMillis,
                supportsExtendedLength,
            )
        } catch (failure: CardException) {
            throw failure.toTransportException(CardOperation.READER_DISCOVERY)
        } catch (failure: SecurityException) {
            throw failure.toTransportException()
        } catch (failure: ProviderException) {
            throw failure.toTransportException()
        }
    }

    internal fun listReaders(terminals: CardTerminals): List<String> =
        try {
            terminals.list().map(CardTerminal::getName)
        } catch (failure: CardException) {
            throw failure.toTransportException(CardOperation.READER_DISCOVERY)
        } catch (failure: SecurityException) {
            throw failure.toTransportException()
        }

    internal fun connect(
        terminals: CardTerminals,
        readerName: String,
        waitTimeoutMillis: Long,
        supportsExtendedLength: Boolean,
    ): SmartCardTransport {
        val terminal =
            listReaders(terminals)
                .let { names ->
                    if (readerName !in names) {
                        throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))
                    }
                    terminals.getTerminal(readerName)
                } ?: throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED))

        try {
            if (!terminal.waitForCardPresent(waitTimeoutMillis)) {
                throw ApduTransportException(NfcTimeout(waitTimeoutMillis))
            }
            val card = terminal.connect(ANY_PROTOCOL)
            return SmartCardTransport(card.basicChannel, supportsExtendedLength)
        } catch (failure: ApduTransportException) {
            throw failure
        } catch (failure: CardException) {
            throw failure.toTransportException(CardOperation.CONNECT)
        } catch (failure: IllegalStateException) {
            throw ApduTransportException(NfcTagLost, failure)
        } catch (failure: SecurityException) {
            throw failure.toTransportException()
        }
    }

    private const val ANY_PROTOCOL: String = "*"
    private const val DEFAULT_WAIT_TIMEOUT_MILLIS: Long = 30_000
}
