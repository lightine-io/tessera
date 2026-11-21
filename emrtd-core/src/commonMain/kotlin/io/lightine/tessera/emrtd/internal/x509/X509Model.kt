package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.internal.asn1.DerBitString
import io.lightine.tessera.emrtd.internal.asn1.DerTag

/** Why an X.509 structure could not be read. Carries no data bytes. */
internal enum class X509Failure {
    /** A time value is not `YYMMDDHHMMSSZ` / `YYYYMMDDHHMMSSZ` with real digits and ranges. */
    INVALID_TIME,

    /** A version number outside v1 to v3. */
    INVALID_VERSION,

    /** A well-known extension whose content is not the structure its OID names. */
    INVALID_EXTENSION,
}

internal class X509Exception(
    val failure: X509Failure,
    val offset: Int,
) : IllegalStateException("X.509: $failure at offset $offset")

/** An algorithm and its parameters. [parameters] is the whole DER encoding of the parameters element, or `null` when absent. */
internal class AlgorithmIdentifier(
    val oid: String,
    val parameters: ByteArray?,
) {
    /** `true` when the parameters are an ASN.1 NULL (`05 00`); Doc 9303 Part 10 says to accept this and absent alike. */
    val parametersAreNull: Boolean
        get() = parameters != null && parameters.size == 2 && parameters[0].toInt() == 0x05 && parameters[1].toInt() == 0
}

/** One attribute of a name: its type OID and its value as text (hex for string types that are not text). */
internal class NameAttribute(
    val oid: String,
    val value: String,
    val stringTag: Int,
)

/** A distinguished name: the attributes in order, and its DER bytes. */
internal class DistinguishedName(
    val encoded: ByteArray,
    val attributes: List<NameAttribute>,
) {
    fun first(oid: String): String? = attributes.firstOrNull { it.oid == oid }?.value

    val countryName: String? get() = first(OID_COUNTRY)
    val commonName: String? get() = first(OID_COMMON_NAME)

    /**
     * The comparison RFC 5280, section 7.1 defines for names whose DER bytes differ: the same
     * attributes in the same order, each a PrintableString or a UTF8String, with values matched
     * case-insensitively and insignificant spaces (leading, trailing and repeated) removed. A name
     * with an attribute of any other type never matches this way. Used only as a fallback after
     * the byte comparison, never in its place.
     */
    fun matchesNormalized(other: DistinguishedName): Boolean {
        if (attributes.size != other.attributes.size) return false
        return attributes.indices.all { i ->
            val a = attributes[i]
            val b = other.attributes[i]
            a.oid == b.oid && comparableText(a) && comparableText(b) && normalizedText(a.value) == normalizedText(b.value)
        }
    }

    private fun comparableText(a: NameAttribute): Boolean = a.stringTag == DerTag.PRINTABLE_STRING || a.stringTag == DerTag.UTF8_STRING

    private fun normalizedText(value: String): String =
        value
            .uppercase()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    companion object {
        const val OID_COUNTRY = "2.5.4.6"
        const val OID_COMMON_NAME = "2.5.4.3"
    }
}

/** A UTC time with whole seconds, as certificates write them. Ordered by time. */
internal data class CertTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) : Comparable<CertTime> {
    private val key: Long
        get() = (((((year * 100L + month) * 100 + day) * 100 + hour) * 100 + minute) * 100 + second)

    override fun compareTo(other: CertTime): Int = key.compareTo(other.key)

    /** `YYYY-MM-DDTHH:MM:SSZ`. */
    fun iso(): String = "${p(year, 4)}-${p(month)}-${p(day)}T${p(hour)}:${p(minute)}:${p(second)}Z"

    private fun p(
        v: Int,
        width: Int = 2,
    ) = v.toString().padStart(width, '0')
}

/** One extension as encoded: its OID, the critical flag and the content bytes (the inside of the OCTET STRING). */
internal class X509Extension(
    val oid: String,
    val critical: Boolean,
    val value: ByteArray,
) {
    /** `true` when this parser knows the extension (Doc 9303 Part 12, Table 6, and the RFC 5280 ones it names). */
    val recognized: Boolean get() = oid in X509Extensions.RECOGNIZED
}

internal enum class KeyUsageBit {
    DIGITAL_SIGNATURE,
    NON_REPUDIATION,
    KEY_ENCIPHERMENT,
    DATA_ENCIPHERMENT,
    KEY_AGREEMENT,
    KEY_CERT_SIGN,
    CRL_SIGN,
    ENCIPHER_ONLY,
    DECIPHER_ONLY,
}

internal class AuthorityKeyIdentifier(
    val keyIdentifier: ByteArray?,
    /** The encoded GeneralNames, as is. */
    val authorityCertIssuer: ByteArray?,
    val authorityCertSerialNumber: ByteArray?,
)

internal class BasicConstraints(
    val ca: Boolean,
    val pathLengthConstraint: Int?,
)

internal class PrivateKeyUsagePeriod(
    val notBefore: CertTime?,
    val notAfter: CertTime?,
)

/** The DocumentTypeList extension (Doc 9303 Part 12, 7.1.1.6): the document types the signer may produce, as in the MRZ (`P`, `ID`). */
internal class DocumentTypeList(
    val version: Int,
    val types: List<String>,
)

/** The extensions this parser decodes. Each is `null` when the certificate has no such extension. */
internal class DecodedExtensions(
    val keyUsage: Set<KeyUsageBit>?,
    val basicConstraints: BasicConstraints?,
    val extendedKeyUsage: List<String>?,
    val subjectKeyIdentifier: ByteArray?,
    val authorityKeyIdentifier: AuthorityKeyIdentifier?,
    val privateKeyUsagePeriod: PrivateKeyUsagePeriod?,
    val documentTypeList: DocumentTypeList?,
    /** The country of the first directoryName in the SubjectAltName extension that carries one, or `null` (Part 12, 7.1). */
    val subjectAltNameCountry: String?,
    /** The country of the first directoryName in the IssuerAltName extension that carries one, or `null`. */
    val issuerAltNameCountry: String?,
    /** `true` when the NameChange extension is present. */
    val nameChange: Boolean,
)

/** A parsed certificate. Nothing is verified: not the signature, not the dates against a clock, not the profile. */
internal class X509Certificate(
    val encoded: ByteArray,
    /** The DER of the TBSCertificate, the bytes the signature covers. */
    val tbsEncoded: ByteArray,
    /** 1, 2 or 3. */
    val version: Int,
    /** The serial number as encoded (two's complement). */
    val serialNumber: ByteArray,
    /** The `signature` field inside the TBSCertificate. */
    val tbsSignatureAlgorithm: AlgorithmIdentifier,
    val issuer: DistinguishedName,
    val notBefore: CertTime,
    val notAfter: CertTime,
    val subject: DistinguishedName,
    /** The DER of the whole SubjectPublicKeyInfo. */
    val subjectPublicKeyInfo: ByteArray,
    val publicKeyAlgorithm: AlgorithmIdentifier,
    val publicKey: DerBitString,
    /** `true` when issuerUniqueID or subjectUniqueID is present (Doc 9303 Part 12 says do not use). */
    val hasUniqueIds: Boolean,
    val extensions: List<X509Extension>,
    val decoded: DecodedExtensions,
    /** The outer `signatureAlgorithm`. */
    val signatureAlgorithm: AlgorithmIdentifier,
    val signature: DerBitString,
) {
    /** Critical extensions this parser does not know. A verifier that cannot process one must say so; the parser only surfaces them. */
    val unrecognizedCriticalExtensions: List<X509Extension>
        get() = extensions.filter { it.critical && !it.recognized }
}
