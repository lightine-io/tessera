@file:OptIn(ExperimentalForeignApi::class)

package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EcdsaSignatureFormat
import io.lightine.tessera.emrtd.internal.crypto.OpenSslChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.Ossl
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import io.lightine.tessera.emrtd.internal.crypto.ossl
import io.lightine.tessera.emrtd.internal.crypto.osslFail
import io.lightine.tessera.emrtd.internal.crypto.osslMd
import io.lightine.tessera.emrtd.internal.crypto.osslOk
import io.lightine.tessera.emrtd.internal.crypto.ptr
import io.lightine.tessera.emrtd.internal.openssl.BIGNUM
import io.lightine.tessera.emrtd.internal.openssl.BN_num_bits
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_free
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_get0_r
import io.lightine.tessera.emrtd.internal.openssl.ECDSA_SIG_get0_s
import io.lightine.tessera.emrtd.internal.openssl.EVP_DigestSign
import io.lightine.tessera.emrtd.internal.openssl.EVP_DigestSignInit
import io.lightine.tessera.emrtd.internal.openssl.EVP_MD_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_MD_CTX_new
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_new_from_name
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_keygen_bits
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_mgf1_md
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_padding
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_set_rsa_pss_saltlen
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_KEYPAIR
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_generate
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_get_bn_param
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_keygen_init
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_G
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_P
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_FFC_Q
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_PRIV_KEY
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_PUB_KEY
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_RSA_E
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_RSA_N
import io.lightine.tessera.emrtd.internal.openssl.RSA_PKCS1_PSS_PADDING
import io.lightine.tessera.emrtd.internal.openssl.d2i_ECDSA_SIG
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/**
 * The fake PKI's signer on OpenSSL (test sources only), the iOS twin of the BouncyCastle signer:
 * with it the signing tests run on iOS too. Keys are made fresh in each test run and never written
 * to disk: DSA and EC private keys are kept as their parameters in bytes, an RSA key as its
 * OpenSSL object, which lives as long as the test process (as the BouncyCastle key objects do).
 */
internal actual fun testSigner(): TestSigner = OpenSslTestSigner

/** The private half: the key type and the parameters to rebuild the OpenSSL key from, or the RSA key itself. */
private class PrivateKey(
    val type: String,
    val params: List<Pair<String, ByteArray>>,
    val ecCurve: EcDomainParameters? = null,
    val rsa: CPointer<EVP_PKEY>? = null,
)

private object OpenSslTestSigner : TestSigner {
    private val crypto = OpenSslChipCrypto

    /** A random value in [1, n). */
    private fun below(n: ByteArray): ByteArray {
        val len = CryptoBytes.stripLeadingZeros(n).size
        while (true) {
            val x = crypto.randomBytes(len)
            if (CryptoBytes.compareUnsigned(x, n) < 0 && x.any { it != 0.toByte() }) return x
        }
    }

    override fun generate(spec: TestKeySpec): TestKeyPair =
        when (spec) {
            is TestKeySpec.Rsa -> {
                generateRsa(spec.bits)
            }

            is TestKeySpec.Dsa -> {
                val g = spec.group
                val q = requireNotNull(g.q) { "DSA needs the subgroup order" }
                val x = below(q)
                val y = crypto.modPow(g.g, x, g.p)
                TestKeyPair(
                    VerificationKey.Dsa(g.p, q, g.g, y),
                    PrivateKey(
                        "DSA",
                        listOf(
                            OSSL_PKEY_PARAM_FFC_P to g.p,
                            OSSL_PKEY_PARAM_FFC_Q to q,
                            OSSL_PKEY_PARAM_FFC_G to g.g,
                            OSSL_PKEY_PARAM_PUB_KEY to y,
                            OSSL_PKEY_PARAM_PRIV_KEY to x,
                        ),
                    ),
                )
            }

            is TestKeySpec.Ec -> {
                val c = spec.curve
                val d = below(c.n)
                val q = requireNotNull(crypto.ecMultiply(c, c.generator, d))
                TestKeyPair(VerificationKey.Ec(c, q), PrivateKey("EC", listOf(OSSL_PKEY_PARAM_PRIV_KEY to d), ecCurve = c))
            }
        }

