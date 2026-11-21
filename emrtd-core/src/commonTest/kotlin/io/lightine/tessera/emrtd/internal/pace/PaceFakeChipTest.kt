package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakePace
import io.lightine.tessera.emrtd.fakechip.FakePaceGm
import io.lightine.tessera.emrtd.fakechip.FakePaceMapping
import io.lightine.tessera.emrtd.fakechip.FakePaceRun
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameters
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PACE with the generic mapping against the fake chip ([FakePace], [FakePaceGm], [FakeSm]),
 * personalized from the project specimen. Every generic-mapping suite (DH and ECDH, 3DES and AES
 * 128/192/256) with every standardized parameter set of its key agreement opens a session and reads
 * DG1 through it. These runs check the reader against an independent chip-side implementation, not
 * against ICAO values: Appendix G prints only AES-128 with BrainpoolP256r1 and the 1024-bit DH group
 * (PaceAppendixGTest).
 */
class PaceFakeChipTest {
    private val lines = FakeChipData.specimenTd3Lines()
    private val files = FakeChipData.plainChipFiles(lines)
    private val dg1 = files.applications.getValue(LdsIds.LDS1_AID.toList()).getValue(LdsIds.EF_DG1)
    private val line2 = lines[1]
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private fun mrzKey(expiryCheckDigit: Char = line2[27]) =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), expiryCheckDigit)

    private class Setup(
        val chip: FakeChip,
        val sm: FakeSm,
        val pace: FakePace,
        val exchange: PlainApduSender,
    )

    private fun setup(
        c: ChipCrypto,
        suites: Set<FakePaceSuite>,
        can: String? = null,
        extended: Boolean = false,
        mappings: List<FakePaceMapping> = listOf(FakePaceGm()),
    ): Setup {
        val sm = FakeSm(c)
        val pace = FakePace(c, sm, suites, mappings, mrzInformation = mrzInformation, can = can)
        val chip =
            FakeChip(
                files,
                maxCommandLength = if (extended) FakeChip.EXTENDED_MAX_COMMAND else FakeChip.SHORT_MAX_COMMAND,
                supportsExtendedLength = extended,
                handlers = listOf(sm, pace),
            )
        return Setup(chip, sm, pace, PlainApduSender(chip))
    }

    private suspend fun readDg1(channel: ApduSender): ByteArray {
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(LdsIds.EF_DG1)).sw)
        var content = channel.send(ChipCommands.readBinary(0, 4)).data
        val total = TlvParser().declaredTotalLength(content)
        while (content.size < total) {
            content += channel.send(ChipCommands.readBinary(content.size, minOf(0xDF, total - content.size))).data
        }
        return content
    }

    @Test
    fun everyGenericMappingSuiteAndParameterSetOpensTheChip() =
        runTest {
            withCrypto { c ->
                var runs = 0
                for (agreement in KeyAgreement.entries) {
                    val ids = (0..31).filter { StandardizedParameters.byId(it)?.agreement == agreement }
                    for (cipherCode in 1..4) {
                        val oid = "$ID_PACE.${if (agreement == KeyAgreement.DH) 1 else 2}.$cipherCode"
                        for (id in ids) {
                            // The 2048-bit DH keys do not fit a short APDU (Part 11, 9.3.1.2).
                            val extended = agreement == KeyAgreement.DH && id != 0
                            val s = setup(c, setOf(FakePaceSuite(oid, id)), extended = extended)
                            val suite = PaceSuite.of(oid, id)
                            val result = Pace.open(s.exchange, c, mrzKey(), suite, PaceOptions(extendedLengthAllowed = extended))
                            assertContentEquals(ByteArray(suite.smCipher.blockSize), result.channel.sscCopy(), "$suite")
                            assertContentEquals(dg1, readDg1(result.channel), "$suite")
                            runs++
                        }
                    }
                }
                // 4 ciphers x (3 DH groups + 11 curves).
                assertEquals(56, runs)
            }
        }

    @Test
    fun theCanPathSendsReferenceTwoAndOpensTheChip() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)), can = "123456")
                val result = Pace.open(s.exchange, c, ChipAccessKey.Can("123456"), PaceSuite.of(ECDH_GM_AES128, 13))
                assertEquals(PacePasswordType.CAN, result.passwordType)
                val setAt = TlvParser().parseAll(s.pace.lastSetAtData!!).associate { it.tag to it.value() }
                assertContentEquals(byteArrayOf(0x02), setAt[0x83])
                assertContentEquals(dg1, readDg1(result.channel))
            }
        }

    @Test
    fun aWrongCanIsRefusedAtMutualAuthentication() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(DH_GM_3DES, 0)), can = "123456")
                val e =
                    assertFailsWith<PaceException> { Pace.open(s.exchange, c, ChipAccessKey.Can("654321"), PaceSuite.of(DH_GM_3DES, 0)) }
                assertEquals(PaceFailure.STEP_REFUSED, e.failure)
                assertEquals(PaceStep.MUTUAL_AUTHENTICATION, e.step)
                assertEquals(0x6300, e.sw)
            }
        }

    @Test
    fun aWrongMrzIsATypedFailureAndNoSessionOpens() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)))
                val wrongDigit = if (line2[27] == '0') '1' else '0'
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(wrongDigit), PaceSuite.of(ECDH_GM_AES128, 13)) }
                assertEquals(PaceFailure.STEP_REFUSED, e.failure)
                assertEquals(PaceStep.MUTUAL_AUTHENTICATION, e.step)
                assertEquals(0x6300, e.sw)
                assertEquals(1, s.pace.refusedTokens)
                assertTrue(!s.sm.isActive)
            }
        }

    @Test
    fun equalEphemeralKeysAreRejectedBeforeAnyToken() =
        runTest {
            withCrypto { c ->
                for ((oid, id) in listOf(ECDH_GM_AES128 to 13, DH_GM_3DES to 0)) {
                    val s = setup(c, setOf(FakePaceSuite(oid, id)))
                    s.pace.reflectReaderKey = true
                    val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(oid, id)) }
                    assertEquals(PaceFailure.EQUAL_EPHEMERAL_KEYS, e.failure)
                    assertEquals(PaceStep.KEY_AGREEMENT, e.step)
                    // Set AT and three GENERAL AUTHENTICATE steps: the token step was never sent.
                    assertEquals(4, s.chip.received.size)
                }
            }
        }

    @Test
    fun aWrongChipTokenIsATypedFailure() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES256, 16)))
                s.pace.corruptChipToken = true
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES256, 16)) }
                assertEquals(PaceFailure.WRONG_CHIP_TOKEN, e.failure)
            }
        }

    @Test
    fun anInvalidChipMappingKeyIsRejected() =
        runTest {
            withCrypto { c ->
                // A chip that answers the mapping step with a point off the curve.
                val bad =
                    object : FakePaceMapping {
                        override val kind = PaceMapping.GENERIC

                        override fun map(
                            run: FakePaceRun,
                            readerData: ByteArray,
                        ): Pair<ByteArray, DomainParameters> = (byteArrayOf(0x04) + ByteArray(64) { 1 }) to run.staticParameters
                    }
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)), mappings = listOf(bad))
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES128, 13)) }
                assertEquals(PaceFailure.INVALID_PUBLIC_KEY, e.failure)
            }
        }

    @Test
    fun setAtRefusalsAreTyped() =
        runTest {
            withCrypto { c ->
                // The chip offers no CAN: 6A88.
                val noCan = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)))
                val e1 =
                    assertFailsWith<PaceException> {
                        Pace.open(
                            noCan.exchange,
                            c,
                            ChipAccessKey.Can("123456"),
                            PaceSuite.of(ECDH_GM_AES128, 13),
                        )
                    }
                assertEquals(PaceFailure.REFERENCED_DATA_NOT_FOUND, e1.failure)
                assertEquals(0x6A88, e1.sw)
                // The chip does not offer the suite: 6A80.
                val other = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)))
                val e2 = assertFailsWith<PaceException> { Pace.open(other.exchange, c, mrzKey(), PaceSuite.of(DH_GM_3DES, 0)) }
                assertEquals(PaceFailure.ALGORITHM_NOT_SUPPORTED, e2.failure)
                assertEquals(PaceStep.SET_AUTHENTICATION_TEMPLATE, e2.step)
            }
        }

    @Test
    fun theParameterIdIsSentWhenTheChipOffersSeveralSets() =
        runTest {
            withCrypto { c ->
                val suites = setOf(FakePaceSuite(ECDH_GM_AES128, 12), FakePaceSuite(ECDH_GM_AES128, 13))
                // Without tag 0x84 the chip cannot tell which set is meant.
                val ambiguous = setup(c, suites)
                assertFailsWith<PaceException> { Pace.open(ambiguous.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES128, 13)) }
                val s = setup(c, suites)
                val result = Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES128, 13), PaceOptions(sendParameterId = true))
                val setAt = TlvParser().parseAll(s.pace.lastSetAtData!!).associate { it.tag to it.value() }
                assertContentEquals(byteArrayOf(13), setAt[0x84])
                assertContentEquals(dg1, readDg1(result.channel))
            }
        }

    @Test
    fun a2048BitGroupNeedsExtendedLength() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(DH_GM_3DES, 2)))
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(DH_GM_3DES, 2)) }
                assertEquals(PaceFailure.EXTENDED_LENGTH_REQUIRED, e.failure)
                assertEquals(PaceStep.MAP_NONCE, e.step)
            }
        }

    @Test
    fun unsupportedSuitesAreRefusedBeforeAnyCommand() =
        runTest {
            // Not a PACE OID; explicit, reserved and mismatched parameter IDs.
            for ((oid, id) in listOf(
                "0.4.0.127.0.7.2.2.3.2.2" to 13,
                ECDH_GM_AES128 to 32,
                ECDH_GM_AES128 to 5,
                ECDH_GM_AES128 to 0,
                DH_GM_3DES to 13,
            )) {
                assertEquals(PaceFailure.UNSUPPORTED_SUITE, assertFailsWith<PaceException> { PaceSuite.of(oid, id) }.failure, "$oid $id")
            }
        }

    @Test
    fun oidContentMatchesTheDerEncoding() {
        for (oid in listOf(ECDH_GM_AES128, DH_GM_3DES, "1.2.840.10045.2.1", "2.23.136.1.1.5")) {
            val der = DerWriter.oid(oid)
            assertContentEquals(der.copyOfRange(2, der.size), Pace.oidContent(oid), oid)
        }
    }

    @Test
    fun tokensUseTheSuiteCipher() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(DH_GM_3DES, 0)))
                val result = Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(DH_GM_3DES, 0))
                assertEquals(SmCipher.DESEDE, result.channel.cipher)
                // The token step is the fifth command: CLA 00, not chained.
                assertEquals(0x00, s.chip.received[4][0].toInt())
                assertTrue(
                    s.chip.received
                        .subList(1, 4)
                        .all { it[0].toInt() == 0x10 },
                )
            }
        }

    @Test
    fun aSetAtRefusalWithAnotherStatusWordIsTyped() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)))
                s.pace.refuseSetAt = 0x6A86
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES128, 13)) }
                assertEquals(PaceFailure.SET_AUTHENTICATION_TEMPLATE_REFUSED, e.failure)
                assertEquals(PaceStep.SET_AUTHENTICATION_TEMPLATE, e.step)
                assertEquals(0x6A86, e.sw)
            }
        }

    @Test
    fun aWrongLengthEncryptedNonceIsTyped() =
        runTest {
            withCrypto { c ->
                val s = setup(c, setOf(FakePaceSuite(ECDH_GM_AES128, 13)))
                s.pace.nonceOverride = ByteArray(15)
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_GM_AES128, 13)) }
                assertEquals(PaceFailure.WRONG_NONCE_LENGTH, e.failure)
                assertEquals(PaceStep.ENCRYPTED_NONCE, e.step)
            }
        }

    @Test
    fun aDegenerateGeneratorIsTyped() =
        runTest {
            withCrypto { c ->
                val suite = PaceSuite.of(ECDH_GM_AES128, 13)
                val params = (suite.group as PaceGroup.Ec).parameters
                // The negated generator, a valid point: with private key 1 and nonce 1 the mapping
                // yields s*G + H = G + (-G), the point at infinity.
                val negatedGenerator = EcPoint(params.generator.x, c.modSubtract(params.p, params.generator.y, params.p))
                val context =
                    MappingContext(
                        c,
                        suite,
                        newPrivateKey = { CryptoBytes.ofInt(1) },
                        randomBytes = { error("the generic mapping needs no random bytes") },
                    )
                val e =
                    assertFailsWith<PaceException> {
                        GenericMapping().map(context, CryptoBytes.ofInt(1)) { EcPointCodec.encode(params, negatedGenerator) }
                    }
                assertEquals(PaceFailure.DEGENERATE_GENERATOR, e.failure)
                assertEquals(PaceStep.MAP_NONCE, e.step)
            }
        }

    private companion object {
        const val ID_PACE = "0.4.0.127.0.7.2.2.4"
        const val ECDH_GM_AES128 = "$ID_PACE.2.2"
        const val ECDH_GM_AES256 = "$ID_PACE.2.4"
        const val DH_GM_3DES = "$ID_PACE.1.1"
    }
}
