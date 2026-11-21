package io.lightine.tessera.emrtd.internal.cms

import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.x509.AlgorithmIdentifier
import io.lightine.tessera.emrtd.internal.x509.readAlgorithmIdentifier

/** Why a CMS structure is not the security object's SignedData. Carries no data bytes. */
internal enum class CmsFailure {
    /** The ContentInfo does not hold a SignedData (wrong content type OID). */
    NOT_SIGNED_DATA,

    /** The signed content is not the LDS security object (wrong `eContentType`). */
    WRONG_CONTENT_TYPE,

    /** The SignedData has no `eContent`. */
    MISSING_CONTENT,
}

internal class CmsException(
    val failure: CmsFailure,
) : IllegalStateException("CMS: $failure")

internal class CmsAttribute(
    val oid: String,
    /** Each value as its whole DER encoding. */
    val values: List<ByteArray>,
)

/** Who signed: the certificate's issuer and serial number, or its subject key identifier. */
internal class SignerIdentifier(
    /** The DER of the issuer name, when the signer is named by issuer and serial number. */
    val issuer: ByteArray?,
    val serialNumber: ByteArray?,
    val subjectKeyIdentifier: ByteArray?,
)

internal class CmsSignerInfo(
    val version: Int,
    val sid: SignerIdentifier,
    val digestAlgorithm: AlgorithmIdentifier,
    val signedAttributes: List<CmsAttribute>?,
    /**
     * The signed attributes as the signature covers them: their DER with the `[0]` tag replaced by the
     * SET tag (RFC 5652, section 5.4). `null` when there are none.
     */
    val signedAttributesDer: ByteArray?,
    val signatureAlgorithm: AlgorithmIdentifier,
    val signature: ByteArray,
    val unsignedAttributes: List<CmsAttribute>?,
) {
    /** The `messageDigest` signed attribute's value, when present. */
    val messageDigest: ByteArray?
        get() =
            signedAttributes
                ?.firstOrNull { it.oid == CmsParser.OID_MESSAGE_DIGEST }
                ?.values
                ?.firstOrNull()
                ?.let { DerReader(it).readOctetString() }

    /** The `contentType` signed attribute's value, when present. */
    val contentTypeAttribute: String?
        get() =
            signedAttributes
                ?.firstOrNull { it.oid == CmsParser.OID_CONTENT_TYPE }
                ?.values
                ?.firstOrNull()
                ?.let { DerReader(it).readOid() }
}

internal class CmsSignedData(
    val version: Int,
    val digestAlgorithms: List<AlgorithmIdentifier>,
    val eContentType: String,
    /** The signed content: the bytes the digest covers, with BER segments joined. */
    val eContent: ByteArray,
    /** Each certificate as DER, in the order stored. */
    val certificates: List<ByteArray>,
    val signerInfos: List<CmsSignerInfo>,
    /** `true` when the bytes were not DER (indefinite or longer-than-needed lengths) and were read through [BerNormalizer]. */
    val wasBer: Boolean,
    /** `true` when the SignedData sat inside the EF.SOD wrapper (tag `77`). */
    val hadFileTag: Boolean,
)

/**
 * Reads EF.SOD (Doc 9303 Part 10, 4.6.2, and Appendix D for V0): the optional file tag `77`, then a
 * CMS ContentInfo holding a SignedData (RFC 5652) whose content is the LDS security object. Parsing
 * only: nothing is verified, and values such as the CMS version are reported, not judged.
 */
internal object CmsParser {
    const val OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
    const val OID_LDS_SECURITY_OBJECT = "2.23.136.1.1.1"
    const val OID_CONTENT_TYPE = "1.2.840.113549.1.9.3"
    const val OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"

    private val FILE_TAG = DerTag(DerTag.APPLICATION, true, 23)

