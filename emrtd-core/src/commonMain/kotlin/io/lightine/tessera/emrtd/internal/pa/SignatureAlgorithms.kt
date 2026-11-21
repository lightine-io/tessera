package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.crypto.EcdsaSignatureFormat
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.KeyLimits
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.x509.AlgorithmIdentifier
import io.lightine.tessera.emrtd.internal.x509.readAlgorithmIdentifier

/** The signature algorithm, the hash or the key is one the SDK does not read. Carries no data. */
internal class UnsupportedAlgorithmException(
    what: String,
) : IllegalStateException("unsupported $what")

/**
 * Maps the algorithm identifiers of Doc 9303 Part 12, 4.1.6 (RFC 4055, RFC 5758, RFC 3279, and BSI
 * TR-03111 for plain ECDSA) to a [SignatureScheme]. SHA-1 forms are read too: Part 12 permits only
 * SHA-2 for new signatures, but documents signed earlier still carry SHA-1, and verifying them is
 * still a calculation. The hash used is visible in the algorithm OIDs the views report.
 */
internal object SignatureAlgorithms {
    const val SHA1 = "1.3.14.3.2.26"
    const val SHA224 = "2.16.840.1.101.3.4.2.4"
    const val SHA256 = "2.16.840.1.101.3.4.2.1"
    const val SHA384 = "2.16.840.1.101.3.4.2.2"
    const val SHA512 = "2.16.840.1.101.3.4.2.3"

    const val RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
    const val RSASSA_PSS = "1.2.840.113549.1.1.10"
    const val MGF1 = "1.2.840.113549.1.1.8"
    const val DSA_KEY = "1.2.840.10040.4.1"
    const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"

    private val rsaPkcs1 =
        mapOf(
            "1.2.840.113549.1.1.5" to HashAlgorithm.SHA1,
            "1.2.840.113549.1.1.14" to HashAlgorithm.SHA224,
            "1.2.840.113549.1.1.11" to HashAlgorithm.SHA256,
            "1.2.840.113549.1.1.12" to HashAlgorithm.SHA384,
            "1.2.840.113549.1.1.13" to HashAlgorithm.SHA512,
        )

    private val ecdsa =
        mapOf(
            "1.2.840.10045.4.1" to HashAlgorithm.SHA1,
            "1.2.840.10045.4.3.1" to HashAlgorithm.SHA224,
            "1.2.840.10045.4.3.2" to HashAlgorithm.SHA256,
            "1.2.840.10045.4.3.3" to HashAlgorithm.SHA384,
            "1.2.840.10045.4.3.4" to HashAlgorithm.SHA512,
        )

    /** ecdsa-plain-SHA* (BSI TR-03111, 0.4.0.127.0.7.1.1.4.1.n): `r || s`. */
    private val ecdsaPlain =
        mapOf(
            "0.4.0.127.0.7.1.1.4.1.1" to HashAlgorithm.SHA1,
            "0.4.0.127.0.7.1.1.4.1.2" to HashAlgorithm.SHA224,
            "0.4.0.127.0.7.1.1.4.1.3" to HashAlgorithm.SHA256,
            "0.4.0.127.0.7.1.1.4.1.4" to HashAlgorithm.SHA384,
            "0.4.0.127.0.7.1.1.4.1.5" to HashAlgorithm.SHA512,
        )

    private val dsa =
        mapOf(
            "1.2.840.10040.4.3" to HashAlgorithm.SHA1,
            "2.16.840.1.101.3.4.3.1" to HashAlgorithm.SHA224,
            "2.16.840.1.101.3.4.3.2" to HashAlgorithm.SHA256,
            "2.16.840.1.101.3.4.3.3" to HashAlgorithm.SHA384,
            "2.16.840.1.101.3.4.3.4" to HashAlgorithm.SHA512,
        )

    /** The hash a digest AlgorithmIdentifier names; parameters absent or NULL are both accepted (Part 12, 9.1). */
    fun hash(oid: String): HashAlgorithm =
        when (oid) {
            SHA1 -> HashAlgorithm.SHA1
            SHA224 -> HashAlgorithm.SHA224
            SHA256 -> HashAlgorithm.SHA256
            SHA384 -> HashAlgorithm.SHA384
            SHA512 -> HashAlgorithm.SHA512
            else -> throw UnsupportedAlgorithmException("hash")
        }

