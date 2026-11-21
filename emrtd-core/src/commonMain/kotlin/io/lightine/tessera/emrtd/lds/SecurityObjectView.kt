package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.cms.CmsException
import io.lightine.tessera.emrtd.internal.cms.CmsFailure
import io.lightine.tessera.emrtd.internal.cms.CmsParser
import io.lightine.tessera.emrtd.internal.cms.CmsSignedData
import io.lightine.tessera.emrtd.internal.cms.LdsSecurityObject
import io.lightine.tessera.emrtd.internal.x509.KeyUsageBit
import io.lightine.tessera.emrtd.internal.x509.X509Certificate
import io.lightine.tessera.emrtd.internal.x509.X509Exception
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * The chip's signed list of checksums (EF.SOD), read but not checked.
 *
 * It holds one hash per data group, the algorithm used, who signed it and the signer's certificate
 * when the chip stores one. The view reports what the file says: nothing here has been verified, not
 * the signature, not the certificate and not the hashes against the data groups. It never says the
 * chip is genuine.
 *
 * Both versions of the object are read: V0 (no LDS version info) and V1 (with it). A file written
 * with BER lengths instead of DER is read too; [wasBer] says so.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class SecurityObjectView internal constructor(
    /** The LDS security object's version as stored: 0 for V0, 1 for V1. */
    public val version: Int,
    /** The CMS SignedData version as stored. */
    public val signedDataVersion: Int,
    /** The OID of the hash algorithm, in dotted form. */
    public val hashAlgorithmOid: String,
    /** The hash algorithm's name (`SHA-1`, `SHA-224`, `SHA-256`, `SHA-384`, `SHA-512`), or `null` for an OID the SDK does not name. */
    public val hashAlgorithmName: String?,
    /** `true` when the hash algorithm identifier carries parameters (an explicit NULL); `false` when they are absent. Both are accepted. */
    public val hashAlgorithmHasParameters: Boolean,
    /** One hash per data group, in the order stored. */
    public val dataGroupHashes: List<DataGroupHash>,
    /** The LDS version (for example `0108`) from V1's version info, or `null` when absent. */
    public val ldsVersion: String?,
    /** The Unicode version from V1's version info, or `null` when absent. */
    public val unicodeVersion: String?,
    /** The signers named in the SignedData; usually one. */
    public val signers: List<SecurityObjectSigner>,
    /** The certificates stored in the file that could be read, in order. */
    public val certificates: List<SignerCertificateView>,
    /** How many stored certificates could not be read. */
    public val unreadableCertificateCount: Int,
    /** `true` when the file was not DER (it used indefinite or longer-than-needed lengths). The content is read the same way. */
    public val wasBer: Boolean,
    internal val encodedContent: ByteArray,
    internal val signedData: CmsSignedData,
) {
    /** The hash stored for data group [number], or `null` when the object has none. The first one if it lists the group twice. */
    public fun hashOf(number: Int): ByteArray? = dataGroupHashes.firstOrNull { it.number == number }?.hash?.copyOf()

    /** The signed content (the encoded LDS security object), as stored: the bytes the signature check hashes. */
    public fun contentBytes(): ByteArray = encodedContent.copyOf()

    override fun toString(): String =
        "SecurityObjectView(version=$version, hashAlgorithm=${hashAlgorithmName ?: hashAlgorithmOid}, dataGroups=${dataGroupHashes.size})"

    public companion object {
        /**
         * Reads EF.SOD from the file's bytes, with or without the file tag `77`.
         *
         * @return [SecurityObjectParse.Parsed] with the view, or [SecurityObjectParse.Unreadable] with the
         * reason and where in the file it was found. Never throws for bad data.
         */
        public fun parse(file: ByteArray): SecurityObjectParse =
            try {
                SecurityObjectParse.Parsed(build(file))
            } catch (e: Asn1Exception) {
                SecurityObjectParse.Unreadable(SecurityObjectProblem.MALFORMED_ENCODING, e.offset)
            } catch (e: CmsException) {
                val problem =
                    when (e.failure) {
                        CmsFailure.NOT_SIGNED_DATA -> SecurityObjectProblem.NOT_SIGNED_DATA
                        CmsFailure.WRONG_CONTENT_TYPE -> SecurityObjectProblem.NOT_A_SECURITY_OBJECT
                        CmsFailure.MISSING_CONTENT -> SecurityObjectProblem.NOT_A_SECURITY_OBJECT
                    }
                SecurityObjectParse.Unreadable(problem, 0)
            }

        private fun build(file: ByteArray): SecurityObjectView {
            val sd = CmsParser.parse(file)
            val lds = LdsSecurityObject.parse(sd.eContent)
            val certs = ArrayList<SignerCertificateView>()
            var unreadable = 0
            for (der in sd.certificates) {
                try {
                    certs.add(SignerCertificateView.from(X509Parser.parse(der)))
                } catch (e: Asn1Exception) {
                    unreadable++
                } catch (e: X509Exception) {
                    unreadable++
                }
            }
            return SecurityObjectView(
                version = lds.version,
                signedDataVersion = sd.version,
                hashAlgorithmOid = lds.hashAlgorithm.oid,
                hashAlgorithmName = hashName(lds.hashAlgorithm.oid),
                hashAlgorithmHasParameters = lds.hashAlgorithm.parameters != null,
                dataGroupHashes = lds.dataGroupHashes.map { DataGroupHash(it.number, it.hash) },
                ldsVersion = lds.ldsVersion,
                unicodeVersion = lds.unicodeVersion,
                signers = sd.signerInfos.map { SecurityObjectSigner.from(it) },
                certificates = certs,
                unreadableCertificateCount = unreadable,
                wasBer = sd.wasBer,
                encodedContent = sd.eContent,
                signedData = sd,
            )
        }

        private fun hashName(oid: String): String? =
            when (oid) {
                "1.3.14.3.2.26" -> "SHA-1"
                "2.16.840.1.101.3.4.2.4" -> "SHA-224"
                "2.16.840.1.101.3.4.2.1" -> "SHA-256"
                "2.16.840.1.101.3.4.2.2" -> "SHA-384"
                "2.16.840.1.101.3.4.2.3" -> "SHA-512"
                else -> null
            }
    }
}

