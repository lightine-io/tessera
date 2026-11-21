package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeAa
import io.lightine.tessera.emrtd.fakechip.FakeAaCurveEncoding
import io.lightine.tessera.emrtd.fakechip.FakeAaKey
import io.lightine.tessera.emrtd.fakechip.FakeBac
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeFileSystem
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.bac.Bac
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.ActiveAuthenticationInfo
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.sm.ApduExchange
import io.lightine.tessera.emrtd.internal.sm.PlainExchange
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Active authentication end to end against the fake chip ([FakeAa]), whose chip side is written
 * apart from the reader's code. ICAO Doc 9303 prints no worked example for any RSA hash trailer
 * but SHA-1's (Appendix F), and none at all for ECDSA active authentication: these tests are the
 * two implementations checking each other, the evidence the test strategy calls second-best after
 * the document's own values.
 *
 * The fake RSA key is synthetic (two published Mersenne primes as factors, made at test time), and
 * every ECDSA key is a fixed scalar on a curve of Part 11, Table 12. Nothing here comes from a
 * real document or a real chip.
 */
class ActiveAuthenticationFakeChipTest {
    private val lines = FakeChipData.specimenTd3Lines()

    // TD3 line 2: number and check digit (0-9), birth date and check digit (13-19), expiry (21-27).
    private val line2 = lines[1]
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private fun accessKey() =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), line2[27])

    /** The specimen chip's files with EF.DG15 added under the LDS1 application. */
    private fun files(dg15: ByteArray): FakeFileSystem {
        val plain = FakeChipData.plainChipFiles(lines)
        val lds1 = plain.applications.getValue(LdsIds.LDS1_AID.toList()).toMutableMap()
        lds1[EF_DG15] = dg15
        return FakeFileSystem(plain.masterFiles, mapOf(LdsIds.LDS1_AID.toList() to lds1))
    }

    private fun chipWith(aa: FakeAa): FakeChip = FakeChip(files(aa.dg15), handlers = listOf(aa))

    /** The reader's nonce out of a sent short command: header 4, Lc 1, then RND.IFD. */
    private fun nonceOf(command: ByteArray): ByteArray = command.copyOfRange(5, 13)

    /** The trailer field each hash names (section 6.1.2.2), taken from the document, not the code. */
    private fun trailerOf(hash: HashAlgorithm): String =
        when (hash) {
            HashAlgorithm.SHA1 -> "BC"
            HashAlgorithm.SHA224 -> "38CC"
            HashAlgorithm.SHA256 -> "34CC"
            HashAlgorithm.SHA384 -> "36CC"
            HashAlgorithm.SHA512 -> "35CC"
        }

    private fun aaInfo(oid: String) = ActiveAuthenticationInfo(SecurityOids.ID_AA, 1, oid)

    @Test
    fun rsaPassesWithEveryHashTrailer() =
        withCrypto { c ->
            runTest {
                for (hash in HashAlgorithm.entries) {
                    val key = FakeAaKey.rsa(c)
                    val aa = FakeAa(key, c, hash)
                    val chip = chipWith(aa)
                    val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, aaInfo = null)
                    assertEquals(ActiveAuthenticationResult.Passed, result, "$hash")

                    // What went over the wire: the INTERNAL AUTHENTICATE command with a fresh
                    // 8-byte nonce, and an answer whose recovered representative carries the
                    // header of partial recovery and the hash's trailer.
                    val command = chip.received.single()
                    assertEquals(0x88, command[1].toInt() and 0xFF)
                    assertEquals(14, command.size)
                    val recovered = c.rsaPublic(key.modulus, key.publicExponent, aa.lastSignature ?: ByteArray(0))
                    assertEquals("6A", recovered.copyOf(1).toHex(), "$hash")
                    assertTrue(recovered.toHex().endsWith(trailerOf(hash)), "$hash")
                }
            }
        }

    @Test
    fun ecdsaPassesWithEveryAllowedHash() =
        withCrypto { c ->
            runTest {
                val cases =
                    listOf(
                        // Table 12 curve id, the hash announced by the ActiveAuthenticationInfo, and
                        // how the chip writes its curve into EF.DG15.
                        Triple(10, HashAlgorithm.SHA224, FakeAaCurveEncoding.STANDARDIZED_ID),
                        Triple(12, HashAlgorithm.SHA256, FakeAaCurveEncoding.EXPLICIT),
                        Triple(15, HashAlgorithm.SHA384, FakeAaCurveEncoding.EXPLICIT),
                        Triple(18, HashAlgorithm.SHA512, FakeAaCurveEncoding.EXPLICIT),
                    )
                for ((id, hash, encoding) in cases) {
                    val aa = FakeAa(FakeAaKey.ecdsa(c, id), c, hash, encoding)
                    val chip = chipWith(aa)
                    val info =
                        aaInfo(
                            when (hash) {
                                HashAlgorithm.SHA224 -> AaSignatureOids.ECDSA_PLAIN_SHA224
                                HashAlgorithm.SHA256 -> AaSignatureOids.ECDSA_PLAIN_SHA256
                                HashAlgorithm.SHA384 -> AaSignatureOids.ECDSA_PLAIN_SHA384
                                HashAlgorithm.SHA512 -> AaSignatureOids.ECDSA_PLAIN_SHA512
                                HashAlgorithm.SHA1 -> error("SHA-1 is not allowed for ECDSA (6.1.2.3)")
                            },
                        )
                    assertEquals(
                        ActiveAuthenticationResult.Passed,
                        ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, info),
                        "$id",
                    )
                }
            }
        }

    @Test
    fun aWrongSignatureFails() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA256)
                aa.corruptNextSignature = true
                val chip = chipWith(aa)
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, aaInfo = null)
                // One flipped bit scatters the whole representative, so the failure is the
                // malformed representative in almost every run and a wrong digest in the rest.
                assertTrue(result is ActiveAuthenticationResult.Failed)
                assertTrue(
                    result.failure == ActiveAuthenticationFailure.MALFORMED_SIGNATURE ||
                        result.failure == ActiveAuthenticationFailure.WRONG_SIGNATURE,
                )
            }
        }

    @Test
    fun aTamperedRecoveredMessageFails() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA1)
                aa.tamperRecoveredMessage = true
                val chip = chipWith(aa)
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, aaInfo = null)
                // The digest is over the original M1, the recovered part says otherwise: the
                // comparison of steps 16 to 18 catches it.
                assertEquals(ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.WRONG_SIGNATURE), result)
            }
        }

    @Test
    fun theCheckRunsUnderSecureMessaging() =
        withCrypto { c ->
            runTest {
                val sm = FakeSm(c)
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA1)
                val chip = FakeChip(files(aa.dg15), handlers = listOf(sm, FakeBac(mrzInformation, sm, c), aa))
                val exchange = PlainExchange(chip)
                assertEquals(StatusWord.SUCCESS, exchange.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
                val channel = Bac.open(exchange, c, accessKey())
                assertTrue(sm.isActive)

                // EF.DG15 read through the open channel, then the challenge through the same one.
                val dg15 = readFile(channel, EF_DG15)
                assertContentEquals(aa.dg15, dg15)
                assertEquals(ActiveAuthenticationResult.Passed, ActiveAuthentication(channel, c).authenticate(dg15, aaInfo = null))

                // Every command after BAC was protected (section 6.1.1), the challenge included.
                assertTrue(chip.received.drop(3).all { it[0] == 0x0C.toByte() })
            }
        }

    @Test
    fun everyRunDrawsAFreshNonce() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA1)
                val chip = chipWith(aa)
                val exchange = PlainExchange(chip)
                assertEquals(ActiveAuthenticationResult.Passed, ActiveAuthentication(exchange, c).authenticate(aa.dg15, aaInfo = null))
                val first = nonceOf(chip.received.single())
                assertEquals(ActiveAuthenticationResult.Passed, ActiveAuthentication(exchange, c).authenticate(aa.dg15, aaInfo = null))
                val second = nonceOf(chip.received.last())
                // Two strong draws agreeing is a 2 to the -64 event; the rule is that they never do
                // (section 6.1.2.1: the nonce used for BAC is never reused for this check either).
                assertFalse(first.contentEquals(second))
            }
        }

    @Test
    fun oneEngineRunsTheCheckOnlyOnce() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA1)
                val chip = chipWith(aa)
                val engine = ActiveAuthentication(PlainExchange(chip), c)
                assertEquals(ActiveAuthenticationResult.Passed, engine.authenticate(aa.dg15, aaInfo = null))
                assertFailsWith<IllegalStateException> { engine.authenticate(aa.dg15, aaInfo = null) }
                assertEquals(1, chip.received.size)
            }
        }

    @Test
    fun aChipWithoutDg15IsNotApplicable() =
        withCrypto { c ->
            runTest {
                val chip = FakeChip(FakeChipData.plainChipFiles(lines))
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(dg15 = null, aaInfo = null)
                assertEquals(ActiveAuthenticationResult.NotApplicable, result)
                assertTrue(chip.received.isEmpty())
            }
        }

    @Test
    fun aRefusedCommandIsReportedWithTheStatusWord() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA1)
                val chip =
                    FakeChip(
                        files(aa.dg15),
                        handlers =
                            listOf(
                                FakeCommandHandler { _, command ->
                                    if (command.ins == ActiveAuthentication.INS_INTERNAL_AUTHENTICATE) {
                                        ResponseApdu(ByteArray(0), StatusWord.CONDITIONS_OF_USE_NOT_SATISFIED)
                                    } else {
                                        null
                                    }
                                },
                            ),
                    )
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, aaInfo = null)
                assertEquals(
                    ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.CHIP_REFUSED, StatusWord.CONDITIONS_OF_USE_NOT_SATISFIED),
                    result,
                )
            }
        }

    @Test
    fun anEcdsaChipWithoutAaInfoFailsBeforeAnyCommand() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.ecdsa(c, 12), c, HashAlgorithm.SHA256)
                val chip = chipWith(aa)
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, aaInfo = null)
                // Without the ActiveAuthenticationInfo of section 9.2.4 the hash is unknown, so
                // the reader sends nothing rather than a challenge it could not check.
                assertEquals(ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.MISSING_SIGNATURE_ALGORITHM), result)
                assertTrue(chip.received.isEmpty())
            }
        }

    @Test
    fun anAaInfoNamingSha1ForEcdsaIsRefused() =
        withCrypto { c ->
            runTest {
                val aa = FakeAa(FakeAaKey.ecdsa(c, 12), c, HashAlgorithm.SHA256)
                val chip = chipWith(aa)
                // TR-03111's ecdsa-plain-SHA1: section 6.1.2.3 forbids SHA-1 for ECDSA here.
                val info = aaInfo("0.4.0.127.0.7.1.4.1")
                val result = ActiveAuthentication(PlainExchange(chip), c).authenticate(aa.dg15, info)
                assertEquals(ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.UNSUPPORTED_SIGNATURE_ALGORITHM), result)
                assertTrue(chip.received.isEmpty())
            }
        }

    /** Selects [fid] and reads its whole content through [channel], as the reader's file code does. */
    private suspend fun readFile(
        channel: ApduExchange,
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

    private companion object {
        /** EF.DG15: DGn has file identifier 0x0100 + n (Doc 9303 Part 10, section 3.10). */
        const val EF_DG15 = 0x010F
    }
}
