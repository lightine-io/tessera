package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipMrzComparison
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipSignedCopy
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.emrtd.sim.SimDrive.assertHashPassed
import io.lightine.tessera.emrtd.sim.SimDrive.driveFlow
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogue group M (LDS content), TES-346, through whole reads. The SDK parses the files into
 * typed views and reports what is stored, never a verdict: `[DIFF]` rows assert the fact.
 */
class CatalogueGroupMTest {
    private suspend fun readOf(flow: FlowChip): ChipRead = driveFlow(flow).complete

    private fun dg1Of(text: String): ByteArray = TlvWriter.constructed(0x61, TlvWriter.encode(0x5F1F, text.encodeToByteArray()))

    // --- M-02: EF.COM versions -----------------------------------------------------------------------

    @Test
    fun m02_efComWithAWrongLdsVersionOrNoUnicodeVersion_isReportedAsStoredAndTheGroupsStillRead() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_A_04: "V3.0" is reported as stored.
                val wrongVersion =
                    TlvWriter.constructed(
                        0x60,
                        TlvWriter.encode(0x5F01, "V3.0".encodeToByteArray()),
                        TlvWriter.encode(0x5F36, "040000".encodeToByteArray()),
                        TlvWriter.encode(0x5C, byteArrayOf(0x61, 0x75)),
                    )
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, efComOverride = wrongVersion))
                // EfComView.ldsVersion holds four ASCII digits only (its documented contract); the
                // stored value stays in the file's elements, verbatim.
                val efCom = assertNotNull(read.efCom)
                assertNull(efCom.ldsVersion)
                assertEquals("V3.0", efCom.elements.first { it.tag == 0x5F01 }.text)
                assertHashPassed(read, 1, 2)
                // [DIFF] P4 LDS_A_07: no Unicode version. Reported as absent or as a typed file error, never an exception.
                val noUnicode =
                    TlvWriter.constructed(
                        0x60,
                        TlvWriter.encode(0x5F01, "0108".encodeToByteArray()),
                        TlvWriter.encode(0x5C, byteArrayOf(0x61, 0x75)),
                    )
                val read2 = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, efComOverride = noUnicode))
                assertTrue(read2.efCom?.unicodeVersion == null)
                assertTrue(read2.efCom != null || read2.fileErrors.any { it.fileId == 0x011E }, "${read2.fileErrors}")
                assertHashPassed(read2, 1, 2)
            }
        }

    // --- M-07, M-09, M-10: DG1 content ---------------------------------------------------------------

    @Test
    fun m07_aWrongCheckDigitInDg1_keepsTheLinesVerbatimAndReportsTheFinding() =
        runTest {
            withChips { c, keys ->
                val lines = FakeReadFlowChip.lines
                val digit = lines[1][9]
                val wrong = ('0' + ((digit - '0' + 1) % 10)).toString()
                val text = lines[0] + lines[1].substring(0, 9) + wrong + lines[1].substring(10)
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(1 to dg1Of(text))))
                val dg1 = assertNotNull(read.dg1)
                assertEquals(text, dg1.mrzText, "verbatim, nothing corrected")
                val failures =
                    when (val r = dg1.parseResult) {
                        is ParseResult.Success -> r.metadata.validationFailures
                        is ParseResult.PartialSuccess -> r.metadata.validationFailures
                        is ParseResult.Failure -> error("DG1 parse failed")
                    }
                assertTrue(failures.any { it is MrzCheckDigitMismatch }, "$failures")
                assertHashPassed(read, 1)
            }
        }

    @Test
    fun m09_aBirthDateWithFillers_isReportedAsStoredAndTheReadIsComplete() =
        runTest {
            withChips { c, keys ->
                // P4 LDS_B_25 to B_28 [DIFF]: an incomplete date is a fact, not a refusal.
                val lines = FakeReadFlowChip.lines
                val text = lines[0] + lines[1].substring(0, 13) + "90<<<<" + lines[1].substring(19)
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(1 to dg1Of(text))))
                val view = read.dg1
                assertTrue(view == null || view.mrzText == text, "verbatim when parsed")
                assertTrue(view != null || read.fileErrors.any { it.fileId == 0x0101 }, "a typed outcome either way: ${read.fileErrors}")
                assertHashPassed(read, 1)
            }
        }

    @Test
    fun m10_aTd1ChipBesideATd3PrintedMrz_isAFieldsDifferFactWithNoVerdict() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_B_29, B_30.
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, dg1Lines = DeviceChipProfile.td1Lines()))
                val printed = (MrzParser.parse(FakeReadFlowChip.lines) as ParseResult.Success).document
                val combined = assertNotNull(ChipMrzComparison.combine(printed, read))
                assertEquals(CheckOutcome.Failed(ChipCheckReason.FIELDS_DIFFER), combined.check.outcome)
                assertEquals(false, combined.sameFormat)
                assertEquals(95, read.dataGroup(1)!!.size, "TD1 DG1 is 95 bytes")
            }
        }

    // --- M-08: DG1 without the MRZ object ------------------------------------------------------------

    @Test
    fun m08_dg1WithoutItsMrzDataObject_isATypedMissingElementAndTheHashStillPasses() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_B_24.
                val noMrz = TlvWriter.constructed(0x61, TlvWriter.encode(0x5F20, byteArrayOf(1, 2, 3)))
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(1 to noMrz)))
                assertNull(read.dg1)
                val error = read.fileErrors.single { it.fileId == 0x0101 }.error
                assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, assertIs<ChipMalformedFile>(error).reason)
                assertHashPassed(read, 1)
            }
        }

    // --- M-13: DG2 structure ---------------------------------------------------------------------------

    @Test
    fun m13_aDg2WithoutItsImageObject_isATypedStructuralFailureAndTheRawBytesStay() =
        runTest {
            withChips { c, keys ->
                val noImage =
                    TlvWriter.constructed(
                        0x75,
                        TlvWriter.constructed(
                            0x7F61,
                            TlvWriter.encode(0x02, byteArrayOf(1)),
                            TlvWriter.constructed(0x7F60, TlvWriter.constructed(0xA1, TlvWriter.encode(0x80, byteArrayOf(1, 1)))),
                        ),
                    )
                val read = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(2 to noImage)))
                assertNull(read.dg2)
                assertIs<ChipMalformedFile>(read.fileErrors.single { it.fileId == 0x0102 }.error)
                assertTrue(read.dataGroup(2)!!.bytes().contentEquals(noImage), "the raw bytes stay in the read")
                assertHashPassed(read, 2)
            }
        }

    // --- M-17: EF.CardSecurity versus EF.CardAccess ----------------------------------------------------

    @Test
    fun m17_cardSecurityWithMoreEntriesPasses_fewerOrDifferentAreFailedFacts() =
        runTest {
            withChips { c, keys ->
                val gm = FakePaceSuite(FakeReadFlowChip.ECDH_GM_AES128, 13)
                val im = FakePaceSuite(FakeReadFlowChip.ECDH_IM_AES128, 13)
                val kind = ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.CARD_SECURITY)
                // B51 LDS_J_01: EF.CardSecurity lists more SecurityInfos (its chip-authentication
                // entries) than EF.CardAccess: success.
                val more = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = listOf(gm), caInMasterFile = true)
                assertEquals(CheckOutcome.Passed, readOf(more).outcomeOf(kind))
                // An extra PACEInfo in the signed copy is a PACE offer missing from EF.CardAccess: the
                // downgrade shape of catalogue A-52, reported as that fact.
                val morePace =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(gm),
                        caInMasterFile = true,
                        cardSecurityExtraInfos = listOf(FakeReadFlowChip.paceInfo(im.oid, im.parameterId)),
                    )
                assertEquals(CheckOutcome.Failed(ChipCheckReason.PACE_MISSING_FROM_CARD_ACCESS), readOf(morePace).outcomeOf(kind))
                // B51 LDS_J_02 [DIFF]: EF.CardAccess lists an entry the signed copy lacks.
                val fewer =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(gm),
                        caInMasterFile = true,
                        cardAccessOverride = DerWriter.set(FakeReadFlowChip.paceInfo(gm.oid, 13), FakeReadFlowChip.paceInfo(im.oid, 13)),
                    )
                assertEquals(CheckOutcome.Failed(ChipCheckReason.ENTRIES_MISSING_FROM_SIGNED_COPY), readOf(fewer).outcomeOf(kind))
                // B51 LDS_J_03 [DIFF]: a different entry (another parameter set) in EF.CardAccess.
                val different =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        // The chip runs PACE on parameter set 12; its signed copy lists set 13.
                        paceSuites = listOf(FakePaceSuite(FakeReadFlowChip.ECDH_GM_AES128, 12)),
                        cardAccessSuites = listOf(gm),
                        caInMasterFile = true,
                        cardAccessOverride = DerWriter.set(FakeReadFlowChip.paceInfo(gm.oid, 12)),
                    )
                val outcome = readOf(different).outcomeOf(kind)
                assertTrue(
                    outcome == CheckOutcome.Failed(ChipCheckReason.DIFFERS_FROM_SIGNED_COPY) ||
                        outcome == CheckOutcome.Failed(ChipCheckReason.ENTRIES_MISSING_FROM_SIGNED_COPY),
                    "$outcome",
                )
            }
        }
}
