package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/**
 * Reads EF.DIR (FID 2F00, Part 10, 3.11.2): application templates `61` each holding an AID (`4F`).
 * The file is not signed; a secured copy is the EFDIRInfo in EF.CardSecurity.
 */
internal object EfDirParser {
    private const val TEMPLATE_TAG = 0x61
    private const val AID_TAG = 0x4F
    private const val MIN_AID = 5
    private const val MAX_AID = 16

    private val lds1 = byteArrayOf(0xA0.toByte(), 0, 0, 2, 0x47, 0x10, 0x01)
    private val travel = byteArrayOf(0xA0.toByte(), 0, 0, 2, 0x47, 0x20, 0x01)
    private val visa = byteArrayOf(0xA0.toByte(), 0, 0, 2, 0x47, 0x20, 0x02)
    private val additional = byteArrayOf(0xA0.toByte(), 0, 0, 2, 0x47, 0x20, 0x03)

    /** The AIDs listed, in file order. Other top-level objects are ignored. */
    fun parse(bytes: ByteArray): List<EfDirEntry> =
        TlvParser()
            .parseAll(bytes)
            .filter { it.tag == TEMPLATE_TAG }
            .mapNotNull { template ->
                val aid = template.child(AID_TAG)?.value() ?: return@mapNotNull null
                if (aid.size !in MIN_AID..MAX_AID) fail(SecurityInfoFailure.INVALID_VALUE, template.offset)
                EfDirEntry(aid, applicationOf(aid))
            }

    private fun applicationOf(aid: ByteArray): LdsApplication? =
        when {
            aid.contentEquals(lds1) -> LdsApplication.LDS1_EMRTD
            aid.contentEquals(travel) -> LdsApplication.TRAVEL_RECORDS
            aid.contentEquals(visa) -> LdsApplication.VISA_RECORDS
            aid.contentEquals(additional) -> LdsApplication.ADDITIONAL_BIOMETRICS
            else -> null
        }
}
