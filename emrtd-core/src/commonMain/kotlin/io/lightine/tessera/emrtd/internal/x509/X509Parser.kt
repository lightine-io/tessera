package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.Asn1Failure
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag

/** Reads an `AlgorithmIdentifier`; shared by certificates and CMS. */
internal fun DerReader.readAlgorithmIdentifier(): AlgorithmIdentifier {
    val seq = readSequence()
    val oid = seq.readOid()
    val parameters = if (seq.hasMore) seq.read().encoded() else null
    seq.finish()
    return AlgorithmIdentifier(oid, parameters)
}

/**
 * Reads one X.509 certificate (RFC 5280) as Doc 9303 Part 12, section 7.1, profiles it. Parsing only:
 * the signature is not checked, the profile is not enforced, and an extension this parser does not
 * know is kept as it is. A critical extension that is unknown is listed in
 * [X509Certificate.unrecognizedCriticalExtensions], never a failure.
 *
 * DER is required (certificates are signed over their bytes). Hostile bytes fail with
 * [Asn1Exception] or [X509Exception], never an index error.
 */
internal object X509Parser {
    fun parse(der: ByteArray): X509Certificate {
        val top = DerReader(der)
        val certElement = top.read(DerTag.universal(DerTag.SEQUENCE, constructed = true))
        top.finish()
        val cert = certElement.contents()

        val tbsElement = cert.read(DerTag.universal(DerTag.SEQUENCE, constructed = true))
        val signatureAlgorithm = cert.readAlgorithmIdentifier()
        val signature = cert.readBitString()
        cert.finish()

        val tbs = tbsElement.contents()
        val versionReader = tbs.readOptional(DerTag.context(0, constructed = true))?.contents()
        val version =
            if (versionReader == null) {
                1
            } else {
                val v = versionReader.readInt()
                versionReader.finish()
                if (v !in 0..2) throw X509Exception(X509Failure.INVALID_VERSION, tbsElement.offset)
                v + 1
            }
        val serial = tbs.readInteger()
        val tbsSignatureAlgorithm = tbs.readAlgorithmIdentifier()
        val issuer = readName(tbs)
        val validity = tbs.readSequence()
        val notBefore = readTime(validity)
        val notAfter = readTime(validity)
        validity.finish()
        val subject = readName(tbs)

        val spkiElement = tbs.read(DerTag.universal(DerTag.SEQUENCE, constructed = true))
        val spki = spkiElement.contents()
        val publicKeyAlgorithm = spki.readAlgorithmIdentifier()
        val publicKey = spki.readBitString()
        spki.finish()

        var uniqueIds = false
        if (tbs.readOptional(DerTag.context(1, constructed = false)) != null) uniqueIds = true
        if (tbs.readOptional(DerTag.context(2, constructed = false)) != null) uniqueIds = true
        val extensionsReader = tbs.readOptional(DerTag.context(3, constructed = true))?.contents()
        val extensions = if (extensionsReader == null) emptyList() else readExtensions(extensionsReader)
        tbs.finish()

        return X509Certificate(
            encoded = der,
            tbsEncoded = tbsElement.encoded(),
            version = version,
            serialNumber = serial,
            tbsSignatureAlgorithm = tbsSignatureAlgorithm,
            issuer = issuer,
            notBefore = notBefore,
            notAfter = notAfter,
            subject = subject,
            subjectPublicKeyInfo = spkiElement.encoded(),
            publicKeyAlgorithm = publicKeyAlgorithm,
            publicKey = publicKey,
            hasUniqueIds = uniqueIds,
            extensions = extensions,
            decoded = X509Extensions.decode(extensions),
            signatureAlgorithm = signatureAlgorithm,
            signature = signature,
        )
    }

