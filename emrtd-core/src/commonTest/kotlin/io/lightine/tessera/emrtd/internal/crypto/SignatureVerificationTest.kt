package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signature verification for every algorithm Doc 9303 Part 12, section 4.1.6 requires: DSA and ECDSA
 * against RFC 6979, RSA PKCS #1 v1.5 and PSS against a synthetic vector made by the JDK, and the raw
 * RSA operation.
 */
class SignatureVerificationTest {
    private val dsaKey =
        with(CryptoVectors.Dsa2048) { VerificationKey.Dsa(hex(p), hex(q), hex(g), hex(y)) }

    private val ecKey =
        with(CryptoVectors.EcdsaP256) { VerificationKey.Ec(ecParams(12), EcPoint(hex(ux), hex(uy))) }

    private val rsaKey = VerificationKey.Rsa(hex(RsaVectors.modulus), hex(RsaVectors.exponent))

    @Test
    fun dsaMatchesRfc6979() =
        withCrypto { c ->
            for (v in CryptoVectors.Dsa2048.signatures) {
                val sig = derSignature(hex(v.r), hex(v.s))
                val msg = v.message.encodeToByteArray()
                assertTrue(c.verify(SignatureScheme.Dsa(v.hash), dsaKey, msg, sig), "${v.hash}")
                assertFalse(c.verify(SignatureScheme.Dsa(v.hash), dsaKey, "other".encodeToByteArray(), sig))
            }
        }

    @Test
    fun ecdsaMatchesRfc6979InDerAndPlainFormat() =
        withCrypto { c ->
            for (v in CryptoVectors.EcdsaP256.signatures) {
                val msg = v.message.encodeToByteArray()
                val der = derSignature(hex(v.r), hex(v.s))
                val plain = CryptoBytes.toFixedLength(hex(v.r), 32) + CryptoBytes.toFixedLength(hex(v.s), 32)
                val derScheme = SignatureScheme.Ecdsa(v.hash, EcdsaSignatureFormat.DER)
                val plainScheme = SignatureScheme.Ecdsa(v.hash, EcdsaSignatureFormat.PLAIN)
                assertTrue(c.verify(derScheme, ecKey, msg, der), "${v.hash} DER")
                assertTrue(c.verify(plainScheme, ecKey, msg, plain), "${v.hash} plain")
                // The wrong format, a changed message, or a changed signature does not verify.
                assertFalse(c.verify(derScheme, ecKey, msg, plain))
                assertFalse(c.verify(plainScheme, ecKey, "other".encodeToByteArray(), plain))
                assertFalse(c.verify(plainScheme, ecKey, msg, plain.copyOf().also { it[5] = (it[5] + 1).toByte() }))
            }
        }

    @Test
    fun rsaPkcs1AndPssVerify() =
        withCrypto { c ->
            val msg = RsaVectors.MESSAGE.encodeToByteArray()
            val pkcs1 = SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA256)
            val pss = SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 32)
            assertTrue(c.verify(pkcs1, rsaKey, msg, hex(RsaVectors.pkcs1Sha256)))
            assertTrue(c.verify(pss, rsaKey, msg, hex(RsaVectors.pssSha256)))
            // Each scheme refuses the other's signature, another hash, and a changed message.
            assertFalse(c.verify(pkcs1, rsaKey, msg, hex(RsaVectors.pssSha256)))
            assertFalse(c.verify(pss, rsaKey, msg, hex(RsaVectors.pkcs1Sha256)))
            assertFalse(c.verify(SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA384), rsaKey, msg, hex(RsaVectors.pkcs1Sha256)))
            assertFalse(
                c.verify(SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 20), rsaKey, msg, hex(RsaVectors.pssSha256)),
            )
            assertFalse(c.verify(pkcs1, rsaKey, "other".encodeToByteArray(), hex(RsaVectors.pkcs1Sha256)))
            // A signature longer than the modulus is malformed: it does not verify, it does not throw.
            assertFalse(c.verify(pkcs1, rsaKey, msg, ByteArray(300) { 0x7F }))
        }

    @Test
    fun rawRsaPublicOperationShowsThePkcs1Encoding() =
        withCrypto { c ->
            // s^e mod n of a PKCS #1 v1.5 signature is 00 01 FF..FF 00 DigestInfo, ending in the hash.
            val em = c.rsaPublic(rsaKey.modulus, rsaKey.publicExponent, hex(RsaVectors.pkcs1Sha256))
            assertEquals(256, em.size)
            assertEquals("0001FFFF", em.copyOf(4).toHex())
            val hash = c.digest(HashAlgorithm.SHA256, RsaVectors.MESSAGE.encodeToByteArray())
            assertEquals(hash.toHex(), em.copyOfRange(em.size - 32, em.size).toHex())
            assertFailsWith<ChipCryptoException> { c.rsaPublic(rsaKey.modulus, rsaKey.publicExponent, ByteArray(257) { -1 }) }
        }

    @Test
    fun aKeyThatDoesNotFitTheSchemeThrows() =
        withCrypto { c ->
            assertFailsWith<ChipCryptoException> {
                c.verify(SignatureScheme.Dsa(HashAlgorithm.SHA256), rsaKey, ByteArray(1), ByteArray(8))
            }
            assertFailsWith<ChipCryptoException> {
                c.verify(SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA256), ecKey, ByteArray(1), ByteArray(8))
            }
            assertFailsWith<ChipCryptoException> {
                c.verify(SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.DER), dsaKey, ByteArray(1), ByteArray(8))
            }
            // An EC key whose point is not on the curve is refused before any verification.
            val bad = VerificationKey.Ec(ecParams(12), EcPoint(ecKey.point.x, ecKey.point.x))
            assertFailsWith<ChipCryptoException> {
                c.verify(SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.DER), bad, ByteArray(1), ByteArray(8))
            }
        }
}
