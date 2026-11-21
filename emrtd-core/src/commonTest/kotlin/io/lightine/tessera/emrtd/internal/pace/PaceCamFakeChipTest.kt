package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakePace
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.FakeGroups
import io.lightine.tessera.emrtd.internal.pa.IssuerNameMatch
import io.lightine.tessera.emrtd.internal.pa.PaCheck
import io.lightine.tessera.emrtd.internal.pa.PaCheckKind
import io.lightine.tessera.emrtd.internal.pa.PaInputs
import io.lightine.tessera.emrtd.internal.pa.PaOutcome
import io.lightine.tessera.emrtd.internal.pa.PassiveAuthentication
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.SignedObject
import io.lightine.tessera.emrtd.internal.pa.SignerCertificateOrigin
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameters
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PACE with the chip authentication mapping against the fake chip ([FakePace] with [FakePaceCam]),
 * personalized from the project specimen, with a static chip authentication key made at test time.
 * Every CAM suite (AES 128/192/256, Part 11 Note 1 of 4.4.3: no 3DES, no DH) on every standardized
 * curve opens a session, and its chip authentication data checks out against the static key. These
 * runs check the reader against an independent chip-side implementation, not against ICAO values:
 * Appendix I prints only AES-128 with BrainpoolP256r1 (PaceAppendixITest).
 *
 * The rule of Part 11, 4.4.3.5.2 (note) is tested here too: the check is Passed only once the
 * passive authentication facts of EF.CardSecurity passed; before that it is NotRun, never Passed.
 */