    private fun readExtensions(seqReader: DerReader): List<X509Extension> {
        val list = seqReader.readSequence()
        seqReader.finish()
        val out = ArrayList<X509Extension>()
        while (list.hasMore) {
            val ext = list.readSequence()
            val oid = ext.readOid()
            val critical = if (ext.peekTag() == DerTag.universal(DerTag.BOOLEAN)) ext.readBoolean() else false
            out.add(X509Extension(oid, critical, ext.readOctetString()))
            ext.finish()
        }
        return out
    }

    private fun readName(parent: DerReader): DistinguishedName {
        val element = parent.read(DerTag.universal(DerTag.SEQUENCE, constructed = true))
        return DistinguishedName(element.encoded(), readNameAttributes(element.contents()))
    }

    /** The attributes of a name from the RDN SETs [rdns] is positioned at: the content of the Name SEQUENCE, or of its `[4] IMPLICIT` form in a GeneralName. */
    internal fun readNameAttributes(rdns: DerReader): List<NameAttribute> {
        val attributes = ArrayList<NameAttribute>()
        while (rdns.hasMore) {
            val set = rdns.readSet()
            while (set.hasMore) {
                val atv = set.readSequence()
                val oid = atv.readOid()
                val value = atv.read()
                atv.finish()
                attributes.add(NameAttribute(oid, stringValue(value.tag.number, value.tag.tagClass, value.value()), value.tag.number))
            }
        }
        return attributes
    }

    private fun stringValue(
        tagNumber: Int,
        tagClass: Int,
        bytes: ByteArray,
    ): String =
        if (tagClass != DerTag.UNIVERSAL) {
            hex(bytes)
        } else {
            when (tagNumber) {
                DerTag.UTF8_STRING -> {
                    bytes.decodeToString()
                }

                DerTag.PRINTABLE_STRING, DerTag.IA5_STRING, 20, 26, 18 -> {
                    CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
                }

                30 -> {
                    CharArray(bytes.size / 2) {
                        (((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF)).toChar()
                    }.concatToString()
                }

                else -> {
                    hex(bytes)
                }
            }
        }

    private fun readTime(reader: DerReader): CertTime {
        val e = reader.read()
        if (e.tag.tagClass != DerTag.UNIVERSAL || e.tag.constructed) throw Asn1Exception(Asn1Failure.UNEXPECTED_TAG, e.offset)
        return when (e.tag.number) {
            DerTag.UTC_TIME -> parseTime(e.value(), twoDigitYear = true, offset = e.offset)
            DerTag.GENERALIZED_TIME -> parseTime(e.value(), twoDigitYear = false, offset = e.offset)
            else -> throw Asn1Exception(Asn1Failure.UNEXPECTED_TAG, e.offset)
        }
    }

    /** `YYMMDDHHMMSSZ` (RFC 5280: 50 to 99 is 19xx, 00 to 49 is 20xx) or `YYYYMMDDHHMMSSZ`. */
    internal fun parseTime(
        bytes: ByteArray,
        twoDigitYear: Boolean,
        offset: Int,
    ): CertTime {
        val yearDigits = if (twoDigitYear) 2 else 4
        if (bytes.size != yearDigits + 11 || bytes.last() != 'Z'.code.toByte()) throw X509Exception(X509Failure.INVALID_TIME, offset)

        fun num(
            from: Int,
            n: Int,
        ): Int {
            var r = 0
            for (i in from until from + n) {
                val d = bytes[i] - '0'.code.toByte()
                if (d !in 0..9) throw X509Exception(X509Failure.INVALID_TIME, offset)
                r = r * 10 + d
            }
            return r
        }
        var p = 0
        var year = num(p, yearDigits)
        p += yearDigits
        if (twoDigitYear) year += if (year >= 50) 1900 else 2000
        val month = num(p, 2)
        val day = num(p + 2, 2)
        val hour = num(p + 4, 2)
        val minute = num(p + 6, 2)
        val second = num(p + 8, 2)
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) {
            throw X509Exception(X509Failure.INVALID_TIME, offset)
        }
        return CertTime(year, month, day, hour, minute, second)
    }

    internal fun hex(bytes: ByteArray): String =
        buildString {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v shr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
}
