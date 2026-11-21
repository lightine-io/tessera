package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakePace
import io.lightine.tessera.emrtd.fakechip.FakePaceIm
import io.lightine.tessera.emrtd.fakechip.FakePaceMapping
import io.lightine.tessera.emrtd.fakechip.FakePaceRun
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.PaceInfo
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.ParameterIdKind
import io.lightine.tessera.emrtd.internal.securityinfo.SessionCipher
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameters
import io.lightine.tessera.emrtd.internal.sm.ApduExchange
import io.lightine.tessera.emrtd.internal.sm.PlainExchange
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PACE with the integrated mapping against the fake chip ([FakePace], [FakePaceIm], [FakeSm]),
 * personalized from the project specimen. Every integrated-mapping suite (DH and ECDH, 3DES and AES
 * 128/192/256) with every standardized parameter set of its key agreement but NIST P-224 opens a
 * session and reads DG1 through it. These runs check the reader against an independent chip-side
 * implementation, not against ICAO values: Appendix H prints only AES-128 with BrainpoolP256r1 and
 * the 1024-bit DH group (PaceAppendixHTest).
 */
class PaceImFakeChipTest {
    private val lines = FakeChipData.specimenTd3Lines()
    private val files = FakeChipData.plainChipFiles(lines)
    private val dg1 = files.applications.getValue(LdsIds.LDS1_AID.toList()).getValue(LdsIds.EF_DG1)
    private val line2 = lines[1]
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private fun mrzKey() =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), line2[27])

    private class Setup(
        val chip: FakeChip,
        val pace: FakePace,
        val exchange: PlainExchange,
    )

    private fun setup(
        c: ChipCrypto,
        suites: Set<FakePaceSuite>,
        extended: Boolean = false,
        mappings: List<FakePaceMapping> = listOf(FakePaceIm()),
    ): Setup {
        val sm = FakeSm(c)
        val pace = FakePace(c, sm, suites, mappings, mrzInformation = mrzInformation)
        val chip =
            FakeChip(
                files,
                maxCommandLength = if (extended) FakeChip.EXTENDED_MAX_COMMAND else FakeChip.SHORT_MAX_COMMAND,
                supportsExtendedLength = extended,
                handlers = listOf(sm, pace),
            )
        return Setup(chip, pace, PlainExchange(chip))
    }

    private suspend fun readDg1(channel: ApduExchange): ByteArray {
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
    fun everyIntegratedMappingSuiteAndParameterSetOpensTheChip() =
        runTest {
            withCrypto { c ->
                var runs = 0
                for (agreement in KeyAgreement.entries) {
                    val ids = (0..31).filter { StandardizedParameters.byId(it)?.agreement == agreement && it != P224 }
                    for (cipherCode in 1..4) {
                        val oid = "$ID_PACE.${if (agreement == KeyAgreement.DH) 3 else 4}.$cipherCode"
                        for (id in ids) {
                            val extended = agreement == KeyAgreement.DH && id != 0
                            val s = setup(c, setOf(FakePaceSuite(oid, id)), extended = extended)
                            val suite = PaceSuite.of(oid, id)
                            val result = Pace.open(s.exchange, c, mrzKey(), suite, PaceOptions(extendedLengthAllowed = extended))
                            assertContentEquals(ByteArray(suite.smCipher.blockSize), result.channel.sscCopy(), "$suite")
                            assertContentEquals(dg1, readDg1(result.channel), "$suite")
                            // Step 2 sends t (k bits) in 0x81: 10 86 00 00 Lc 7C L 81 L t Le (short APDU),
                            // or with a three-byte Lc when extended length is in use.
                            val mapStep = s.chip.received[2]
                            val at = if (extended) 9 else 7
                            assertEquals(0x81, mapStep[at].toInt() and 0xFF)
                            assertEquals(suite.smCipher.keyType.length, mapStep[at + 1].toInt(), "$suite")
                            runs++
                        }
                    }
                }
                // 4 ciphers x (3 DH groups + 10 curves).
                assertEquals(52, runs)
            }
        }

    @Test
    fun nistP224IsRefusedWithTheIntegratedMapping() {
        for (cipherCode in 1..4) {
            val e = assertFailsWith<PaceException> { PaceSuite.of("$ID_PACE.4.$cipherCode", P224) }
            assertEquals(PaceFailure.UNSUPPORTED_SUITE, e.failure)
        }
        // The generic mapping still takes it.
        assertEquals("NIST P-224", PaceSuite.of("$ID_PACE.2.2", P224).parameter.name)
        assertFalse(StandardizedParameters.byId(P224)!!.usableWithIntegratedMapping)
        // A chip's PACEInfo naming the pair is not offered as usable.
        val im =
            PaceInfo(
                "$ID_PACE.4.2",
                PaceMapping.INTEGRATED,
                KeyAgreement.ECDH,
                SessionCipher.AES_CBC_CMAC_128,
                2,
                P224,
                ParameterIdKind.STANDARDIZED,
            )
        assertFalse(im.usable)
        assertTrue(im.copy(protocol = "$ID_PACE.2.2", mapping = PaceMapping.GENERIC).usable)
        assertTrue(im.copy(parameterId = 13).usable)
    }

    @Test
    fun aes192KeysAreCutToTwentyFourOctets() =
        withCrypto { c ->
            // R(s, t) for AES-192 by hand: every key k_i is octets 1 to 24 of a 32-octet CBC output.
            val s = ByteArray(32) { (it * 7 + 1).toByte() }
            val t = ByteArray(24) { (it * 13 + 5).toByte() }
            val c0 = "D463D65234124EF7897054986DCA0A174E28DF758CBAA03F240616414D5A1676".hexToByteArray()
            val c1 = "54BD7255F0AAF831BEC3423FCF39D69B6CBF066677D0FAAE5AADD99DF8E53517".hexToByteArray()
            val iv = ByteArray(16)

            fun aes(
                key: ByteArray,
                data: ByteArray,
            ) = c.encrypt(SmCipher.AES192.blockCipher, CipherMode.CBC, key, iv, data)
            var k = aes(t, s).copyOf(24)
            var expected = ByteArray(0)
            // BrainpoolP384r1: 384 + 64 bits need two 256-bit outputs.
            repeat(2) {
                expected += aes(k, c1)
                k = aes(k, c0).copyOf(24)
            }
            assertContentEquals(expected, IntegratedMapping.pseudoRandom(c, SmCipher.AES192, s, t, 384))
            assertEquals(32, IntegratedMapping.nonceLength(SmCipher.AES192))
        }

    @Test
    fun aNonceShorterThanTheKeyIsRefused() =
        runTest {
            withCrypto { c ->
                // A chip that sends a one-block nonce with AES-256 (l must be 256 bits).
                val shortNonce =
                    object : FakePaceMapping by FakePaceIm() {
                        override fun nonceLength(cipher: SmCipher): Int = cipher.blockSize
                    }
                val s = setup(c, setOf(FakePaceSuite(ECDH_IM_AES256, 13)), mappings = listOf(shortNonce))
                val e = assertFailsWith<PaceException> { Pace.open(s.exchange, c, mrzKey(), PaceSuite.of(ECDH_IM_AES256, 13)) }
                assertEquals(PaceFailure.WRONG_NONCE_LENGTH, e.failure)
                assertEquals(PaceStep.MAP_NONCE, e.step)
            }
        }

    @Test
    fun aDegenerateDhGeneratorIsRefused() =
        withCrypto { c ->
            val group = (PaceSuite.of(DH_IM_AES128, 0).group as PaceGroup.Dh).parameters
            // 1 and p-1 both map to 1 (the cofactor is even).
            for (x in listOf(byteArrayOf(1), c.modSubtract(group.p, byteArrayOf(1), group.p))) {
                val e = assertFailsWith<PaceException> { IntegratedMapping.mapDh(c, group, x) }
                assertEquals(PaceFailure.DEGENERATE_GENERATOR, e.failure)
            }
        }

    @Test
    fun aChipThatMapsAnotherNonceFailsMutualAuthentication() =
        runTest {
            withCrypto { c ->
                // A chip that maps with a different t than the reader sent: the tokens no longer match.
                val tampered =
                    object : FakePaceMapping by FakePaceIm() {
                        override fun map(
                            run: FakePaceRun,
                            readerData: ByteArray,
                        ): Pair<ByteArray, DomainParameters>? =
                            FakePaceIm().map(
                                run,
                                readerData.copyOf().also {
                                    it[0] =
                                        (it[0] + 1).toByte()
                                },
                            )
                    }
                val t = setup(c, setOf(FakePaceSuite(ECDH_IM_AES256, 16)), mappings = listOf(tampered))
                val e2 = assertFailsWith<PaceException> { Pace.open(t.exchange, c, mrzKey(), PaceSuite.of(ECDH_IM_AES256, 16)) }
                assertEquals(PaceFailure.STEP_REFUSED, e2.failure)
                assertEquals(0x6300, e2.sw)
            }
        }

    @Test
    fun theReaderHasAnIntegratedMapping() {
        assertTrue(Mappings.forKind(PaceMapping.INTEGRATED) is IntegratedMapping)
    }

    private companion object {
        const val ID_PACE = "0.4.0.127.0.7.2.2.4"
        const val ECDH_IM_AES256 = "$ID_PACE.4.4"
        const val DH_IM_AES128 = "$ID_PACE.3.2"
        const val P224 = 10
    }
}
