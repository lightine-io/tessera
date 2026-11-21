package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeBac
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.bac.Bac
import io.lightine.tessera.emrtd.internal.bac.BacException
import io.lightine.tessera.emrtd.internal.bac.BacFailure
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.files.FileReader
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * BAC and secure messaging end to end against the fake chip ([FakeBac], [FakeSm]), personalized
 * from the project specimen, plus the failures that end a session.
 */
class SecureChannelFakeChipTest {
    private val lines = FakeChipData.specimenTd3Lines()
    private val files = FakeChipData.plainChipFiles(lines)
    private val efCom = files.applications.getValue(LdsIds.LDS1_AID.toList()).getValue(LdsIds.EF_COM)
    private val dg1 = files.applications.getValue(LdsIds.LDS1_AID.toList()).getValue(LdsIds.EF_DG1)

    // TD3 line 2: number and check digit (0-9), birth date and check digit (13-19), expiry (21-27).
    private val line2 = lines[1]
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private fun key(expiryCheckDigit: Char = line2[27]) =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), expiryCheckDigit)

    private class Setup(
        val chip: FakeChip,
        val sm: FakeSm,
        val exchange: PlainApduSender,
    )

    private fun setup(c: ChipCrypto): Setup {
        val sm = FakeSm(c)
        val chip = FakeChip(files, handlers = listOf(sm, FakeBac(mrzInformation, sm, c)))
        return Setup(chip, sm, PlainApduSender(chip))
    }

    private suspend fun openBac(
        s: Setup,
        c: ChipCrypto,
    ): SecureChannel {
        assertEquals(StatusWord.SUCCESS, s.exchange.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
        return Bac.open(s.exchange, c, key())
    }

    private suspend fun readFile(
        channel: ApduSender,
        fid: Int,
    ): ByteArray {
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(fid)).sw)
        var content = channel.send(ChipCommands.readBinary(0, 4)).data
        val total = TlvParser().declaredTotalLength(content)
        while (content.size < total) {
            val r = channel.send(ChipCommands.readBinary(content.size, minOf(0xDF, total - content.size)))
            assertTrue(StatusWord.isSuccess(r.sw))
            content += r.data
        }
        return content
    }

    @Test
    fun bacOpensTheChipAndFilesAreReadUnderTripleDesSecureMessaging() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                // Before BAC the chip refuses plain file access (Part 11, section 4.3.2).
                assertEquals(StatusWord.SECURITY_STATUS_NOT_SATISFIED, s.exchange.send(ChipCommands.selectFile(LdsIds.EF_COM)).sw)
                val channel = openBac(s, c)
                assertTrue(s.sm.isActive)
                assertEquals(SmCipher.DESEDE, channel.cipher)
                assertContentEquals(efCom, readFile(channel, LdsIds.EF_COM))
                assertContentEquals(dg1, readFile(channel, LdsIds.EF_DG1))
                // Every command after BAC went protected (CLA 0C).
                val afterBac = s.chip.received.drop(4)
                assertTrue(afterBac.all { it[0] == 0x0C.toByte() })
            }
        }

    @Test
    fun fileReaderReadsFilesThroughBacAndSecureMessaging() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                // Plain: select the application, then open BAC over the raw sender.
                FileReader(ApduChannel(PlainApduSender(s.chip))).selectApplication()
                val channel = Bac.open(s.exchange, c, key())
                // The same FileReader reads protected files through the secure channel.
                val reader = FileReader(channel)
                assertContentEquals(efCom, reader.readFile(LdsIds.EF_COM))
                assertContentEquals(dg1, reader.readFile(LdsIds.EF_DG1))
                assertTrue(channel.isOpen)
                // Every command after the plain SELECT and the two BAC commands went protected (CLA 0C).
                val afterBac = s.chip.received.drop(3)
                assertTrue(afterBac.all { it[0] == 0x0C.toByte() })
            }
        }

    @Test
    fun aProtectedErrorAnswerKeepsTheSession() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                assertEquals(StatusWord.FILE_NOT_FOUND, channel.send(ChipCommands.selectFile(0x0110)).sw)
                assertTrue(channel.isOpen)
                assertContentEquals(efCom, readFile(channel, LdsIds.EF_COM))
            }
        }

    @Test
    fun aWrongMrzKeyIsRefusedByTheChip() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                assertEquals(StatusWord.SUCCESS, s.exchange.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
                val wrong = if (line2[27] == '0') '1' else '0'
                val e = assertFailsWith<BacException> { Bac.open(s.exchange, c, key(wrong)) }
                assertEquals(BacFailure.AUTHENTICATION_REFUSED, e.failure)
                assertEquals(0x6300, e.sw)
                assertFalse(s.sm.isActive)
            }
        }

    @Test
    fun aPlainCommandInsideTheSessionIsRefusedAndEndsIt() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                // A plain command reaching the chip mid-session (CHIP-3, CHIP-5; sections 4.3.2, 9.8.3).
                val plain = s.chip.transceive(ChipCommands.selectFile(LdsIds.EF_COM).encode(false))
                assertEquals("6982", plain.toHexString().uppercase())
                assertFalse(s.sm.isActive)
                // The reader learns it from the chip's bare 6982 and ends its side too.
                val e = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.selectFile(LdsIds.EF_COM)) }
                assertEquals(SmFailure.CHIP_ENDED_SESSION, e.failure)
                assertEquals(StatusWord.SECURITY_STATUS_NOT_SATISFIED, e.sw)
                assertFalse(channel.isOpen)
            }
        }

    @Test
    fun aWrongMacOnTheAnswerEndsTheSession() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                s.sm.corruptNextResponseMac = true
                val e = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.selectFile(LdsIds.EF_COM)) }
                assertEquals(SmFailure.WRONG_MAC, e.failure)
                assertFalse(channel.isOpen)
                // Nothing more is sent once the session has ended.
                val sent = s.chip.received.size
                val closed = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.readBinary(0, 4)) }
                assertEquals(SmFailure.SESSION_CLOSED, closed.failure)
                assertEquals(sent, s.chip.received.size)
            }
        }

    @Test
    fun aWrongMacOnTheCommandMakesTheChipEndTheSessionWithABareStatusWord() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                // The reader moves to other keys while the chip keeps the old ones.
                channel.restart(SessionKeys(SmCipher.DESEDE, ByteArray(16) { 1 }, ByteArray(16) { 2 }), ByteArray(8))
                val e = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.selectFile(LdsIds.EF_COM)) }
                assertEquals(SmFailure.CHIP_ENDED_SESSION, e.failure)
                assertEquals(StatusWord.SM_DATA_OBJECTS_INCORRECT, e.sw)
                assertFalse(s.sm.isActive)
                assertEquals(1, s.sm.sessionsAborted)
            }
        }

    @Test
    fun aBareErrorStatusWordIsReturnedAndTheSessionGoesOn() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                s.sm.answerNextBare = StatusWord.FILE_NOT_FOUND
                assertEquals(StatusWord.FILE_NOT_FOUND, channel.send(ChipCommands.selectFile(LdsIds.EF_COM)).sw)
                assertTrue(channel.isOpen)
                assertContentEquals(efCom, readFile(channel, LdsIds.EF_COM))
            }
        }

    @Test
    fun aPlainAnswerLengthHintInsideTheSessionEndsIt() =
        withCrypto { c ->
            runTest {
                // 61xx and 6Cxx are plain ISO/IEC 7816-4 hints; a chip must not answer a protected
                // command with them. Each one ends the session as a typed failure.
                for (hint in listOf(0x6102, 0x6C05)) {
                    val s = setup(c)
                    val channel = openBac(s, c)
                    s.sm.answerNextBare = hint
                    val e = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.selectFile(LdsIds.EF_COM)) }
                    assertEquals(SmFailure.PLAIN_ANSWER_LENGTH_HINT, e.failure)
                    assertEquals(hint, e.sw)
                    assertFalse(channel.isOpen)
                }
            }
        }

    @Test
    fun aLostTagMidSessionEndsItWithTheTransportError() =
        withCrypto { c ->
            runTest {
                val s = setup(c)
                val channel = openBac(s, c)
                s.chip.loseTagAtCommand = s.chip.received.size + 2
                val e =
                    assertFailsWith<ApduTransportException> {
                        readFile(channel, LdsIds.EF_DG1)
                    }
                assertSame(NfcTagLost, e.error)
                assertFalse(channel.isOpen)
                val closed = assertFailsWith<SecureMessagingException> { channel.send(ChipCommands.selectFile(LdsIds.EF_COM)) }
                assertEquals(SmFailure.SESSION_CLOSED, closed.failure)
            }
        }

    @Test
    fun aesSecureMessagingRoundTripsForEveryKeyLength() =
        withCrypto { c ->
            runTest {
                // No ICAO worked example covers AES secure messaging: this checks the reader against
                // the fake chip's separate implementation, and AesSecureMessagingReferenceTest (JVM)
                // checks one command against BouncyCastle directly.
                for (cipher in listOf(SmCipher.AES128, SmCipher.AES192, SmCipher.AES256)) {
                    val s = setup(c)
                    assertEquals(StatusWord.SUCCESS, s.exchange.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
                    val n = cipher.keyType.length
                    val kEnc = c.randomBytes(n)
                    val kMac = c.randomBytes(n)
                    s.sm.start(cipher, kEnc, kMac, cipher.zeroSsc())
                    val channel = SecureChannel(s.exchange, c, SessionKeys(cipher, kEnc, kMac), cipher.zeroSsc())
                    assertContentEquals(efCom, readFile(channel, LdsIds.EF_COM), "$cipher")
                    assertContentEquals(dg1, readFile(channel, LdsIds.EF_DG1), "$cipher")
                    assertEquals(16, channel.sscCopy().size)
                }
            }
        }

    @Test
    fun aRestartSwitchesBothSidesToNewKeysAndAFreshCounter() =
        withCrypto { c ->
            runTest {
                // The shape chip authentication uses (Part 11, section 9.8.2): after the last
                // key-agreement answer, both sides move to new keys and the new start counter.
                val s = setup(c)
                val channel = openBac(s, c)
                assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(LdsIds.EF_COM)).sw)
                val kEnc = c.randomBytes(16)
                val kMac = c.randomBytes(16)
                s.sm.start(SmCipher.AES128, kEnc, kMac, SmCipher.AES128.zeroSsc())
                channel.restart(SessionKeys(SmCipher.AES128, kEnc, kMac), SmCipher.AES128.zeroSsc())
                assertEquals(SmCipher.AES128, channel.cipher)
                assertContentEquals(dg1, readFile(channel, LdsIds.EF_DG1))
            }
        }
}
