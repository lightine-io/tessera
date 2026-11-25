package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.x509.X509Certificate
import io.lightine.tessera.emrtd.internal.x509.X509Exception
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * A root certificate the app supplies: a country signing certificate (CSCA) the app chose to use
 * for checking chip signatures.
 *
 * The SDK never bundles or downloads root certificates; which roots to use is the app's choice. The
 * SDK only checks, as facts, whether a chip's signer certificate is signed by one of the roots the
 * app passed in. A trust anchor here is just a certificate that was read: nothing about it has been
 * checked, and the SDK never says a root is trustworthy.
 *
 * Make one from a DER certificate with [fromDer], or read many from a master list with
 * [MasterListReader].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class TrustAnchor internal constructor(
    internal val certificate: X509Certificate,
) {
    /** The subject's `countryName` (two letters, as the certificate writes it), or `null`. */
    public val subjectCountry: String? get() = certificate.subject.countryName

    /** The subject's `commonName`, or `null`. */
    public val subjectCommonName: String? get() = certificate.subject.commonName

    /** The serial number as lowercase hex, as encoded. */
    public val serialNumberHex: String get() = X509Parser.hex(certificate.serialNumber)

    /** Start of validity as `YYYY-MM-DDTHH:MM:SSZ`. */
    public val notBefore: String get() = certificate.notBefore.iso()

    /** End of validity as `YYYY-MM-DDTHH:MM:SSZ`. */
    public val notAfter: String get() = certificate.notAfter.iso()

    /** The subject key identifier, or `null` when the certificate has none. Signer certificates name their root by it. */
    public val subjectKeyIdentifier: ByteArray? get() = certificate.decoded.subjectKeyIdentifier?.copyOf()

    /** The OID of the public key algorithm. */
    public val publicKeyAlgorithmOid: String get() = certificate.publicKeyAlgorithm.oid

    /** The certificate as DER (a copy). */
    public fun encodedBytes(): ByteArray = certificate.encoded.copyOf()

    override fun toString(): String = "TrustAnchor(country=$subjectCountry, notAfter=$notAfter)"

    public companion object {
        /**
         * Reads one X.509 certificate in DER.
         *
         * @return [TrustAnchorParse.Parsed] with the anchor, or [TrustAnchorParse.Unreadable] with
         * the reason and where in the bytes it was found, as an offset counted from the first byte
         * of the DER passed here. Never throws for bad data.
         */
        public fun fromDer(der: ByteArray): TrustAnchorParse =
            try {
                TrustAnchorParse.Parsed(TrustAnchor(X509Parser.parse(der.copyOf())))
            } catch (e: Asn1Exception) {
                TrustAnchorParse.Unreadable(TrustAnchorProblem.MALFORMED_ENCODING, e.offset)
            } catch (e: X509Exception) {
                TrustAnchorParse.Unreadable(TrustAnchorProblem.INVALID_CERTIFICATE_FIELD, e.offset)
            }
    }
}

/**
 * The outcome of reading a root certificate.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class TrustAnchorParse {
    /** The certificate was read. */
    public class Parsed internal constructor(
        public val anchor: TrustAnchor,
    ) : TrustAnchorParse()

    /** The bytes are not a certificate the SDK can read. */
    public class Unreadable internal constructor(
        public val problem: TrustAnchorProblem,
        /** The byte offset, counted from the first byte of the DER that was passed to `fromDer`, where the problem was found. */
        public val offset: Int,
    ) : TrustAnchorParse()
}

/**
 * Why a root certificate could not be read.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class TrustAnchorProblem {
    /** The bytes are not well-formed DER, or not shaped as a certificate. */
    MALFORMED_ENCODING,

    /** A field has a value a certificate cannot have: a time, the version, or a known extension's content. */
    INVALID_CERTIFICATE_FIELD,
}
