package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.KeyCheck
import io.lightine.tessera.emrtd.internal.crypto.KeyLimits
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import io.lightine.tessera.emrtd.internal.x509.X509Certificate

/** A public key that could not be read (bad encoding, a point off the curve). Carries no data. */
internal class UnreadableKeyException : IllegalStateException("unreadable public key")

/**
 * Reads a certificate's SubjectPublicKeyInfo into a [VerificationKey]: RSA (RFC 8017 RSAPublicKey,
 * under `rsaEncryption` or `id-RSASSA-PSS`), DSA (RFC 3279 Dss-Parms and INTEGER y), and EC with
 * explicit ECParameters (Part 12, 4.1.6.3) or, read as well, a named prime curve the SDK's
 * standardized table holds.
 *
 * Parameters a certificate leaves out (DSA or EC parameters absent or NULL) come from its issuer's
 * key, as Part 12, App D.1.1.3 c) says: pass the issuer's key as [inherited].
 */
internal object PublicKeys {
    private const val PRIME_FIELD = "1.2.840.10045.1.1"

    /** Named prime curves (RFC 5480, RFC 5639) to their ids in Doc 9303 Part 11, Table 12. */
    private val namedCurves =
        mapOf(
            "1.2.840.10045.3.1.1" to 8,
            "1.3.36.3.3.2.8.1.1.3" to 9,
            "1.3.132.0.33" to 10,
            "1.3.36.3.3.2.8.1.1.5" to 11,
            "1.2.840.10045.3.1.7" to 12,
            "1.3.36.3.3.2.8.1.1.7" to 13,
            "1.3.36.3.3.2.8.1.1.9" to 14,
            "1.3.132.0.34" to 15,
            "1.3.36.3.3.2.8.1.1.11" to 16,
            "1.3.36.3.3.2.8.1.1.13" to 17,
            "1.3.132.0.35" to 18,
        )

    /**
     * The key of [certificate]. Throws [UnsupportedAlgorithmException] for an algorithm the SDK does
     * not read, and [UnreadableKeyException] for one it reads but whose bytes are malformed, or
     * whose parameters are absent with nothing to inherit them from.
     */
    fun of(
        certificate: X509Certificate,
        crypto: ChipCrypto,
        inherited: VerificationKey? = null,
    ): VerificationKey =
        try {
            read(certificate, crypto, inherited)
        } catch (e: Asn1Exception) {
            throw UnreadableKeyException()
        } catch (e: ChipCryptoException) {
            throw UnreadableKeyException()
        }

    private fun read(
        certificate: X509Certificate,
        crypto: ChipCrypto,
        inherited: VerificationKey?,
    ): VerificationKey {
        val algorithm = certificate.publicKeyAlgorithm
        val bits = certificate.publicKey
        if (bits.unusedBits != 0) throw UnreadableKeyException()
        val params = algorithm.parameters?.takeUnless { it.size == 2 && it[0].toInt() == 0x05 }
        return when (algorithm.oid) {
            SignatureAlgorithms.RSA_ENCRYPTION, SignatureAlgorithms.RSASSA_PSS -> {
                val top = DerReader(bits.bytes)
                val seq = top.readSequence()
                top.finish()
                val n = seq.readUnsignedInteger()
                val e = seq.readUnsignedInteger()
                seq.finish()
                checked(KeyLimits.rsa(n, e))
                VerificationKey.Rsa(n, e)
            }

            SignatureAlgorithms.DSA_KEY -> {
                val y = DerReader(bits.bytes).let { r -> r.readUnsignedInteger().also { r.finish() } }
                if (params == null) {
                    val parent = inherited as? VerificationKey.Dsa ?: throw UnreadableKeyException()
                    checked(KeyLimits.dsa(parent.p, parent.q, parent.g, y))
                    VerificationKey.Dsa(parent.p, parent.q, parent.g, y)
                } else {
                    val top = DerReader(params)
                    val seq = top.readSequence()
                    top.finish()
                    val p = seq.readUnsignedInteger()
                    val q = seq.readUnsignedInteger()
                    val g = seq.readUnsignedInteger()
                    seq.finish()
                    checked(KeyLimits.dsa(p, q, g, y))
                    VerificationKey.Dsa(p, q, g, y)
                }
            }

            SignatureAlgorithms.EC_PUBLIC_KEY -> {
                val curve =
                    when {
                        params == null -> (inherited as? VerificationKey.Ec)?.parameters ?: throw UnreadableKeyException()
                        params[0].toInt() == 0x06 -> namedCurve(params)
                        else -> explicitCurve(params, crypto)
                    }
                VerificationKey.Ec(curve, EcPointCodec.decode(crypto, curve, bits.bytes))
            }

            else -> {
                throw UnsupportedAlgorithmException("public key algorithm")
            }
        }
    }

    /**
     * Applies the shared size policy ([KeyLimits]): a key larger than the SDK computes with is an
     * unsupported algorithm, a malformed one (even modulus, exponent 1, order below 2) unreadable.
     */
    private fun checked(check: KeyCheck) {
        when (check) {
            KeyCheck.OK -> Unit
            KeyCheck.TOO_LARGE -> throw UnsupportedAlgorithmException("key size")
            KeyCheck.INVALID -> throw UnreadableKeyException()
        }
    }

    private fun namedCurve(params: ByteArray): EcDomainParameters {
        val r = DerReader(params)
        val oid = r.readOid()
        r.finish()
        val id = namedCurves[oid] ?: throw UnsupportedAlgorithmException("named curve")
        return StandardDomainParameterValues.forId(id) as? EcDomainParameters ?: throw UnsupportedAlgorithmException("named curve")
    }

    /** ECParameters (X9.62 / RFC 3279): version 1, a prime field, the curve a and b (seed ignored), the base point, order and cofactor. */
    private fun explicitCurve(
        params: ByteArray,
        crypto: ChipCrypto,
    ): EcDomainParameters {
        val top = DerReader(params)
        val ec = top.readSequence()
        top.finish()
        if (ec.readInt() != 1) throw UnsupportedAlgorithmException("EC parameters version")
        val field = ec.readSequence()
        if (field.readOid() != PRIME_FIELD) throw UnsupportedAlgorithmException("EC field")
        val p = field.readUnsignedInteger()
        field.finish()
        val curve = ec.readSequence()
        val a = curve.readOctetString()
        val b = curve.readOctetString()
        curve.readOptional(DerTag.universal(DerTag.BIT_STRING)) // seed: not used
        curve.finish()
        val base = ec.readOctetString()
        val n = ec.readUnsignedInteger()
        val h = if (ec.peekTag() == DerTag.universal(DerTag.INTEGER)) ec.readUnsignedInteger() else byteArrayOf(1)
        ec.finish()
        checked(KeyLimits.ecCurve(p, a, b, n, h))
        val length = CryptoBytes.stripLeadingZeros(p).size
        if (base.size != 1 + 2 * length || base[0].toInt() != 0x04) throw UnreadableKeyException()
        val generator = EcPoint(base.copyOfRange(1, 1 + length), base.copyOfRange(1 + length, base.size))
        val params0 = EcDomainParameters(p, a, b, generator, n, h)
        if (!crypto.ecIsOnCurve(params0, generator)) throw UnreadableKeyException()
        return params0
    }
}
