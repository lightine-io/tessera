package io.lightine.tessera.emrtd.internal.cms

import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.x509.AlgorithmIdentifier
import io.lightine.tessera.emrtd.internal.x509.readAlgorithmIdentifier

internal class LdsDataGroupHash(
    val number: Int,
    val hash: ByteArray,
)

/**
 * The LDSSecurityObject (Doc 9303 Part 10, 4.6.2.1 and 4.6.2.3 for V1, Appendix D for V0): the hash
 * algorithm and one hash per data group, in the order stored. The version, the count of hashes and
 * the presence of [ldsVersion] are reported as they are: V0 has no version info, V1 has it, and the
 * parser does not enforce either.
 */
internal class LdsSecurityObject(
    val version: Int,
    val hashAlgorithm: AlgorithmIdentifier,
    val dataGroupHashes: List<LdsDataGroupHash>,
    val ldsVersion: String?,
    val unicodeVersion: String?,
) {
    companion object {
        /** Reads the encapsulated content. BER lengths are accepted (see [BerNormalizer]). */
        fun parse(content: ByteArray): LdsSecurityObject {
            val top = DerReader(BerNormalizer.normalize(content).der)
            val seq = top.readSequence()
            top.finish()
            val version = seq.readInt()
            val hashAlgorithm = seq.readAlgorithmIdentifier()
            val groups = seq.readSequence()
            val hashes = ArrayList<LdsDataGroupHash>()
            while (groups.hasMore) {
                val g = groups.readSequence()
                val number = g.readInt()
                val hash = CmsParser.readOctetStringAny(g)
                g.finish()
                hashes.add(LdsDataGroupHash(number, hash))
            }
            var ldsVersion: String? = null
            var unicodeVersion: String? = null
            if (seq.hasMore) {
                val info = seq.readSequence()
                ldsVersion = info.read(DerTag.universal(DerTag.PRINTABLE_STRING)).value().decodeToString()
                unicodeVersion = info.read(DerTag.universal(DerTag.PRINTABLE_STRING)).value().decodeToString()
                info.finish()
            }
            seq.finish()
            return LdsSecurityObject(version, hashAlgorithm, hashes, ldsVersion, unicodeVersion)
        }
    }
}
