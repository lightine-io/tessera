package io.lightine.tessera.emrtd.pcsc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.security.ProviderException
import javax.smartcardio.ATR
import javax.smartcardio.Card
import javax.smartcardio.CardChannel
import javax.smartcardio.CardException
import javax.smartcardio.CardNotPresentException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals
import javax.smartcardio.CommandAPDU
import javax.smartcardio.ResponseAPDU
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmartCardTransportTest {
    @Test
    fun transceive_passes_the_command_and_returns_the_response_bytes_unchanged() =
        runTest {
            val command = byteArrayOf(0, 0xA4.toByte(), 4, 0, 2, 0x3F, 0)
            val answer = byteArrayOf(0x62, 0x01, 0x90.toByte(), 0)
            val channel = FakeChannel(ResponseAPDU(answer))
            val transport = SmartCardTransport(channel)

            assertContentEquals(answer, transport.transceive(command))
            assertContentEquals(command, channel.transmittedCommand)
        }

    @Test
    fun extended_length_is_disabled_by_default_and_can_be_enabled_explicitly() {
        val short = SmartCardTransport(FakeChannel(ResponseAPDU(byteArrayOf(0x90.toByte(), 0))))
        val extended =
            SmartCardTransport(
                FakeChannel(ResponseAPDU(byteArrayOf(0x90.toByte(), 0))),
                supportsExtendedLength = true,
            )

        assertFalse(short.supportsExtendedLength)
        assertEquals(261, short.maxCommandLength)
        assertTrue(extended.supportsExtendedLength)
        assertEquals(65_544, extended.maxCommandLength)
    }

    @Test
    fun a_removed_card_during_transmit_maps_to_tag_lost_and_keeps_the_cause() =
        runTest {
            val failure = CardException("PC/SC status 0x80100069")
            val transport = SmartCardTransport(FakeChannel(failure = failure))

            val thrown =
                assertFailsWith<ApduTransportException> {
                    transport.transceive(byteArrayOf(0, 1, 2, 3))
                }

            assertEquals(NfcTagLost, thrown.error)
            assertSame(failure, thrown.cause)
        }

    @Test
    fun a_disconnected_channel_maps_to_tag_lost() =
        runTest {
            val transport =
                SmartCardTransport(
                    FakeChannel(
                        stateFailure = IllegalStateException("card disconnected"),
                    ),
                )

            val thrown =
                assertFailsWith<ApduTransportException> {
                    transport.transceive(byteArrayOf(0, 1, 2, 3))
                }

            assertEquals(NfcTagLost, thrown.error)
        }

    @Test
    fun listing_no_readers_maps_to_not_supported() {
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.connect(
                    FakeTerminals(emptyList()),
                    readerName = "USB reader",
                    waitTimeoutMillis = 100,
                    supportsExtendedLength = false,
                )
            }

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED), error.error)
    }

    @Test
    fun listing_pcsc_service_failure_maps_to_disabled() {
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.listReaders(FakeTerminals(listFailure = CardException("PC/SC status 0x8010001d")))
            }

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), error.error)
    }

    @Test
    fun listing_a_reader_service_error_maps_to_not_supported() {
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.listReaders(
                    FakeTerminals(listFailure = CardException("PC/SC status 0x8010002e")),
                )
            }

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED), error.error)
    }

    @Test
    fun a_permission_failure_maps_to_not_permitted() {
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.listReaders(FakeTerminals(listFailure = SecurityException("access denied")))
            }

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED), error.error)
    }

    @Test
    fun a_pcsc_access_error_maps_to_not_permitted() {
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.listReaders(FakeTerminals(listFailure = CardException("SCARD_E_NO_ACCESS")))
            }

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED), error.error)
    }

    @Test
    fun provider_failure_maps_to_disabled() {
        val cause = ProviderException("PC/SC service unavailable")

        val error = cause.toTransportException()

        assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), error.error)
        assertSame(cause, error.cause)
    }

    @Test
    fun a_card_wait_timeout_maps_to_nfc_timeout_with_the_requested_duration() {
        val terminal = FakeTerminal(readerName = "USB reader", waitResult = false)
        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.connect(
                    FakeTerminals(listOf(terminal)),
                    readerName = terminal.readerName,
                    waitTimeoutMillis = 1234,
                    supportsExtendedLength = false,
                )
            }

        assertEquals(NfcTimeout(1234), error.error)
        assertEquals(1234, terminal.waitTimeoutMillis)
    }

    @Test
    fun connect_uses_any_protocol_and_the_basic_channel() {
        val channel = FakeChannel(ResponseAPDU(byteArrayOf(0x90.toByte(), 0)))
        val card = channel.card
        val terminal = FakeTerminal(readerName = "USB reader", card = card)

        val transport =
            PcscReaders.connect(
                FakeTerminals(listOf(terminal)),
                readerName = terminal.readerName,
                waitTimeoutMillis = 10,
                supportsExtendedLength = true,
            )

        assertEquals("*", terminal.protocol)
        assertSame(channel, card.basicChannel)
        assertTrue(transport.supportsExtendedLength)
    }

    @Test
    fun connect_maps_a_missing_card_to_tag_lost() {
        val terminal =
            FakeTerminal(
                readerName = "USB reader",
                connectFailure = CardNotPresentException("no card present"),
            )

        val error =
            assertFailsWith<ApduTransportException> {
                PcscReaders.connect(
                    FakeTerminals(listOf(terminal)),
                    readerName = terminal.readerName,
                    waitTimeoutMillis = 10,
                    supportsExtendedLength = false,
                )
            }

        assertEquals(NfcTagLost, error.error)
    }

    @Test
    fun list_readers_returns_terminal_names_from_the_injected_factory() {
        val terminals =
            FakeTerminals(
                listOf(
                    FakeTerminal("First reader"),
                    FakeTerminal("Second reader"),
                ),
            )

        assertEquals(listOf("First reader", "Second reader"), PcscReaders.listReaders(terminals))
    }

    @Test
    fun close_disconnects_without_resetting_the_card() {
        val channel = FakeChannel(ResponseAPDU(byteArrayOf(0x90.toByte(), 0)))
        val transport = SmartCardTransport(channel)

        transport.close()

        assertEquals(false, channel.card.resetRequested)
        assertTrue(channel.card.disconnected)
    }
}

