@file:OptIn(ExperimentalForeignApi::class)

package io.lightine.tessera.emrtd.internal.crypto

import io.lightine.tessera.emrtd.internal.openssl.BIGNUM
import io.lightine.tessera.emrtd.internal.openssl.BN_FLG_CONSTTIME
import io.lightine.tessera.emrtd.internal.openssl.BN_bin2bn
import io.lightine.tessera.emrtd.internal.openssl.BN_cmp
import io.lightine.tessera.emrtd.internal.openssl.BN_is_odd
import io.lightine.tessera.emrtd.internal.openssl.BN_is_zero
import io.lightine.tessera.emrtd.internal.openssl.BN_mod_add
import io.lightine.tessera.emrtd.internal.openssl.BN_mod_exp
import io.lightine.tessera.emrtd.internal.openssl.BN_mod_inverse
import io.lightine.tessera.emrtd.internal.openssl.BN_mod_mul
import io.lightine.tessera.emrtd.internal.openssl.BN_mod_sub
import io.lightine.tessera.emrtd.internal.openssl.BN_set_flags
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_free
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_new
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_set0
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_add
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_is_on_curve
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_mul
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_set_affine_coordinates
import io.lightine.tessera.emrtd.internal.openssl.ERR_clear_error
import io.lightine.tessera.emrtd.internal.openssl.EVP_CIPHER
import io.lightine.tessera.emrtd.internal.openssl.EVP_CIPHER_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_CIPHER_CTX_new
import io.lightine.tessera.emrtd.internal.openssl.EVP_CIPHER_CTX_set_padding
import io.lightine.tessera.emrtd.internal.openssl.EVP_CipherFinal_ex
import io.lightine.tessera.emrtd.internal.openssl.EVP_CipherInit_ex2
import io.lightine.tessera.emrtd.internal.openssl.EVP_CipherUpdate
import io.lightine.tessera.emrtd.internal.openssl.EVP_Digest
import io.lightine.tessera.emrtd.internal.openssl.EVP_DigestVerify
import io.lightine.tessera.emrtd.internal.openssl.EVP_DigestVerifyInit
import io.lightine.tessera.emrtd.internal.openssl.EVP_MD_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_MD_CTX_new
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_mgf1_md
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_padding
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_pss_saltlen
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_PUBLIC_KEY
import io.lightine.tessera.emrtd.internal.openssl.EVP_Q_mac
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_128_cbc
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_128_ecb
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_192_cbc
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_192_ecb
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_256_cbc
import io.lightine.tessera.emrtd.internal.openssl.EVP_aes_256_ecb
import io.lightine.tessera.emrtd.internal.openssl.EVP_des_ede3_cbc
import io.lightine.tessera.emrtd.internal.openssl.EVP_des_ede3_ecb
import io.lightine.tessera.emrtd.internal.openssl.OSSL_MAC_NAME_CMAC
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_G
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_P
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_Q
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_PUB_KEY
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_RSA_E
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_RSA_N
import io.lightine.tessera.emrtd.internal.openssl.RAND_bytes
import io.lightine.tessera.emrtd.internal.openssl.RSA_PKCS1_PSS_PADDING
import io.lightine.tessera.emrtd.internal.openssl.SN_aes_128_cbc
import io.lightine.tessera.emrtd.internal.openssl.SN_aes_192_cbc
import io.lightine.tessera.emrtd.internal.openssl.SN_aes_256_cbc
import io.lightine.tessera.emrtd.internal.openssl.i2d_ECDSA_SIG
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/**
 * [ChipCrypto] on OpenSSL 3 libcrypto for iOS (ADR-031): static, built by
 * scripts/build-openssl-ios.sh and bound through cinterop (src/nativeInterop/cinterop). It mirrors
 * [BcChipCrypto] call for call, so the same commonTest vector suite checks both backends.
 *
 * Only EVP, BN, EC and the param builder are used, all non-deprecated 3.x API
 * (`OPENSSL_NO_DEPRECATED`); the default provider is built into the static library, and the legacy
 * provider is not built (single DES for the retail MAC is three-key 3DES with K1 = K2 = K3).
 * Nothing leaves this file but bytes and this package's types; OpenSSL's error text never does.
 *
 * Side channels: secret DH exponents take OpenSSL's constant-time modular exponentiation
 * ([BN_FLG_CONSTTIME]; DH moduli are odd primes), and a scalar times one point takes OpenSSL's
 * constant-time Montgomery ladder. AES runs on the CPU's AES instructions. DES (BAC's 3DES) is
 * table-based in OpenSSL, as in BouncyCastle.
 */
