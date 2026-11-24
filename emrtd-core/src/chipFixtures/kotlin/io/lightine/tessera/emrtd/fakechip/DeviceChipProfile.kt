package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate

/**
 * EF.CardAccess on the two recorded chips (device evidence, card TES-346): the chips have no
 * EF.CardAccess, yet under the eMRTD application SELECT 011C answers 6982, and with the master file
 * current SELECT 011C answers 9000 while the following READ BINARY answers 6986 (no current EF).
 * Goes in `leadingHandlers`, before secure messaging: the chips answer this before any session.
 */
internal class DeviceCardAccessQuirk : FakeCommandHandler {
    private var selectedAbsentFile = false

    override fun powerOff() {
        selectedAbsentFile = false
    }

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        val selectsCardAccess =
            command.cla == 0x00 && command.ins == ChipCommands.INS_SELECT && command.data.size == 2 &&
                ((command.data[0].toInt() and 0xFF) shl 8 or (command.data[1].toInt() and 0xFF)) == LdsIds.EF_CARD_ACCESS
        if (selectsCardAccess) {
            if (chip.currentApplication != null) return ResponseApdu(ByteArray(0), 0x6982)
            selectedAbsentFile = true
            return ResponseApdu(ByteArray(0), 0x9000)
        }
        if (selectedAbsentFile && command.cla == 0x00 && command.ins == ChipCommands.INS_READ_BINARY) {
            return ResponseApdu(ByteArray(0), 0x6986)
        }
        if (command.ins == ChipCommands.INS_SELECT) selectedAbsentFile = false
        return null
    }
}

/**
 * A chip shaped like the two recorded on devices (facts only; card TES-346, brief item 5), with
 * synthetic content from the project specimen and keys made at test time:
 *
 * - BAC only, no EF.CardAccess (see [DeviceCardAccessQuirk]); chip authentication by MSE:Set KAT
 *   (ECDH with 3DES, Part 11, 9.2.5), so active authentication is offered (DG15) but not needed.
 * - Sizes: DG14 exactly 366 bytes; DG15 229 (a 1536-bit RSA key); DG1 93 bytes (TD3) or 95
 *   (TD1); DG2 20 203. EF.COM and EF.SOD come out of the generator at their own sizes (EF.SOD is
 *   signed for real; it carries the root certificate too, to reach the recorded size range);
 *   [sizes] reports them. Each file falls into the same number of 223-byte reads as on the
 *   device, so the command count matches.
 * - Timings ([latency]): a protected READ BINARY of 0xDF bytes 31 to 43 ms, EXTERNAL AUTHENTICATE
 *   98 to 250 ms, MSE:Set KAT 427 to 454 ms (recorded). Every other command 12 ms: an assumption,
 *   the evidence does not time them.
 */
internal object DeviceChipProfile {
    /** The DG2 image filler that makes DG2 20 200 bytes. */
    const val DG2_IMAGE_BYTES = 20_175

    const val DG14_BYTES = 366

    /** The recorded sizes of the files a reader reads, for the test's report. */
    val recorded: Map<String, Int> = mapOf("EF.COM" to 29, "EF.SOD" to 2261, "DG1" to 93, "DG2" to 20_200, "DG14" to 366, "DG15" to 229)

    /** The specimen in TD1 form (an ID card): the same number and dates, so the same BAC key. */
    fun td1Lines(): List<String> {
        val result =
            MrzGenerator.generateTD1(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = "T22000129",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "ALEX MORGAN",
                nationality = "UTO",
                dateOfBirth = LocalDate(1990, 3, 15),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2034, 3, 14),
                optionalData1 = "",
                optionalData2 = "",
            )
        return (result as GenerationResult.Success).mrz
    }

    fun key(): ChipAccessKey.Mrz = FakeReadFlowChip.mrzKey()

    fun build(
        c: ChipCrypto,
        keys: TestKeys,
        td1: Boolean = false,
        startInApplication: Boolean = false,
        extraHandlers: List<FakeCommandHandler> = emptyList(),
        leadingHandlers: List<FakeCommandHandler> = emptyList(),
        keyMrzInformation: String = FakeReadFlowChip.mrzInformation,
        dg2ImageBytes: Int = DG2_IMAGE_BYTES,
    ): FlowChip =
        FakeReadFlowChip.build(
            c,
            keys,
            FlowAccess.BAC,
            caInApplication = true,
            caOffers = listOf(FakeCaOffer(dh = false, cipherCode = 1)),
            activeAuthentication = true,
            dg14Size = DG14_BYTES,
            dg2Bytes = dg2ImageBytes,
            dg1Lines = if (td1) td1Lines() else FakeReadFlowChip.lines,
            startInApplication = startInApplication,
            leadingHandlers = listOf(DeviceCardAccessQuirk()) + leadingHandlers,
            extraHandlers = extraHandlers,
            keyMrzInformation = keyMrzInformation,
            dg15Override = dg15(keys),
            extraSodCertificates = listOf(Pki.rsa(keys).csca),
        )

    private var aaKey: TestKeyPair? = null

    /**
     * DG15 with a 1536-bit RSA key: 229 bytes, as recorded. The chip passes chip authentication, so
     * the reader reads DG15 but runs no active authentication with it.
     */
    fun dg15(keys: TestKeys): ByteArray {
        val key = aaKey ?: keys.signer.generate(TestKeySpec.Rsa(1536)).also { aaKey = it }
        return TlvWriter.encode(0x6F, FakePki.publicKeyInfo(key.publicKey))
    }

    /** The sizes of the files the reader reads from [flow], by name. */
    fun sizes(flow: FlowChip): Map<String, Int> {
        val app = flow.chip.files.applications.getValue(LdsIds.LDS1_AID.toList())
        return mapOf(
            "EF.COM" to app.getValue(LdsIds.EF_COM).size,
            "EF.SOD" to app.getValue(LdsIds.EF_SOD).size,
            "DG1" to app.getValue(0x0101).size,
            "DG2" to app.getValue(0x0102).size,
            "DG14" to app.getValue(0x010E).size,
            "DG15" to app.getValue(0x010F).size,
        )
    }

    /** The recorded command timings, spread over their range by the command's index (no randomness). */
    val latency: (ChipCommand) -> Long = { command ->
        val spread = command.index * 7
        when {
            command.ins == ChipCommands.INS_READ_BINARY && command.secured -> 31L + spread % 13
            command.ins == 0x82 -> 98L + spread % 153
            command.ins == 0x22 && command.apdu?.p2 == 0xA6 -> 427L + spread % 28
            else -> 12L
        }
    }
}
