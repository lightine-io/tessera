package io.lightine.tessera.emrtd.internal.crypto

import org.bouncycastle.asn1.x9.ECNamedCurveTable
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM-only checks: the Table 12 curves against BouncyCastle's own curve tables (a second,
 * independent copy of RFC 5114 and RFC 5639; BouncyCastle no longer ships the RFC 5114 DH groups,
 * which the RFC 5114 Appendix A agreement vectors check in commonTest instead), and the backend against the JDK's own providers (SunJCE, SunRsaSign,
 * SunEC), which share no code with BouncyCastle.
 */
class BcChipCryptoJvmTest {
    private val c: ChipCrypto = chipCrypto()

    @Test
    fun jvmUsesBouncyCastle() {
        assertSame(BcChipCrypto, c)
    }

    @Test
    fun standardCurvesMatchBouncyCastlesTables() {
        val names =
            mapOf(
                8 to "secp192r1",
                9 to "brainpoolP192r1",
                10 to "secp224r1",
                11 to "brainpoolP224r1",
                12 to "secp256r1",
                13 to "brainpoolP256r1",
                14 to "brainpoolP320r1",
                15 to "secp384r1",
                16 to "brainpoolP384r1",
                17 to "brainpoolP512r1",
                18 to "secp521r1",
            )
        for ((id, name) in names) {
            val ours = ecParams(id)
            val bc = ECNamedCurveTable.getByName(name)
            val g = bc.g.normalize()
            assertEquals(bc.curve.field.characteristic, int(ours.p), "$id p")
            assertEquals(bc.curve.a.toBigInteger(), int(ours.a), "$id a")
            assertEquals(bc.curve.b.toBigInteger(), int(ours.b), "$id b")
            assertEquals(g.affineXCoord.toBigInteger(), int(ours.generator.x), "$id Gx")
            assertEquals(g.affineYCoord.toBigInteger(), int(ours.generator.y), "$id Gy")
            assertEquals(bc.n, int(ours.n), "$id n")
            assertEquals(bc.h, int(ours.cofactor), "$id h")
        }
    }

    @Test
    fun ciphersAndHashesAgreeWithTheJdk() {
        val r = Random(313)
        for (len in listOf(16, 24, 32)) {
            val key = r.nextBytes(len)
            val iv = r.nextBytes(16)
            val data = r.nextBytes(64)
            val jdk =
                Cipher
                    .getInstance(
                        "AES/CBC/NoPadding",
                    ).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            assertEquals(jdk.doFinal(data).toHex(), c.encrypt(BlockCipher.AES, CipherMode.CBC, key, iv, data).toHex())
        }
        val key3 = r.nextBytes(24)
        val iv8 = r.nextBytes(8)
        val data = r.nextBytes(40)
        val jdk =
            Cipher.getInstance("DESede/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key3, "DESede"), IvParameterSpec(iv8))
            }
        assertEquals(jdk.doFinal(data).toHex(), c.encrypt(BlockCipher.DESEDE, CipherMode.CBC, key3, iv8, data).toHex())
        val names =
            mapOf(
                HashAlgorithm.SHA1 to "SHA-1",
                HashAlgorithm.SHA224 to "SHA-224",
                HashAlgorithm.SHA256 to "SHA-256",
                HashAlgorithm.SHA384 to "SHA-384",
                HashAlgorithm.SHA512 to "SHA-512",
            )
        for ((alg, name) in names) {
            assertEquals(
                java.security.MessageDigest
                    .getInstance(name)
                    .digest(data)
                    .toHex(),
                c.digest(alg, data).toHex(),
            )
        }
    }

    @Test
    fun rsaSignaturesFromTheJdkVerify() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val key = VerificationKey.Rsa(pub.modulus.toByteArray(), pub.publicExponent.toByteArray())
        val msg = "differential".encodeToByteArray()
        val hashes =
            mapOf(
                HashAlgorithm.SHA1 to "SHA-1",
                HashAlgorithm.SHA224 to "SHA-224",
                HashAlgorithm.SHA256 to "SHA-256",
                HashAlgorithm.SHA384 to "SHA-384",
                HashAlgorithm.SHA512 to "SHA-512",
            )
        for ((alg, name) in hashes) {
            val pkcs1 =
                Signature.getInstance("${name.replace("-", "")}withRSA").run {
                    initSign(kp.private)
                    update(msg)
                    sign()
                }
            assertTrue(c.verify(SignatureScheme.RsaPkcs1V15(alg), key, msg, pkcs1), "PKCS1 $name")
            val salt = alg.outputLength
            val pss =
                Signature.getInstance("RSASSA-PSS").run {
                    setParameter(PSSParameterSpec(name, "MGF1", MGF1ParameterSpec(name), salt, 1))
                    initSign(kp.private)
                    update(msg)
                    sign()
                }
            assertTrue(c.verify(SignatureScheme.RsaPss(alg, alg, salt), key, msg, pss), "PSS $name")
        }
    }

    @Test
    fun ecdsaAndDsaSignaturesFromTheJdkVerify() {
        val msg = "differential".encodeToByteArray()
        for ((id, curve) in mapOf(12 to "secp256r1", 15 to "secp384r1", 18 to "secp521r1")) {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
            val w = (kp.public as ECPublicKey).w
            val key = VerificationKey.Ec(ecParams(id), EcPoint(w.affineX.toByteArray(), w.affineY.toByteArray()))
            val der =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(kp.private)
                    update(msg)
                    sign()
                }
            val plain =
                Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initSign(kp.private)
                    update(msg)
                    sign()
                }
            assertTrue(c.verify(SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.DER), key, msg, der), curve)
            assertTrue(c.verify(SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.PLAIN), key, msg, plain), curve)
        }
        val kp = KeyPairGenerator.getInstance("DSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as DSAPublicKey
        val key =
            VerificationKey.Dsa(
                pub.params.p.toByteArray(),
                pub.params.q.toByteArray(),
                pub.params.g.toByteArray(),
                pub.y.toByteArray(),
            )
        val sig =
            Signature.getInstance("SHA256withDSA").run {
                initSign(kp.private)
                update(msg)
                sign()
            }
        assertTrue(c.verify(SignatureScheme.Dsa(HashAlgorithm.SHA256), key, msg, sig))
    }

    @Test
    fun ecdhAgreesWithTheJdk() {
        val e = ecParams(12)
        val jdk = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val w = (jdk.public as ECPublicKey).w
        val ours = EllipticCurves.generatePrivateKey(c, e)
        val ourPublic = EllipticCurves.publicKey(c, e, ours)
        val jdkView =
            java.security.KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.ECPublicKeySpec(
                    java.security.spec.ECPoint(int(ourPublic.x), int(ourPublic.y)),
                    (jdk.public as ECPublicKey).params,
                ),
            )
        val jdkSecret =
            KeyAgreement.getInstance("ECDH").run {
                init(jdk.private)
                doPhase(jdkView, true)
                generateSecret()
            }
        val ourSecret = EllipticCurves.sharedSecret(c, e, ours, EcPoint(w.affineX.toByteArray(), w.affineY.toByteArray()))
        assertEquals(jdkSecret.toHex(), ourSecret.toHex())
    }

    private fun int(b: ByteArray) = BigInteger(1, b)
}