internal object OpenSslChipCrypto : ChipCrypto {
    override fun digest(
        algorithm: HashAlgorithm,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArray(algorithm.outputLength)
        memScoped {
            val len = alloc<UIntVar>()
            data.ptr { d ->
                out.ptr { o ->
                    osslOk(EVP_Digest(d, data.size.convert(), o, len.ptr, osslMd(algorithm), null), "digest failed")
                }
            }
        }
        return out
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
        if (paddedData.isEmpty() || paddedData.size % 8 != 0) {
            throw ChipCryptoException("retail MAC data is not a whole number of blocks")
        }
        // ISO/IEC 9797-1 MAC algorithm 3: single-DES CBC under K1, then decrypt under K2 and
        // encrypt under K1 on the last block. Single DES is 3DES with three equal keys.
        val k1 = key.copyOfRange(0, 8).let { it + it + it }
        val k2 = key.copyOfRange(8, 16).let { it + it + it }
        val cbc = crypt(true, BlockCipher.DESEDE, CipherMode.CBC, k1, ByteArray(8), paddedData)
        val h = cbc.copyOfRange(cbc.size - 8, cbc.size)
        val mid = crypt(false, BlockCipher.DESEDE, CipherMode.ECB, k2, null, h)
        val mac = crypt(true, BlockCipher.DESEDE, CipherMode.ECB, k1, null, mid)
        CryptoBytes.wipe(k1, k2, cbc, h, mid)
        return mac
    }

