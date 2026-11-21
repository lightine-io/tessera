package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.bitString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.boolean
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.concat
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.explicit
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.generalizedTime
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.implicitConstructed
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.implicitPrimitive
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.integer
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.nullValue
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.octetString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.oid
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.printableString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.sequence
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.set
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.unsignedInteger
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.utcTime

/**
 * A fake public-key infrastructure for tests: certificates and EF.SOD files built at test time from
 * synthetic values (the fictional state UTO, as the SDK's other fixtures).
 *
 * **Every signature here is a placeholder.** The bytes in `signatureValue` and in the SignerInfo
 * are fixed filler, not the output of a signing key, and the public keys are filler too. This is
 * enough for the parsing tests (structure, versions, extensions, BER and DER forms) and for nothing
 * else: no signature check can pass on them. The signature-verification card (N18) builds real
 * signatures on top of the same structure builders, through the test crypto it adds.
 */
internal object FakePki {
    const val OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
    const val OID_LDS_SECURITY_OBJECT = "2.23.136.1.1.1"
    const val OID_CONTENT_TYPE = "1.2.840.113549.1.9.3"
    const val OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
    const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
    const val OID_SHA1 = "1.3.14.3.2.26"
    const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    const val OID_PRIME256V1 = "1.2.840.10045.3.1.7"

    const val OID_KEY_USAGE = "2.5.29.15"
    const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    const val OID_SKI = "2.5.29.14"
    const val OID_AKI = "2.5.29.35"
    const val OID_PKUP = "2.5.29.16"
    const val OID_SAN = "2.5.29.17"
    const val OID_CRL_DP = "2.5.29.31"
    const val OID_DOCUMENT_TYPE = "2.23.136.1.1.6.2"

    /** An extension to put in a certificate: [content] is the DER inside the extension's OCTET STRING. */
    class Ext(
        val oid: String,
        val critical: Boolean,
        val content: ByteArray,
    )