/**
 * One data group's stored hash.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class DataGroupHash internal constructor(
    /** The data group number as stored, normally 1 to 16. */
    public val number: Int,
    hash: ByteArray,
) {
    private val stored: ByteArray = hash.copyOf()

    /** The stored hash bytes (a copy). */
    public val hash: ByteArray get() = stored.copyOf()

    override fun toString(): String = "DataGroupHash(number=$number, hashLength=${stored.size})"
}

/**
 * A signer named in the security object's SignedData, as stored.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class SecurityObjectSigner internal constructor(
    /** The SignerInfo version as stored (1 for issuer and serial number, 3 for a key identifier). */
    public val version: Int,
    /** The signer's certificate serial number as lowercase hex, when named by issuer and serial number; else `null`. */
    public val issuerSerialNumberHex: String?,
    /** The signer's subject key identifier, when named by key identifier; else `null`. */
    public val subjectKeyIdentifier: ByteArray?,
    /** The OID of the digest algorithm the signature uses. */
    public val digestAlgorithmOid: String,
    /** The OID of the signature algorithm; its parameters are not exposed here. */
    public val signatureAlgorithmOid: String,
    /** `true` when the signature covers signed attributes (the normal case). */
    public val hasSignedAttributes: Boolean,
    /** The `messageDigest` signed attribute, when present. */
    public val messageDigest: ByteArray?,
) {
    internal companion object {
        fun from(info: io.lightine.tessera.emrtd.internal.cms.CmsSignerInfo) =
            SecurityObjectSigner(
                version = info.version,
                issuerSerialNumberHex = info.sid.serialNumber?.let { X509Parser.hex(it) },
                subjectKeyIdentifier = info.sid.subjectKeyIdentifier?.copyOf(),
                digestAlgorithmOid = info.digestAlgorithm.oid,
                signatureAlgorithmOid = info.signatureAlgorithm.oid,
                hasSignedAttributes = info.signedAttributes != null,
                messageDigest = info.messageDigest,
            )
    }
}

/**
 * An extension of a certificate: its OID, whether it is marked critical, and whether the SDK knows
 * it.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CertificateExtensionView internal constructor(
    public val oid: String,
    public val critical: Boolean,
    /** `true` when the SDK knows this extension (those the ICAO certificate profile names). */
    public val recognized: Boolean,
)

