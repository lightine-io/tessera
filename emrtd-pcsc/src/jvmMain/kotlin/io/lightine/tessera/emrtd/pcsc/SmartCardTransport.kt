package io.lightine.tessera.emrtd.pcsc

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTagLost
import javax.smartcardio.CardChannel
import javax.smartcardio.CardException
import javax.smartcardio.CommandAPDU
import javax.smartcardio.ResponseAPDU

/**
 * Sends APDUs through a connected PC/SC card channel.
 *
 * The command bytes and response bytes pass through unchanged. This transport does not inspect
 * chip data or decide whether a response is trustworthy. It owns the connected card session:
 * [close] disconnects the card without requesting a reset.
 *
 * PC/SC readers often support extended-length APDUs, but support depends on the reader, driver and
 * card. Set [supportsExtendedLength] to `true` only when the reader path is known to carry them.
 * The default is conservative and reports short APDUs only.
 *
 * Blocking PC/SC calls run on the calling thread, including inside [transceive].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property channel The connected card's channel. This transport takes ownership of its card session.
 * @property supportsExtendedLength Whether this reader path is known to carry extended APDUs.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class SmartCardTransport
    @JvmOverloads
    constructor(
        private val channel: CardChannel,
        public override val supportsExtendedLength: Boolean = false,
    ) : ApduTransport,
        AutoCloseable {
        /**
         * The longest command APDU this transport reports it can send, in bytes.
         *
         * Short APDUs are limited to 261 bytes. When [supportsExtendedLength] is true, the maximum
         * includes a 65,535-byte command body and an extended expected-length field.
         *
         * @since 1.0.0
         */
        public override val maxCommandLength: Int
            get() = if (supportsExtendedLength) MAX_EXTENDED_COMMAND_LENGTH else MAX_SHORT_COMMAND_LENGTH

        /**
         * Sends one command APDU and returns the card's response bytes, including the status word.
         *
         * PC/SC failures are wrapped in [ApduTransportException]. A failure reported as a removed
         * card, or a channel that is already disconnected, maps to [NfcTagLost].
         *
         * @param command Complete command APDU bytes.
         * @return The response APDU bytes, unchanged.
         * @throws ApduTransportException if the card exchange fails.
         * @since 1.0.0
         */
        @Throws(ApduTransportException::class)
        public override suspend fun transceive(command: ByteArray): ByteArray =
            try {
                val response: ResponseAPDU = channel.transmit(CommandAPDU(command))
                response.bytes
            } catch (failure: CardException) {
                throw failure.toTransportException(CardOperation.EXCHANGE)
            } catch (failure: IllegalStateException) {
                throw ApduTransportException(NfcTagLost, failure)
            } catch (failure: SecurityException) {
                throw failure.toTransportException()
            }

        /**
         * Disconnects the card without asking the reader to reset it.
         *
         * @throws ApduTransportException if PC/SC cannot disconnect the card.
         * @since 1.0.0
         */
        @Throws(ApduTransportException::class)
        public override fun close() {
            try {
                channel.card.disconnect(false)
            } catch (failure: CardException) {
                throw failure.toTransportException(CardOperation.DISCONNECT)
            } catch (failure: IllegalStateException) {
                throw ApduTransportException(NfcTagLost, failure)
            } catch (failure: SecurityException) {
                throw failure.toTransportException()
            }
        }

        private companion object {
            const val MAX_SHORT_COMMAND_LENGTH: Int = 261
            const val MAX_EXTENDED_COMMAND_LENGTH: Int = 65_544
        }
    }
