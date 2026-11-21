package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcdsaSignatureFormat
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.DSAParameters
import org.bouncycastle.crypto.params.DSAPrivateKeyParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.DSADigestSigner
import org.bouncycastle.crypto.signers.DSASigner
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.signers.PlainDSAEncoding
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.signers.StandardDSAEncoding
import org.bouncycastle.math.ec.ECCurve
import java.math.BigInteger
import java.security.SecureRandom

/** The fake PKI's signer on BouncyCastle's lightweight API (test sources only). */
internal actual fun testSigner(): TestSigner? = BcTestSigner

private object BcTestSigner : TestSigner {
    private val random = SecureRandom()

    private fun big(b: ByteArray) = BigInteger(1, b)

    private fun bytes(v: BigInteger) = CryptoBytes.stripLeadingZeros(v.toByteArray())

    private fun below(n: BigInteger): BigInteger {
        while (true) {
            val x = BigInteger(n.bitLength(), random)
            if (x.signum() > 0 && x < n) return x
        }
    }

    override fun generate(spec: TestKeySpec): TestKeyPair =
        when (spec) {
            is TestKeySpec.Rsa -> {
                val gen = RSAKeyPairGenerator()
                gen.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), random, spec.bits, 80))
                val pair = gen.generateKeyPair()
                val pub = pair.public as RSAKeyParameters
                TestKeyPair(VerificationKey.Rsa(bytes(pub.modulus), bytes(pub.exponent)), pair.private)
            }

            is TestKeySpec.Dsa -> {
                val g = spec.group
                val q = big(requireNotNull(g.q) { "DSA needs the subgroup order" })
                val p = big(g.p)
                val x = below(q)
                val y = big(g.g).modPow(x, p)
                TestKeyPair(
                    VerificationKey.Dsa(g.p, g.q, g.g, bytes(y)),
                    DSAPrivateKeyParameters(x, DSAParameters(p, q, big(g.g))),
                )
            }

            is TestKeySpec.Ec -> {
                val c = spec.curve
                val n = big(c.n)
                val h = big(c.cofactor)
                val curve = ECCurve.Fp(big(c.p), big(c.a), big(c.b), n, h)
                val base = curve.createPoint(big(c.generator.x), big(c.generator.y))
                val d = below(n)
                val q = base.multiply(d).normalize()
                val len = c.fieldLength
                TestKeyPair(
                    VerificationKey.Ec(
                        c,
                        EcPoint(
                            CryptoBytes.toFixedLength(bytes(q.affineXCoord.toBigInteger()), len),
                            CryptoBytes.toFixedLength(bytes(q.affineYCoord.toBigInteger()), len),
                        ),
                    ),
                    ECPrivateKeyParameters(d, ECDomainParameters(curve, base, n, h)),
                )
            }
        }

    private fun digest(h: HashAlgorithm): Digest =
        when (h) {
            HashAlgorithm.SHA1 -> SHA1Digest()
            HashAlgorithm.SHA224 -> SHA224Digest()
            HashAlgorithm.SHA256 -> SHA256Digest()
            HashAlgorithm.SHA384 -> SHA384Digest()
            HashAlgorithm.SHA512 -> SHA512Digest()
        }

    override fun sign(
        key: TestKeyPair,
        scheme: SignatureScheme,
        message: ByteArray,
    ): ByteArray {
        val signer =
            when (scheme) {
                is SignatureScheme.RsaPkcs1V15 -> {
                    RSADigestSigner(digest(scheme.hash))
                }

                is SignatureScheme.RsaPss -> {
                    PSSSigner(RSAEngine(), digest(scheme.hash), digest(scheme.mgfHash), scheme.saltLength)
                }

                is SignatureScheme.Dsa -> {
                    DSADigestSigner(DSASigner(), digest(scheme.hash))
                }

                is SignatureScheme.Ecdsa -> {
                    val encoding =
                        if (scheme.format ==
                            EcdsaSignatureFormat.PLAIN
                        ) {
                            PlainDSAEncoding.INSTANCE
                        } else {
                            StandardDSAEncoding.INSTANCE
                        }
                    DSADigestSigner(ECDSASigner(), digest(scheme.hash), encoding)
                }
            }
        signer.init(true, ParametersWithRandom(key.privateKey as org.bouncycastle.crypto.CipherParameters, random))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }
}
