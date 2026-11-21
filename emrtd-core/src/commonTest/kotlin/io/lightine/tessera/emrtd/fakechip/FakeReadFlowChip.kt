package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.emrtd.trust.TrustAnchorParse
import io.lightine.tessera.types.errors.NfcTagLost

/** How a fake chip for the read flow lets a reader in (Part 11, 4.1). */
internal enum class FlowAccess {
    /** No access control: a plain chip. */
    PLAIN,

    /** BAC only; no EF.CardAccess. */
    BAC,

    /** PACE (EF.CardAccess) and BAC. */
    PACE_AND_BAC,

    /** PACE only. */
    PACE_ONLY,
}

/** A fake chip set up for one read-flow test, and what the test needs to check it. */
internal class FlowChip(
    val chip: FakeChip,
    val pki: Pki,
    val dataGroups: Map<Int, ByteArray>,
    val sm: FakeSm?,
    val pace: FakePace?,
    val bac: FakeBac?,
    val ca: FakeCa?,
    val aa: FakeAa?,
) {
    val anchor: TrustAnchor get() = (TrustAnchor.fromDer(pki.csca) as TrustAnchorParse.Parsed).anchor
}

/** Throws [NfcTagLost] at the first READ BINARY of [fileId] past offset 0: the document left the field mid-file. */
internal class FakeTagLoss(
    private val fileId: Int,
) : FakeCommandHandler {
    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        val offset = (command.p1 shl 8) or command.p2
        if (command.ins == ChipCommands.INS_READ_BINARY && chip.currentEf == fileId && offset > 0) {
            throw ApduTransportException(NfcTagLost)
        }
        return null
    }
}

/** Answers SELECT of [fileId] with 6982, as a chip does for DG3 and DG4 without terminal authentication. */
internal class FakeProtectedFile(
    private val fileId: Int,
) : FakeCommandHandler {
    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (command.ins != ChipCommands.INS_SELECT || command.data.size != 2) return null
        val fid = ((command.data[0].toInt() and 0xFF) shl 8) or (command.data[1].toInt() and 0xFF)
        return if (fid == fileId) ResponseApdu(ByteArray(0), 0x6982) else null
    }
}

/**
 * Misbehaves on the first SELECT of the application after a chip-authentication key switch:
 * [bareStatus] answers it with that bare status word (no secure messaging); otherwise the answer's
 * checksum is corrupted. Goes in `leadingHandlers`, before secure messaging.
 */
internal class FakeAfterKeySwitch(
    private val bareStatus: Int?,
) : FakeCommandHandler {
    var flow: FlowChip? = null
    private var fired = false

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        val f = flow ?: return null
        val selectAid = command.ins == ChipCommands.INS_SELECT && command.p1 == ChipCommands.SELECT_BY_AID && command.cla and 0x0C == 0x0C
        if (fired || !selectAid || f.ca?.restarts != 1) return null
        fired = true
        if (bareStatus != null) return ResponseApdu(ByteArray(0), bareStatus)
        f.sm!!.corruptNextResponseMac = true
        return null
    }
}

/**
 * Builds fake chips for the end-to-end read tests from the project specimen (SPECIMEN, ALEX MORGAN,
 * Utopia) and keys made at test time: EF.COM, DG1, a synthetic DG2, optional DG14 and DG15, an
 * EF.SOD signed by a fake Utopia signer, and in the master file EF.CardAccess and EF.CardSecurity
 * as the configuration needs. Nothing here comes from a real document.
 */
internal object FakeReadFlowChip {
    const val ID_PACE = "0.4.0.127.0.7.2.2.4"
    const val ECDH_GM_AES128 = "$ID_PACE.2.2"
    const val ECDH_IM_AES128 = "$ID_PACE.4.2"
    const val ECDH_CAM_AES128 = "$ID_PACE.6.2"
    const val DH_GM_3DES = "$ID_PACE.1.1"