private class FakeChannel(
    private val response: ResponseAPDU? = null,
    private val failure: CardException? = null,
    private val stateFailure: IllegalStateException? = null,
) : CardChannel() {
    val card = FakeCard()
    var transmittedCommand: ByteArray? = null
        private set

    init {
        card.channel = this
    }

    override fun getCard(): Card = card

    override fun getChannelNumber(): Int = 0

    override fun transmit(command: CommandAPDU): ResponseAPDU {
        transmittedCommand = command.bytes
        failure?.let { throw it }
        stateFailure?.let { throw it }
        return requireNotNull(response)
    }

    override fun transmit(
        command: ByteBuffer,
        response: ByteBuffer,
    ): Int = error("The byte-buffer overload is not used by this transport.")

    override fun close() = Unit
}

private class FakeCard(
    var disconnectFailure: CardException? = null,
) : Card() {
    lateinit var channel: CardChannel
    var resetRequested: Boolean? = null
        private set
    var disconnected: Boolean = false
        private set

    override fun getATR(): ATR = ATR(byteArrayOf(0x3B, 0))

    override fun getProtocol(): String = "T=1"

    override fun getBasicChannel(): CardChannel = channel

    override fun openLogicalChannel(): CardChannel = error("No logical channel is used.")

    override fun beginExclusive() = Unit

    override fun endExclusive() = Unit

    override fun transmitControlCommand(
        controlCode: Int,
        command: ByteArray,
    ): ByteArray = error("Control commands are not used.")

    override fun disconnect(reset: Boolean) {
        resetRequested = reset
        disconnectFailure?.let { throw it }
        disconnected = true
    }
}

private class FakeTerminal(
    val readerName: String,
    private val card: Card = FakeChannel(ResponseAPDU(byteArrayOf(0x90.toByte(), 0))).card,
    private val waitResult: Boolean = true,
    private val connectFailure: CardException? = null,
) : CardTerminal() {
    var waitTimeoutMillis: Long? = null
        private set
    var protocol: String? = null
        private set

    override fun getName(): String = readerName

    override fun connect(protocol: String): Card {
        this.protocol = protocol
        connectFailure?.let { throw it }
        return card
    }

    override fun isCardPresent(): Boolean = waitResult

    override fun waitForCardPresent(timeout: Long): Boolean {
        waitTimeoutMillis = timeout
        return waitResult
    }

    override fun waitForCardAbsent(timeout: Long): Boolean = !waitResult
}

private class FakeTerminals(
    private val terminals: List<CardTerminal> = emptyList(),
    private val listFailure: Exception? = null,
) : CardTerminals() {
    override fun list(state: State): List<CardTerminal> {
        listFailure?.let { throw it }
        return terminals
    }

    override fun waitForChange(timeout: Long): Boolean = false
}
