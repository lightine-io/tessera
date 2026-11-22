package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag

/**
 * Decodes the extensions Doc 9303 Part 12 (Table 6, 7.1.1.5, 7.1.1.6) names and that a later check
 * needs: KeyUsage, BasicConstraints, ExtKeyUsage, SubjectKeyIdentifier, AuthorityKeyIdentifier,
 * PrivateKeyUsagePeriod, DocumentTypeList, NameChange, and the country a directoryName in
 * SubjectAltName or IssuerAltName carries (7.1). The first extension with an OID wins. A known
 * extension with the wrong content fails with [X509Exception], at an offset counted from the start
 * of the certificate; an unknown one is skipped.
 */
internal object X509Extensions {
    const val KEY_USAGE = "2.5.29.15"
    const val BASIC_CONSTRAINTS = "2.5.29.19"
    const val EXT_KEY_USAGE = "2.5.29.37"
    const val SUBJECT_KEY_ID = "2.5.29.14"
    const val AUTHORITY_KEY_ID = "2.5.29.35"
    const val PRIVATE_KEY_USAGE_PERIOD = "2.5.29.16"
    const val SUBJECT_ALT_NAME = "2.5.29.17"
    const val ISSUER_ALT_NAME = "2.5.29.18"
    const val CERTIFICATE_POLICIES = "2.5.29.32"
    const val CRL_DISTRIBUTION_POINTS = "2.5.29.31"
    const val AUTHORITY_INFO_ACCESS = "1.3.6.1.5.5.7.1.1"
    const val SUBJECT_INFO_ACCESS = "1.3.6.1.5.5.7.1.11"
    const val NAME_CHANGE = "2.23.136.1.1.6.1"
    const val DOCUMENT_TYPE_LIST = "2.23.136.1.1.6.2"

    /** The extensions the profile in Part 12, Table 6 names. Anything else that is critical is surfaced as unrecognized. */
    val RECOGNIZED: Set<String> =
        setOf(
            KEY_USAGE,
            BASIC_CONSTRAINTS,
            EXT_KEY_USAGE,
            SUBJECT_KEY_ID,
            AUTHORITY_KEY_ID,
            PRIVATE_KEY_USAGE_PERIOD,
            SUBJECT_ALT_NAME,
            ISSUER_ALT_NAME,
            CERTIFICATE_POLICIES,
            CRL_DISTRIBUTION_POINTS,
            AUTHORITY_INFO_ACCESS,
            SUBJECT_INFO_ACCESS,
            NAME_CHANGE,
            DOCUMENT_TYPE_LIST,
        )

    fun decode(extensions: List<X509Extension>): DecodedExtensions {
        fun find(oid: String) = extensions.firstOrNull { it.oid == oid }
        return DecodedExtensions(
            keyUsage = find(KEY_USAGE)?.let { e -> wrap(e) { keyUsage(e.value) } },
            basicConstraints = find(BASIC_CONSTRAINTS)?.let { e -> wrap(e) { basicConstraints(e.value) } },
            extendedKeyUsage = find(EXT_KEY_USAGE)?.let { e -> wrap(e) { extKeyUsage(e.value) } },
            subjectKeyIdentifier = find(SUBJECT_KEY_ID)?.let { e -> wrap(e) { DerReader(e.value).readOctetString().also { _ -> } } },
            authorityKeyIdentifier = find(AUTHORITY_KEY_ID)?.let { e -> wrap(e) { authorityKeyId(e.value) } },
            privateKeyUsagePeriod = find(PRIVATE_KEY_USAGE_PERIOD)?.let { e -> wrap(e) { keyUsagePeriod(e) } },
            documentTypeList = find(DOCUMENT_TYPE_LIST)?.let { e -> wrap(e) { documentTypeList(e.value) } },
            subjectAltNameCountry = find(SUBJECT_ALT_NAME)?.let { e -> wrap(e) { altNameCountry(e.value) } },
            issuerAltNameCountry = find(ISSUER_ALT_NAME)?.let { e -> wrap(e) { altNameCountry(e.value) } },
            nameChange =
                find(NAME_CHANGE)?.let {
                    wrap(it) {
                        DerReader(it.value).also { r ->
                            r.readNull()
                            r.finish()
                        }
                    }
                } != null,
        )
    }

    // A malformed known extension is a malformed certificate, reported with one failure kind and an
    // offset counted from the start of the certificate (the reader inside the value counts from
    // the value, so the value's own offset is added back).
    private fun <T> wrap(
        ext: X509Extension,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Asn1Exception) {
            throw X509Exception(X509Failure.INVALID_EXTENSION, ext.valueOffset + e.offset)
        }

