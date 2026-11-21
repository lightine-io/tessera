package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.emrtd.internal.sm.PlainExchange
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * PACE with the chip authentication mapping, AES-192 and AES-256, against a chip written straight
 * from Doc 9303 Part 11 (sections 4.4, 4.4.3.5, 9.7) on BouncyCastle alone: its own curve tables,
 * point arithmetic, SHA, AES and CMAC, its own TLV and APDU handling. It shares no code with the
 * reader or with the common fake chip. Appendix I prints only AES-128, so these suites are "checked
 * against an independent reference, not ICAO values".
 */
class PaceCamReferenceTest {
    private val random = SecureRandom()

    // The project specimen's MRZ (synthetic): the password both sides use.
    private val line2 = FakeChipData.specimenTd3Lines()[1]
    private val key =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), line2[27])
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private class Suite(
        val cipherCode: Int,
        val keyLength: Int,
        val parameterId: Int,
        val curveName: String,
    )

    @Test
    fun aes192And256CamRunsAndTheKeyChecksOut() =
        runTest {
            val suites =
                listOf(
                    Suite(3, 24, 13, "brainpoolP256r1"),
                    Suite(3, 24, 12, "P-256"),
                    Suite(4, 32, 16, "brainpoolP384r1"),
                    Suite(4, 32, 15, "P-384"),
                )
            for (s in suites) {
                val chip = ReferenceCamChip(s, mrzInformation, random)
                val oid = "0.4.0.127.0.7.2.2.4.6.${s.cipherCode}"
                val result = Pace.open(PlainExchange(chip), chipCrypto(), key, PaceSuite.of(oid, s.parameterId))
                assertEquals(if (s.keyLength == 24) SmCipher.AES192 else SmCipher.AES256, result.channel.cipher, s.curveName)
                val evidence = assertIs<ChipAuthMappingEvidence>(result.mappingEvidence, s.curveName)
                assertNull(evidence.dataFailure, s.curveName)
                val info = SecurityInfoParser.parse(chip.securityInfos()).chipAuthenticationPublicKey(s.parameterId)!!
                assertEquals(
                    CamOutcome.Passed,
                    ChipAuthMappingCheck.keyMatches(chipCrypto(), evidence, evidence.chipAuthenticationData!!, info),
                    s.curveName,
                )
                // The same run with a chip that sends CA_IC + 1: the check fails.
                val wrong = ReferenceCamChip(s, mrzInformation, random, offByOne = true)
                val bad = Pace.open(PlainExchange(wrong), chipCrypto(), key, PaceSuite.of(oid, s.parameterId))
                val badEvidence = bad.mappingEvidence as ChipAuthMappingEvidence
                val badInfo = SecurityInfoParser.parse(wrong.securityInfos()).chipAuthenticationPublicKey(s.parameterId)!!
                assertEquals(
                    CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH),
                    ChipAuthMappingCheck.keyMatches(chipCrypto(), badEvidence, badEvidence.chipAuthenticationData!!, badInfo),
                    s.curveName,
                )
            }
        }

    /** The chip side of PACE-CAM, BouncyCastle only. Short APDUs, MRZ password, one run. */
    private class ReferenceCamChip(
        private val suite: Suite,
        mrzInformation: String,
        private val random: SecureRandom,
        private val offByOne: Boolean = false,
    ) : ApduTransport {
        override val maxCommandLength: Int = 261
        override val supportsExtendedLength: Boolean = false

        private val curve = ECNamedCurveTable.getByName(suite.curveName)
        private val n: BigInteger = curve.n
        private val fieldLength = (curve.curve.fieldSize + 7) / 8
        private val oid = ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.4.6.${suite.cipherCode}").encoded.let { it.copyOfRange(2, it.size) }
        private val kPi = kdf(sha1(mrzInformation.toByteArray()), 3)
        private val skIc = randomScalar()
        private val pkIc: ECPoint = curve.g.multiply(skIc).normalize()

        private var step = 0
        private lateinit var s: ByteArray
        private lateinit var skMap: BigInteger
        private lateinit var mappedG: ECPoint
        private lateinit var readerKey: ByteArray
        private lateinit var chipKey: ByteArray
        private lateinit var kEnc: ByteArray
        private lateinit var kMac: ByteArray

        /** EF.CardSecurity's SecurityInfos: a ChipAuthenticationPublicKeyInfo for SK_IC, keyId = parameter ID. */
        fun securityInfos(): ByteArray {
            val algorithm = DERSequence(arrayOf(ASN1ObjectIdentifier("0.4.0.127.0.7.1.2"), ASN1Integer(suite.parameterId.toLong())))
            val spki = DERSequence(arrayOf(algorithm, DERBitString(pkIc.getEncoded(false))))
            val v = ASN1EncodableVector()
            v.add(ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.1.2"))
            v.add(spki)
            v.add(ASN1Integer(suite.parameterId.toLong()))
            return DERSet(DERSequence(v)).encoded
        }

        override suspend fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            val lc = command[4].toInt() and 0xFF
            val data = command.copyOfRange(5, 5 + lc)
            if (ins == 0x22) return byteArrayOf(0x90.toByte(), 0x00)
            val inner = parseTlv(parseTlv(data).single().second)
            step++
            val answer =
                when (step) {
                    1 -> {
                        s = ByteArray(16).also { random.nextBytes(it) }
                        tlv(0x80, aesCbc(kPi, ByteArray(16), s))
                    }

                    2 -> {
                        val reader = curve.curve.decodePoint(inner.single { it.first == 0x81 }.second)
                        skMap = randomScalar()
                        val h = reader.multiply(skMap)
                        mappedG =
                            curve.g
                                .multiply(BigInteger(1, s))
                                .add(h)
                                .normalize()
                        tlv(
                            0x82,
                            curve.g
                                .multiply(skMap)
                                .normalize()
                                .getEncoded(false),
                        )
                    }

                    3 -> {
                        readerKey = inner.single { it.first == 0x83 }.second
                        val sk = randomScalar()
                        chipKey = mappedG.multiply(sk).normalize().getEncoded(false)
                        val shared =
                            curve.curve
                                .decodePoint(readerKey)
                                .multiply(sk)
                                .normalize()
                                .affineXCoord.encoded
                        kEnc = kdf(fixed(shared, fieldLength), 1)
                        kMac = kdf(fixed(shared, fieldLength), 2)
                        tlv(0x84, chipKey)
                    }

                    else -> {
                        val token = inner.single { it.first == 0x85 }.second
                        check(token.contentEquals(token(chipKey))) { "reader token" }
                        var ca = skIc.modInverse(n).multiply(skMap).mod(n)
                        if (offByOne) ca = ca.add(BigInteger.ONE).mod(n)
                        val fe2os = fixed(ca.toByteArray(), (n.bitLength() + 7) / 8)
                        val iv = aesEcb(kEnc, ByteArray(16) { -1 })
                        tlv(0x86, token(readerKey)) + tlv(0x8A, aesCbc(kEnc, iv, pad(fe2os)))
                    }
                }
            return tlv(0x7C, answer) + byteArrayOf(0x90.toByte(), 0x00)
        }

        private fun randomScalar(): BigInteger {
            while (true) {
                val k = BigInteger(n.bitLength(), random)
                if (k.signum() > 0 && k < n) return k
            }
        }

        private fun token(key: ByteArray): ByteArray {
            val input = byteArrayOf(0x7F, 0x49) + len(2 + oid.size + 2 + key.size) + tlv(0x06, oid) + tlv(0x86, key)
            val mac = CMac(AESEngine.newInstance())
            mac.init(KeyParameter(kMac))
            mac.update(input, 0, input.size)
            return ByteArray(16).also { mac.doFinal(it, 0) }.copyOf(8)
        }

        /** KDF of Part 11, 9.7.1: SHA-256 for AES-192/256, cut to the key length. */
        private fun kdf(
            k: ByteArray,
            counter: Int,
        ): ByteArray {
            val d = SHA256Digest()
            val input = k + byteArrayOf(0, 0, 0, counter.toByte())
            d.update(input, 0, input.size)
            return ByteArray(32).also { d.doFinal(it, 0) }.copyOf(suite.keyLength)
        }

        private fun sha1(b: ByteArray) =
            ByteArray(20).also {
                SHA1Digest().run {
                    update(b, 0, b.size)
                    doFinal(it, 0)
                }
            }

        private fun aesEcb(
            key: ByteArray,
            block: ByteArray,
        ): ByteArray {
            val e = AESEngine.newInstance()
            e.init(true, KeyParameter(key))
            return ByteArray(16).also { e.processBlock(block, 0, it, 0) }
        }

        private fun aesCbc(
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val c = CBCBlockCipher.newInstance(AESEngine.newInstance())
            c.init(true, ParametersWithIV(KeyParameter(key), iv))
            val out = ByteArray(data.size)
            for (i in data.indices step 16) c.processBlock(data, i, out, i)
            return out
        }

        private fun pad(d: ByteArray): ByteArray =
            ByteArray((d.size / 16 + 1) * 16).also {
                d.copyInto(it)
                it[d.size] = 0x80.toByte()
            }

        private fun fixed(
            v: ByteArray,
            length: Int,
        ): ByteArray {
            val stripped = v.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(length - stripped.size) + stripped
        }

        private fun len(l: Int): ByteArray = if (l < 0x80) byteArrayOf(l.toByte()) else byteArrayOf(0x81.toByte(), l.toByte())

        private fun tlv(
            tag: Int,
            value: ByteArray,
        ): ByteArray = byteArrayOf(tag.toByte()) + len(value.size) + value

        /** One-byte tags, lengths up to 0x81 xx: enough for these answers. */
        private fun parseTlv(b: ByteArray): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>()
            var i = 0
            while (i < b.size) {
                val tag = b[i++].toInt() and 0xFF
                var l = b[i++].toInt() and 0xFF
                if (l == 0x81) l = b[i++].toInt() and 0xFF
                out += tag to b.copyOfRange(i, i + l)
                i += l
            }
            return out
        }
    }
}
