package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The standardized domain parameters (Part 11, Table 12), DH and ECDH with public-key validation, EC
 * point arithmetic and the uncompressed encoding, against RFC 5114 and RFC 7027 test data.
 */
class KeyAgreementTest {
    private val ecIds = listOf(8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
    private val dhIds = listOf(0, 1, 2)

    @Test
    fun table12HasValuesForEveryStandardizedIdAndNoneForReservedIds() {
        for (id in 0..40) {
            val p = StandardDomainParameterValues.forId(id)
            when (id) {
                in dhIds -> assertTrue(p is DhDomainParameters, "ID $id")
                in ecIds -> assertTrue(p is EcDomainParameters, "ID $id")
                else -> assertNull(p, "ID $id")
            }
        }
        // Field sizes Table 12 lists.
        val bits =
            mapOf(
                8 to 192,
                9 to 192,
                10 to 224,
                11 to 224,
                12 to 256,
                13 to 256,
                14 to 320,
                15 to 384,
                16 to 384,
                17 to 512,
                18 to 528,
            )
        for ((id, b) in bits) assertEquals(b, ecParams(id).fieldLength * 8, "ID $id")
        assertEquals(128, dhParams(0).length)
        assertEquals(256, dhParams(1).length)
        assertEquals(256, dhParams(2).length)
    }

    @Test
    fun everyStandardCurveGeneratorIsOnTheCurveAndHasOrderN() =
        withCrypto { c ->
            // The point checks for the RFC 5639 Brainpool curves and the NIST curves: G satisfies
            // the curve equation, n*G is the point at infinity, and (n-1)*G is -G.
            for (id in ecIds) {
                val e = ecParams(id)
                assertTrue(c.ecIsOnCurve(e, e.generator), "ID $id")
                assertNull(c.ecMultiply(e, e.generator, e.n), "ID $id")
                val nMinusOne = c.modSubtract(e.n, CryptoBytes.ofInt(1), e.n)
                val minusG = assertNotNull(c.ecMultiply(e, e.generator, nMinusOne))
                assertTrue(CryptoBytes.unsignedEquals(e.generator.x, minusG.x), "ID $id")
                assertTrue(CryptoBytes.unsignedEquals(c.modSubtract(e.p, e.generator.y, e.p), minusG.y), "ID $id")
                assertTrue(EllipticCurves.isValidPublicKey(c, e, e.generator), "ID $id")
            }
        }

    @Test
    fun everyStandardDhGroupGeneratorHasOrderQ() =
        withCrypto { c ->
            for (id in dhIds) {
                val d = dhParams(id)
                assertTrue(DiffieHellman.isValidPublicKey(c, d, d.g), "ID $id")
                assertEquals("01", CryptoBytes.stripLeadingZeros(c.modPow(d.g, d.q!!, d.p)).toHex(), "ID $id")
            }
        }

    @Test
    fun dhMatchesRfc5114TestData() =
        withCrypto { c ->
            for (v in CryptoVectors.dh) {
                val d = dhParams(v.parameterId)
                assertEquals(norm(v.yA, d.length), DiffieHellman.publicKey(c, d, hex(v.xA)).toHex())
                assertEquals(norm(v.yB, d.length), DiffieHellman.publicKey(c, d, hex(v.xB)).toHex())
                assertEquals(norm(v.z, d.length), DiffieHellman.sharedSecret(c, d, hex(v.xA), hex(v.yB)).toHex())
                assertEquals(norm(v.z, d.length), DiffieHellman.sharedSecret(c, d, hex(v.xB), hex(v.yA)).toHex())
            }
        }

    @Test
    fun dhValidationRefusesKeysOutsideTheSubgroup() =
        withCrypto { c ->
            val d = dhParams(0)
            val pMinusOne = c.modSubtract(d.p, CryptoBytes.ofInt(1), d.p)
            assertFalse(DiffieHellman.isValidPublicKey(c, d, ByteArray(1)))
            assertFalse(DiffieHellman.isValidPublicKey(c, d, CryptoBytes.ofInt(1)))
            assertFalse(DiffieHellman.isValidPublicKey(c, d, pMinusOne))
            assertFalse(DiffieHellman.isValidPublicKey(c, d, d.p))
            // 2 is in range, but not in the order-q subgroup of this group.
            assertFalse(DiffieHellman.isValidPublicKey(c, d, CryptoBytes.ofInt(2)))
            assertFailsWith<ChipCryptoException> { DiffieHellman.sharedSecret(c, d, CryptoBytes.ofInt(5), pMinusOne) }
            // Without q only the range check is possible; p-1 is still refused.
            val noQ = DhDomainParameters(d.p, d.g, null)
            assertTrue(DiffieHellman.isValidPublicKey(c, noQ, CryptoBytes.ofInt(2)))
            assertFalse(DiffieHellman.isValidPublicKey(c, noQ, pMinusOne))
        }

    @Test
    fun dhKeyPairsAgreeAndGeneratorCanBeReplaced() =
        withCrypto { c ->
            val base = dhParams(2)
            // A mapped generator, as PACE builds one: g~ = g^s mod p stays in the order-q subgroup.
            val mapped = base.withGenerator(c.modPow(base.g, c.randomBytes(16), base.p))
            for (d in listOf(base, mapped)) {
                val a = DiffieHellman.generatePrivateKey(c, d)
                val b = DiffieHellman.generatePrivateKey(c, d)
                assertTrue(CryptoBytes.compareUnsigned(a, d.q!!) < 0)
                val pa = DiffieHellman.publicKey(c, d, a)
                val pb = DiffieHellman.publicKey(c, d, b)
                assertEquals(
                    DiffieHellman.sharedSecret(c, d, a, pb).toHex(),
                    DiffieHellman.sharedSecret(c, d, b, pa).toHex(),
                )
            }
        }

    @Test
    fun ecdhMatchesRfc5114AndRfc7027TestData() =
        withCrypto { c ->
            for (v in CryptoVectors.ecdh) {
                val e = ecParams(v.parameterId)
                val qa = EcPoint(hex(v.xA), hex(v.yA))
                val qb = EcPoint(hex(v.xB), hex(v.yB))
                assertEquals(qa, EllipticCurves.publicKey(c, e, hex(v.dA)), "ID ${v.parameterId}")
                assertEquals(qb, EllipticCurves.publicKey(c, e, hex(v.dB)), "ID ${v.parameterId}")
                val z = EcPoint(hex(v.xZ), hex(v.yZ))
                assertEquals(z, EllipticCurves.sharedPoint(c, e, hex(v.dA), qb), "ID ${v.parameterId}")
                assertEquals(z, EllipticCurves.sharedPoint(c, e, hex(v.dB), qa), "ID ${v.parameterId}")
                assertEquals(
                    norm(v.xZ, e.fieldLength),
                    EllipticCurves.sharedSecret(c, e, hex(v.dA), qb).toHex(),
                    "ID ${v.parameterId}",
                )
            }
        }

    @Test
    fun ecdhWithACustomGeneratorAgrees() =
        withCrypto { c ->
            val e = ecParams(13)
            // A mapped generator G~ = s*G + H, as the generic mapping builds it.
            val s = EllipticCurves.generatePrivateKey(c, e)
            val h = EllipticCurves.publicKey(c, e, EllipticCurves.generatePrivateKey(c, e))
            val mapped = e.withGenerator(assertNotNull(c.ecAdd(e, EllipticCurves.publicKey(c, e, s), h)))
            val a = EllipticCurves.generatePrivateKey(c, mapped)
            val b = EllipticCurves.generatePrivateKey(c, mapped)
            val pa = EllipticCurves.publicKey(c, mapped, a)
            val pb = EllipticCurves.publicKey(c, mapped, b)
            assertEquals(
                EllipticCurves.sharedSecret(c, mapped, a, pb).toHex(),
                EllipticCurves.sharedSecret(c, mapped, b, pa).toHex(),
            )
        }

    @Test
    fun pointAdditionAndMultiplicationAreConsistent() =
        withCrypto { c ->
            val e = ecParams(16)
            val g = e.generator
            val twoG = assertNotNull(c.ecAdd(e, g, g))
            assertEquals(twoG, c.ecMultiply(e, g, CryptoBytes.ofInt(2)))
            assertEquals(c.ecMultiply(e, g, CryptoBytes.ofInt(3)), c.ecAdd(e, twoG, g))
            val minusG = EcPoint(g.x, c.modSubtract(e.p, g.y, e.p))
            assertNull(c.ecAdd(e, g, minusG))
            assertNull(c.ecMultiply(e, g, ByteArray(0)))
        }

    @Test
    fun ecValidationRefusesPointsOffTheCurveAndOutOfRange() =
        withCrypto { c ->
            val e = ecParams(12)
            val g = e.generator
            val off = EcPoint(g.x, c.modAdd(g.y, CryptoBytes.ofInt(1), e.p))
            assertFalse(c.ecIsOnCurve(e, off))
            assertFalse(EllipticCurves.isValidPublicKey(c, e, off))
            assertFalse(EllipticCurves.isValidPublicKey(c, e, EcPoint(e.p, g.y)))
            assertFailsWith<ChipCryptoException> { EllipticCurves.sharedSecret(c, e, CryptoBytes.ofInt(7), off) }
            assertFailsWith<ChipCryptoException> { c.ecMultiply(e, off, CryptoBytes.ofInt(2)) }
        }

    @Test
    fun uncompressedEncodingRoundTripsAndRefusesOtherForms() =
        withCrypto { c ->
            for (id in ecIds) {
                val e = ecParams(id)
                val p = EllipticCurves.publicKey(c, e, EllipticCurves.generatePrivateKey(c, e))
                val enc = EcPointCodec.encode(e, p)
                assertEquals(1 + 2 * e.fieldLength, enc.size)
                assertEquals(p, EcPointCodec.decode(c, e, enc))
            }
            val e = ecParams(13)
            val enc = EcPointCodec.encode(e, e.generator)
            // Compressed form (02/03 || X), a wrong prefix, a wrong length, and a point off the curve.
            assertFailsWith<ChipCryptoException> { EcPointCodec.decode(c, e, byteArrayOf(0x02) + e.generator.x) }
            assertFailsWith<ChipCryptoException> { EcPointCodec.decode(c, e, enc.copyOf().also { it[0] = 0x06 }) }
            assertFailsWith<ChipCryptoException> { EcPointCodec.decode(c, e, enc.copyOf(enc.size - 1)) }
            assertFailsWith<ChipCryptoException> {
                EcPointCodec.decode(c, e, enc.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
            }
        }

    @Test
    fun modularArithmetic() =
        withCrypto { c ->
            val m = CryptoBytes.ofInt(97)
            assertEquals("0C", c.modAdd(CryptoBytes.ofInt(60), CryptoBytes.ofInt(49), m).toHex())
            assertEquals("54", c.modSubtract(CryptoBytes.ofInt(3), CryptoBytes.ofInt(16), m).toHex())
            // 20 * 30 = 600 = 6 * 97 + 18.
            assertEquals("12", c.modMultiply(CryptoBytes.ofInt(20), CryptoBytes.ofInt(30), m).toHex())
            // 3^-1 mod 97 = 65, since 3 * 65 = 195 = 2 * 97 + 1.
            assertEquals("41", c.modInverse(CryptoBytes.ofInt(3), m).toHex())
            assertFailsWith<ChipCryptoException> { c.modInverse(CryptoBytes.ofInt(97), m) }
            assertFailsWith<ChipCryptoException> { c.modPow(CryptoBytes.ofInt(2), CryptoBytes.ofInt(2), ByteArray(1)) }
        }

    @Test
    fun randomPrivateKeysStayBelowTheBound() =
        withCrypto { c ->
            val bound = CryptoBytes.ofInt(0x0101)
            repeat(200) {
                val k = randomBelow(c, bound)
                assertTrue(CryptoBytes.compareUnsigned(k, bound) < 0 && !CryptoBytes.isZero(k))
            }
            assertFailsWith<ChipCryptoException> { randomBelow(c, CryptoBytes.ofInt(1)) }
        }

    private fun norm(
        hexValue: String,
        length: Int,
    ): String = CryptoBytes.toFixedLength(hex(hexValue), length).toHex()
}