    private fun keyUsage(value: ByteArray): Set<KeyUsageBit> {
        val reader = DerReader(value)
        val bits = reader.readBitString()
        reader.finish()
        val all = KeyUsageBit.entries
        val set = LinkedHashSet<KeyUsageBit>()
        for (i in all.indices) {
            val byteIndex = i / 8
            if (byteIndex < bits.bytes.size && (bits.bytes[byteIndex].toInt() and (0x80 shr (i % 8))) != 0) set.add(all[i])
        }
        return set
    }

    private fun basicConstraints(value: ByteArray): BasicConstraints {
        val reader = DerReader(value)
        val seq = reader.readSequence()
        reader.finish()
        val ca = if (seq.peekTag() == DerTag.universal(DerTag.BOOLEAN)) seq.readBoolean() else false
        val pathLen = if (seq.hasMore) seq.readInt() else null
        seq.finish()
        return BasicConstraints(ca, pathLen)
    }

    private fun extKeyUsage(value: ByteArray): List<String> {
        val reader = DerReader(value)
        val seq = reader.readSequence()
        reader.finish()
        val oids = ArrayList<String>()
        while (seq.hasMore) oids.add(seq.readOid())
        return oids
    }

    private fun authorityKeyId(value: ByteArray): AuthorityKeyIdentifier {
        val reader = DerReader(value)
        val seq = reader.readSequence()
        reader.finish()
        val keyId = seq.readOptional(DerTag.context(0, constructed = false))?.value()
        val issuer = seq.readOptional(DerTag.context(1, constructed = true))?.encoded()
        val serial = seq.readOptional(DerTag.context(2, constructed = false))?.value()
        seq.finish()
        return AuthorityKeyIdentifier(keyId, issuer, serial)
    }

    private fun keyUsagePeriod(ext: X509Extension): PrivateKeyUsagePeriod {
        val reader = DerReader(ext.value)
        val seq = reader.readSequence()
        reader.finish()
        // Both are [n] IMPLICIT GeneralizedTime, so the content is YYYYMMDDHHMMSSZ.
        val before = seq.readOptional(DerTag.context(0, constructed = false))
        val after = seq.readOptional(DerTag.context(1, constructed = false))
        seq.finish()
        return PrivateKeyUsagePeriod(
            before?.let { X509Parser.parseTime(it.value(), twoDigitYear = false, offset = ext.valueOffset + it.offset) },
            after?.let { X509Parser.parseTime(it.value(), twoDigitYear = false, offset = ext.valueOffset + it.offset) },
        )
    }

    private fun documentTypeList(value: ByteArray): DocumentTypeList {
        val reader = DerReader(value)
        val seq = reader.readSequence()
        reader.finish()
        val version = seq.readInt()
        val set = seq.readSet()
        seq.finish()
        val types = ArrayList<String>()
        while (set.hasMore) {
            val e = set.read(DerTag.universal(DerTag.PRINTABLE_STRING))
            types.add(e.value().decodeToString())
        }
        return DocumentTypeList(version, types)
    }

    /**
     * The country of the first `directoryName` GeneralName that carries one (Part 12, 7.1), or
     * `null`. The other GeneralName forms are skipped: this parser names no ICAO `otherName`, so
     * only a directoryName's country attribute is read. `Name` is a CHOICE, so its `[4]` tag is
     * explicit under the ASN.1 rules (X.680): the whole Name SEQUENCE sits inside the tag. That is
     * the standard form and the one read first. A `[4]` whose content starts with the RDN SETs
     * directly (the tag written as if it were implicit) is also read; anything else inside the
     * `[4]` fails.
     */
    private fun altNameCountry(value: ByteArray): String? {
        val reader = DerReader(value)
        val names = reader.readSequence()
        reader.finish()
        var country: String? = null
        while (names.hasMore) {
            val e = names.read()
            if (country == null && e.tag == DerTag.context(4, constructed = true)) {
                val contents = e.contents()
                // A Name's own tag is a SEQUENCE and an RDN's is a SET, so the first element tells
                // the standard explicit form from the bare one.
                val rdns: DerReader =
                    if (contents.peekTag() == DerTag.universal(DerTag.SEQUENCE, constructed = true)) {
                        val name = contents.readSequence()
                        contents.finish()
                        name
                    } else {
                        contents
                    }
                country = X509Parser.readNameAttributes(rdns).firstOrNull { it.oid == DistinguishedName.OID_COUNTRY }?.value
            }
        }
        return country
    }
}
