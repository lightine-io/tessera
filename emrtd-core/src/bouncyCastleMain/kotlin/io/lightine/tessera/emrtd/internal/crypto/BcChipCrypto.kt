package io.lightine.tessera.emrtd.internal.crypto

import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.DataLengthException
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.Signer
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESEngine
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.DSAParameters
import org.bouncycastle.crypto.params.DSAPublicKeyParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.DSADigestSigner
import org.bouncycastle.crypto.signers.DSASigner
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.signers.PlainDSAEncoding
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.signers.StandardDSAEncoding
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.BlockCipher as BcBlockCipher

/**
 * [ChipCrypto] on BouncyCastle's lightweight API (`bcprov-jdk18on`; ADR-031 point 2), for JVM and
 * Android. Only the lightweight classes are used, never the JCA provider, so nothing is registered
 * globally and an app's own BouncyCastle setup is untouched. The library is an `implementation`
 * dependency and none of its types leave this file (CI checks the ABI dumps for its name).
 *
 * Big-integer arithmetic (modPow, RSA) is java.math.BigInteger, as in BouncyCastle itself; EC point
 * arithmetic is BouncyCastle's `ECCurve.Fp`, built from the explicit parameters on each call.
 */
internal object BcChipCrypto : ChipCrypto {
    private val random: SecureRandom by lazy { SecureRandom() }

    override fun digest(
        algorithm: HashAlgorithm,
        data: ByteArray,
    ): ByteArray =
        guard {
            val d = newDigest(algorithm)
            d.update(data, 0, data.size)
            ByteArray(d.digestSize).also { d.doFinal(it, 0) }
        }