    /**
     * Reads [file] as a SignedData whose content type is [expectedContentType]: the LDS security
     * object by default; EF.CardSecurity (id-SecurityObject) and a CSCA master list name theirs.
     */
    fun parse(
        file: ByteArray,
        expectedContentType: String = OID_LDS_SECURITY_OBJECT,
    ): CmsSignedData {
        val normalized = BerNormalizer.normalize(file)
        var reader = DerReader(normalized.der)
        val outer = reader
        var hadFileTag = false
        if (reader.peekTag() == FILE_TAG) {
            hadFileTag = true
            reader = reader.read(FILE_TAG).contents()
            outer.finish()
        }
        val contentInfoReader = reader
        val contentInfo = contentInfoReader.readSequence()
        contentInfoReader.finish()
        if (contentInfo.readOid() != OID_SIGNED_DATA) throw CmsException(CmsFailure.NOT_SIGNED_DATA)
        val signedData = contentInfo.readExplicit(0).readSequence()
        contentInfo.finish()

        val version = signedData.readInt()
        val digestAlgorithms = ArrayList<AlgorithmIdentifier>()
        val digestSet = signedData.readSet()
        while (digestSet.hasMore) digestAlgorithms.add(digestSet.readAlgorithmIdentifier())

        val encap = signedData.readSequence()
        val contentType = encap.readOid()
        val contentReader = encap.readOptional(DerTag.context(0, constructed = true))?.contents()
        encap.finish()
        if (contentType != expectedContentType) throw CmsException(CmsFailure.WRONG_CONTENT_TYPE)
        if (contentReader == null) throw CmsException(CmsFailure.MISSING_CONTENT)
        val eContent = readOctetStringAny(contentReader)
        contentReader.finish()

        val certificates = ArrayList<ByteArray>()
        signedData.readOptional(DerTag.context(0, constructed = true))?.contents()?.let { certs ->
            while (certs.hasMore) {
                val e = certs.read()
                // Only X.509 certificates (a SEQUENCE); the other CertificateChoices forms are not used by eMRTDs.
                if (e.tag == DerTag.universal(DerTag.SEQUENCE, constructed = true)) certificates.add(e.encoded())
            }
        }
        signedData.readOptional(DerTag.context(1, constructed = true)) // crls: not used, skipped

        val signerInfos = ArrayList<CmsSignerInfo>()
        val signerSet = signedData.readSet()
        while (signerSet.hasMore) signerInfos.add(readSignerInfo(signerSet))
        signedData.finish()

        return CmsSignedData(version, digestAlgorithms, contentType, eContent, certificates, signerInfos, normalized.changed, hadFileTag)
    }

    private fun readSignerInfo(parent: DerReader): CmsSignerInfo {
        val si = parent.readSequence()
        val version = si.readInt()
        val sid =
            if (si.peekTag() == DerTag.context(0, constructed = false)) {
                SignerIdentifier(null, null, si.read().value())
            } else {
                val ias = si.readSequence()
                val issuer = ias.read(DerTag.universal(DerTag.SEQUENCE, constructed = true)).encoded()
                val serial = ias.readInteger()
                ias.finish()
                SignerIdentifier(issuer, serial, null)
            }
        val digestAlgorithm = si.readAlgorithmIdentifier()
        val signedElement = si.readOptional(DerTag.context(0, constructed = true))
        val signedAttributes = signedElement?.let { readAttributes(it.contents()) }
        val signedDer = signedElement?.encoded()?.also { it[0] = 0x31 }
        val signatureAlgorithm = si.readAlgorithmIdentifier()
        val signature = readOctetStringAny(si)
        val unsigned = si.readOptional(DerTag.context(1, constructed = true))?.let { readAttributes(it.contents()) }
        si.finish()
        return CmsSignerInfo(version, sid, digestAlgorithm, signedAttributes, signedDer, signatureAlgorithm, signature, unsigned)
    }

    private fun readAttributes(reader: DerReader): List<CmsAttribute> {
        val out = ArrayList<CmsAttribute>()
        while (reader.hasMore) {
            val attr = reader.readSequence()
            val oid = attr.readOid()
            val values = attr.readSet()
            val list = ArrayList<ByteArray>()
            while (values.hasMore) list.add(values.read().encoded())
            attr.finish()
            out.add(CmsAttribute(oid, list))
        }
        return out
    }

    /** An OCTET STRING's bytes; a constructed one (BER segments) is joined. Nesting is bounded by the reader's depth limit. */
    internal fun readOctetStringAny(reader: DerReader): ByteArray {
        val octetPrimitive = DerTag.universal(DerTag.OCTET_STRING)
        val octetConstructed = DerTag.universal(DerTag.OCTET_STRING, constructed = true)
        val e = reader.read()
        return when (e.tag) {
            octetPrimitive -> {
                e.value()
            }

            octetConstructed -> {
                val sink = ByteSink()
                val kids = e.contents()
                while (kids.hasMore) sink.write(readOctetStringAny(kids))
                sink.toByteArray()
            }

            else -> {
                throw io.lightine.tessera.emrtd.internal.asn1.Asn1Exception(
                    io.lightine.tessera.emrtd.internal.asn1.Asn1Failure.UNEXPECTED_TAG,
                    e.offset,
                )
            }
        }
    }
}