    private fun generateRsa(bits: Int): TestKeyPair =
        ossl {
            val pctx = EVP_PKEY_CTX_new_from_name(null, "RSA", null) ?: osslFail("no key context")
            try {
                osslOk(EVP_PKEY_keygen_init(pctx), "keygen failed")
                osslOk(EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, bits), "keygen failed")
                // Not owned by the scope: the key lives for the test run.
                val pkey =
                    memScoped {
                        val out = alloc<CPointerVar<EVP_PKEY>>()
                        osslOk(EVP_PKEY_generate(pctx, out.ptr), "keygen failed")
                        out.value ?: osslFail("keygen failed")
                    }
                val n = bnParam(pkey, OSSL_PKEY_PARAM_RSA_N)
                val e = bnParam(pkey, OSSL_PKEY_PARAM_RSA_E)
                TestKeyPair(VerificationKey.Rsa(n, e), PrivateKey("RSA", emptyList(), rsa = pkey))
            } finally {
                EVP_PKEY_CTX_free(pctx)
            }
        }

    private fun Ossl.bnParam(
        pkey: CPointer<EVP_PKEY>,
        name: String,
    ): ByteArray =
        memScoped {
            val out = alloc<CPointerVar<BIGNUM>>()
            osslOk(EVP_PKEY_get_bn_param(pkey, name, out.ptr), "no key parameter")
            val bn = ownBn(out.value ?: osslFail("no key parameter"))
            val len = (BN_num_bits(bn) + 7) / 8
            bytes(bn, len)
        }

    override fun sign(
        key: TestKeyPair,
        scheme: SignatureScheme,
        message: ByteArray,
    ): ByteArray =
        ossl {
            val priv = key.privateKey as PrivateKey
            val pkey: CPointer<EVP_PKEY> =
                when (priv.type) {
                    "RSA" -> {
                        requireNotNull(priv.rsa)
                    }

                    "EC" -> {
                        val c = requireNotNull(priv.ecCurve)
                        val pub = (key.publicKey as VerificationKey.Ec).point
                        fromData("EC", EVP_PKEY_KEYPAIR) { b ->
                            pushEcDomain(b, c)
                            pushOctets(b, OSSL_PKEY_PARAM_PUB_KEY, EcPointCodec.encode(c, pub))
                            priv.params.forEach { (name, value) -> pushBn(b, name, value) }
                        }
                    }

                    else -> {
                        fromData(priv.type, EVP_PKEY_KEYPAIR) { b -> priv.params.forEach { (name, value) -> pushBn(b, name, value) } }
                    }
                }
            val mctx = EVP_MD_CTX_new() ?: osslFail("no digest context")
            val der =
                try {
                    memScoped {
                        val pctxVar = alloc<CPointerVar<EVP_PKEY_CTX>>()
                        osslOk(EVP_DigestSignInit(mctx, pctxVar.ptr, osslMd(scheme.hash), null, pkey), "sign setup failed")
                        if (scheme is SignatureScheme.RsaPss) {
                            val pctx = pctxVar.value
                            osslOk(EVP_PKEY_CTX_set_rsa_padding(pctx, RSA_PKCS1_PSS_PADDING), "PSS setup failed")
                            osslOk(EVP_PKEY_CTX_set_rsa_pss_saltlen(pctx, scheme.saltLength), "PSS setup failed")
                            osslOk(EVP_PKEY_CTX_set_rsa_mgf1_md(pctx, osslMd(scheme.mgfHash)), "PSS setup failed")
                        }
                        val len = alloc<ULongVar>()
                        message.ptr { m -> osslOk(EVP_DigestSign(mctx, null, len.ptr, m, message.size.convert()), "sign failed") }
                        val sig = ByteArray(len.value.toInt())
                        sig.ptr { s ->
                            message.ptr { m -> osslOk(EVP_DigestSign(mctx, s, len.ptr, m, message.size.convert()), "sign failed") }
                        }
                        sig.copyOf(len.value.toInt())
                    }
                } finally {
                    EVP_MD_CTX_free(mctx)
                }
            if (scheme is SignatureScheme.Ecdsa && scheme.format == EcdsaSignatureFormat.PLAIN) {
                derToPlain((key.publicKey as VerificationKey.Ec).parameters.n, der)
            } else {
                der
            }
        }

    /** DER `SEQUENCE { r, s }` to TR-03111 plain `r || s` at the order length. */
    private fun Ossl.derToPlain(
        order: ByteArray,
        der: ByteArray,
    ): ByteArray {
        val len = CryptoBytes.stripLeadingZeros(order).size
        val sig =
            der.ptr { p ->
                memScoped {
                    val pp = alloc<CPointerVar<UByteVar>>()
                    pp.value = p
                    d2i_ECDSA_SIG(null, pp.ptr, der.size.convert()) ?: osslFail("signature decoding failed")
                }
            }
        try {
            val r = ECDSA_SIG_get0_r(sig) ?: osslFail("no r")
            val s = ECDSA_SIG_get0_s(sig) ?: osslFail("no s")
            return bytes(r, len) + bytes(s, len)
        } finally {
            ECDSA_SIG_free(sig)
        }
    }
}