/**
 * The signer certificate stored in the chip's security object, as read: no signature, date or
 * profile has been checked.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class SignerCertificateView internal constructor(
    /** The certificate version, 1 to 3. */
    public val version: Int,
    /** The serial number as lowercase hex, as encoded. */
    public val serialNumberHex: String,
    /** The issuer's `countryName`, or `null`. */
    public val issuerCountry: String?,
    /** The issuer's `commonName`, or `null`. */
    public val issuerCommonName: String?,
    /** The subject's `countryName`, or `null`. */
    public val subjectCountry: String?,
    /** The subject's `commonName`, or `null`. */
    public val subjectCommonName: String?,
    /** Start of validity as `YYYY-MM-DDTHH:MM:SSZ`. */
    public val notBefore: String,
    /** End of validity as `YYYY-MM-DDTHH:MM:SSZ`. */
    public val notAfter: String,
    /** The OID of the signature algorithm. */
    public val signatureAlgorithmOid: String,
    /** The OID of the public key algorithm. */
    public val publicKeyAlgorithmOid: String,
    /** Key usage names (RFC 5280, for example `digitalSignature`, `keyCertSign`), or `null` when there is no such extension. */
    public val keyUsage: Set<String>?,
    /** Extended key usage OIDs, or `null` when there is no such extension. */
    public val extendedKeyUsage: List<String>?,
    /** The document types from the DocumentType extension (`P`, `ID`), or `null` when the extension is absent. */
    public val documentTypes: List<String>?,
    /** Start of the private key usage period (the signer's own signing window) as `YYYY-MM-DDTHH:MM:SSZ`, or `null`. */
    public val privateKeyNotBefore: String?,
    /** End of the private key usage period, or `null`. */
    public val privateKeyNotAfter: String?,
    /** The authority key identifier's key id, or `null`. */
    public val authorityKeyIdentifier: ByteArray?,
    /** The subject key identifier, or `null`. */
    public val subjectKeyIdentifier: ByteArray?,
    /** `true` when BasicConstraints marks this a CA; `null` when there is no such extension. */
    public val isCa: Boolean?,
    /** Every extension, in order. */
    public val extensions: List<CertificateExtensionView>,
    private val encoded: ByteArray,
) {
    /** OIDs of critical extensions the SDK does not know. A verifier must refuse a certificate it cannot fully process; the reader only reports them. */
    public val unrecognizedCriticalExtensions: List<String>
        get() = extensions.filter { it.critical && !it.recognized }.map { it.oid }

    /** The certificate as DER (a copy). */
    public fun encodedBytes(): ByteArray = encoded.copyOf()

    override fun toString(): String = "SignerCertificateView(version=$version, signatureAlgorithm=$signatureAlgorithmOid)"

    internal companion object {
        fun from(c: X509Certificate): SignerCertificateView =
            SignerCertificateView(
                version = c.version,
                serialNumberHex = X509Parser.hex(c.serialNumber),
                issuerCountry = c.issuer.countryName,
                issuerCommonName = c.issuer.commonName,
                subjectCountry = c.subject.countryName,
                subjectCommonName = c.subject.commonName,
                notBefore = c.notBefore.iso(),
                notAfter = c.notAfter.iso(),
                signatureAlgorithmOid = c.signatureAlgorithm.oid,
                publicKeyAlgorithmOid = c.publicKeyAlgorithm.oid,
                keyUsage =
                    c.decoded.keyUsage
                        ?.map { keyUsageName(it) }
                        ?.toSet(),
                extendedKeyUsage = c.decoded.extendedKeyUsage,
                documentTypes = c.decoded.documentTypeList?.types,
                privateKeyNotBefore =
                    c.decoded.privateKeyUsagePeriod
                        ?.notBefore
                        ?.iso(),
                privateKeyNotAfter =
                    c.decoded.privateKeyUsagePeriod
                        ?.notAfter
                        ?.iso(),
                authorityKeyIdentifier = c.decoded.authorityKeyIdentifier?.keyIdentifier,
                subjectKeyIdentifier = c.decoded.subjectKeyIdentifier,
                isCa = c.decoded.basicConstraints?.ca,
                extensions = c.extensions.map { CertificateExtensionView(it.oid, it.critical, it.recognized) },
                encoded = c.encoded,
            )

        private fun keyUsageName(bit: KeyUsageBit): String =
            when (bit) {
                KeyUsageBit.DIGITAL_SIGNATURE -> "digitalSignature"
                KeyUsageBit.NON_REPUDIATION -> "nonRepudiation"
                KeyUsageBit.KEY_ENCIPHERMENT -> "keyEncipherment"
                KeyUsageBit.DATA_ENCIPHERMENT -> "dataEncipherment"
                KeyUsageBit.KEY_AGREEMENT -> "keyAgreement"
                KeyUsageBit.KEY_CERT_SIGN -> "keyCertSign"
                KeyUsageBit.CRL_SIGN -> "cRLSign"
                KeyUsageBit.ENCIPHER_ONLY -> "encipherOnly"
                KeyUsageBit.DECIPHER_ONLY -> "decipherOnly"
            }
    }
}

/**
 * The outcome of reading EF.SOD.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class SecurityObjectParse {
    /** The file was read. */
    public class Parsed internal constructor(
        public val view: SecurityObjectView,
    ) : SecurityObjectParse()

    /** The file could not be read: the [problem] and the byte [offset] where it was found. No data bytes are included. */
    public class Unreadable internal constructor(
        public val problem: SecurityObjectProblem,
        public val offset: Int,
    ) : SecurityObjectParse()
}

/**
 * Why EF.SOD could not be read.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class SecurityObjectProblem {
    /** The bytes are not valid ASN.1: cut short, a bad length, or nested too deep. */
    MALFORMED_ENCODING,

    /** The file is ASN.1 but not a CMS SignedData. */
    NOT_SIGNED_DATA,

    /** The SignedData does not hold an LDS security object, or holds no content. */
    NOT_A_SECURITY_OBJECT,
}
