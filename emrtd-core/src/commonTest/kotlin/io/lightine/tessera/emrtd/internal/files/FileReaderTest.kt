package io.lightine.tessera.emrtd.internal.files

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeFileSystem
import io.lightine.tessera.emrtd.fakechip.FakeFiles
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.files.FileNotFound
import io.lightine.tessera.emrtd.internal.files.FileSecurityStatusNotSatisfied
import io.lightine.tessera.emrtd.internal.files.FileStatusFailure
import io.lightine.tessera.emrtd.internal.files.MalformedFile
import io.lightine.tessera.emrtd.internal.tlv.TlvFailure
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileReaderTest {
    @Test
    fun selects_application_and_master_file_then_reads_a_small_file_by_fid() =
        runTest {
            val bytes = TlvWriter.encode(0x61, byteArrayOf(1, 2, 3))
            val chip =
                FakeChip(
                    FakeFileSystem(
                        masterFiles = mapOf(0x2F01 to bytes),
                        applications = mapOf(LdsIds.LDS1_AID.toList() to emptyMap()),
                    ),
                    handlers = listOf(FakeFiles),
                )
            val reader = FileReader(plain(chip))

            reader.selectApplication()
            reader.selectMasterFile()
            assertContentEquals(bytes, reader.readFile(0x2F01))
            assertEquals(ChipCommands.INS_SELECT, CommandApdu.decode(chip.received[0])!!.ins)
            assertContentEquals(
                byteArrayOf(0x3F, 0x00),
                CommandApdu.decode(chip.received[1])!!.data,
            )
        }

    @Test
    fun reads_a_file_by_sfi() =
        runTest {
            val bytes = TlvWriter.encode(0x61, byteArrayOf(9, 8, 7))
            val chip = chip(mapOf(LdsIds.EF_COM to bytes))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(bytes, reader.readFileBySfi(LdsIds.sfi(LdsIds.EF_COM)))
            assertEquals(LdsIds.EF_COM, chip.currentEf)
            assertEquals(ChipCommands.INS_READ_BINARY, CommandApdu.decode(chip.received[1])!!.ins)
        }

    @Test
    fun reads_files_over_32_kb_with_b1_and_tag_54_offset() =
        runTest {
            val bytes = TlvWriter.encode(0x61, ByteArray(40_000) { (it % 251).toByte() })
            val chip = chip(mapOf(LdsIds.EF_DG1 to bytes))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(bytes, reader.readFile(LdsIds.EF_DG1, extendedLengthAllowed = true))
            val b1 =
                chip.received
                    .mapNotNull(CommandApdu::decode)
                    .first { it.ins == ChipCommands.INS_READ_BINARY_ODD }
            val offsetObject = TlvParser().parseSingle(b1.data)
            assertEquals(0x54, offsetObject.tag)
            val offset = offsetObject.value().fold(0) { n, b -> (n shl 8) or (b.toInt() and 0xFF) }
            assertTrue(offset > 32_767)
        }

    @Test
    fun short_only_transport_uses_conservative_chunks_and_never_asks_for_256() =
        runTest {
            val bytes = TlvWriter.encode(0x61, ByteArray(700) { it.toByte() })
            val chip = chip(mapOf(LdsIds.EF_DG1 to bytes))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(bytes, reader.readFile(LdsIds.EF_DG1, extendedLengthAllowed = true))
            val reads =
                chip.received
                    .mapNotNull(CommandApdu::decode)
                    .filter { it.ins == ChipCommands.INS_READ_BINARY }
            assertTrue(reads.size > 1)
            assertTrue(reads.all { it.fitsShort && it.ne != 256 && (it.ne ?: 0) <= 0xDF })
        }

    @Test
    fun extended_reads_require_both_transport_support_and_caller_permission() =
        runTest {
            val bytes = TlvWriter.encode(0x61, ByteArray(900) { it.toByte() })
            val files = FakeFileSystem(applications = mapOf(LdsIds.LDS1_AID.toList() to mapOf(LdsIds.EF_DG1 to bytes)))
            val deniedChip = FakeChip(files, supportsExtendedLength = true, handlers = listOf(FakeFiles))
            val deniedReader = FileReader(plain(deniedChip))
            deniedReader.selectApplication()
            assertContentEquals(bytes, deniedReader.readFile(LdsIds.EF_DG1, extendedLengthAllowed = false))
            val deniedReads =
                deniedChip.received
                    .mapNotNull(CommandApdu::decode)
                    .filter { it.ins == ChipCommands.INS_READ_BINARY }
            assertTrue(deniedReads.all { it.fitsShort && (it.ne ?: 0) <= 0xDF })

            val allowedChip = FakeChip(files, supportsExtendedLength = true, handlers = listOf(FakeFiles))
            val allowedReader = FileReader(plain(allowedChip))
            allowedReader.selectApplication()
            assertContentEquals(bytes, allowedReader.readFile(LdsIds.EF_DG1, extendedLengthAllowed = true))
            val allowedReads =
                allowedChip.received
                    .mapNotNull(CommandApdu::decode)
                    .filter { it.ins == ChipCommands.INS_READ_BINARY }
            assertTrue(allowedReads.any { it.ne!! > 256 })
        }

    @Test
    fun end_of_file_warning_keeps_the_bytes_received() =
        runTest {
            val truncated = byteArrayOf(0x61, 0x82.toByte(), 0x01, 0xF4.toByte(), 1, 2, 3)
            val chip = chip(mapOf(LdsIds.EF_DG1 to truncated))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(truncated, reader.readFile(LdsIds.EF_DG1))
            assertEquals(3, chip.received.size)
        }

    @Test
    fun a_short_file_with_trailing_zero_bytes_past_its_tlv_reads_as_exactly_the_tlv() =
        runTest {
            val tlv = TlvWriter.encode(0x61, byteArrayOf(1, 2, 3))
            // The chip's physical file is the TLV plus fill bytes; one short read returns it all with 6282.
            val chip = chip(mapOf(LdsIds.EF_DG1 to tlv + ByteArray(20)))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(tlv, reader.readFile(LdsIds.EF_DG1))
            val reads =
                chip.received
                    .mapNotNull(CommandApdu::decode)
                    .filter { it.ins == ChipCommands.INS_READ_BINARY }
            assertEquals(1, reads.size, "one READ BINARY, then the reader stops at the declared length")
        }

    @Test
    fun a_short_file_with_non_zero_trailing_bytes_past_its_tlv_reads_as_exactly_the_tlv() =
        runTest {
            val tlv = TlvWriter.encode(0x61, byteArrayOf(1, 2, 3))
            val chip = chip(mapOf(LdsIds.EF_DG1 to tlv + ByteArray(20) { 0xA5.toByte() }))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(tlv, reader.readFile(LdsIds.EF_DG1))
        }

    @Test
    fun a_short_file_with_trailing_bytes_and_status_9000_reads_as_exactly_the_tlv() =
        runTest {
            val tlv = TlvWriter.encode(0x61, byteArrayOf(1, 2, 3))
            // Sized to exactly one short chunk, so the chip answers 9000 with the whole padded file.
            val padded = tlv + ByteArray(FileReader.DEFAULT_SHORT_CHUNK - tlv.size) { 0x5A.toByte() }
            val chip = chip(mapOf(LdsIds.EF_DG1 to padded))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(tlv, reader.readFile(LdsIds.EF_DG1))
        }

    @Test
    fun reading_by_sfi_drops_trailing_bytes_past_the_tlv() =
        runTest {
            val tlv = TlvWriter.encode(0x61, byteArrayOf(9, 8, 7))
            val chip = chip(mapOf(LdsIds.EF_COM to tlv + ByteArray(12) { 0x33.toByte() }))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(tlv, reader.readFileBySfi(LdsIds.sfi(LdsIds.EF_COM)))
        }

    @Test
    fun a_multi_chunk_file_with_trailing_bytes_past_its_tlv_reads_as_exactly_the_tlv() =
        runTest {
            // 404 bytes: the first chunk does not reach the declared end, so the read continues.
            val tlv = TlvWriter.encode(0x61, ByteArray(400) { (it % 251).toByte() })
            val chip = chip(mapOf(LdsIds.EF_DG1 to tlv + ByteArray(30) { 0xFF.toByte() }))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertContentEquals(tlv, reader.readFile(LdsIds.EF_DG1))
            val reads =
                chip.received
                    .mapNotNull(CommandApdu::decode)
                    .filter { it.ins == ChipCommands.INS_READ_BINARY }
            assertEquals(2, reads.size, "the reader stops once the declared length is reached")
        }

    @Test
    fun missing_file_is_typed() =
        runTest {
            val reader = FileReader(plain(chip(emptyMap())))
            reader.selectApplication()

            val failure = assertFailsWith<FileNotFound> { reader.readFile(0x0102) }
            assertEquals(0x0102, failure.fid)
        }

    @Test
    fun security_status_failure_is_typed() =
        runTest {
            val securityHandler =
                FakeCommandHandler { _, command ->
                    if (command.ins == ChipCommands.INS_SELECT && command.p1 == ChipCommands.SELECT_EF_UNDER_CURRENT_DF) {
                        ResponseApdu(ByteArray(0), 0x6982)
                    } else {
                        null
                    }
                }
            val chip = FakeChip(FakeFileSystem(), handlers = listOf(securityHandler, FakeFiles))
            val reader = FileReader(plain(chip))

            val failure = assertFailsWith<FileSecurityStatusNotSatisfied> { reader.readFile(0x0101) }
            assertEquals(0x0101, failure.fid)
        }

    @Test
    fun unexpected_status_word_is_typed() =
        runTest {
            val statusHandler =
                FakeCommandHandler { _, command ->
                    if (command.ins == ChipCommands.INS_READ_BINARY) ResponseApdu(ByteArray(0), 0x6A86) else null
                }
            val wrapped = chip(mapOf(LdsIds.EF_DG1 to TlvWriter.encode(0x61, byteArrayOf(1))), listOf(statusHandler, FakeFiles))
            val failingReader = FileReader(plain(wrapped))
            failingReader.selectApplication()

            val failure = assertFailsWith<FileStatusFailure> { failingReader.readFile(LdsIds.EF_DG1) }
            assertEquals(0x6A86, failure.statusWord)
        }

    @Test
    fun tag_loss_during_a_chunked_read_keeps_the_transport_error() =
        runTest {
            val chip = chip(mapOf(LdsIds.EF_DG1 to TlvWriter.encode(0x61, ByteArray(700) { it.toByte() })))
            chip.loseTagAtCommand = 3
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            val failure = assertFailsWith<ApduTransportException> { reader.readFile(LdsIds.EF_DG1) }
            assertSame(NfcTagLost, failure.error)
        }

    @Test
    fun malformed_tlv_header_is_a_typed_file_failure() =
        runTest {
            val chip = chip(mapOf(LdsIds.EF_DG1 to byteArrayOf(0x61, 0x82.toByte(), 0x01)))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            val failure = assertFailsWith<MalformedFile> { reader.readFile(LdsIds.EF_DG1) }
            assertEquals(TlvFailure.TRUNCATED_LENGTH, failure.failure)
        }

    @Test
    fun a_file_declaring_more_than_the_limit_is_refused_after_one_read() =
        runTest {
            // A hostile chip declares a file of 0x7FFFFFFF bytes in all (6 header bytes and a value
            // length of 0x7FFFFFF9, which still fits an Int) and sends a few.
            val hostile = byteArrayOf(0x61, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF9.toByte(), 1, 2, 3)
            val chip = chip(mapOf(LdsIds.EF_DG1 to hostile))
            val reader = FileReader(plain(chip))
            reader.selectApplication()
            val sentBefore = chip.received.size

            val failure = assertFailsWith<MalformedFile> { reader.readFile(LdsIds.EF_DG1) }
            assertEquals(TlvFailure.LENGTH_TOO_LONG, failure.failure)
            assertEquals(sentBefore + 2, chip.received.size, "SELECT and one READ BINARY, then nothing")

            // The limit is the reader's: exactly at it is read, one byte over is refused.
            val content = ByteArray(996) { it.toByte() }
            val exact = TlvWriter.encode(0x61, content)
            val atLimit = chip(mapOf(LdsIds.EF_DG1 to exact))
            val limited = FileReader(plain(atLimit), maxFileLength = exact.size)
            limited.selectApplication()
            assertContentEquals(exact, limited.readFile(LdsIds.EF_DG1))
            val over = FileReader(plain(chip(mapOf(LdsIds.EF_DG1 to exact))), maxFileLength = exact.size - 1)
            over.selectApplication()
            assertEquals(TlvFailure.LENGTH_TOO_LONG, assertFailsWith<MalformedFile> { over.readFile(LdsIds.EF_DG1) }.failure)
        }

    @Test
    fun empty_read_binary_with_9000_mid_file_is_a_protocol_failure() =
        runTest {
            val bytes = TlvWriter.encode(0x61, ByteArray(300) { it.toByte() })
            var reads = 0
            val emptyHandler =
                FakeCommandHandler { _, command ->
                    if (command.ins == ChipCommands.INS_READ_BINARY && reads++ > 0) {
                        ResponseApdu(ByteArray(0), StatusWord.SUCCESS)
                    } else {
                        null
                    }
                }
            val chip = chip(mapOf(LdsIds.EF_DG1 to bytes), listOf(emptyHandler, FakeFiles))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            assertFailsWith<FileProtocolFailure> { reader.readFile(LdsIds.EF_DG1) }
        }

    @Test
    fun an_answer_longer_than_requested_is_a_malformed_file() =
        runTest {
            val overHandler =
                FakeCommandHandler { _, command ->
                    if (command.ins == ChipCommands.INS_READ_BINARY) {
                        ResponseApdu(ByteArray((command.ne ?: 0) + 5) { it.toByte() }, StatusWord.SUCCESS)
                    } else {
                        null
                    }
                }
            val chip = chip(mapOf(LdsIds.EF_DG1 to TlvWriter.encode(0x61, byteArrayOf(1))), listOf(overHandler, FakeFiles))
            val reader = FileReader(plain(chip))
            reader.selectApplication()

            val failure = assertFailsWith<MalformedFile> { reader.readFile(LdsIds.EF_DG1) }
            assertNull(failure.failure)
        }

    private fun plain(chip: FakeChip): ApduSender = ApduChannel(PlainApduSender(chip))

    private fun chip(
        files: Map<Int, ByteArray>,
        handlers: List<FakeCommandHandler> = listOf(FakeFiles),
    ): FakeChip =
        FakeChip(
            FakeFileSystem(applications = mapOf(LdsIds.LDS1_AID.toList() to files)),
            handlers = handlers,
        )
}
