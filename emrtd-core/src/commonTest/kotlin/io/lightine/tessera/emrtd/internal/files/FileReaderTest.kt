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
