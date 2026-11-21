package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakeChipTest {
    private fun chip() = FakeChip(FakeChipData.plainChipFiles())

    private fun channel(chip: FakeChip): ApduChannel = ApduChannel(PlainApduSender(chip))

    /** Reads a whole EF in chunks of [chunk] bytes: the first chunk gives the declared length. */
    private suspend fun readFile(
        channel: ApduChannel,
        fid: Int,
        chunk: Int = 0xDF,
    ): ByteArray {
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(fid)).sw)
        var content = channel.send(ChipCommands.readBinary(0, 4)).data
        val total = TlvParser().declaredTotalLength(content)
        while (content.size < total) {
            val r = channel.send(ChipCommands.readBinary(content.size, minOf(chunk, total - content.size)))
            assertTrue(StatusWord.isSuccess(r.sw))
            content += r.data
        }
        return content
    }

    @Test
    fun a_plain_chip_gives_back_the_generated_mrz_through_dg1() =
        runTest {
            val chip = chip()
            val channel = channel(chip)
            assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)

            val com = TlvParser().parseSingle(readFile(channel, LdsIds.EF_COM))
            assertEquals(0x60, com.tag)
            assertContentEquals(byteArrayOf(0x61), com.requireChild(0x5C).value())

            val dg1 = TlvParser().parseSingle(readFile(channel, LdsIds.EF_DG1, chunk = 16))
            val mrz = dg1.requireChild(0x5F1F).value().decodeToString()
            val lines = FakeChipData.specimenTd3Lines()
            assertEquals(lines.joinToString(""), mrz)
            val parsed = MrzParser.parse(mrz.chunked(44))
            assertIs<ParseResult.Success>(parsed)
        }

    @Test
    fun read_binary_by_short_file_identifier_selects_the_file() =
        runTest {
            val chip = chip()
            val channel = channel(chip)
            channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID))
            val r = channel.send(ChipCommands.readBinaryBySfi(LdsIds.sfi(LdsIds.EF_DG1), 0, 2))
            assertEquals(StatusWord.SUCCESS, r.sw)
            assertEquals(0x61, r.data[0].toInt())
            assertEquals(LdsIds.EF_DG1, chip.currentEf)
        }

    @Test
    fun status_words_for_plain_file_errors() =
        runTest {
            val channel = channel(chip())
            // No application selected: the master file has no EF.COM.
            assertEquals(StatusWord.FILE_NOT_FOUND, channel.send(ChipCommands.selectFile(LdsIds.EF_COM)).sw)
            assertEquals(StatusWord.FILE_NOT_FOUND, channel.send(ChipCommands.selectApplication(byteArrayOf(1, 2, 3, 4, 5))).sw)
            assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
            // No current EF yet.
            assertEquals(StatusWord.NO_CURRENT_EF, channel.send(ChipCommands.readBinary(0, 1)).sw)
            channel.send(ChipCommands.selectFile(LdsIds.EF_COM))
            val size = FakeChipData.efCom(listOf(0x61)).size
            // Reading past the end gives what exists with the end-of-file warning.
            val tail = channel.send(ChipCommands.readBinary(size - 2, 10))
            assertEquals(StatusWord.END_OF_FILE_BEFORE_NE, tail.sw)
            assertEquals(2, tail.data.size)
            assertEquals(StatusWord.WRONG_P1_P2, channel.send(ChipCommands.readBinary(size + 1, 1)).sw)
            // READ BINARY without Le.
            assertEquals(StatusWord.WRONG_LENGTH, channel.send(CommandApdu(0, 0xB0, 0, 0)).sw)
            assertEquals(StatusWord.INS_NOT_SUPPORTED, channel.send(CommandApdu(0, 0x84, 0, 0, ne = 8)).sw)
            assertEquals(StatusWord.CLA_NOT_SUPPORTED, channel.send(CommandApdu(0x0C, 0xB0, 0, 0, ne = 8)).sw)
            assertEquals(StatusWord.CHAINING_NOT_SUPPORTED, channel.send(CommandApdu(0x10, 0xB0, 0, 0, ne = 8)).sw)
            assertEquals(StatusWord.INCORRECT_P1_P2, channel.send(CommandApdu(0, 0xA4, 0x04, 0x00, LdsIds.LDS1_AID)).sw)
        }

    @Test
    fun malformed_commands_get_wrong_length() =
        runTest {
            val chip = chip()
            assertContentEquals(byteArrayOf(0x67, 0x00), chip.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04)))
            assertContentEquals(byteArrayOf(0x67, 0x00), chip.transceive(byteArrayOf(0, 0xB0.toByte(), 0, 0, 0, 0)))
        }

    @Test
    fun protocol_handlers_run_before_the_core() =
        runTest {
            val handler = FakeCommandHandler { _, c -> if (c.ins == 0x84) ResponseApdu(ByteArray(8) { 7 }, StatusWord.SUCCESS) else null }
            val chip = FakeChip(FakeChipData.plainChipFiles(), handlers = listOf(handler))
            val r = channel(chip).send(CommandApdu(0, 0x84, 0, 0, ne = 8))
            assertEquals(8, r.data.size)
            assertEquals(StatusWord.SUCCESS, channel(chip).send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
        }

    @Test
    fun losing_the_tag_throws_the_typed_transport_error() =
        runTest {
            val chip = chip()
            chip.loseTagAtCommand = 1
            val channel = channel(chip)
            channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID))
            val e = assertFailsWith<ApduTransportException> { channel.send(ChipCommands.selectFile(LdsIds.EF_DG1)) }
            assertSame(NfcTagLost, e.error)
            assertEquals(2, chip.received.size)
        }
}
