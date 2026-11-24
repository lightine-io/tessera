package io.lightine.tessera.emrtd.internal.apdu

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ApduChannelTest {
    /** Answers each command with the next scripted answer and records what it got. */
    private class Scripted(
        vararg answers: ByteArray,
        override val maxCommandLength: Int = 261,
        override val supportsExtendedLength: Boolean = false,
    ) : ApduTransport {
        private val queue = ArrayDeque(answers.toList())
        val sent = mutableListOf<CommandApdu>()

        override suspend fun transceive(command: ByteArray): ByteArray {
            sent += CommandApdu.decode(command)!!
            return queue.removeFirstOrNull() ?: byteArrayOf(0x90.toByte(), 0x00)
        }
    }

    private fun answer(
        data: ByteArray,
        sw: Int,
    ) = ResponseApdu(data, sw).encode()

    private fun channel(t: ApduTransport) = ApduChannel(PlainApduSender(t))

    @Test
    fun plain_exchange() =
        runTest {
            val t = Scripted(answer(byteArrayOf(1, 2), 0x9000))
            val r = channel(t).send(ChipCommands.readBinary(0, 2))
            assertContentEquals(byteArrayOf(1, 2), r.data)
            assertEquals(0x9000, r.sw)
        }

    @Test
    fun wrong_le_is_sent_again_with_the_length_the_chip_names() =
        runTest {
            val t = Scripted(answer(ByteArray(0), 0x6C05), answer(ByteArray(5), 0x9000))
            val r = channel(t).send(ChipCommands.readBinary(0, 256))
            assertEquals(5, r.data.size)
            assertEquals(listOf(256, 5), t.sent.map { it.ne })
        }

    @Test
    fun an_authentication_command_is_never_sent_again_on_wrong_le() =
        runTest {
            // A second EXTERNAL AUTHENTICATE would be a second attempt with the same cryptogram.
            val t = Scripted(answer(ByteArray(0), 0x6C28), answer(ByteArray(40), 0x9000))
            val r = channel(t).sendOnce(CommandApdu(0x00, 0x82, 0, 0, ByteArray(40), 40))
            assertEquals(0x6C28, r.sw)
            assertEquals(listOf(0x82), t.sent.map { it.ins })
        }

    @Test
    fun an_authentication_answer_with_bytes_remaining_is_fetched_without_a_second_attempt() =
        runTest {
            val t = Scripted(answer(byteArrayOf(1), 0x6101), answer(byteArrayOf(2), 0x9000))
            val r = channel(t).sendOnce(CommandApdu(0x00, 0x82, 0, 0, ByteArray(40), 40))
            assertContentEquals(byteArrayOf(1, 2), r.data)
            assertEquals(listOf(0x82, 0xC0), t.sent.map { it.ins })
        }

    @Test
    fun bytes_remaining_are_fetched_with_get_response_and_joined() =
        runTest {
            val t =
                Scripted(
                    answer(byteArrayOf(1), 0x6102),
                    answer(byteArrayOf(2, 3), 0x6101),
                    answer(byteArrayOf(4), 0x9000),
                )
            val r = channel(t).send(CommandApdu(0x00, 0x86, 0, 0, byteArrayOf(9), 256))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), r.data)
            assertEquals(0x9000, r.sw)
            assertEquals(listOf(0x86, 0xC0, 0xC0), t.sent.map { it.ins })
            assertEquals(listOf(256, 2, 1), t.sent.map { it.ne })
        }

    @Test
    fun a_chip_that_never_stops_announcing_bytes_is_cut_off() =
        runTest {
            val endless =
                object : ApduTransport {
                    override val maxCommandLength = 261
                    override val supportsExtendedLength = false
                    var calls = 0

                    override suspend fun transceive(command: ByteArray): ByteArray {
                        calls++
                        return answer(ByteArray(0), 0x6100)
                    }
                }
            assertFailsWith<ResponseTooLong> { channel(endless).send(ChipCommands.readBinary(0, 1)) }
            assertEquals(ApduChannel.MAX_GET_RESPONSE_ROUNDS + 1, endless.calls)
        }

    @Test
    fun a_chip_that_sends_too_many_bytes_is_cut_off() =
        runTest {
            val big =
                object : ApduTransport {
                    override val maxCommandLength = 261
                    override val supportsExtendedLength = false

                    override suspend fun transceive(command: ByteArray): ByteArray = answer(ByteArray(256), 0x6100)
                }
            assertFailsWith<ResponseTooLong> { channel(big).send(ChipCommands.readBinary(0, 1)) }
        }

    @Test
    fun extended_length_needs_both_transport_and_permission() =
        runTest {
            val long = CommandApdu(0x00, 0xB0, 0, 0, ne = 1000)
            assertFailsWith<ExtendedLengthRequired> { channel(Scripted(supportsExtendedLength = true)).send(long) }
            assertFailsWith<ExtendedLengthRequired> {
                channel(Scripted(supportsExtendedLength = false)).send(long, extendedLengthAllowed = true)
            }
            val ok = Scripted(maxCommandLength = 65_544, supportsExtendedLength = true)
            channel(ok).send(long, extendedLengthAllowed = true)
            assertEquals(1000, ok.sent.single().ne)
        }

    @Test
    fun a_command_longer_than_the_transport_takes_is_never_sent() =
        runTest {
            val t = Scripted(maxCommandLength = 20)
            val e = assertFailsWith<CommandTooLong> { channel(t).send(CommandApdu(0, 0x86, 0, 0, ByteArray(30))) }
            assertEquals(35, e.length)
            assertEquals(0, t.sent.size)
        }

    @Test
    fun chain_stops_at_the_first_non_success_answer() =
        runTest {
            val t = Scripted(answer(ByteArray(0), 0x9000), answer(ByteArray(0), 0x6982))
            val chain = CommandChaining.split(CommandApdu(0, 0x86, 0, 0, ByteArray(600)), 255)
            val r = channel(t).sendChain(chain)
            assertEquals(0x6982, r.sw)
            assertEquals(2, t.sent.size)
        }

    @Test
    fun transport_errors_pass_through() =
        runTest {
            val e = ApduTransportException(NfcTagLost)
            val failing =
                object : ApduTransport {
                    override val maxCommandLength = 261
                    override val supportsExtendedLength = false

                    override suspend fun transceive(command: ByteArray): ByteArray = throw e
                }
            assertSame(e, assertFailsWith<ApduTransportException> { channel(failing).send(ChipCommands.readBinary(0, 1)) })
        }

    @Test
    fun an_answer_without_status_word_is_malformed() =
        runTest {
            assertFailsWith<MalformedResponse> { channel(Scripted(byteArrayOf(0x90.toByte()))).send(ChipCommands.readBinary(0, 1)) }
        }
}