    override fun encrypt(
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray = crypt(true, cipher, mode, key, iv, data)

    override fun decrypt(
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray = crypt(false, cipher, mode, key, iv, data)

    override fun retailMac(
        key: ByteArray,
        paddedData: ByteArray,
    ): ByteArray {
        if (key.size != 16) throw ChipCryptoException("retail MAC key must be 16 bytes")
        if (paddedData.size % 8 != 0) throw ChipCryptoException("retail MAC data is not a whole number of blocks")
        return guard {
            // No padding argument: the data is already padded (Part 11, section 9.8.3).
            val mac = ISO9797Alg3Mac(DESEngine())
            mac.init(KeyParameter(key))
            mac.update(paddedData, 0, paddedData.size)
            ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        }
    }

    override fun cmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        checkKey(BlockCipher.AES, key)
        return guard {
            val mac = CMac(AESEngine.newInstance())
            mac.init(KeyParameter(key))
            mac.update(data, 0, data.size)
            ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        if (length < 0) throw ChipCryptoException("negative length")
        return ByteArray(length).also { random.nextBytes(it) }
    }

    override fun modPow(
        base: ByteArray,
        exponent: ByteArray,
        modulus: ByteArray,
    ): ByteArray {
        if (KeyLimits.bitLength(exponent) > KeyLimits.MAX_RSA_MODULUS_BITS) throw ChipCryptoException("exponent too large")
        return modOp(modulus) { m -> int(base).modPow(int(exponent), m) }
    }

    override fun modMultiply(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m -> int(a).multiply(int(b)).mod(m) }

    override fun modAdd(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m -> int(a).add(int(b)).mod(m) }

    override fun modSubtract(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m -> int(a).subtract(int(b)).mod(m) }

    override fun modInverse(
        a: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m -> int(a).modInverse(m) }

    override fun ecIsOnCurve(
        curve: EcDomainParameters,
        point: EcPoint,
    ): Boolean {
        val p = int(curve.p)
        val x = int(point.x)
        val y = int(point.y)
        if (x >= p || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs =
            x
                .pow(3)
                .add(int(curve.a).multiply(x))
                .add(int(curve.b))
                .mod(p)
        return lhs == rhs
    }

    override fun ecAdd(
        curve: EcDomainParameters,
        p: EcPoint,
        q: EcPoint,
    ): EcPoint? {
        val c = bcCurve(curve)
        return fromBc(curve, toBc(c, curve, p).add(toBc(c, curve, q)))
    }

    override fun ecMultiply(
        curve: EcDomainParameters,
        point: EcPoint,
        scalar: ByteArray,
    ): EcPoint? {
        val c = bcCurve(curve)
        val k = int(scalar)
        return fromBc(curve, guard { toBc(c, curve, point).multiply(k) })
    }

    override fun rsaPublic(
        modulus: ByteArray,
        exponent: ByteArray,
        input: ByteArray,
    ): ByteArray {
        if (KeyLimits.rsa(modulus, exponent) != KeyCheck.OK) throw ChipCryptoException("RSA key outside the size policy")
        val n = int(modulus)
        val m = int(input)
        if (m >= n) throw ChipCryptoException("RSA input is not below the modulus")
        return fixed(m.modPow(int(exponent), n), modulus)
    }

    override fun verify(
        scheme: SignatureScheme,
        key: VerificationKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val signer = signerFor(scheme, key)
        return try {
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: RuntimeException) {
            // A signature BouncyCastle cannot even decode (too long, bad DER) does not verify.
            false
        }
    }

    private fun signerFor(
        scheme: SignatureScheme,
        key: VerificationKey,
    ): Signer =
        guard {
            when (scheme) {
                is SignatureScheme.RsaPkcs1V15 -> {
                    val k = key as? VerificationKey.Rsa ?: throw mismatch()
                    RSADigestSigner(newDigest(scheme.hash)).also { it.init(false, rsaKey(k)) }
                }

                is SignatureScheme.RsaPss -> {
                    val k = key as? VerificationKey.Rsa ?: throw mismatch()
                    // RFC 8017, 9.1.1 step 3: the salt must fit the key. Checked here as well as in the
                    // caller, so no backend call ever builds a signer from an unbounded length.
                    if (scheme.saltLength < 0 || scheme.saltLength > KeyLimits.maxPssSaltLength(k.modulus, scheme.hash)) {
                        throw ChipCryptoException("PSS salt length does not fit the key")
                    }
                    PSSSigner(
                        RSABlindedEngine(),
                        newDigest(scheme.hash),
                        newDigest(scheme.mgfHash),
                        scheme.saltLength,
                        PSSSigner.TRAILER_IMPLICIT,
                    ).also { it.init(false, rsaKey(k)) }
                }

                is SignatureScheme.Dsa -> {
                    val k = key as? VerificationKey.Dsa ?: throw mismatch()
                    if (KeyLimits.dsa(k.p, k.q, k.g, k.y) != KeyCheck.OK) throw ChipCryptoException("DSA key outside the size policy")
                    val params = DSAParameters(int(k.p), int(k.q), int(k.g))
                    DSADigestSigner(DSASigner(), newDigest(scheme.hash))
                        .also { it.init(false, DSAPublicKeyParameters(int(k.y), params)) }
                }

                is SignatureScheme.Ecdsa -> {
                    val k = key as? VerificationKey.Ec ?: throw mismatch()
                    val c = bcCurve(k.parameters)
                    val domain =
                        ECDomainParameters(
                            c,
                            toBc(c, k.parameters, k.parameters.generator),
                            int(k.parameters.n),
                            int(k.parameters.cofactor),
                        )
                    val encoding =
                        when (scheme.format) {
                            EcdsaSignatureFormat.DER -> StandardDSAEncoding.INSTANCE
                            EcdsaSignatureFormat.PLAIN -> PlainDSAEncoding.INSTANCE
                        }
                    DSADigestSigner(ECDSASigner(), newDigest(scheme.hash), encoding)
                        .also { it.init(false, ECPublicKeyParameters(toBc(c, k.parameters, k.point), domain)) }
                }
            }
        }

    private fun mismatch() = ChipCryptoException("key type does not fit the signature scheme")

    private fun rsaKey(k: VerificationKey.Rsa): RSAKeyParameters {
        // The shared size policy (KeyLimits): odd modulus of at most 8192 bits, odd exponent above 1.
        if (KeyLimits.rsa(k.modulus, k.publicExponent) != KeyCheck.OK) throw ChipCryptoException("RSA key outside the size policy")
        return RSAKeyParameters(false, int(k.modulus), int(k.publicExponent))
    }

    private fun crypt(
        forEncryption: Boolean,
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray {
        checkKey(cipher, key)
        val bs = cipher.blockSize
        if (data.size % bs != 0) throw ChipCryptoException("data is not a whole number of blocks")
        val engine: BcBlockCipher =
            when (cipher) {
                BlockCipher.DESEDE -> DESedeEngine()
                BlockCipher.AES -> AESEngine.newInstance()
            }
        val keyParam = KeyParameter(key)
        val c: BcBlockCipher =
            when (mode) {
                CipherMode.ECB -> {
                    if (iv != null) throw ChipCryptoException("ECB takes no IV")
                    engine.also { it.init(forEncryption, keyParam) }
                }

                CipherMode.CBC -> {
                    if (iv == null || iv.size != bs) throw ChipCryptoException("CBC needs an IV of one block")
                    CBCBlockCipher.newInstance(engine).also { it.init(forEncryption, ParametersWithIV(keyParam, iv)) }
                }
            }
        return guard {
            val out = ByteArray(data.size)
            var off = 0
            while (off < data.size) {
                c.processBlock(data, off, out, off)
                off += bs
            }
            out
        }
    }

    private fun checkKey(
        cipher: BlockCipher,
        key: ByteArray,
    ) {
        val ok =
            when (cipher) {
                BlockCipher.DESEDE -> key.size == 16 || key.size == 24
                BlockCipher.AES -> key.size == 16 || key.size == 24 || key.size == 32
            }
        if (!ok) throw ChipCryptoException("${cipher.name} key has the wrong length")
    }

    private fun newDigest(algorithm: HashAlgorithm): Digest =
        when (algorithm) {
            HashAlgorithm.SHA1 -> SHA1Digest()
            HashAlgorithm.SHA224 -> SHA224Digest()
            HashAlgorithm.SHA256 -> SHA256Digest.newInstance()
            HashAlgorithm.SHA384 -> SHA384Digest()
            HashAlgorithm.SHA512 -> SHA512Digest()
        }

    private fun bcCurve(params: EcDomainParameters): ECCurve =
        guard {
            if (KeyLimits.bitLength(params.p) > KeyLimits.MAX_EC_FIELD_BITS) throw ChipCryptoException("curve field too large")
            val p = int(params.p)
            if (p.signum() <= 0 || !p.testBit(0)) throw ChipCryptoException("curve prime is not a positive odd number")
            ECCurve.Fp(p, int(params.a), int(params.b), int(params.n), int(params.cofactor))
        }

    private fun toBc(
        c: ECCurve,
        params: EcDomainParameters,
        point: EcPoint,
    ): ECPoint {
        if (!ecIsOnCurve(params, point)) throw ChipCryptoException("point is not on the curve")
        return guard { c.createPoint(int(point.x), int(point.y)) }
    }

    private fun fromBc(
        params: EcDomainParameters,
        point: ECPoint,
    ): EcPoint? {
        if (point.isInfinity) return null
        val n = point.normalize()
        val len = params.fieldLength
        return EcPoint(
            CryptoBytes.toFixedLength(n.affineXCoord.toBigInteger().toByteArray(), len),
            CryptoBytes.toFixedLength(n.affineYCoord.toBigInteger().toByteArray(), len),
        )
    }

    private inline fun modOp(
        modulus: ByteArray,
        op: (BigInteger) -> BigInteger,
    ): ByteArray {
        // No caller needs a modulus above the largest key the size policy admits.
        if (KeyLimits.bitLength(modulus) > KeyLimits.MAX_RSA_MODULUS_BITS) throw ChipCryptoException("modulus too large")
        val m = int(modulus)
        if (m.signum() <= 0) throw ChipCryptoException("modulus must be positive")
        return fixed(guard { op(m) }, modulus)
    }

    /** [value] at the byte length of [modulus] (leading zeros of the modulus ignored). */
    private fun fixed(
        value: BigInteger,
        modulus: ByteArray,
    ): ByteArray = CryptoBytes.toFixedLength(value.toByteArray(), CryptoBytes.stripLeadingZeros(modulus).size)

    private fun int(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    /** Turns BouncyCastle's and the JDK's input errors into [ChipCryptoException], with no data in the text. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: ChipCryptoException) {
            throw e
        } catch (e: ArithmeticException) {
            throw ChipCryptoException("arithmetic error", e)
        } catch (e: IllegalArgumentException) {
            throw ChipCryptoException("invalid input", e)
        } catch (e: IllegalStateException) {
            throw ChipCryptoException("invalid state", e)
        } catch (e: DataLengthException) {
            throw ChipCryptoException("wrong data length", e)
        } catch (e: CryptoException) {
            throw ChipCryptoException("cryptographic failure", e)
        }
}
