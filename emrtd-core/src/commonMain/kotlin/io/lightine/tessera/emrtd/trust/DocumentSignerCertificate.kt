package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.x509.X509Certificate
import io.lightine.tessera.emrtd.internal.x509.X509Exception
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * A document signer certificate the app supplies, for a chip whose EF.SOD carries none (ICAO Doc
 * 9303 Part 10, Appendix D allows issuers to leave it out and publish it elsewhere).
 *
 * The reader uses it only when EF.SOD's own certificates name no signer; the signature fact then
 * records that the certificate came from the app ([io.lightine.tessera.emrtd.ChipSignerOrigin]).
 * Nothing about the certificate is checked when it is read here: the signer facts of the read are
 * the checks.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class DocumentSignerCertificate internal constructor(
    internal val certificate: X509Certificate,
) {
    /** The subject's country, or `null`. */
    public val subjectCountry: String? get() = certificate.subject.countryName

    /** The serial number, as hexadecimal. */
    public val serialNumberHex: String get() = X509Parser.hex(certificate.serialNumber)

    /** A copy of the DER bytes. */
    public fun encodedBytes(): ByteArray = certificate.encoded.copyOf()

    /** The country and serial number. */
    override fun toString(): String = "DocumentSignerCertificate(country=$subjectCountry, serial=$serialNumberHex)"

    /** Reads a document signer certificate. */
    public companion object {
        /**
         * Reads a DER X.509 certificate.
         *
         * @return [DocumentSignerCertificateParse.Parsed], or [DocumentSignerCertificateParse.Unreadable]
         *   with the problem and its byte offset, counted from the start of the DER passed here;
         *   never an exception.
         */
        public fun fromDer(der: ByteArray): DocumentSignerCertificateParse =
            try {
                DocumentSignerCertificateParse.Parsed(DocumentSignerCertificate(X509Parser.parse(der.copyOf())))
            } catch (e: Asn1Exception) {
                DocumentSignerCertificateParse.Unreadable(TrustAnchorProblem.MALFORMED_ENCODING, e.offset)
            } catch (e: X509Exception) {
                DocumentSignerCertificateParse.Unreadable(TrustAnchorProblem.INVALID_CERTIFICATE_FIELD, e.offset)
            }
    }
}

/**
 * The outcome of reading a document signer certificate.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class DocumentSignerCertificateParse {
    /** The certificate was read. */
    public class Parsed internal constructor(
        public val certificate: DocumentSignerCertificate,
    ) : DocumentSignerCertificateParse()

    /** The bytes are not a certificate the SDK can read, for [problem] at byte [offset], counted from the first byte of the DER that was passed to `fromDer`. */
    public class Unreadable internal constructor(
        public val problem: TrustAnchorProblem,
        public val offset: Int,
    ) : DocumentSignerCertificateParse()
}
