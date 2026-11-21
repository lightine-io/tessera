package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared size policy for keys and domain parameters from untrusted data, at each boundary. Pure
 * byte arithmetic, so it runs on every platform, iOS included.
 */
class KeyLimitsTest {
    /** An odd number of exactly [bits] bits: top bit and bottom bit set. */
    private fun oddOfBits(bits: Int): ByteArray {
        val bytes = ByteArray((bits + 7) / 8)
        val topBit = (bits - 1) % 8
        bytes[0] = (1 shl topBit).toByte()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or 1).toByte()
        return bytes
    }

    private val f4 = CryptoBytes.ofInt(65_537)

    @Test
    fun bitLengthCountsFromTheTopSetBit() {
        assertEquals(0, KeyLimits.bitLength(byteArrayOf(0, 0)))
        assertEquals(1, KeyLimits.bitLength(byteArrayOf(0, 1)))
        assertEquals(17, KeyLimits.bitLength(f4))
        assertEquals(8192, KeyLimits.bitLength(oddOfBits(8192)))
        assertEquals(8193, KeyLimits.bitLength(byteArrayOf(0) + oddOfBits(8193)))
    }

    @Test
    fun rsaModulusUpTo8192BitsAndExponentUpTo64BitsOddAboveOne() {
        assertEquals(KeyCheck.OK, KeyLimits.rsa(oddOfBits(8192), f4))
        assertEquals(KeyCheck.TOO_LARGE, KeyLimits.rsa(oddOfBits(8193), f4))
        assertEquals(KeyCheck.OK, KeyLimits.rsa(oddOfBits(2048), oddOfBits(64)))
        assertEquals(KeyCheck.TOO_LARGE, KeyLimits.rsa(oddOfBits(2048), oddOfBits(65)))
        assertEquals(KeyCheck.INVALID, KeyLimits.rsa(oddOfBits(2048), CryptoBytes.ofInt(1)), "exponent 1")
        assertEquals(KeyCheck.INVALID, KeyLimits.rsa(oddOfBits(2048), CryptoBytes.ofInt(65_536)), "even exponent")
        assertEquals(KeyCheck.INVALID, KeyLimits.rsa(oddOfBits(2048).also { it[it.size - 1] = 0 }, f4), "even modulus")
        assertEquals(KeyCheck.INVALID, KeyLimits.rsa(ByteArray(0), f4), "zero modulus")
        assertEquals(KeyCheck.INVALID, KeyLimits.rsa(CryptoBytes.ofInt(15), CryptoBytes.ofInt(17)), "exponent not below the modulus")
    }

    @Test
    fun dsaPrimeUpTo4096Bits() {
        val two = CryptoBytes.ofInt(2)
        val q = oddOfBits(256)
        assertEquals(KeyCheck.OK, KeyLimits.dsa(oddOfBits(4096), q, two, two))
        assertEquals(KeyCheck.TOO_LARGE, KeyLimits.dsa(oddOfBits(4097), q, two, two))
        assertEquals(KeyCheck.INVALID, KeyLimits.dsa(oddOfBits(2048), CryptoBytes.ofInt(1), two, two), "q below 2")
        assertEquals(KeyCheck.INVALID, KeyLimits.dsa(oddOfBits(2048), oddOfBits(2049), two, two), "q longer than p")
        assertEquals(KeyCheck.INVALID, KeyLimits.dsa(oddOfBits(2048), q, oddOfBits(2048), two), "g not below p")
        assertEquals(KeyCheck.INVALID, KeyLimits.dsa(oddOfBits(2048), q, two, CryptoBytes.ofInt(1)), "y below 2")
    }

    @Test
    fun ecFieldUpTo640BitsWithAnOrderOfAtLeastTwo() {
        val one = CryptoBytes.ofInt(1)
        val n = oddOfBits(640)
        assertEquals(KeyCheck.OK, KeyLimits.ecCurve(oddOfBits(640), one, one, n, one))
        assertEquals(KeyCheck.OK, KeyLimits.ecCurve(oddOfBits(640), one, one, oddOfBits(641), null), "Hasse: one bit more")
        assertEquals(KeyCheck.TOO_LARGE, KeyLimits.ecCurve(oddOfBits(641), one, one, n, one))
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256), one, one, byteArrayOf(0), one), "n = 0")
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256), one, one, one, one), "n = 1")
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256), one, one, oddOfBits(258), one), "n too long")
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256).also { it[it.size - 1] = 0 }, one, one, n, one), "even p")
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256), oddOfBits(256), one, oddOfBits(256), one), "a not below p")
        assertEquals(KeyCheck.INVALID, KeyLimits.ecCurve(oddOfBits(256), one, one, oddOfBits(256), byteArrayOf(0)), "cofactor 0")
    }

    @Test
    fun dhPrimeUpTo4096BitsWithAGeneratorInTwoToPMinusTwo() {
        val two = CryptoBytes.ofInt(2)
        assertEquals(KeyCheck.OK, KeyLimits.dh(oddOfBits(4096), two, null))
        assertEquals(KeyCheck.TOO_LARGE, KeyLimits.dh(oddOfBits(4097), two, null))
        assertEquals(KeyCheck.OK, KeyLimits.dh(CryptoBytes.ofInt(23), CryptoBytes.ofInt(21), CryptoBytes.ofInt(11)), "g = p-2")
        assertEquals(KeyCheck.INVALID, KeyLimits.dh(CryptoBytes.ofInt(23), CryptoBytes.ofInt(22), null), "g = p-1")
        assertEquals(KeyCheck.INVALID, KeyLimits.dh(CryptoBytes.ofInt(23), CryptoBytes.ofInt(1), null), "g = 1")
        assertEquals(KeyCheck.INVALID, KeyLimits.dh(CryptoBytes.ofInt(22), two, null), "even p")
        assertEquals(KeyCheck.INVALID, KeyLimits.dh(CryptoBytes.ofInt(23), two, CryptoBytes.ofInt(1)), "q = 1")
    }

    @Test
    fun theStandardizedParametersAllPass() {
        for (id in listOf(0, 1, 2, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)) {
            when (val p = StandardDomainParameterValues.forId(id)) {
                is DhDomainParameters -> assertEquals(KeyCheck.OK, KeyLimits.dh(p.p, p.g, p.q), "id $id")
                is EcDomainParameters -> assertEquals(KeyCheck.OK, KeyLimits.ecCurve(p.p, p.a, p.b, p.n, p.cofactor), "id $id")
                null -> Unit
            }
        }
    }

    @Test
    fun thePssSaltBoundIsEmLenMinusHashLengthMinusTwo() {
        // RFC 8017, 9.1.1: a 2048-bit key has emLen 256; with SHA-256 the salt may be 222 bytes.
        assertEquals(222, KeyLimits.maxPssSaltLength(oddOfBits(2048), HashAlgorithm.SHA256))
        // A 2049-bit modulus: emBits 2048, still 256 bytes.
        assertEquals(222, KeyLimits.maxPssSaltLength(oddOfBits(2049), HashAlgorithm.SHA256))
        assertEquals(256 - 64 - 2, KeyLimits.maxPssSaltLength(oddOfBits(2048), HashAlgorithm.SHA512))
    }
}
