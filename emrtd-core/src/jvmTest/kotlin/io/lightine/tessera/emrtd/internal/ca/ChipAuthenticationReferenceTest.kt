package io.lightine.tessera.emrtd.internal.ca

import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.crypto.dhParams
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.agreement.DHStandardGroups
import org.bouncycastle.crypto.params.DHParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reference check, not ICAO values: Doc 9303 Part 11 prints no worked example for standalone chip
 * authentication, so every suite of Tables 5 and 6 (DH and ECDH, each with 3DES and AES-128, 192
 * and 256) runs against [CaReferenceChip], an independent chip built only on BouncyCastle, on every
 * standardized curve of Table 12 and on three DH groups (keys written with explicit domain
 * parameters, as section 9.2 asks; DH in both the X9.42 and the PKCS #3 form). A suite passes when the reader and the
 * reference agree on keys: after chip authentication the reader reads a file under the new keys.
 */
class ChipAuthenticationReferenceTest {
    private val file = byteArrayOf(0x61, 0x05, 0x5F, 0x1F, 0x02, 0x41, 0x42)

    private val curves =
        listOf(
            "secp192r1",
            "brainpoolP192r1",
            "secp224r1",
            "brainpoolP224r1",
            "secp256r1",
            "brainpoolP256r1",
            "brainpoolP320r1",
            "secp384r1",
            "brainpoolP384r1",
            "brainpoolP512r1",
            "secp521r1",
        ).map { CaReferenceChip.Group.Ec(it, ECDomainParameters(ECNamedCurveTable.getByName(it))) }

    // BouncyCastle no longer ships the RFC 5114 groups of Table 12, so two DH groups are its own:
    // RFC 2409 Oakley group 2 (no q, written as PKCS #3) and RFC 7919 ffdhe2048 (written as X9.42
    // when BouncyCastle gives its q). A chip key carries explicit parameters, so any group is a
    // valid key. The third is RFC 5114 1024/160 from the SDK's own table (commonTest checks its
    // values against the RFC 5114 Appendix A vectors); it brings a small subgroup q.
    private val dhGroups =
        listOf(
            CaReferenceChip.Group.Dh("RFC 2409 1024", DHStandardGroups.rfc2409_1024, x942 = false),
            CaReferenceChip.Group.Dh(
                "RFC 7919 ffdhe2048",
                DHStandardGroups.rfc7919_ffdhe2048,
                x942 =
                    DHStandardGroups.rfc7919_ffdhe2048.q != null,
            ),
            dhParams(0).let { g ->
                CaReferenceChip.Group.Dh(
                    "RFC 5114 1024/160",
                    DHParameters(BigInteger(1, g.p), BigInteger(1, g.g), BigInteger(1, g.q!!)),
                    x942 = true,
                )
            },
        )

    private fun smCipher(c: CaReferenceChip.Cipher) =
        when (c) {
            CaReferenceChip.Cipher.TDES -> SmCipher.DESEDE
            CaReferenceChip.Cipher.AES128 -> SmCipher.AES128
            CaReferenceChip.Cipher.AES192 -> SmCipher.AES192
            CaReferenceChip.Cipher.AES256 -> SmCipher.AES256
        }

    private class Run(
        val chip: CaReferenceChip,
        val channel: SecureChannel,
        val outcome: CaOutcome,
    )

    private suspend fun run(
        group: CaReferenceChip.Group,
        offered: List<CaReferenceChip.Cipher>,
        old: CaReferenceChip.Cipher,
        setKatSupported: Boolean = true,
    ): Run {
        val kEnc = Random.nextBytes(old.keyLength)
        val kMac = Random.nextBytes(old.keyLength)
        val ssc = Random.nextBytes(old.blockSize)
        val chip = CaReferenceChip(group, offered.map { it.code }, old, kEnc to kMac, ssc, file, setKatSupported)
        val crypto = chipCrypto()
        val channel = SecureChannel(PlainApduSender(chip), crypto, SessionKeys(smCipher(old), kEnc, kMac), ssc)
        val infos = SecurityInfoParser.parse(chip.securityInfos())
        val outcome = ChipAuthentication.run(channel, crypto, infos, CaContext.EMRTD_APPLICATION, extendedLengthAllowed = true)
        return Run(chip, channel, outcome)
    }

    private suspend fun assertReadsUnderNewKeys(r: Run) {
        assertEquals(StatusWord.SUCCESS, r.channel.send(ChipCommands.selectFile(0x0101)).sw)
        val read = r.channel.send(ChipCommands.readBinary(0, file.size))
        assertEquals(StatusWord.SUCCESS, read.sw)
        assertContentEquals(file, read.data)
        assertTrue(r.channel.isOpen)
    }

    @Test
    fun everySuiteAgreesKeysWithTheReferenceChipOnEveryGroup() =
        runTest {
            var runs = 0
            for (cipher in CaReferenceChip.Cipher.entries) {
                for (group in curves + dhGroups) {
                    // The old session alternates between 3DES (as after BAC) and AES (as after PACE).
                    val old = if (runs % 2 == 0) CaReferenceChip.Cipher.TDES else CaReferenceChip.Cipher.AES128
                    val r = run(group, listOf(cipher), old)
                    val name = "$cipher on ${(group as? CaReferenceChip.Group.Ec)?.name ?: (group as CaReferenceChip.Group.Dh).name}"
                    val outcome = assertIs<CaOutcome.Restarted>(r.outcome, name)
                    assertEquals(smCipher(cipher), outcome.cipher, name)
                    val expected =
                        if (cipher == CaReferenceChip.Cipher.TDES) {
                            listOf(0x22 to 0xA6)
                        } else {
                            listOf(0x22 to 0xA4, 0x86 to 0x00)
                        }
                    assertEquals(expected, r.chip.commands, name)
                    assertEquals(1, r.chip.restarts, name)
                    assertReadsUnderNewKeys(r)
                    runs++
                }
            }
            assertEquals(4 * 14, runs)
        }

    @Test
    fun theReaderFallsBackToSetAtWhenTheReferenceChipRefusesSetKat() =
        runTest {
            for (group in listOf(curves[5], dhGroups[0])) {
                val r = run(group, listOf(CaReferenceChip.Cipher.TDES), CaReferenceChip.Cipher.TDES, setKatSupported = false)
                val outcome = assertIs<CaOutcome.Restarted>(r.outcome)
                assertTrue(outcome.usedSetAtFallback)
                assertEquals(listOf(0x22 to 0xA6, 0x22 to 0xA4, 0x86 to 0x00), r.chip.commands)
                assertReadsUnderNewKeys(r)
            }
        }

    @Test
    fun theReaderPrefersAesWhenTheReferenceChipOffersBoth() =
        runTest {
            val r = run(curves[4], listOf(CaReferenceChip.Cipher.TDES, CaReferenceChip.Cipher.AES256), CaReferenceChip.Cipher.TDES)
            val outcome = assertIs<CaOutcome.Restarted>(r.outcome)
            assertEquals(SmCipher.AES256, outcome.cipher)
            assertEquals(listOf(0x22 to 0xA4, 0x86 to 0x00), r.chip.commands)
            assertReadsUnderNewKeys(r)
        }
}