    val lines: List<String> = FakeChipData.specimenTd3Lines()
    private val line2 = lines[1]
    val mrzInformation: String = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    fun mrzKey(): ChipAccessKey.Mrz =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), line2[27])

    /** A key with a wrong date of birth: the chip does not match it. */
    fun wrongMrzKey(): ChipAccessKey.Mrz =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], "000101", '1', line2.substring(21, 27), line2[27])

    /** A synthetic DG2: one biometric template holding [imageBytes] filler bytes (no image). */
    fun dg2(imageBytes: Int = 900): ByteArray =
        TlvWriter.constructed(
            0x75,
            TlvWriter.constructed(
                0x7F61,
                TlvWriter.encode(0x02, byteArrayOf(1)),
                TlvWriter.constructed(
                    0x7F60,
                    TlvWriter.constructed(0xA1, TlvWriter.encode(0x80, byteArrayOf(1, 1))),
                    TlvWriter.encode(0x5F2E, ByteArray(imageBytes) { (it * 7).toByte() }),
                ),
            ),
        )

    fun paceInfo(
        oid: String,
        parameterId: Int,
    ): ByteArray = DerWriter.sequence(DerWriter.oid(oid), DerWriter.integer(2), DerWriter.integer(parameterId.toLong()))

    fun build(
        c: ChipCrypto,
        keys: TestKeys,
        access: FlowAccess,
        paceSuites: List<FakePaceSuite> = listOf(FakePaceSuite(ECDH_GM_AES128, 13)),
        cardAccessSuites: List<FakePaceSuite> = paceSuites,
        cam: FakePaceCam? = null,
        caInApplication: Boolean = false,
        caInMasterFile: Boolean = false,
        activeAuthentication: Boolean = false,
        can: String? = null,
        blank: Boolean = false,
        tagLoss: FakeTagLoss? = null,
        extraApplicationFiles: Map<Int, ByteArray> = emptyMap(),
        extraHandlers: List<FakeCommandHandler> = emptyList(),
        /** PACE entries for the signed copy in DG14 (DG14 is written even without chip authentication). */
        signedPaceSuites: List<FakePaceSuite>? = null,
        /** The bytes of EF.CardAccess in place of the built ones (also on a BAC-only chip). */
        cardAccessOverride: ByteArray? = null,
        /** Handlers before secure messaging: they see protected commands and can answer bare. */
        leadingHandlers: List<FakeCommandHandler> = emptyList(),
        /** The signer and root in place of the default Utopia pair. */
        pki: Pki = Pki.rsa(keys),
        /** The certificates EF.SOD carries; empty for a file that names no signer of its own. */
        sodCertificates: List<ByteArray>? = null,
    ): FlowChip {
        val groups = linkedMapOf(1 to FakeChipData.dg1(lines), 2 to dg2())
        val caKey = if (caInApplication || caInMasterFile) FakeCaKey.generate(c, 13) else null
        val caOffers = listOf(FakeCaOffer(dh = false, cipherCode = 2))
        if (caInApplication || signedPaceSuites != null) {
            val entries = ArrayList<ByteArray>()
            if (caInApplication) {
                entries +=
                    TlvParser().parseSingle(FakeCa.securityInfos(caOffers, listOf(caKey!!))).children().map { it.encoded() }
            }
            signedPaceSuites?.forEach { entries += paceInfo(it.oid, it.parameterId) }
            groups[14] = TlvWriter.encode(0x6E, DerWriter.set(*entries.toTypedArray()))
        }
        val aa = if (activeAuthentication) FakeAa(FakeAaKey.rsa(c), c, HashAlgorithm.SHA256) else null
        if (aa != null) groups[15] = aa.dg15
        val efCom = FakeChipData.efCom(groups.keys.map { tagOf(it) })
        val app = HashMap<Int, ByteArray>()
        app[LdsIds.EF_COM] = efCom
        app[LdsIds.EF_SOD] = if (sodCertificates == null) pki.sod(groups) else pki.sod(groups, certificates = sodCertificates)
        groups.forEach { (n, bytes) -> app[0x0100 + n] = bytes }
        app.putAll(extraApplicationFiles)

        val mf = HashMap<Int, ByteArray>()
        val usesPace = access == FlowAccess.PACE_AND_BAC || access == FlowAccess.PACE_ONLY
        val paceInfos = cardAccessSuites.map { paceInfo(it.oid, it.parameterId) }
        if (usesPace) mf[LdsIds.EF_CARD_ACCESS] = DerWriter.set(*paceInfos.toTypedArray())
        cardAccessOverride?.let { mf[LdsIds.EF_CARD_ACCESS] = it }
        val securityInfos = ArrayList<ByteArray>(paceInfos)
        if (cam != null) {
            val id = paceSuites.first().parameterId
            securityInfos += cam.chipAuthenticationPublicKeyInfo(StandardDomainParameterValues.forId(id) as EcDomainParameters, id)
        }
        if (caInMasterFile) {
            val ca = FakeCa.securityInfos(caOffers, listOf(caKey!!))
            securityInfos += TlvParser().parseSingle(ca).children().map { it.encoded() }
        }
        if (usesPace && (cam != null || caInMasterFile)) mf[0x011D] = pki.cardSecurity(DerWriter.set(*securityInfos.toTypedArray()))

        val files =
            FakeFileSystem(
                masterFiles = mf,
                applications = if (blank) emptyMap() else mapOf(LdsIds.LDS1_AID.toList() to app),
            )
        val sm = if (access == FlowAccess.PLAIN) null else FakeSm(c, openFiles = setOf(LdsIds.EF_CARD_ACCESS, 0x2F01))
        val pace =
            if (usesPace) {
                FakePace(
                    c,
                    sm!!,
                    paceSuites.toSet(),
                    listOfNotNull(FakePaceGm(), FakePaceIm(), cam),
                    mrzInformation = mrzInformation,
                    can = can,
                )
            } else {
                null
            }
        val bac = if (access == FlowAccess.BAC || access == FlowAccess.PACE_AND_BAC) FakeBac(mrzInformation, sm!!, c) else null
        val ca = if (caKey != null && sm != null) FakeCa(sm, c, listOf(caKey), caOffers) else null
        // The chip-authentication handler goes before PACE's: both answer GENERAL AUTHENTICATE, and only
        // the CA handler checks that its own MSE came first.
        val handlers = leadingHandlers + listOfNotNull(sm, tagLoss) + extraHandlers + listOfNotNull(ca, pace, bac, aa)
        val flow = FlowChip(FakeChip(files, handlers = handlers), pki, groups, sm, pace, bac, ca, aa)
        leadingHandlers.filterIsInstance<FakeAfterKeySwitch>().forEach { it.flow = flow }
        return flow
    }

    private fun tagOf(dataGroup: Int): Int =
        when (dataGroup) {
            1 -> 0x61
            2 -> 0x75
            14 -> 0x6E
            15 -> 0x6F
            else -> 0x60 + dataGroup
        }
}
