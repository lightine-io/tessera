package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.fakechip.FakeProtectedFile
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.DH_GM_3DES
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_CAM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_GM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_IM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeTagLoss
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipBlank
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipFileNotFound
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.errors.ChipUnexpectedAnswer
import io.lightine.tessera.types.errors.ChipUnsupportedAlgorithm
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end reads of fake chips through the public [ChipReader]: every chip configuration of
 * Part 11, 4.1 (plain, BAC only, PACE and BAC, PACE only), each PACE mapping, chip authentication in
 * the application and in the master file, active authentication, a tag lost in the middle of DG2,
 * the blank chip, and every error the read returns. The chips are personalized from the project
 * specimen with keys made at test time; these runs check the reader against the independent fake
 * chip, not against ICAO values. They need the crypto backend and a test signer, which every
 * platform has (OpenSSL on iOS), so they run everywhere.
 */
class ChipReaderTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private class Recorder : TelemetrySink {
        val events = mutableListOf<TelemetryEvent>()

        override fun record(event: TelemetryEvent) {
            events += event
        }
    }

    private fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        dataGroups: Set<Int> = setOf(1, 2),
    ) = ChipReadOptions(key, dataGroups, listOf(flow.anchor), checkTime)

    private suspend fun read(
        flow: FlowChip,
        options: ChipReadOptions = options(flow),
        telemetry: TelemetrySink = Recorder(),
    ): ChipReadResult = ChipReader(telemetry).read(flow.chip, options)

    private fun complete(result: ChipReadResult): ChipRead = assertIs<ChipReadResult.Complete>(result, "$result").read

    private fun failed(result: ChipReadResult): ChipReadResult.Failed = assertIs<ChipReadResult.Failed>(result, "$result")

    /** The facts every complete read of these chips has: DG1 and DG2 read, every hash and signer fact passed. */
    private fun assertPassiveAuthenticationPassed(read: ChipRead) {
        assertEquals(ReadMethod.NFC_CHIP, assertNotNull(read.dg1).parseResult.metadata.readMethod)
        assertNotNull(read.dg2)
        assertNotNull(read.securityObject)
        for (n in read.files.mapNotNull { it.dataGroup }) {
            assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)), "DG$n")
        }
        val sod = ChipSignedFile.SECURITY_OBJECT
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(sod)))
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignerChainsToSuppliedRoot(sod)))
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignerWithinValidityPeriod(sod)))
        assertEquals(CheckOutcome.NotRun(ChipCheckReason.NOT_SUPPORTED), read.outcomeOf(ChipCheckKind.SignerNotRevoked))
        assertTrue(read.fileErrors.isEmpty(), "${read.fileErrors}")
    }

    private fun assertNoGetChallenge(flow: FlowChip) {
        // GET CHALLENGE (INS 84) sent in the clear means BAC started: never in a session PACE opened.
        assertTrue(flow.chip.received.none { it.size > 1 && it[0] == 0x00.toByte() && it[1] == 0x84.toByte() })
    }

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    // --- Chip configurations ------------------------------------------------------------------

    @Test
    fun plainChipIsReadWithoutAccessControl() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN)
                val read = complete(read(flow))
                assertEquals(ChipAccessProtocol.NONE, read.access.protocol)
                assertNull(read.access.password)
                assertFalse(read.access.cardAccessRead)
                assertPassiveAuthenticationPassed(read)
                assertEquals(CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED), read.outcomeOf(ChipCheckKind.ActiveAuthentication))
                assertEquals(
                    CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED),
                    read.outcomeOf(ChipCheckKind.PaceChipAuthenticationMapping),
                )
                assertEquals(
                    CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED),
                    read.outcomeOf(ChipCheckKind.ChipAuthentication(null)),
                )
                assertTrue(read.dataGroup(1)!!.bytes().contentEquals(flow.dataGroups.getValue(1)))
            }
        }

    @Test
    fun bacOnlyChipIsOpenedWithBac() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val read = complete(read(flow))
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                assertEquals(ChipPassword.MRZ, read.access.password)
                assertPassiveAuthenticationPassed(read)
            }
        }

    @Test
    fun aChipThatPadsShortFilesPastTheirTlvStillReadsOverBac() =
        runTest {
            withChips { c, keys ->
                val dg1 = FakeChipData.dg1(FakeReadFlowChip.lines)
                val efCom = FakeChipData.efCom(listOf(0x61, 0x75))
                // The physical EF.COM and DG1 are longer than their outer TLV: trailing fill bytes
                // the chip returns whole, with 6282, on the first READ BINARY of each file.
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraApplicationFiles =
                            mapOf(
                                LdsIds.EF_COM to efCom + ByteArray(8),
                                LdsIds.EF_DG1 to dg1 + ByteArray(16) { 0x5A.toByte() },
                            ),
                    )
                val read = complete(read(flow))
                assertTrue(
                    read.files
                        .first { it.fileId == LdsIds.EF_COM }
                        .bytes()
                        .contentEquals(efCom),
                )
                assertTrue(read.dataGroup(1)!!.bytes().contentEquals(dg1))
                val kept = read.dataGroup(1)!!
                assertEquals(dg1.size, kept.size)
                assertEquals(1, kept.dataGroup)
                assertFalse(kept.inMasterFile)
                assertEquals("ChipFile(0101, ${dg1.size} bytes, attempt 1)", kept.toString())
                assertNull(read.files.first { it.fileId == LdsIds.EF_COM }.dataGroup)
                // The hash in the security object still judges the kept bytes.
                assertPassiveAuthenticationPassed(read)
            }
        }

    @Test
    fun paceAndBacChipIsOpenedWithPaceOnly() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC)
                val read = complete(read(flow))
                assertEquals(ChipAccessProtocol.PACE, read.access.protocol)
                assertEquals(ChipPaceMapping.GENERIC, read.access.paceMapping)
                assertEquals(13, read.access.paceParameterId)
                assertTrue(read.access.cardAccessRead)
                assertEquals(1, read.access.offeredPace.size)
                assertTrue(
                    read.access.offeredPace
                        .single()
                        .readerCanRun,
                )
                assertNoGetChallenge(flow)
                assertPassiveAuthenticationPassed(read)
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.NO_SIGNED_COPY),
                    read.outcomeOf(ChipCheckKind.CardAccessMatchesSignedCopy(null)),
                )
            }
        }

    @Test
    fun paceOnlyChipsOpenWithEachMappingAndCipherFamily() =
        runTest {
            withChips { c, keys ->
                val cases =
                    listOf(
                        FakePaceSuite(ECDH_GM_AES128, 13) to ChipPaceMapping.GENERIC,
                        FakePaceSuite(ECDH_IM_AES128, 13) to ChipPaceMapping.INTEGRATED,
                        FakePaceSuite(DH_GM_3DES, 0) to ChipPaceMapping.GENERIC,
                    )
                for ((suite, mapping) in cases) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = listOf(suite))
                    val read = complete(read(flow))
                    assertEquals(ChipAccessProtocol.PACE, read.access.protocol, "$suite")
                    assertEquals(mapping, read.access.paceMapping, "$suite")
                    assertEquals(suite.oid, read.access.paceProtocolOid)
                    assertPassiveAuthenticationPassed(read)
                }
            }
        }

    @Test
    fun paceWithTheChipAuthenticationMappingReportsTheMappingCheck() =
        runTest {
            withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
                val suite = FakePaceSuite(ECDH_CAM_AES128, 13)
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = listOf(suite), cam = cam)
                val read = complete(read(flow))
                assertEquals(ChipPaceMapping.CHIP_AUTHENTICATION, read.access.paceMapping)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.PaceChipAuthenticationMapping))
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.CARD_SECURITY)))
                assertNotNull(read.files.firstOrNull { it.fileId == 0x011D && it.inMasterFile })
                assertEquals(
                    CheckOutcome.Passed,
                    read.outcomeOf(ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.CARD_SECURITY)),
                )
                assertPassiveAuthenticationPassed(read)
            }
        }

    @Test
    fun theBestSuiteIsChosenAndTheParameterIdSentWhenSeveralAreListed() =
        runTest {
            withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
                val suites = listOf(FakePaceSuite(ECDH_CAM_AES128, 13), FakePaceSuite(ECDH_GM_AES128, 13), FakePaceSuite(DH_GM_3DES, 0))
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = suites, cam = cam)
                val read = complete(read(flow))
                assertEquals(ChipPaceMapping.CHIP_AUTHENTICATION, read.access.paceMapping)
                assertEquals(3, read.access.offeredPace.size)
                val offered = read.access.offeredPace
                assertEquals(suites.map { it.oid }, offered.map { it.protocolOid })
                assertEquals(
                    listOf(ChipPaceMapping.CHIP_AUTHENTICATION, ChipPaceMapping.GENERIC, ChipPaceMapping.GENERIC),
                    offered.map { it.mapping },
                )
                assertEquals(listOf<Int?>(13, 13, 0), offered.map { it.parameterId })
                assertTrue(offered.all { it.readerCanRun })
                // Set AT carries the parameter ID (tag 84) when EF.CardAccess lists several suites.
                val setAt = assertNotNull(flow.pace!!.lastSetAtData)
                assertTrue(setAt.indices.any { setAt[it] == 0x84.toByte() && it + 2 < setAt.size && setAt[it + 2] == 13.toByte() })
            }
        }

    @Test
    fun paceWithTheCardAccessNumberReportsCan() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, can = "123456")
                val read = complete(read(flow, options(flow, key = ChipAccessKey.Can("123456"))))
                assertEquals(ChipPassword.CAN, read.access.password)
                assertPassiveAuthenticationPassed(read)
            }
        }

    // --- Chip authentication and active authentication ----------------------------------------

    @Test
    fun chipAuthenticationInTheApplicationPassesWithItsKeyFileChecked() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val flow = FakeReadFlowChip.build(c, keys, access, caInApplication = true, activeAuthentication = true)
                    val read = complete(read(flow))
                    assertEquals(1, flow.ca!!.restarts, "$access")
                    assertEquals(
                        CheckOutcome.Passed,
                        read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION)),
                        "$access",
                    )
                    // Chip authentication ran, so active authentication is not run as well (Appendix J.1 step 3).
                    assertEquals(
                        CheckOutcome.NotRun(ChipCheckReason.OTHER_CHIP_AUTHENTICATION_USED),
                        read.outcomeOf(ChipCheckKind.ActiveAuthentication),
                    )
                    assertPassiveAuthenticationPassed(read)
                }
            }
        }

    @Test
    fun chipAuthenticationWithoutSuppliedRootsIsNotPassed() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true)
                val read = complete(read(flow, ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1), emptyList(), checkTime)))
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED),
                    read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION)),
                )
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.NO_ROOTS_SUPPLIED),
                    read.outcomeOf(ChipCheckKind.SignerChainsToSuppliedRoot(ChipSignedFile.SECURITY_OBJECT)),
                )
            }
        }

    @Test
    fun chipAuthenticationInTheMasterFileUsesEfCardSecurity() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, caInMasterFile = true)
                val read = complete(read(flow))
                assertEquals(1, flow.ca!!.restarts)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.MASTER_FILE)))
                assertPassiveAuthenticationPassed(read)
            }
        }

    @Test
    fun activeAuthenticationRunsWhenThereIsNoChipAuthentication() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.PLAIN, FlowAccess.BAC, FlowAccess.PACE_AND_BAC)) {
                    val flow = FakeReadFlowChip.build(c, keys, access, activeAuthentication = true)
                    val read = complete(read(flow))
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ActiveAuthentication), "$access")
                    assertNotNull(flow.aa!!.lastSignature)
                    assertPassiveAuthenticationPassed(read)
                }
            }
        }

    @Test
    fun aWrongActiveAuthenticationSignatureFails() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, activeAuthentication = true)
                flow.aa!!.corruptNextSignature = true
                val read = complete(read(flow))
                assertIs<CheckOutcome.Failed>(read.outcomeOf(ChipCheckKind.ActiveAuthentication))
            }
        }

    // --- Partial and failed reads -------------------------------------------------------------

    @Test
    fun aTagLostInTheMiddleOfDg2GivesPartialWithDg1Kept() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.PLAIN, FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val flow = FakeReadFlowChip.build(c, keys, access, tagLoss = FakeTagLoss(0x0102))
                    val partial = assertIs<ChipReadResult.Partial>(read(flow), "$access")
                    assertEquals(NfcTagLost, partial.stoppedBy)
                    assertNotNull(partial.read.dg1, "$access")
                    assertNull(partial.read.dataGroup(2))
                    assertNull(partial.read.dg2)
                    assertEquals(CheckOutcome.Passed, partial.read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1)))
                    assertEquals(
                        CheckOutcome.NotRun(ChipCheckReason.DATA_GROUP_NOT_READ),
                        partial.read.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)),
                    )
                }
            }
        }

    @Test
    fun aBlankChipIsItsOwnOutcome() =
        runTest {
            withChips { c, keys ->
                val plain = failed(read(FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN, blank = true)))
                assertEquals(ChipBlank, plain.error)
                assertNull(plain.access)
                val pace = failed(read(FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, blank = true)))
                assertEquals(ChipBlank, pace.error)
                assertEquals(ChipAccessProtocol.PACE, pace.access?.protocol)
            }
        }

    @Test
    fun aWrongKeyIsAccessDeniedForBacAndPace() =
        runTest {
            withChips { c, keys ->
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val e1 = failed(read(bac, options(bac, key = FakeReadFlowChip.wrongMrzKey()))).error
                assertEquals(ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), e1)
                val pace = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val e2 = failed(read(pace, options(pace, key = FakeReadFlowChip.wrongMrzKey()))).error
                assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), e2)
            }
        }

    @Test
    fun aClearedKeyAndACanWithoutPaceAreAccessDenied() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val cleared = FakeReadFlowChip.mrzKey().also { it.clear() }
                assertEquals(
                    ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_CLEARED),
                    failed(read(flow, options(flow, key = cleared))).error,
                )
                assertTrue(flow.chip.received.isEmpty())
                assertEquals(
                    ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_NEEDS_PACE),
                    failed(read(flow, options(flow, key = ChipAccessKey.Can("123456")))).error,
                )
            }
        }

    @Test
    fun aLostSessionAfterAccessIsPartialWithSecureMessagingLost() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                flow.sm!!.corruptNextResponseMac = true
                val partial = assertIs<ChipReadResult.Partial>(read(flow))
                assertEquals(ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_CHECKSUM), partial.stoppedBy)
                assertEquals(ChipAccessProtocol.BAC, partial.read.access.protocol)
                assertTrue(partial.read.files.isEmpty())
            }
        }

    @Test
    fun suitesTheReaderCannotRunOrTheChipRefusesAreUnsupportedAlgorithm() =
        runTest {
            withChips { c, keys ->
                // EF.CardAccess lists only a reserved parameter ID: no suite the reader runs, and no BAC.
                val none =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        cardAccessSuites = listOf(FakePaceSuite(ECDH_GM_AES128, 5)),
                    )
                val r1 = failed(read(none))
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE),
                    r1.error,
                )
                assertFalse(r1.error.toString().contains("SPECIMEN"))
                // EF.CardAccess lists a suite the chip then refuses (6A80 to MSE:Set AT).
                val refused =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(FakePaceSuite(ECDH_IM_AES128, 13)),
                        cardAccessSuites = listOf(FakePaceSuite(ECDH_GM_AES128, 13)),
                    )
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.CHIP_REFUSED_SUITE, 0x6A80),
                    failed(read(refused)).error,
                )
            }
        }

    @Test
    fun anAnswerWithoutAStatusWordIsAnUnexpectedAnswer() =
        runTest {
            val silent =
                object : ApduTransport {
                    override val maxCommandLength: Int = 261
                    override val supportsExtendedLength: Boolean = false

                    override suspend fun transceive(command: ByteArray): ByteArray = ByteArray(0)
                }
            val result = ChipReader(Recorder()).read(silent, ChipReadOptions(FakeReadFlowChip.mrzKey()))
            assertEquals(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.NO_STATUS_WORD), failed(result).error)
        }

    @Test
    fun missingAndMalformedFilesAreListedAndTheReadGoesOn() =
        runTest {
            withChips { c, keys ->
                // EF.COM lists DG5 but the chip has no such file; DG2 is TLV but has no biometric group.
                val com = FakeChipData.efCom(listOf(0x61, 0x75, 0x65))
                val badDg2 = TlvWriter.constructed(0x75, TlvWriter.encode(0x02, byteArrayOf(1)))
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraApplicationFiles = mapOf(0x011E to com, 0x0102 to badDg2),
                    )
                val read = complete(read(flow, options(flow, dataGroups = setOf(1, 2, 5, 11))))
                assertTrue(
                    ChipFileError(0x0105, false, ChipFileNotFound(0x0105, listedByChip = true)) in read.fileErrors,
                    "${read.fileErrors}",
                )
                assertTrue(
                    ChipFileError(0x010B, false, ChipFileNotFound(0x010B, listedByChip = false)) in read.fileErrors,
                    "${read.fileErrors}",
                )
                val malformed =
                    read.fileErrors
                        .map { it.error }
                        .filterIsInstance<ChipMalformedFile>()
                        .single()
                assertEquals(0x0102, malformed.fileId)
                assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, malformed.reason)
                assertNull(read.dg2)
                assertTrue(read.dataGroup(2)!!.bytes().contentEquals(badDg2))
                assertEquals(CheckOutcome.Failed(ChipCheckReason.HASH_DIFFERS), read.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)))
                assertNotNull(read.dg1)
            }
        }

    @Test
    fun aProtectedDataGroupIsAccessDeniedAndTheReadGoesOn() =
        runTest {
            withChips { c, keys ->
                val com = FakeChipData.efCom(listOf(0x61, 0x75, 0x63))
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraApplicationFiles =
                            mapOf(
                                0x011E to com,
                                0x0103 to TlvWriter.constructed(0x63, TlvWriter.encode(0x02, byteArrayOf(1))),
                            ),
                        extraHandlers = listOf(FakeProtectedFile(0x0103)),
                    )
                val read = complete(read(flow, options(flow, dataGroups = setOf(1, 2, 3))))
                assertEquals(
                    listOf(
                        ChipFileError(
                            0x0103,
                            false,
                            ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.FILE_PROTECTED, 0x6982),
                        ),
                    ),
                    read.fileErrors,
                )
                assertNotNull(read.dg2)
            }
        }

    @Test
    fun withoutCryptographyOnlyAPlainChipReads() =
        runTest {
            withChips { c, keys ->
                val noCrypto = ChipReader(Recorder()) { throw ChipCryptoUnavailableException() }
                val pace = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_CRYPTOGRAPHY_ON_PLATFORM),
                    failed(noCrypto.read(pace.chip, options(pace))).error,
                )
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.BAC, ChipUnsupportedAlgorithm.Reason.NO_CRYPTOGRAPHY_ON_PLATFORM),
                    failed(noCrypto.read(bac.chip, options(bac))).error,
                )
                val plain = FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN)
                val read = complete(noCrypto.read(plain.chip, options(plain)))
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.CRYPTO_UNAVAILABLE),
                    read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1)),
                )
            }
        }

    @Test
    fun anUnreadableSecurityObjectIsTheOneMalformedFileError() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PLAIN,
                        extraApplicationFiles = mapOf(0x011D to TlvWriter.encode(0x77, byteArrayOf(0x30, 0x03, 0x02, 0x01))),
                    )
                val read = complete(read(flow))
                val error =
                    read.fileErrors
                        .map { it.error }
                        .filterIsInstance<ChipMalformedFile>()
                        .single()
                assertEquals(0x011D, error.fileId)
                assertNull(read.securityObject)
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.UNREADABLE_SECURITY_OBJECT),
                    read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1)),
                )
            }
        }

    @Test
    fun anEfSodThatIsNotSignedDataOrHoldsAnotherContentIsNamedSo() =
        runTest {
            withChips { c, keys ->
                val notSigned =
                    DerWriter.tlv(
                        0x77,
                        DerWriter.sequence(DerWriter.oid("1.2.840.113549.1.7.1"), DerWriter.explicit(0, DerWriter.nullValue())),
                    )
                // A security object whose content type's last arc is 2: SignedData, not an LDS security object.
                val sod = FakePki.sod(FakePki.ldsSecurityObject(1, groups = listOf(1 to FakePki.fakeHash(1))))
                val oidBytes = DerWriter.oid(FakePki.OID_LDS_SECURITY_OBJECT)
                val at = (0..sod.size - oidBytes.size).first { i -> oidBytes.indices.all { sod[i + it] == oidBytes[it] } }
                val otherContent = sod.copyOf().also { it[at + oidBytes.size - 1] = 2 }
                val cases =
                    listOf(
                        TlvWriter.encode(0x77, byteArrayOf(0x30, 0x03, 0x02, 0x01)) to ChipMalformedFile.Reason.NOT_VALID_TLV,
                        notSigned to ChipMalformedFile.Reason.NOT_SIGNED_DATA,
                        otherContent to ChipMalformedFile.Reason.UNEXPECTED_CONTENT_TYPE,
                    )
                for ((file, reason) in cases) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN, extraApplicationFiles = mapOf(0x011D to file))
                    val error =
                        complete(read(flow))
                            .fileErrors
                            .map { it.error }
                            .filterIsInstance<ChipMalformedFile>()
                            .single()
                    assertEquals(reason, error.reason, "$reason")
                    assertEquals("malformed-file." + reason.name.lowercase().replace('_', '-'), error.code)
                }
            }
        }

    // --- Telemetry, options, names, MIXED -----------------------------------------------------

    @Test
    fun telemetryCarriesProtocolsFilesAndCountsButNoDocumentValues() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val recorder = Recorder()
                complete(read(flow, telemetry = recorder))
                val events = recorder.events.map { assertIs<ChipReadEvent>(it) }
                assertTrue(events.any { it.step == ChipReadStep.PACE && it.protocol == "PACE-ECDH-GM-AES-128" })
                assertTrue(
                    events.any {
                        it.step == ChipReadStep.READ_FILE && it.fileId == 0x0101 &&
                            it.byteCount == flow.dataGroups.getValue(1).size
                    },
                )
                assertEquals(ChipReadStep.READ, events.last().step)
                val text = events.joinToString()
                for (value in listOf("T22000129", "SPECIMEN", "MORGAN", "900315")) assertFalse(value in text, value)
            }
        }

    @Test
    fun optionsRejectDataGroupsOutsideOneToSixteen() {
        assertFailsWith<IllegalArgumentException> { ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(0)) }
        assertFailsWith<IllegalArgumentException> { ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(17)) }
        assertFalse(ChipReadOptions(FakeReadFlowChip.mrzKey()).toString().contains("T22000129"))
    }

    @Test
    fun noPublicNameSaysGenuineValidTrustedOrAccepted() {
        val names =
            ChipCheckReason.entries.map { it.name } + ChipReadStep.entries.map { it.name } +
                ChipReadStepOutcome.entries.map { it.name } +
                listOf(
                    ChipCheckKind.DataGroupHashMatches(1),
                    ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT),
                    ChipCheckKind.SignerChainsToSuppliedRoot(ChipSignedFile.SECURITY_OBJECT),
                    ChipCheckKind.SignerWithinValidityPeriod(ChipSignedFile.SECURITY_OBJECT),
                    ChipCheckKind.SignerCriticalExtensionsRecognized(ChipSignedFile.SECURITY_OBJECT),
                    ChipCheckKind.CardAccessMatchesSignedCopy(null),
                    ChipCheckKind.EfDirMatchesSignedCopy,
                    ChipCheckKind.SignerDocumentTypeMatches,
                    ChipCheckKind.SignerKeyUsageIsDigitalSignatureOnly,
                    ChipCheckKind.SignerCountryMatchesRootCountry,
                    ChipCheckKind.IssueDateInSignerUsagePeriod,
                    ChipCheckKind.SignerNotRevoked,
                    ChipCheckKind.ChipAuthentication(null),
                    ChipCheckKind.PaceChipAuthenticationMapping,
                    ChipCheckKind.ActiveAuthentication,
                    ChipCheckKind.Dg1MatchesPrintedMrz,
                    ChipCheckKind.PaceOfferMatchesSignedCopy(null),
                ).map { it::class.simpleName ?: "" } +
                listOf("CheckOutcome", "ChipCheck", "ChipRead", "ChipReadResult", "AccessReport", "CombinedMrzRead")
        val banned = setOf("GENUINE", "VALID", "TRUSTED", "TRUST", "ACCEPT", "ACCEPTED", "AUTHENTIC", "ISVALID")
        for (name in names) {
            val words = name.split('_').flatMap { it.split(Regex("(?<=[a-z])(?=[A-Z])")) }.map { it.uppercase() }
            assertTrue(words.none { it in banned }, name)
        }
    }

    @Test
    fun combineGivesMixedWithPerFieldFactsAndNeverCorrects() =
        runTest {
            withChips { c, keys ->
                val read = complete(read(FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN)))
                val printedSame = (MrzParser.parse(FakeReadFlowChip.lines) as ParseResult.Success).document
                val same = assertNotNull(ChipMrzComparison.combine(printedSame, read))
                assertEquals(ReadMethod.MIXED, same.parseResult.metadata.readMethod)
                assertEquals(CheckOutcome.Passed, same.check.outcome)
                assertTrue(same.differingFields.isEmpty())
                assertTrue(same.sexSame && same.sameFormat)

                val otherLines =
                    (
                        MrzGenerator.generateTD3(
                            documentType = "P",
                            issuingState = "UTO",
                            documentNumber = "T22000130",
                            primaryIdentifier = "SPECIMEN",
                            secondaryIdentifier = "ALEX MORGAN",
                            nationality = "UTO",
                            dateOfBirth = LocalDate(1990, 3, 15),
                            sex = Sex.FEMALE,
                            dateOfExpiry = LocalDate(2034, 3, 14),
                            personalNumber = "S98765432",
                        ) as GenerationResult.Success
                    ).mrz
                val printedOther = (MrzParser.parse(otherLines) as ParseResult.Success).document
                val differs = assertNotNull(ChipMrzComparison.combine(printedOther, read))
                assertEquals(CheckOutcome.Failed(ChipCheckReason.FIELDS_DIFFER), differs.check.outcome)
                assertTrue(MrzField.DOCUMENT_NUMBER in differs.differingFields)
                assertEquals(
                    MrzFieldComparison(MrzField.DOCUMENT_NUMBER, false),
                    differs.fieldComparisons.single {
                        it.field ==
                            MrzField.DOCUMENT_NUMBER
                    },
                )
                assertTrue(differs.fieldComparisons.single { it.field == MrzField.DATE_OF_BIRTH }.sameAsPrinted)
                assertTrue(same.fieldComparisons.isNotEmpty() && same.fieldComparisons.all { it.sameAsPrinted })
                assertTrue(same.fieldComparisons.single { it.field == MrzField.SEX }.sameAsPrinted)
                // The chip's values stay the chip's: nothing is corrected or picked from print.
                val doc = (differs.parseResult as ParseResult.Success).document
                assertEquals(
                    read.dg1!!
                        .document.commonFields.documentNumber,
                    doc.commonFields.documentNumber,
                )
                assertFalse(differs.toString().contains("T2200013"))
            }
        }

    @Test
    fun everyReadErrorTextIsFreeOfDocumentValues() {
        val errors: List<ChipError> =
            listOf(
                ChipBlank,
                ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300),
                ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_CHECKSUM),
                ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE),
                ChipFileNotFound(0x0105, true),
                ChipMalformedFile(0x0102, ChipMalformedFile.Reason.INVALID_ELEMENT),
                ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.NO_STATUS_WORD),
            )
        for (e in errors) for (value in listOf("T22000129", "SPECIMEN", "MORGAN")) assertFalse(value in e.description)
    }
}
