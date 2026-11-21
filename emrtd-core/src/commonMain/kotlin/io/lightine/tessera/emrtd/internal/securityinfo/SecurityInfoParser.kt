package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.tlv.TlvException

/** Runs [block], turning DER and TLV failures into a [SecurityInfoException]. */
internal inline fun <T> guardedParse(block: () -> T): T =
    try {
        block()
    } catch (e: Asn1Exception) {
        throw SecurityInfoException(SecurityInfoFailure.MALFORMED_DER, e.offset)
    } catch (e: TlvException) {
        throw SecurityInfoException(SecurityInfoFailure.MALFORMED_TLV, e.offset)
    }

internal fun fail(
    failure: SecurityInfoFailure,
    offset: Int = 0,
): Nothing = throw SecurityInfoException(failure, offset)

/**
 * Reads a DER `SecurityInfos ::= SET OF SecurityInfo` (Doc 9303 Part 11, 9.2) from a chip.
 *
 * The set must be well-formed DER; that is structure, and failing it throws
 * [SecurityInfoException]. Each entry is then read on its own: an entry with an unknown protocol is
 * kept as [UnknownSecurityInfo] and otherwise ignored; an entry with a known protocol that is
 * malformed (wrong version, wrong shape) goes to [SecurityInfos.rejected] and is not used. One bad
 * entry never hides the good ones.
 */
internal object SecurityInfoParser {
    fun parse(bytes: ByteArray): SecurityInfos =
        guardedParse {
            val top = DerReader(bytes)
            val set = top.readSet()
            top.finish()
            parseSetContents(set)
        }

    internal fun parseSetContents(set: DerReader): SecurityInfos {
        val entries = ArrayList<SecurityInfo>()
        val rejected = ArrayList<RejectedSecurityInfo>()
        while (set.hasMore) {
            val element = set.read()
            var protocol: String? = null
            try {
                if (element.tag != DerTag.universal(DerTag.SEQUENCE, constructed = true)) {
                    fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE, element.offset)
                }
                val seq = element.contents()
                protocol = seq.readOid()
                entries.add(parseEntry(protocol, seq))
            } catch (e: Asn1Exception) {
                rejected.add(RejectedSecurityInfo(protocol, SecurityInfoFailure.MALFORMED_DER, e.offset))
            } catch (e: SecurityInfoException) {
                rejected.add(RejectedSecurityInfo(protocol, e.failure, e.offset))
            } catch (e: TlvException) {
                rejected.add(RejectedSecurityInfo(protocol, SecurityInfoFailure.MALFORMED_TLV, e.offset))
            }
        }
        return SecurityInfos(entries, rejected)
    }

    private fun parseEntry(
        protocol: String,
        seq: DerReader,
    ): SecurityInfo {
        SecurityOids.paceProtocol(protocol)?.let { return parsePace(protocol, it, seq) }
        SecurityOids.paceDomainParameterType(protocol)?.let { return parsePaceDomainParameters(protocol, it, seq) }
        SecurityOids.chipAuthentication(protocol)?.let { return parseChipAuthentication(protocol, it, seq) }
        SecurityOids.publicKeyAgreement(protocol)?.let { return parseChipAuthenticationKey(protocol, it, seq) }
        return when {
            protocol == SecurityOids.ID_AA -> parseActiveAuthentication(protocol, seq)
            SecurityOids.isTerminalAuthentication(protocol) -> TerminalAuthenticationInfo(protocol)
            protocol == SecurityOids.ID_EFDIR -> parseEfDirInfo(protocol, seq)
            else -> UnknownSecurityInfo(protocol)
        }
    }

    private fun readVersion(
        seq: DerReader,
        expected: Int,
    ): Int {
        val version = seq.readInt()
        if (version != expected) fail(SecurityInfoFailure.UNSUPPORTED_VERSION)
        return version
    }

    private fun readOptionalId(seq: DerReader): Int? {
        if (seq.peekTag() != DerTag.universal(DerTag.INTEGER)) return null
        val id = seq.readInt()
        if (id < 0) fail(SecurityInfoFailure.INVALID_VALUE)
        return id
    }

    private fun parsePace(
        protocol: String,
        suite: SecurityOids.PaceProtocol,
        seq: DerReader,
    ): PaceInfo {
        val version = readVersion(seq, PACE_VERSION)
        val parameterId = readOptionalId(seq)
        seq.finish()
        if (suite.mapping == PaceMapping.CHIP_AUTHENTICATION && parameterId == null) {
            // With chip authentication mapping the id also names the CA key: it is required (9.2.1).
            fail(SecurityInfoFailure.INVALID_VALUE)
        }
        return PaceInfo(
            protocol,
            suite.mapping,
            suite.agreement,
            suite.cipher,
            version,
            parameterId,
            StandardizedParameters.kindOf(parameterId, suite.agreement),
        )
    }

    private fun parsePaceDomainParameters(
        protocol: String,
        group: SecurityOids.PaceGroup,
        seq: DerReader,
    ): PaceDomainParameterInfo {
        val parameters = DomainParameterReader.readAlgorithmIdentifier(seq)
        val parameterId = readOptionalId(seq)
        seq.finish()
        return PaceDomainParameterInfo(protocol, group.agreement, group.mapping, parameters, parameterId)
    }

    private fun parseChipAuthentication(
        protocol: String,
        suite: Pair<KeyAgreement, SessionCipher>,
        seq: DerReader,
    ): ChipAuthenticationInfo {
        val version = readVersion(seq, CA_VERSION)
        val keyId = readOptionalId(seq)
        seq.finish()
        return ChipAuthenticationInfo(protocol, suite.first, suite.second, version, keyId)
    }

    private fun parseChipAuthenticationKey(
        protocol: String,
        agreement: KeyAgreement,
        seq: DerReader,
    ): ChipAuthenticationPublicKeyInfo {
        val key = DomainParameterReader.readSubjectPublicKey(seq)
        val keyId = readOptionalId(seq)
        seq.finish()
        return ChipAuthenticationPublicKeyInfo(protocol, agreement, key, keyId)
    }

    private fun parseActiveAuthentication(
        protocol: String,
        seq: DerReader,
    ): ActiveAuthenticationInfo {
        val version = readVersion(seq, AA_VERSION)
        val signatureAlgorithm = seq.readOid()
        seq.finish()
        return ActiveAuthenticationInfo(protocol, version, signatureAlgorithm)
    }

    private fun parseEfDirInfo(
        protocol: String,
        seq: DerReader,
    ): EfDirInfo {
        val efDir = seq.readOctetString()
        seq.finish()
        val applications = guardedParse { EfDirParser.parse(efDir) }
        return EfDirInfo(protocol, applications, efDir)
    }

    const val PACE_VERSION = 2
    const val CA_VERSION = 1
    const val AA_VERSION = 1
}