    /**
     * The scheme for [signatureAlgorithm]. [digestAlgorithm] is used only for the bare key OIDs
     * (`rsaEncryption`, `id-ecPublicKey`, `id-dsa`) that some signers write as the CMS signature
     * algorithm; they say which key, and the SignerInfo's digest algorithm says which hash.
     */
    fun scheme(
        signatureAlgorithm: AlgorithmIdentifier,
        digestAlgorithm: AlgorithmIdentifier? = null,
    ): SignatureScheme {
        val oid = signatureAlgorithm.oid
        rsaPkcs1[oid]?.let { return SignatureScheme.RsaPkcs1V15(it) }
        ecdsa[oid]?.let { return SignatureScheme.Ecdsa(it, EcdsaSignatureFormat.DER) }
        ecdsaPlain[oid]?.let { return SignatureScheme.Ecdsa(it, EcdsaSignatureFormat.PLAIN) }
        dsa[oid]?.let { return SignatureScheme.Dsa(it) }
        val digest = { hash(digestAlgorithm?.oid ?: throw UnsupportedAlgorithmException("signature algorithm")) }
        return when (oid) {
            RSASSA_PSS -> pss(signatureAlgorithm.parameters)
            RSA_ENCRYPTION -> SignatureScheme.RsaPkcs1V15(digest())
            EC_PUBLIC_KEY -> SignatureScheme.Ecdsa(digest(), EcdsaSignatureFormat.DER)
            DSA_KEY -> SignatureScheme.Dsa(digest())
            else -> throw UnsupportedAlgorithmException("signature algorithm")
        }
    }

    /**
     * RSASSA-PSS-params (RFC 4055, 3.1): hash [0] (default SHA-1), mask generation [1] (MGF1 with a
     * hash, default SHA-1), salt length [2] (default 20), trailer field [3] (only 1 is defined).
     */
    private fun pss(parameters: ByteArray?): SignatureScheme =
        try {
            readPss(parameters)
        } catch (e: Asn1Exception) {
            // Parameters that do not parse (a salt length too long for an Int included) name no
            // scheme the SDK can run.
            throw UnsupportedAlgorithmException("PSS parameters")
        }

    private fun readPss(parameters: ByteArray?): SignatureScheme {
        var hash = HashAlgorithm.SHA1
        var mgfHash = HashAlgorithm.SHA1
        var salt = 20
        if (parameters != null && !(parameters.size == 2 && parameters[0].toInt() == 0x05)) {
            val top = DerReader(parameters)
            val seq = top.readSequence()
            top.finish()
            seq.readOptional(DerTag.context(0, constructed = true))?.contents()?.let { r ->
                hash = hash(r.readAlgorithmIdentifier().oid)
                r.finish()
            }
            seq.readOptional(DerTag.context(1, constructed = true))?.contents()?.let { r ->
                val mgf = r.readAlgorithmIdentifier()
                r.finish()
                if (mgf.oid != MGF1 || mgf.parameters == null) throw UnsupportedAlgorithmException("mask generation")
                val inner = DerReader(mgf.parameters)
                mgfHash = hash(inner.readAlgorithmIdentifier().oid)
                inner.finish()
            }
            seq.readOptional(DerTag.context(2, constructed = true))?.contents()?.let { r ->
                salt = r.readInt()
                r.finish()
            }
            seq.readOptional(DerTag.context(3, constructed = true))?.contents()?.let { r ->
                if (r.readInt() != 1) throw UnsupportedAlgorithmException("trailer field")
                r.finish()
            }
            seq.finish()
        }
        // The salt length is untrusted: refuse what no key the SDK reads could hold (RFC 8017, 9.1.1).
        // The exact bound depends on the key: SignatureChecks.verifySignature checks it.
        if (salt < 0 || salt > KeyLimits.MAX_RSA_MODULUS_BITS / 8) throw UnsupportedAlgorithmException("salt length")
        return SignatureScheme.RsaPss(hash, mgfHash, salt)
    }
}