    private fun filler(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { (seed + it).toByte() }

    private fun algorithm(
        oid: String,
        withNull: Boolean,
    ) = if (withNull) sequence(oid(oid), nullValue()) else sequence(oid(oid))

    fun name(
        country: String,
        commonName: String,
    ): ByteArray =
        sequence(
            set(sequence(oid("2.5.4.6"), printableString(country))),
            set(sequence(oid("2.5.4.3"), printableString(commonName))),
        )

    // ---- extensions ----

    fun keyUsage(
        vararg bits: Int,
        critical: Boolean = true,
    ): Ext {
        // RFC 5280 bit numbers: digitalSignature 0 ... keyCertSign 5, cRLSign 6. DER drops trailing zero bits.
        val highest = bits.max()
        val bytes = ByteArray(highest / 8 + 1)
        for (b in bits) bytes[b / 8] = (bytes[b / 8].toInt() or (0x80 shr (b % 8))).toByte()
        val unused = 7 - highest % 8
        return Ext(OID_KEY_USAGE, critical, bitString(bytes, unused))
    }

    fun basicConstraints(
        ca: Boolean,
        pathLength: Int?,
    ) = Ext(
        OID_BASIC_CONSTRAINTS,
        true,
        sequence(*listOfNotNull(if (ca) boolean(true) else null, pathLength?.let { integer(it.toLong()) }).toTypedArray()),
    )

    fun subjectKeyIdentifier(id: ByteArray) = Ext(OID_SKI, false, octetString(id))

    fun authorityKeyIdentifier(id: ByteArray) = Ext(OID_AKI, false, sequence(implicitPrimitive(0, id)))

    /** Both bounds are `YYYYMMDDHHMMSSZ` GeneralizedTime in `[0]` and `[1]`, as Part 12 requires. */
    fun privateKeyUsagePeriod(
        notBefore: String?,
        notAfter: String?,
    ) = Ext(
        OID_PKUP,
        false,
        sequence(
            *listOfNotNull(
                notBefore?.let { implicitPrimitive(0, it.encodeToByteArray()) },
                notAfter?.let { implicitPrimitive(1, it.encodeToByteArray()) },
            ).toTypedArray(),
        ),
    )

    /** DocumentTypeList (Part 12, 7.1.1.6): version 0 and a SET OF PrintableString. */
    fun documentTypeList(vararg types: String) =
        Ext(OID_DOCUMENT_TYPE, false, sequence(integer(0), set(*types.map { printableString(it) }.toTypedArray())))

    fun subjectAltName(uri: String) = Ext(OID_SAN, false, sequence(implicitPrimitive(6, uri.encodeToByteArray())))

    fun crlDistributionPoint(uri: String) =
        Ext(OID_CRL_DP, false, sequence(sequence(explicit(0, explicit(0, implicitPrimitive(6, uri.encodeToByteArray()))))))

    // ---- certificates ----

    /**
     * A certificate with placeholder signature and public key. Times are given as ready elements
     * ([utcTime] or [generalizedTime]); the default is a 2024 to 2034 validity.
     */
    fun certificate(
        subject: ByteArray = name("UT", "Utopia Document Signer 01"),
        issuer: ByteArray = name("UT", "Utopia CSCA"),
        serial: Long = 0x1234,
        notBefore: ByteArray = utcTime("240101000000Z"),
        notAfter: ByteArray = utcTime("340101000000Z"),
        extensions: List<Ext> = emptyList(),
        version: Int = 3,
        signatureNull: Boolean = false,
    ): ByteArray {
        val sigAlg = algorithm(OID_ECDSA_SHA256, signatureNull)
        val spki =
            sequence(
                sequence(oid(OID_EC_PUBLIC_KEY), oid(OID_PRIME256V1)),
                bitString(byteArrayOf(0x04) + filler(64, 0x30)),
            )
        val extElements =
            extensions.map { e ->
                sequence(*listOfNotNull(oid(e.oid), if (e.critical) boolean(true) else null, octetString(e.content)).toTypedArray())
            }
        val tbs =
            sequence(
                *listOfNotNull(
                    if (version != 1) explicit(0, integer((version - 1).toLong())) else null,
                    integer(serial),
                    sigAlg,
                    issuer,
                    sequence(notBefore, notAfter),
                    subject,
                    spki,
                    if (extElements.isNotEmpty()) explicit(3, sequence(*extElements.toTypedArray())) else null,
                ).toTypedArray(),
            )
        // Placeholder signature: an ECDSA-Sig-Value shape over filler, not a real signature.
        val signature = sequence(unsignedInteger(filler(32, 0x70)), unsignedInteger(filler(32, 0x10)))
        return sequence(tbs, sigAlg, bitString(signature))
    }

    /** A Sixth Edition Document Signer certificate (Part 12, Tables 5 and 6): critical KeyUsage digitalSignature, DocumentType, PKUP, AKI, SKI, SAN, CRL distribution point. */
    fun documentSignerCertificate(
        documentTypes: List<String> = listOf("P"),
        pkuNotBefore: String = "20240101000000Z",
        pkuNotAfter: String = "20250101000000Z",
        extra: List<Ext> = emptyList(),
    ): ByteArray =
        certificate(
            extensions =
                listOf(
                    authorityKeyIdentifier(filler(20, 0x01)),
                    subjectKeyIdentifier(filler(20, 0x41)),
                    keyUsage(0),
                    privateKeyUsagePeriod(pkuNotBefore, pkuNotAfter),
                    documentTypeList(*documentTypes.toTypedArray()),
                    subjectAltName("https://utopia.example/ds"),
                    crlDistributionPoint("https://utopia.example/crl"),
                ) + extra,
        )

    /** A CSCA root: CA, pathLen 0, keyCertSign and cRLSign. Self-issued. */
    fun cscaCertificate(): ByteArray =
        certificate(
            subject = name("UT", "Utopia CSCA"),
            issuer = name("UT", "Utopia CSCA"),
            extensions = listOf(subjectKeyIdentifier(filler(20, 0x01)), keyUsage(5, 6), basicConstraints(true, 0)),
        )

    // ---- EF.SOD ----

    /** The LDSSecurityObject. [ldsVersion] and [unicodeVersion] are written only for V1. */
    fun ldsSecurityObject(
        version: Int,
        hashOid: String = OID_SHA256,
        hashParametersNull: Boolean = false,
        groups: List<Pair<Int, ByteArray>>,
        ldsVersion: String? = "0108",
        unicodeVersion: String? = "040000",
    ): ByteArray =
        sequence(
            *listOfNotNull(
                integer(version.toLong()),
                algorithm(hashOid, hashParametersNull),
                sequence(*groups.map { (n, h) -> sequence(integer(n.toLong()), octetString(h)) }.toTypedArray()),
                if (version >= 1 &&
                    ldsVersion != null
                ) {
                    sequence(printableString(ldsVersion), printableString(unicodeVersion ?: "040000"))
                } else {
                    null
                },
            ).toTypedArray(),
        )

    private fun element(
        tag: Int,
        indefinite: Boolean,
        vararg parts: ByteArray,
    ) = if (indefinite) DerWriter.indefinite(tag, *parts) else DerWriter.tlv(tag, concat(*parts))

    /**
     * A complete EF.SOD file around [lds]: file tag `77`, ContentInfo, SignedData with the given
     * certificates and one SignerInfo whose signature and message digest are placeholders.
     * [indefinite] writes BER with indefinite lengths throughout and splits the content into two
     * OCTET STRING segments.
     */
    fun sod(
        lds: ByteArray,
        certificates: List<ByteArray> = emptyList(),
        digestOid: String = OID_SHA256,
        digestParametersNull: Boolean = false,
        signerBySubjectKeyId: Boolean = false,
        indefinite: Boolean = false,
        fileTag: Boolean = true,
    ): ByteArray {
        val content =
            if (indefinite) {
                val half = lds.size / 2
                DerWriter.indefinite(0x24, octetString(lds.copyOfRange(0, half)), octetString(lds.copyOfRange(half, lds.size)))
            } else {
                octetString(lds)
            }
        val encap = element(0x30, indefinite, oid(OID_LDS_SECURITY_OBJECT), element(0xA0, indefinite, content))
        val sid =
            if (signerBySubjectKeyId) {
                implicitPrimitive(0, filler(20, 0x41))
            } else {
                sequence(name("UT", "Utopia CSCA"), integer(0x1234))
            }
        val signedAttrs =
            implicitConstructed(
                0,
                sequence(oid(OID_CONTENT_TYPE), set(oid(OID_LDS_SECURITY_OBJECT))),
                sequence(oid(OID_MESSAGE_DIGEST), set(octetString(filler(32, 0x55)))),
            )
        val signerInfo =
            sequence(
                integer(if (signerBySubjectKeyId) 3 else 1),
                sid,
                algorithm(digestOid, digestParametersNull),
                signedAttrs,
                algorithm(OID_ECDSA_SHA256, true),
                octetString(filler(64, 0x90)), // placeholder signature
            )
        val parts =
            listOfNotNull(
                integer(3),
                set(algorithm(digestOid, digestParametersNull)),
                encap,
                if (certificates.isNotEmpty()) implicitConstructed(0, *certificates.toTypedArray()) else null,
                set(signerInfo),
            )
        val signedData = element(0x30, indefinite, *parts.toTypedArray())
        val contentInfo = element(0x30, indefinite, oid(OID_SIGNED_DATA), element(0xA0, indefinite, signedData))
        return if (fileTag) element(0x77, indefinite, contentInfo) else contentInfo
    }

    /** Synthetic stand-in hashes: not the hash of any real file. */
    fun fakeHash(
        seed: Int,
        length: Int = 32,
    ) = filler(length, seed)
}