/** Reads AlgorithmIdentifier and SubjectPublicKeyInfo structures for the SecurityInfos (Part 11, 9.1 and 9.5). */
internal object DomainParameterReader {
    /** Reads one AlgorithmIdentifier element from [reader]. */
    fun readAlgorithmIdentifier(reader: DerReader): DomainParameters = readAlgorithm(reader.readSequence())

    private fun readAlgorithm(alg: DerReader): DomainParameters {
        val oid = alg.readOid()
        return when (oid) {
            SecurityOids.STANDARDIZED_DOMAIN_PARAMETERS -> {
                val id = alg.readInt()
                alg.finish()
                if (id < 0) fail(SecurityInfoFailure.INVALID_VALUE)
                DomainParameters.Standardized(id)
            }

            SecurityOids.EC_PUBLIC_KEY -> {
                // Named curves and implicit parameters are not allowed (9.5.2): not read.
                if (alg.peekTag() == DerTag.universal(DerTag.SEQUENCE, constructed = true)) {
                    readEcParameters(alg.readSequence()) ?: DomainParameters.Unsupported(oid)
                } else {
                    DomainParameters.Unsupported(oid)
                }
            }

            SecurityOids.DH_PUBLIC_NUMBER -> {
                val dh = alg.readSequence()
                val p = dh.readUnsignedInteger()
                val g = dh.readUnsignedInteger()
                val q = dh.readUnsignedInteger()
                DomainParameters.ExplicitDh(DhFormat.X942, p, g, q)
            }

            SecurityOids.DH_KEY_AGREEMENT -> {
                val dh = alg.readSequence()
                val p = dh.readUnsignedInteger()
                val g = dh.readUnsignedInteger()
                DomainParameters.ExplicitDh(DhFormat.PKCS3, p, g, null)
            }

            else -> {
                DomainParameters.Unsupported(oid)
            }
        }
    }

    /** ECParameters over a prime field; `null` for a field this module does not read. */
    private fun readEcParameters(ec: DerReader): DomainParameters? {
        if (ec.readInt() != 1) fail(SecurityInfoFailure.UNSUPPORTED_VERSION)
        val field = ec.readSequence()
        if (field.readOid() != SecurityOids.PRIME_FIELD) return null
        val p = field.readUnsignedInteger()
        val curve = ec.readSequence()
        val a = curve.readOctetString()
        val b = curve.readOctetString()
        val generator = ec.readOctetString()
        val order = ec.readUnsignedInteger()
        val cofactor = if (ec.peekTag() == DerTag.universal(DerTag.INTEGER)) ec.readUnsignedInteger() else null
        return DomainParameters.ExplicitEc(p, a, b, generator, order, cofactor)
    }

    /** Reads one SubjectPublicKeyInfo element from [reader]. */
    fun readSubjectPublicKey(reader: DerReader): SubjectPublicKey {
        val spki = reader.readSequence()
        val parameters = readAlgorithmIdentifier(spki)
        val bits = spki.readBitString()
        spki.finish()
        if (bits.unusedBits != 0 || bits.bytes.isEmpty()) fail(SecurityInfoFailure.INVALID_VALUE)
        val publicValue =
            when (parameters) {
                is DomainParameters.ExplicitDh -> DerReader(bits.bytes).let { r -> r.readUnsignedInteger().also { r.finish() } }
                is DomainParameters.Standardized -> keyBytesFor(parameters, bits.bytes)
                else -> bits.bytes
            }
        return SubjectPublicKey(parameters, publicValue)
    }

    /**
     * For a standardized set the key encoding follows the set's type: an INTEGER for the DH sets
     * (0 to 2), the point as encoded for the EC sets.
     */
    private fun keyBytesFor(
        parameters: DomainParameters.Standardized,
        bits: ByteArray,
    ): ByteArray =
        if (StandardizedParameters.byId(parameters.id)?.agreement == KeyAgreement.DH) {
            DerReader(bits).let { r -> r.readUnsignedInteger().also { r.finish() } }
        } else {
            bits
        }
}
