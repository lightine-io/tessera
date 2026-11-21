package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag

/**
 * Decodes the extensions Doc 9303 Part 12 (Table 6, 7.1.1.5, 7.1.1.6) names and that a later check
 * needs: KeyUsage, BasicConstraints, ExtKeyUsage, SubjectKeyIdentifier, AuthorityKeyIdentifier,
 * PrivateKeyUsagePeriod, DocumentTypeList and NameChange. The first extension with an OID wins.
 * A known extension with the wrong content fails with [X509Exception]; an unknown one is skipped.
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
            keyUsage = find(KEY_USAGE)?.let { wrap { keyUsage(it.value) } },
            basicConstraints = find(BASIC_CONSTRAINTS)?.let { wrap { basicConstraints(it.value) } },
            extendedKeyUsage = find(EXT_KEY_USAGE)?.let { wrap { extKeyUsage(it.value) } },
            subjectKeyIdentifier = find(SUBJECT_KEY_ID)?.let { wrap { DerReader(it.value).readOctetString().also { _ -> } } },
            authorityKeyIdentifier = find(AUTHORITY_KEY_ID)?.let { wrap { authorityKeyId(it.value) } },
            privateKeyUsagePeriod = find(PRIVATE_KEY_USAGE_PERIOD)?.let { wrap { keyUsagePeriod(it.value) } },
            documentTypeList = find(DOCUMENT_TYPE_LIST)?.let { wrap { documentTypeList(it.value) } },
            nameChange =
                find(NAME_CHANGE)?.let {
                    wrap {
                        DerReader(it.value).also { r ->
                            r.readNull()
                            r.finish()
                        }
                    }
                } != null,
        )
    }

    // A malformed known extension is a malformed certificate, reported with one failure kind.
    private fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: Asn1Exception) {
            throw X509Exception(X509Failure.INVALID_EXTENSION, e.offset)
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

    private fun keyUsagePeriod(value: ByteArray): PrivateKeyUsagePeriod {
        val reader = DerReader(value)
        val seq = reader.readSequence()
        reader.finish()
        // Both are [n] IMPLICIT GeneralizedTime, so the content is YYYYMMDDHHMMSSZ.
        val before = seq.readOptional(DerTag.context(0, constructed = false))
        val after = seq.readOptional(DerTag.context(1, constructed = false))
        seq.finish()
        return PrivateKeyUsagePeriod(
            before?.let { X509Parser.parseTime(it.value(), twoDigitYear = false, offset = it.offset) },
            after?.let { X509Parser.parseTime(it.value(), twoDigitYear = false, offset = it.offset) },
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
}