    override fun cmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val cipherName =
            when (key.size) {
                16 -> SN_aes_128_cbc
                24 -> SN_aes_192_cbc
                32 -> SN_aes_256_cbc
                else -> throw ChipCryptoException("AES key has the wrong length")
            }
        val out = ByteArray(16)
        memScoped {
            val len = alloc<ULongVar>()
            key.ptr { k ->
                data.ptr { d ->
                    out.ptr { o ->
                        val result =
                            EVP_Q_mac(
                                null,
                                OSSL_MAC_NAME_CMAC,
                                null,
                                cipherName,
                                null,
                                k,
                                key.size.convert(),
                                d,
                                data.size.convert(),
                                o,
                                16u,
                                len.ptr,
                            )
                        if (result == null) osslFail("CMAC failed")
                    }
                }
            }
            if (len.value != 16uL) osslFail("CMAC has the wrong length")
        }
        return out
    }

    override fun randomBytes(length: Int): ByteArray {
        if (length < 0) throw ChipCryptoException("negative length")
        val out = ByteArray(length)
        if (length > 0) out.ptr { o -> osslOk(RAND_bytes(o, length), "no random bytes") }
        return out
    }

    override fun modPow(
        base: ByteArray,
        exponent: ByteArray,
        modulus: ByteArray,
    ): ByteArray {
        if (KeyLimits.bitLength(exponent) > KeyLimits.MAX_RSA_MODULUS_BITS) throw ChipCryptoException("exponent too large")
        return modOp(modulus) { m, r ->
            val e = bn(exponent)
            // The exponent may be secret (a DH private key): flag it so BN_mod_exp takes the
            // constant-time Montgomery path, which OpenSSL offers for odd moduli (DH primes are odd).
            if (BN_is_odd(m) == 1) BN_set_flags(e, BN_FLG_CONSTTIME)
            osslOk(BN_mod_exp(r, bn(base), e, m, ctx), "modPow failed")
        }
    }

    override fun modMultiply(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m, r -> osslOk(BN_mod_mul(r, bn(a), bn(b), m, ctx), "modMultiply failed") }

    override fun modAdd(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m, r -> osslOk(BN_mod_add(r, bn(a), bn(b), m, ctx), "modAdd failed") }

    override fun modSubtract(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray = modOp(modulus) { m, r -> osslOk(BN_mod_sub(r, bn(a), bn(b), m, ctx), "modSubtract failed") }

    override fun modInverse(
        a: ByteArray,
        modulus: ByteArray,
    ): ByteArray =
        modOp(modulus) { m, r ->
            if (BN_mod_inverse(r, bn(a), m, ctx) == null) osslFail("no inverse")
        }

    override fun ecIsOnCurve(
        curve: EcDomainParameters,
        point: EcPoint,
    ): Boolean =
        ossl {
            if (CryptoBytes.compareUnsigned(point.x, curve.p) >= 0) return@ossl false
            if (CryptoBytes.compareUnsigned(point.y, curve.p) >= 0) return@ossl false
            val g = group(curve, withGenerator = false)
            val pt = newPoint(g)
            // OpenSSL 3 refuses to set coordinates that are not on the curve.
            if (EC_POINT_set_affine_coordinates(g, pt, bn(point.x), bn(point.y), ctx) != 1) return@ossl false
            EC_POINT_is_on_curve(g, pt, ctx) == 1
        }

    override fun ecAdd(
        curve: EcDomainParameters,
        p: EcPoint,
        q: EcPoint,
    ): EcPoint? =
        ossl {
            val g = group(curve)
            val r = newPoint(g)
            osslOk(EC_POINT_add(g, r, point(g, curve, p), point(g, curve, q), ctx), "point addition failed")
            fromPoint(g, curve, r)
        }

    override fun ecMultiply(
        curve: EcDomainParameters,
        point: EcPoint,
        scalar: ByteArray,
    ): EcPoint? =
        ossl {
            // The group carries its order and cofactor (group() sets the generator), which
            // OpenSSL's ladder needs.
            val g = group(curve)
            val r = newPoint(g)
            // One point, no generator term: OpenSSL takes its constant-time Montgomery ladder, and
            // reduces a scalar at or above the group order first (n * point is infinity).
            osslOk(EC_POINT_mul(g, r, null, point(g, curve, point), bn(scalar), ctx), "point multiplication failed")
            fromPoint(g, curve, r)
        }

    override fun rsaPublic(
        modulus: ByteArray,
        exponent: ByteArray,
        input: ByteArray,
    ): ByteArray {
        if (KeyLimits.rsa(modulus, exponent) != KeyCheck.OK) throw ChipCryptoException("RSA key outside the size policy")
        return modOp(modulus) { m, r ->
            val x = bn(input)
            if (BN_cmp(x, m) >= 0) throw ChipCryptoException("RSA input is not below the modulus")
            // Public values only: no constant-time flag needed.
            osslOk(BN_mod_exp(r, x, bn(exponent), m, ctx), "RSA operation failed")
        }
    }

    override fun verify(
        scheme: SignatureScheme,
        key: VerificationKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        ossl {
            val pkey = publicKey(scheme, key)
            val sig =
                if (scheme is SignatureScheme.Ecdsa && scheme.format == EcdsaSignatureFormat.PLAIN) {
                    plainToDer((key as VerificationKey.Ec).parameters, signature) ?: return@ossl false
                } else {
                    signature
                }
            val mctx = EVP_MD_CTX_new() ?: osslFail("no digest context")
            try {
                memScoped {
                    val pctxVar = alloc<CPointerVar<EVP_PKEY_CTX>>()
                    osslOk(
                        EVP_DigestVerifyInit(mctx, pctxVar.ptr, osslMd(scheme.hash), null, pkey),
                        "key does not fit the signature scheme",
                    )
                    if (scheme is SignatureScheme.RsaPss) {
                        if (scheme.saltLength < 0) throw ChipCryptoException("negative PSS salt length")
                        val pctx = pctxVar.value
                        osslOk(EVP_PKEY_CTX_set_rsa_padding(pctx, RSA_PKCS1_PSS_PADDING), "PSS setup failed")
                        osslOk(EVP_PKEY_CTX_set_rsa_pss_saltlen(pctx, scheme.saltLength), "PSS setup failed")
                        osslOk(EVP_PKEY_CTX_set_rsa_mgf1_md(pctx, osslMd(scheme.mgfHash)), "PSS setup failed")
                    }
                }
                // 1 verifies; 0 or a negative value (malformed signature) does not.
                sig.ptr { s -> message.ptr { m -> EVP_DigestVerify(mctx, s, sig.size.convert(), m, message.size.convert()) == 1 } }
            } finally {
                EVP_MD_CTX_free(mctx)
            }
        }

    // --- ciphers ---

    private fun crypt(
        forEncryption: Boolean,
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray {
        val bs = cipher.blockSize
        val evp = evpCipher(cipher, mode, key.size)
        if (data.size % bs != 0) throw ChipCryptoException("data is not a whole number of blocks")
        when (mode) {
            CipherMode.ECB -> if (iv != null) throw ChipCryptoException("ECB takes no IV")
            CipherMode.CBC -> if (iv == null || iv.size != bs) throw ChipCryptoException("CBC needs an IV of one block")
        }
        if (data.isEmpty()) return ByteArray(0)
        // Two-key 3DES is three-key 3DES with K3 = K1.
        val fullKey = if (cipher == BlockCipher.DESEDE && key.size == 16) key + key.copyOf(8) else key
        val out = ByteArray(data.size + bs)
        val ctx = EVP_CIPHER_CTX_new() ?: osslFail("no cipher context")
        try {
            memScoped {
                val n1 = alloc<IntVar>()
                val n2 = alloc<IntVar>()
                fullKey.ptr { k ->
                    (iv ?: ByteArray(0)).ptr { v ->
                        osslOk(
                            EVP_CipherInit_ex2(
                                ctx,
                                evp,
                                k,
                                if (iv ==
                                    null
                                ) {
                                    null
                                } else {
                                    v
                                },
                                if (forEncryption) 1 else 0,
                                null,
                            ),
                            "cipher setup failed",
                        )
                    }
                }
                osslOk(EVP_CIPHER_CTX_set_padding(ctx, 0), "cipher setup failed")
                data.ptr { d ->
                    out.ptr { o ->
                        osslOk(EVP_CipherUpdate(ctx, o, n1.ptr, d, data.size), "cipher failed")
                        osslOk(EVP_CipherFinal_ex(ctx, o + n1.value, n2.ptr), "cipher failed")
                    }
                }
                if (n1.value + n2.value != data.size) osslFail("cipher output has the wrong length")
            }
        } finally {
            // EVP_CIPHER_CTX_free clears the expanded key schedule.
            EVP_CIPHER_CTX_free(ctx)
            if (fullKey !== key) CryptoBytes.wipe(fullKey)
            ERR_clear_error()
        }
        return out.copyOf(data.size).also { CryptoBytes.wipe(out) }
    }

    private fun evpCipher(
        cipher: BlockCipher,
        mode: CipherMode,
        keySize: Int,
    ): CPointer<EVP_CIPHER>? {
        val cbc = mode == CipherMode.CBC
        return when (cipher) {
            BlockCipher.DESEDE -> {
                when (keySize) {
                    16, 24 -> if (cbc) EVP_des_ede3_cbc() else EVP_des_ede3_ecb()
                    else -> throw ChipCryptoException("DESEDE key has the wrong length")
                }
            }

            BlockCipher.AES -> {
                when (keySize) {
                    16 -> if (cbc) EVP_aes_128_cbc() else EVP_aes_128_ecb()
                    24 -> if (cbc) EVP_aes_192_cbc() else EVP_aes_192_ecb()
                    32 -> if (cbc) EVP_aes_256_cbc() else EVP_aes_256_ecb()
                    else -> throw ChipCryptoException("AES key has the wrong length")
                }
            }
        }
    }

    // --- big numbers, keys and signatures ---

    private fun modOp(
        modulus: ByteArray,
        op: Ossl.(m: CPointer<BIGNUM>, r: CPointer<BIGNUM>) -> Unit,
    ): ByteArray =
        ossl {
            // No caller needs a modulus above the largest key the size policy admits.
            if (KeyLimits.bitLength(modulus) > KeyLimits.MAX_RSA_MODULUS_BITS) throw ChipCryptoException("modulus too large")
            val m = bn(modulus)
            if (BN_is_zero(m) == 1) throw ChipCryptoException("modulus must be positive")
            val r = newBn()
            op(m, r)
            bytes(r, CryptoBytes.stripLeadingZeros(modulus).size)
        }

    private fun Ossl.publicKey(
        scheme: SignatureScheme,
        key: VerificationKey,
    ): CPointer<EVP_PKEY> =
        when (scheme) {
            is SignatureScheme.RsaPkcs1V15, is SignatureScheme.RsaPss -> {
                val k = key as? VerificationKey.Rsa ?: throw mismatch()
                // The shared size policy (KeyLimits): odd modulus of at most 8192 bits, odd exponent above 1.
                if (KeyLimits.rsa(k.modulus, k.publicExponent) != KeyCheck.OK) throw ChipCryptoException("RSA key outside the size policy")
                // RFC 8017, 9.1.1 step 3: the salt must fit the key (as the BouncyCastle backend checks).
                if (scheme is SignatureScheme.RsaPss &&
                    (scheme.saltLength < 0 || scheme.saltLength > KeyLimits.maxPssSaltLength(k.modulus, scheme.hash))
                ) {
                    throw ChipCryptoException("PSS salt length does not fit the key")
                }
                val n = bn(k.modulus)
                if (BN_is_odd(n) != 1) throw ChipCryptoException("RSA modulus is not a positive odd number")
                fromData("RSA", EVP_PKEY_PUBLIC_KEY) { b ->
                    pushBn(b, OSSL_PKEY_PARAM_RSA_N, k.modulus)
                    pushBn(b, OSSL_PKEY_PARAM_RSA_E, k.publicExponent)
                }
            }

            is SignatureScheme.Dsa -> {
                val k = key as? VerificationKey.Dsa ?: throw mismatch()
                if (KeyLimits.dsa(k.p, k.q, k.g, k.y) != KeyCheck.OK) throw ChipCryptoException("DSA key outside the size policy")
                fromData("DSA", EVP_PKEY_PUBLIC_KEY) { b ->
                    pushBn(b, OSSL_PKEY_PARAM_FFC_P, k.p)
                    pushBn(b, OSSL_PKEY_PARAM_FFC_Q, k.q)
                    pushBn(b, OSSL_PKEY_PARAM_FFC_G, k.g)
                    pushBn(b, OSSL_PKEY_PARAM_PUB_KEY, k.y)
                }
            }

            is SignatureScheme.Ecdsa -> {
                val k = key as? VerificationKey.Ec ?: throw mismatch()
                val c = k.parameters
                // Refuse a point off the curve before anything else (as the BouncyCastle backend).
                point(group(c), c, k.point)
                fromData("EC", EVP_PKEY_PUBLIC_KEY) { b ->
                    pushEcDomain(b, c)
                    pushOctets(b, OSSL_PKEY_PARAM_PUB_KEY, EcPointCodec.encode(c, k.point))
                }
            }
        }

    /** TR-03111 plain `r || s` to DER, or null when it is not two values at the order length. */
    private fun plainToDer(
        curve: EcDomainParameters,
        plain: ByteArray,
    ): ByteArray? {
        val len = CryptoBytes.stripLeadingZeros(curve.n).size
        if (plain.size != 2 * len) return null
        val sig = ECDSA_SIG_new() ?: osslFail("no signature")
        try {
            val r = plain.copyOfRange(0, len).let { b -> b.ptr { BN_bin2bn(it, len, null) } } ?: osslFail("no BIGNUM")
            val s = plain.copyOfRange(len, 2 * len).let { b -> b.ptr { BN_bin2bn(it, len, null) } } ?: osslFail("no BIGNUM")
            osslOk(ECDSA_SIG_set0(sig, r, s), "signature build failed") // sig owns r and s now
            val derLen = i2d_ECDSA_SIG(sig, null)
            if (derLen <= 0) return null
            val der = ByteArray(derLen)
            der.ptr { p ->
                memScoped {
                    val pp = alloc<CPointerVar<UByteVar>>()
                    pp.value = p
                    if (i2d_ECDSA_SIG(sig, pp.ptr) != derLen) osslFail("signature encoding failed")
                }
            }
            return der
        } finally {
            ECDSA_SIG_free(sig)
        }
    }

    private fun mismatch() = ChipCryptoException("key type does not fit the signature scheme")
}
