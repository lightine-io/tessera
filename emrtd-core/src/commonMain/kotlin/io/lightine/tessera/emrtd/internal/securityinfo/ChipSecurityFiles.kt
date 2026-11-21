package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/**
 * The files that carry SecurityInfos (Doc 9303 Part 10, 3.11 and 3.8; Part 11, 9.2.11).
 *
 * What these read is exactly what the file says. EF.CardAccess is not signed. EF.DG14 and
 * EF.CardSecurity are protected by passive authentication, and this module does not check that:
 * the signature and the hashes are separate checks. Nothing here says a SecurityInfo is genuine.
 */
internal object ChipSecurityFiles {
    /** The tag of EF.DG14 (`6E`). */
    const val DG14_TAG = 0x6E

    /** EF.CardAccess (FID 011C): the DER SecurityInfos set, with no wrapper (Part 10, 3.11.3). */
    fun parseCardAccess(bytes: ByteArray): SecurityInfos = SecurityInfoParser.parse(bytes)

    /** EF.DG14 (tag `6E` around the DER SecurityInfos set). */
    fun parseDg14(bytes: ByteArray): SecurityInfos =
        guardedParse {
            val dg = TlvParser().parseSingle(bytes)
            if (dg.tag != DG14_TAG) fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE)
            SecurityInfoParser.parse(dg.value())
        }

    /**
     * EF.CardSecurity (FID 011D): CMS SignedData whose signed content has the type id-SecurityObject
     * and holds the DER SecurityInfos set (Part 10, 3.11.4). Only the signed content is read. The
     * certificates and the signature are not touched here, and the content is not verified.
     */
    fun parseCardSecurityContent(bytes: ByteArray): SecurityInfos =
        guardedParse {
            val top = DerReader(bytes)
            val contentInfo = top.readSequence()
            top.finish()
            if (contentInfo.readOid() != SecurityOids.CMS_SIGNED_DATA) fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE)
            val signedData = contentInfo.readExplicit(0).readSequence()
            signedData.readInteger() // CMSVersion
            signedData.readSet() // digestAlgorithms
            val encap = signedData.readSequence()
            if (encap.readOid() != SecurityOids.ID_SECURITY_OBJECT) fail(SecurityInfoFailure.WRONG_CONTENT_TYPE)
            val eContent = encap.readExplicit(0).readOctetString()
            encap.finish()
            SecurityInfoParser.parse(eContent)
        }
}