class PaceCamFakeChipTest {
    private val lines = FakeChipData.specimenTd3Lines()
    private val files = FakeChipData.plainChipFiles(lines)
    private val dg1 = files.applications.getValue(LdsIds.LDS1_AID.toList()).getValue(LdsIds.EF_DG1)
    private val line2 = lines[1]
    private val mrzInformation = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 28)

    private fun mrzKey() =
        ChipAccessKey.Mrz(line2.substring(0, 9), line2[9], line2.substring(13, 19), line2[19], line2.substring(21, 27), line2[27])

    private class Run(
        val result: PaceResult,
        val cam: FakePaceCam,
        val capk: ByteArray,
        val chip: FakeChip,
    ) {
        val evidence: ChipAuthMappingEvidence get() = result.mappingEvidence as ChipAuthMappingEvidence
    }

    private suspend fun open(
        c: ChipCrypto,
        oid: String,
        id: Int,
        configure: (FakePaceCam) -> Unit = {},
    ): Run {
        val params = StandardDomainParameterValues.forId(id) as EcDomainParameters
        val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
        configure(cam)
        val sm = FakeSm(c)
        val pace = FakePace(c, sm, setOf(FakePaceSuite(oid, id)), listOf(cam), mrzInformation = mrzInformation)
        val chip = FakeChip(files, handlers = listOf(sm, pace))
        val result = Pace.open(PlainApduSender(chip), c, mrzKey(), PaceSuite.of(oid, id))
        return Run(result, cam, cam.chipAuthenticationPublicKeyInfo(params, id), chip)
    }

    /** PA facts with both EF.CardSecurity facts at [outcome]. */
    private fun cardSecurityFacts(outcome: PaOutcome) = ChipAuthMappingCheck.requiredCardSecurityFacts.map { PaCheck(it, outcome) }

    /**
     * An EF.CardSecurity shape with [infos] as its signed content and no signer: the check reads only
     * the content, and the signature facts come from passive authentication (given per test).
     */
    private fun unsignedCardSecurity(vararg infos: ByteArray): ByteArray =
        DerWriter.sequence(
            DerWriter.oid("1.2.840.113549.1.7.2"),
            DerWriter.explicit(
                0,
                DerWriter.sequence(
                    DerWriter.integer(3),
                    DerWriter.set(),
                    DerWriter.sequence(
                        DerWriter.oid("0.4.0.127.0.7.3.2.1"),
                        DerWriter.explicit(0, DerWriter.octetString(FakeGroups.securityInfos(*infos))),
                    ),
                    DerWriter.set(),
                ),
            ),
        )

    @Test
    fun everyCamSuiteOnEveryCurveOpensTheChipAndTheKeyMatches() =
        runTest {
            withCrypto { c ->
                val ids = (0..31).filter { StandardizedParameters.byId(it)?.agreement == KeyAgreement.ECDH }
                var runs = 0
                for (cipherCode in 2..4) {
                    val oid = "$ID_PACE.6.$cipherCode"
                    for (id in ids) {
                        val run = open(c, oid, id)
                        val suite = run.result.suite
                        assertContentEquals(dg1, readDg1(run), "$suite")
                        val evidence = run.evidence
                        assertNull(evidence.dataFailure, "$suite")
                        val params = StandardDomainParameterValues.forId(id) as EcDomainParameters
                        assertEquals(ChipAuthMapping.orderLength(params), evidence.chipAuthenticationData!!.size, "$suite")
                        val check =
                            ChipAuthMappingCheck.run(
                                c,
                                evidence,
                                unsignedCardSecurity(run.capk),
                                cardSecurityFacts(PaOutcome.Passed),
                            )
                        assertEquals(true, check.keyMatches, "$suite")
                        assertEquals(CamOutcome.Passed, check.outcome, "$suite")
                        runs++
                    }
                }
                // 3 AES ciphers x 11 curves.
                assertEquals(33, runs)
            }
        }

    private suspend fun readDg1(run: Run): ByteArray {
        val channel = run.result.channel
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(LdsIds.EF_DG1)).sw)
        var content = channel.send(ChipCommands.readBinary(0, 4)).data
        val total =
            io.lightine.tessera.emrtd.internal.tlv
                .TlvParser()
                .declaredTotalLength(content)
        while (content.size < total) {
            content += channel.send(ChipCommands.readBinary(content.size, minOf(0xDF, total - content.size))).data
        }
        return content
    }

    @Test
    fun wrongChipAuthenticationDataFailsButTheSessionStillOpens() =
        runTest {
            withCrypto { c ->
                val run = open(c, ECDH_CAM_AES256, 13) { it.corruptData = true }
                assertEquals(SmCipher.AES256, run.result.channel.cipher)
                assertContentEquals(dg1, readDg1(run))
                val check = ChipAuthMappingCheck.run(c, run.evidence, unsignedCardSecurity(run.capk), cardSecurityFacts(PaOutcome.Passed))
                assertEquals(false, check.keyMatches)
                assertEquals(CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH), check.outcome)
            }
        }

    @Test
    fun anotherStaticKeyFails() =
        runTest {
            withCrypto { c ->
                val run = open(c, ECDH_CAM_AES128, 13)
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val other =
                    FakePaceCam(
                        FakePaceMath.randomPrivateKey(c, params, c::randomBytes),
                    ).chipAuthenticationPublicKeyInfo(params, 13)
                val check = ChipAuthMappingCheck.run(c, run.evidence, unsignedCardSecurity(other), cardSecurityFacts(PaOutcome.Passed))
                assertEquals(CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH), check.outcome)
            }
        }

    @Test
    fun absentOrMalformedDataIsAFactNotAnAbort() =
        runTest {
            withCrypto { c ->
                val absent = open(c, ECDH_CAM_AES128, 13) { it.omitData = true }
                assertEquals(CamFailure.DATA_ABSENT, absent.evidence.dataFailure)
                assertNull(absent.evidence.encryptedData)
                assertContentEquals(dg1, readDg1(absent))
                assertEquals(
                    CamOutcome.Failed(CamFailure.DATA_ABSENT),
                    ChipAuthMappingCheck
                        .run(
                            c,
                            absent.evidence,
                            unsignedCardSecurity(absent.capk),
                            cardSecurityFacts(PaOutcome.Passed),
                        ).outcome,
                )

                val unpadded = open(c, ECDH_CAM_AES192, 13) { it.badPadding = true }
                assertEquals(CamFailure.DATA_MALFORMED, unpadded.evidence.dataFailure)
                assertNull(unpadded.evidence.chipAuthenticationData)
                assertTrue(unpadded.evidence.encryptedData!!.isNotEmpty())
                assertEquals(
                    CamOutcome.Failed(CamFailure.DATA_MALFORMED),
                    ChipAuthMappingCheck.run(c, unpadded.evidence, null, emptyList()).outcome,
                )
            }
        }

    @Test
    fun withoutTheStaticKeyTheCheckDoesNotRun() =
        runTest {
            withCrypto { c ->
                val run = open(c, ECDH_CAM_AES128, 13)
                val passed = cardSecurityFacts(PaOutcome.Passed)
                assertEquals(
                    CamOutcome.NotRun(CamNotRun.CARD_SECURITY_NOT_READ),
                    ChipAuthMappingCheck.run(c, run.evidence, null, passed).outcome,
                )
                assertEquals(
                    CamOutcome.NotRun(CamNotRun.UNREADABLE_CARD_SECURITY),
                    ChipAuthMappingCheck.run(c, run.evidence, byteArrayOf(0x30, 0x01), passed).outcome,
                )
                // A key whose ID is not the PACEInfo parameter ID is never picked (Part 11, 9.2.1).
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val wrongId = run.cam.chipAuthenticationPublicKeyInfo(params, 13, keyId = 7)
                assertEquals(
                    CamOutcome.NotRun(CamNotRun.NO_CHIP_AUTHENTICATION_KEY),
                    ChipAuthMappingCheck.run(c, run.evidence, unsignedCardSecurity(wrongId), passed).outcome,
                )
                // A key on another curve than the PACE group.
                val otherCurve = run.cam.chipAuthenticationPublicKeyInfo(params, 12, keyId = 13)
                assertEquals(
                    CamOutcome.NotRun(CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY),
                    ChipAuthMappingCheck.run(c, run.evidence, unsignedCardSecurity(otherCurve), passed).outcome,
                )
                assertEquals(
                    CamOutcome.NotRun(CamNotRun.CRYPTO_UNAVAILABLE),
                    ChipAuthMappingCheck.run(null, run.evidence, unsignedCardSecurity(run.capk), passed).outcome,
                )
            }
        }

    @Test
    fun theCheckIsIncompleteUntilPassiveAuthenticationOfCardSecurityPassed() =
        runTest {
            withCrypto { c ->
                val run = open(c, ECDH_CAM_AES128, 13)
                val cardSecurity = unsignedCardSecurity(run.capk)
                val pending = CamOutcome.NotRun(CamNotRun.CARD_SECURITY_NOT_AUTHENTICATED)
                // No PA facts at all, a NotRun one, or only one of the two passed: the calculation holds, the check is not Passed.
                for (facts in listOf(
                    emptyList(),
                    cardSecurityFacts(PaOutcome.NotRun(io.lightine.tessera.emrtd.internal.pa.PaNotRun.NO_ROOTS_SUPPLIED)),
                    listOf(
                        PaCheck(
                            PaCheckKind.SignatureVerifies(SignedObject.CARD_SECURITY, SignerCertificateOrigin.STORED_IN_FILE),
                            PaOutcome.Passed,
                        ),
                    ),
                    // The EF.SOD facts do not stand in for EF.CardSecurity's.
                    listOf(
                        PaCheck(
                            PaCheckKind.SignatureVerifies(SignedObject.SECURITY_OBJECT, SignerCertificateOrigin.STORED_IN_FILE),
                            PaOutcome.Passed,
                        ),
                        PaCheck(
                            PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.SECURITY_OBJECT, IssuerNameMatch.BYTE_EQUAL),
                            PaOutcome.Passed,
                        ),
                    ),
                )) {
                    val check = ChipAuthMappingCheck.run(c, run.evidence, cardSecurity, facts)
                    assertEquals(true, check.keyMatches)
                    assertEquals(pending, check.outcome)
                }
                // A failed calculation stays Failed whatever passive authentication says.
                val bad = open(c, ECDH_CAM_AES128, 13) { it.corruptData = true }
                assertEquals(
                    CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH),
                    ChipAuthMappingCheck.run(c, bad.evidence, unsignedCardSecurity(bad.capk), emptyList()).outcome,
                )
            }
        }

    @Test
    fun withARealSignedCardSecurityTheCheckPassesOnlyWithTheRoot() =
        runTest {
            withKeys { keys ->
                withCrypto { c ->
                    val run = open(c, ECDH_CAM_AES128, 13)
                    val pki = Pki.rsa(keys)
                    val cardSecurity = pki.cardSecurity(FakeGroups.securityInfos(run.capk))

                    fun pa(roots: List<ByteArray>) =
                        PassiveAuthentication().run(
                            PaInputs(securityObject = null, cardSecurity = cardSecurity, roots = roots.map { X509Parser.parse(it) }),
                        )
                    val withRoot = ChipAuthMappingCheck.run(c, run.evidence, cardSecurity, pa(listOf(pki.csca)))
                    assertEquals(CamOutcome.Passed, withRoot.outcome)
                    val noRoot = ChipAuthMappingCheck.run(c, run.evidence, cardSecurity, pa(emptyList()))
                    assertEquals(true, noRoot.keyMatches)
                    assertEquals(CamOutcome.NotRun(CamNotRun.CARD_SECURITY_NOT_AUTHENTICATED), noRoot.outcome)
                    assertIs<PaOutcome.Passed>(
                        pa(
                            emptyList(),
                        ).single { (it.kind as? PaCheckKind.SignatureVerifies)?.signed == SignedObject.CARD_SECURITY }.outcome,
                    )
                }
            }
        }

    private companion object {
        const val ID_PACE = "0.4.0.127.0.7.2.2.4"
        const val ECDH_CAM_AES128 = "$ID_PACE.6.2"
        const val ECDH_CAM_AES192 = "$ID_PACE.6.3"
        const val ECDH_CAM_AES256 = "$ID_PACE.6.4"
    }
}
