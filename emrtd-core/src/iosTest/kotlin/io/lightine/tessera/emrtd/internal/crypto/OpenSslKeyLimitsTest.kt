package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The OpenSSL backend applies the same size policy ([KeyLimits]) as the BouncyCastle one, with the
 * same typed failure ([ChipCryptoException]). The shared hostile-parameter tests in commonTest run
 * on iOS too; these call the backend directly.
 */
class OpenSslKeyLimitsTest {
    private fun oddOfBits(bits: Int): ByteArray {
        val out = ByteArray((bits + 7) / 8) { 0xFF.toByte() }
        out[0] = (1 shl ((bits - 1) % 8)).toByte() // top bit set, the rest of that byte clear
        out[out.size - 1] = (out[out.size - 1].toInt() or 1).toByte()
        return out
    }

    private val f4 = byteArrayOf(1, 0, 1)
    private val message = "UTOPIA SPECIMEN".encodeToByteArray()

    @Test
    fun modPowRefusesAnOversizeExponentOrModulus() {
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modPow(byteArrayOf(2), oddOfBits(8193), oddOfBits(2048)) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modPow(byteArrayOf(2), f4, oddOfBits(8193)) }
    }

    @Test
    fun modularArithmeticRefusesAnOversizeModulus() {
        val big = oddOfBits(8193)
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modMultiply(byteArrayOf(2), byteArrayOf(3), big) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modAdd(byteArrayOf(2), byteArrayOf(3), big) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modSubtract(byteArrayOf(3), byteArrayOf(2), big) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.modInverse(byteArrayOf(3), big) }
    }

    @Test
    fun rsaPublicRefusesKeysOutsideThePolicy() {
        val input = byteArrayOf(2)
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.rsaPublic(oddOfBits(8193), f4, input) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.rsaPublic(oddOfBits(2048), oddOfBits(65), input) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.rsaPublic(oddOfBits(2048), byteArrayOf(1), input) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.rsaPublic(oddOfBits(2048).also { it[it.size - 1] = 0 }, f4, input) }
    }

    @Test
    fun verifyRefusesRsaKeysOutsideThePolicy() {
        val scheme = SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA256)
        assertFailsWith<ChipCryptoException> {
            OpenSslChipCrypto.verify(scheme, VerificationKey.Rsa(oddOfBits(8193), f4), message, ByteArray(1025))
        }
        assertFailsWith<ChipCryptoException> {
            OpenSslChipCrypto.verify(scheme, VerificationKey.Rsa(oddOfBits(2048), byteArrayOf(1)), message, ByteArray(256))
        }
    }

    @Test
    fun verifyRefusesAPssSaltThatDoesNotFitTheKey() {
        val key = VerificationKey.Rsa(oddOfBits(2048), f4)
        val tooLong = SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 223) // 222 fits
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.verify(tooLong, key, message, ByteArray(256)) }
        val negative = SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, -1)
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.verify(negative, key, message, ByteArray(256)) }
    }

    @Test
    fun verifyRefusesDsaKeysOutsideThePolicy() {
        val two = byteArrayOf(2)
        val scheme = SignatureScheme.Dsa(HashAlgorithm.SHA256)
        assertFailsWith<ChipCryptoException> {
            OpenSslChipCrypto.verify(scheme, VerificationKey.Dsa(oddOfBits(4097), oddOfBits(256), two, two), message, ByteArray(64))
        }
        assertFailsWith<ChipCryptoException> {
            OpenSslChipCrypto.verify(scheme, VerificationKey.Dsa(oddOfBits(2048), byteArrayOf(1), two, two), message, ByteArray(64))
        }
    }

    @Test
    fun ellipticCurveOperationsRefuseAFieldAboveTheLimit() {
        val one = byteArrayOf(1)
        val curve = EcDomainParameters(oddOfBits(641), one, one, EcPoint(one, one), oddOfBits(641), one)
        val point = EcPoint(one, one)
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.ecIsOnCurve(curve, point) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.ecAdd(curve, point, point) }
        assertFailsWith<ChipCryptoException> { OpenSslChipCrypto.ecMultiply(curve, point, one) }
        assertFailsWith<ChipCryptoException> {
            OpenSslChipCrypto.verify(
                SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.PLAIN),
                VerificationKey.Ec(curve, point),
                message,
                ByteArray(160),
            )
        }
    }
}
