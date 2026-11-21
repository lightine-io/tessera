package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAfterKeySwitch
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.DH_GM_3DES
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_CAM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_GM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeTagLoss
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.flow.ReadFlow
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.errors.ChipUnsupportedAlgorithm
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The failure paths of the read flow: a chip-authentication key switch that the chip does not
 * confirm, a stripped or broken EF.CardAccess against its signed copy, chip authentication refused
 * then active authentication, CAM data faults, a malformed DG14, a throwing telemetry sink, wiped
 * keys after every kind of result, PACE that needs extended length, and cryptography that throws.
 * Fake chips from the project specimen; they run on every platform.
 */
class ChipReaderFailurePathTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private fun options(
        flow: FlowChip,
        dataGroups: Set<Int> = setOf(1, 2),
    ) = ChipReadOptions(FakeReadFlowChip.mrzKey(), dataGroups, listOf(flow.anchor), checkTime)

    private suspend fun read(
        flow: FlowChip,
        telemetry: TelemetrySink = NoOpTelemetrySink,
        crypto: (() -> ChipCrypto)? = null,
    ): ChipReadResult =
        if (crypto == null) {
            ChipReader(telemetry).read(flow.chip, options(flow))
        } else {
            ChipReader(telemetry, crypto).read(flow.chip, options(flow))
        }

    private fun ChipRead.ca(context: ChipFileContext?) = outcomeOf(ChipCheckKind.ChipAuthentication(context))

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    // --- B1, S1: the key switch of chip authentication -------------------------------------------

    @Test
    fun aBareErrorAnswerAfterTheKeySwitchDoesNotConfirmTheKeys() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        caInApplication = true,
                        leadingHandlers = listOf(FakeAfterKeySwitch(bareStatus = 0x6A82)),
                    )
                val read =
                    when (val r = read(flow)) {
                        is ChipReadResult.Complete -> r.read
                        is ChipReadResult.Partial -> r.read
                        is ChipReadResult.Failed -> error("$r")
                    }
                assertEquals(1, flow.ca!!.restarts)
                assertEquals(CheckOutcome.Failed(ChipCheckReason.NEW_KEYS_NOT_CONFIRMED), read.ca(ChipFileContext.EMRTD_APPLICATION))
            }
        }

    @Test
    fun aWrongChecksumAfterTheKeySwitchIsPartialWithTheFailedFact() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            access,
                            caInApplication = access == FlowAccess.BAC,
                            caInMasterFile = access == FlowAccess.PACE_ONLY,
                            leadingHandlers = listOf(FakeAfterKeySwitch(bareStatus = null)),
                        )
                    val partial = assertIs<ChipReadResult.Partial>(read(flow), "$access")
                    assertEquals(ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_CHECKSUM), partial.stoppedBy)
                    val where = if (access == FlowAccess.BAC) ChipFileContext.EMRTD_APPLICATION else ChipFileContext.MASTER_FILE
                    assertEquals(CheckOutcome.Failed(ChipCheckReason.NEW_KEYS_NOT_CONFIRMED), partial.read.ca(where), "$access")
                    // The read still carries its facts, not only the error.
                    assertTrue(partial.read.checks.any { it.kind == ChipCheckKind.SignerNotRevoked })
                }
            }
        }

    @Test
    fun chipAuthenticationRefusedThenActiveAuthenticationRuns() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true, activeAuthentication = true)
                flow.ca!!.refuseGeneralAuthenticate = 0x6A80
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(CheckOutcome.Failed(ChipCheckReason.CHIP_REFUSED), read.ca(ChipFileContext.EMRTD_APPLICATION))
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ActiveAuthentication))
            }
        }

    @Test
    fun aMalformedDg14IsAFileErrorAndChipAuthenticationIsNotRun() =
        runTest {
            withChips { c, keys ->
                val broken = TlvWriter.encode(0x6E, byteArrayOf(0x31, 0x05, 0x01))
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        caInApplication = true,
                        extraApplicationFiles =
                            mapOf(0x010E to broken),
                    )
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(CheckOutcome.NotRun(ChipCheckReason.UNREADABLE_FILE), read.ca(ChipFileContext.EMRTD_APPLICATION))
                val error = read.fileErrors.single { it.fileId == 0x010E }
                assertFalse(error.inMasterFile)
                assertIs<ChipMalformedFile>(error.error)
                assertEquals(0, flow.ca!!.restarts)
            }
        }

    // --- S2: EF.CardAccess against its signed copy ------------------------------------------------

    @Test
    fun aStrippedOrEmptyCardAccessIsNotPassedWhenTheSignedCopyListsPace() =
        runTest {
            withChips { c, keys ->
                val signed = listOf(FakePaceSuite(ECDH_GM_AES128, 13))
                for (cardAccess in listOf(DerWriter.set(), null)) {
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            if (cardAccess == null) FlowAccess.BAC else FlowAccess.PACE_AND_BAC,
                            signedPaceSuites = signed,
                            cardAccessOverride = cardAccess,
                        )
                    val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                    assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                    assertEquals(
                        CheckOutcome.Failed(ChipCheckReason.PACE_MISSING_FROM_CARD_ACCESS),
                        read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)),
                        "cardAccess=${cardAccess?.size}",
                    )
                    val copyCheck = read.checks.single { it.kind is ChipCheckKind.CardAccessMatchesSignedCopy }
                    assertTrue(copyCheck.outcome != CheckOutcome.Passed, "$copyCheck")
                    assertFalse(copyCheck.outcome is CheckOutcome.NotApplicable, "$copyCheck")
                }
            }
        }

    @Test
    fun paceListedInBothButNotUsedIsReported() =
        runTest {
            withChips { c, keys ->
                // Both files list a suite with a reserved parameter ID: the reader cannot run it and uses BAC.
                val suite = listOf(FakePaceSuite(ECDH_GM_AES128, 5))
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC, paceSuites = suite, signedPaceSuites = suite)
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                assertEquals(
                    CheckOutcome.Failed(ChipCheckReason.PACE_OFFERED_BUT_NOT_USED),
                    read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)),
                )
            }
        }

    @Test
    fun paceInBothAndUsedPasses() =
        runTest {
            withChips { c, keys ->
                val suite = listOf(FakePaceSuite(ECDH_GM_AES128, 13))
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = suite, signedPaceSuites = suite)
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)))
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.DG14)))
            }
        }

    @Test
    fun aMalformedCardAccessIsAMasterFileErrorAndBacIsUsed() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC, cardAccessOverride = byteArrayOf(0x31, 0x05, 0x01))
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                val error = read.fileErrors.single { it.fileId == 0x011C }
                assertTrue(error.inMasterFile)
                assertIs<ChipMalformedFile>(error.error)
            }
        }

    // --- CAM through the flow ---------------------------------------------------------------------

    @Test
    fun chipAuthenticationMappingFaultsAreFailedFacts() =
        runTest {
            withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val cases =
                    listOf<Pair<(FakePaceCam) -> Unit, ChipCheckReason>>(
                        { cam: FakePaceCam -> cam.omitData = true } to ChipCheckReason.CHIP_AUTHENTICATION_DATA_ABSENT,
                        { cam: FakePaceCam -> cam.corruptData = true } to ChipCheckReason.MAPPING_KEY_DIFFERS,
                        { cam: FakePaceCam -> cam.badPadding = true } to ChipCheckReason.CHIP_AUTHENTICATION_DATA_MALFORMED,
                    )
                for ((configure, reason) in cases) {
                    val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes)).also(configure)
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_ONLY,
                            paceSuites = listOf(FakePaceSuite(ECDH_CAM_AES128, 13)),
                            cam = cam,
                        )
                    val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                    assertEquals(CheckOutcome.Failed(reason), read.outcomeOf(ChipCheckKind.PaceChipAuthenticationMapping), "$reason")
                }
            }
        }

    // --- S3: telemetry and wiping -----------------------------------------------------------------

    @Test
    fun aThrowingSinkNeverChangesTheRead() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val throwing =
                    object : TelemetrySink {
                        override fun record(event: TelemetryEvent): Unit = throw IllegalStateException("sink broke")
                    }
                assertIs<ChipReadResult.Complete>(read(flow, throwing))
            }
        }

    @Test
    fun sessionKeysAreWipedAfterCompletePartialAndFailed() =
        runTest {
            withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
                val complete =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(FakePaceSuite(ECDH_CAM_AES128, 13)),
                        cam = cam,
                    )
                val partial = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, tagLoss = FakeTagLoss(0x0102))
                val failed = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, blank = true)
                val throwing =
                    object : TelemetrySink {
                        override fun record(event: TelemetryEvent): Unit = throw IllegalStateException("sink broke")
                    }
                for ((flow, expected) in listOf(complete to "Complete", partial to "Partial", failed to "Failed")) {
                    val run = ReadFlow(flow.chip, options(flow), throwing)
                    val result = run.run()
                    assertTrue(result::class.simpleName == expected, "$result")
                    assertTrue(run.openedChannels.isNotEmpty(), expected)
                    assertTrue(run.openedChannels.none { it.isOpen }, expected)
                    if (expected == "Complete") {
                        val data = assertNotNull(run.mappingEvidence?.chipAuthenticationData)
                        assertTrue(data.all { it == 0.toByte() })
                    }
                }
            }
        }

    @Test
    fun aTagLostDuringAccessIsFailedWithoutAccess() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                flow.chip.loseTagAtCommand = 2
                val failed = assertIs<ChipReadResult.Failed>(read(flow))
                assertEquals(NfcTagLost, failed.error)
            }
        }

    // --- Extended length and cryptography -------------------------------------------------------

    @Test
    fun paceThatNeedsExtendedLengthOnAShortReaderIsUnsupported() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = listOf(FakePaceSuite(DH_GM_3DES, 2)))
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.EXTENDED_LENGTH_REQUIRED),
                    assertIs<ChipReadResult.Failed>(read(flow)).error,
                )
            }
        }

    @Test
    fun cryptographyThatThrowsMidAccessIsATypedError() =
        runTest {
            withChips { c, keys ->
                val brokenEc =
                    object : ChipCrypto by c {
                        override fun ecAdd(
                            curve: EcDomainParameters,
                            p: EcPoint,
                            q: EcPoint,
                        ): EcPoint? = throw ChipCryptoException("backend broke")
                    }
                val pace = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.CRYPTOGRAPHY_FAILED),
                    assertIs<ChipReadResult.Failed>(read(pace, crypto = { brokenEc })).error,
                )
                val brokenMac =
                    object : ChipCrypto by c {
                        override fun retailMac(
                            key: ByteArray,
                            paddedData: ByteArray,
                        ): ByteArray = throw ChipCryptoException("backend broke")
                    }
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.BAC, ChipUnsupportedAlgorithm.Reason.CRYPTOGRAPHY_FAILED),
                    assertIs<ChipReadResult.Failed>(read(bac, crypto = { brokenMac })).error,
                )
            }
        }

    // --- S6: active authentication the reader cannot check ---------------------------------------

    @Test
    fun anUnreadableActiveAuthenticationKeyIsNotRunAndAFileError() =
        runTest {
            withChips { c, keys ->
                val brokenDg15 = TlvWriter.encode(0x6F, byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01))
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        activeAuthentication = true,
                        extraApplicationFiles =
                            mapOf(0x010F to brokenDg15),
                    )
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertIs<CheckOutcome.NotRun>(read.outcomeOf(ChipCheckKind.ActiveAuthentication))
                assertTrue(read.fileErrors.any { it.fileId == 0x010F })
            }
        }

    // --- S8: options ------------------------------------------------------------------------------

    @Test
    fun theBuilderMakesTheSameOptions() {
        val key = FakeReadFlowChip.mrzKey()
        val built =
            ChipReadOptions
                .Builder(key)
                .dataGroups(setOf(1, 11))
                .checkTime(checkTime)
                .allowExtendedLength(false)
                .build()
        assertEquals(setOf(1, 11), built.dataGroups)
        assertEquals(checkTime, built.checkTime)
        assertFalse(built.allowExtendedLength)
        assertTrue(built.trustAnchors.isEmpty())
    }

    @Test
    fun withNoCheckTimeTheReaderReadsNoClockAndLeavesDg1Unparsed() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN)
                val read = assertIs<ChipReadResult.Complete>(ChipReader().read(flow.chip, ChipReadOptions(FakeReadFlowChip.mrzKey()))).read
                assertEquals(null, read.dg1)
                assertNotNull(read.dataGroup(1))
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.NO_TIME_GIVEN),
                    read.outcomeOf(ChipCheckKind.SignerWithinValidityPeriod(ChipSignedFile.SECURITY_OBJECT)),
                )
            }
        }
}
