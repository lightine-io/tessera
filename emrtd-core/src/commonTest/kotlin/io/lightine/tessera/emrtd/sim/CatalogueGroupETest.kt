package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.sim.SimDrive.assertHashPassed
import io.lightine.tessera.emrtd.sim.SimDrive.driveFlow
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catalogue group E (passive authentication), TES-346, through whole reads. Every check runs after
 * the radio part and is a fact (`Passed`, `Failed`, `NotRun`, `NotApplicable`), never a verdict:
 * where the test specification says the inspection system "SHALL indicate failed", the test
 * asserts the fact and says `[DIFF]` (catalogue, "How to read this catalogue").
 */
class CatalogueGroupETest {
    private val signature = ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT)
    private val chain = ChipCheckKind.SignerChainsToSuppliedRoot(ChipSignedFile.SECURITY_OBJECT)

    private suspend fun readOf(
        flow: FlowChip,
        groups: Set<Int> = setOf(1, 2),
    ): ChipRead = driveFlow(flow, options = options(flow, groups = groups)).complete

    private fun bac(
        c: ChipCrypto,
        keys: TestKeys,
        pki: Pki = Pki.rsa(keys),
        sod: ((Pki, Map<Int, ByteArray>, List<ByteArray>) -> ByteArray)? = null,
    ) = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, pki = pki, sodBuilder = sod?.let { b -> { g, certs -> b(pki, g, certs) } })

    // --- E-03: mixed key types -------------------------------------------------------------------------

    @Test
    fun e03_mixedKeyTypes_anEcdsaRootOverAnRsaSignerAndTheReverse_verifyAndChain() =
        runTest {
            withChips { c, keys ->
                val ecRootRsaSigner =
                    Pki.build(keys, keys.p256, FakePki.ecdsa(HashAlgorithm.SHA256), keys.rsaDs, FakePki.rsaPkcs1(HashAlgorithm.SHA256))
                val rsaRootEcSigner =
                    Pki.build(keys, keys.rsaCsca, FakePki.rsaPkcs1(HashAlgorithm.SHA256), keys.p256, FakePki.ecdsa(HashAlgorithm.SHA256))
                for (pki in listOf(ecRootRsaSigner, rsaRootEcSigner)) {
                    val read = readOf(bac(c, keys, pki))
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(signature))
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(chain))
                    assertHashPassed(read, 1, 2)
                }
            }
        }

    // --- E-05, E-12, E-30: versions are reported as stored --------------------------------------------

    @Test
    fun e05_aSignedDataVersionOtherThanThree_isReportedAsStoredAndTheChecksStillRun() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_D_05: the IS shall indicate failed; the SDK reports the version as stored.
                val read = readOf(bac(c, keys) { pki, g, certs -> pki.sod(g, certificates = certs, signedDataVersion = 1) })
                assertEquals(1, assertNotNull(read.securityObject).signedDataVersion)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(signature))
                assertHashPassed(read, 1, 2)
            }
        }

    @Test
    fun e12_e30_ldsSecurityObjectVersionsAndAFutureLdsVersion_areReportedAsStoredWithNoVerdict() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_D_36, D_37; B51 LDS_H_89 (LDS 1.9): reported as stored, no verdict on the pairing.
                val v1Lds19 =
                    readOf(bac(c, keys) { pki, g, certs -> pki.sod(g, certificates = certs, ldsObjectVersion = 1, ldsVersion = "0109") })
                val view = assertNotNull(v1Lds19.securityObject)
                assertEquals(1, view.version)
                assertEquals("0109", view.ldsVersion)
                assertEquals("040000", view.unicodeVersion)
                assertHashPassed(v1Lds19, 1, 2)
                val v0 = readOf(bac(c, keys))
                assertEquals(0, v0.securityObject!!.version)
                assertEquals(null, v0.securityObject!!.ldsVersion)
            }
        }

    // --- E-06, E-09: unsupported digest algorithms ---------------------------------------------------

    @Test
    fun e06_anMd5DigestAlgorithmInTheLdsSecurityObject_leavesTheHashChecksNotRun() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_D_07: MD5 is not one of the SDK's hash algorithms.
                val read = readOf(bac(c, keys) { pki, g, certs -> pki.sod(g, certificates = certs, ldsHashOid = "1.2.840.113549.2.5") })
                for (n in listOf(1, 2)) {
                    assertEquals(
                        CheckOutcome.NotRun(ChipCheckReason.UNSUPPORTED_ALGORITHM),
                        read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n)),
                        "DG$n",
                    )
                }
                assertEquals("1.2.840.113549.2.5", read.securityObject!!.hashAlgorithmOid)
            }
        }

    @Test
    fun e09_anIllegalSignerInfoDigestAlgorithm_isNotAPassAndNeverAnException() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_D_15: the fact is a non-pass (not run or failed); the read is complete.
                val read =
                    readOf(bac(c, keys) { pki, g, certs -> pki.sod(g, certificates = certs, signerDigestOid = "1.2.840.113549.2.5") })
                val outcome = assertNotNull(read.outcomeOf(signature))
                assertTrue(outcome != CheckOutcome.Passed, "$outcome")
                assertHashPassed(read, 1, 2)
            }
        }

    // --- E-08: the signer named by its key identifier --------------------------------------------------

    @Test
    fun e08_aSignerNamedBySubjectKeyIdentifierVerifies_andOneNamingNoCertificateDoesNot() =
        runTest {
            withChips { c, keys ->
                // B51 LDS_H_84, H_85: the correct SID in both forms is a success.
                val bySki =
                    readOf(bac(c, keys) { pki, g, certs -> pki.sod(g, certificates = certs, sid = FakePki.sidSubjectKeyId(Pki.DS_SKI)) })
                assertEquals(CheckOutcome.Passed, bySki.outcomeOf(signature))
                assertEquals(
                    3,
                    bySki.securityObject!!
                        .signers
                        .single()
                        .version,
                )
                // P4 LDS_D_14 [DIFF]: a key identifier that names no certificate in the file.
                val wrong =
                    readOf(
                        bac(
                            c,
                            keys,
                        ) { pki, g, certs -> pki.sod(g, certificates = certs, sid = FakePki.sidSubjectKeyId(ByteArray(20) { 0x7E })) },
                    )
                assertTrue(wrong.outcomeOf(signature) != CheckOutcome.Passed, "${wrong.outcomeOf(signature)}")
            }
        }

    // --- E-13: different digests in the LDS object and the signature ---------------------------------

    @Test
    fun e13_differentValidDigestsInTheLdsObjectAndTheSignature_bothPass() =
        runTest {
            withChips { c, keys ->
                // B51 LDS_H_74: success. Hashes follow the LDS object's algorithm, the signature the SignerInfo's.
                val read =
                    readOf(
                        bac(c, keys) { pki, g, certs ->
                            pki.sod(
                                g,
                                hash = HashAlgorithm.SHA256,
                                certificates = certs,
                                signatureDigest = HashAlgorithm.SHA512,
                                signing = FakePki.Signing(pki.signer, pki.dsKey, FakePki.rsaPkcs1(HashAlgorithm.SHA512)),
                            )
                        },
                    )
                assertEquals(CheckOutcome.Passed, read.outcomeOf(signature))
                assertHashPassed(read, 1, 2)
                assertEquals(
                    "2.16.840.1.101.3.4.2.3",
                    read.securityObject!!
                        .signers
                        .single()
                        .digestAlgorithmOid,
                )
            }
        }

    // --- E-17: stored hashes for groups not read, wrong numbers ---------------------------------------

    @Test
    fun e17_anExtraHashForAGroupNotOnTheChip_isNotRun_andAHashUnderAnotherNumberLeavesDg2WithNone() =
        runTest {
            withChips { c, keys ->
                // [DIFF] B51 LDS_H_45: an extra hash for DG10, which the chip does not hold.
                val extra = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, sodGroups = listOf(1, 2, 10))
                val read = readOf(extra)
                assertHashPassed(read, 1, 2)
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.DATA_GROUP_NOT_READ),
                    read.outcomeOf(ChipCheckKind.DataGroupHashMatches(10)),
                )
                // [DIFF] B51 LDS_H_46: DG2's hash stored under number 3.
                val probe = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val dg2Hash = c.digest(HashAlgorithm.SHA256, probe.dataGroups.getValue(2))
                val renumbered =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        sodGroups = listOf(1, 3),
                        sodHashOverrides =
                            mapOf(3 to dg2Hash),
                    )
                val read2 = readOf(renumbered)
                assertEquals(
                    CheckOutcome.Failed(ChipCheckReason.NO_HASH_IN_SECURITY_OBJECT),
                    read2.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)),
                )
                assertEquals(CheckOutcome.Passed, read2.outcomeOf(signature), "the signature over the object is still good")
            }
        }

    // --- E-18, E-19: OPEN DECISIONS (current behaviour) -----------------------------------------------

    @Test
    fun e18_openDecision_efSodListingOtherGroupsThanEfCom_isReadAsTheUnionWithNoFactComparingTheLists() =
        runTest {
            withChips { c, keys ->
                // OPEN DECISION (catalogue E-18; B51 LDS_H_90 to H_92 demand "failed"). Today the reader
                // reads the union of the two lists (ReadFlow.listedDataGroups) and no check compares
                // them. Options: add a fact (EfComMatchesSecurityObject) or accept as out of scope.
                val dg11 =
                    io.lightine.tessera.emrtd.internal.pa.FakeGroups
                        .group(0x6B, 11)
                val more = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(11 to dg11), efComGroups = listOf(1, 2))
                val fewer = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraDataGroups = mapOf(11 to dg11), sodGroups = listOf(1, 2))
                val different =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraDataGroups = mapOf(11 to dg11),
                        efComGroups = listOf(1, 11),
                        sodGroups = listOf(1, 2),
                    )
                for ((name, flow) in listOf("SOD lists more" to more, "SOD lists fewer" to fewer, "lists differ" to different)) {
                    val read = readOf(flow, groups = setOf(1, 2, 11))
                    assertTrue(
                        read.dataGroup(1) != null && read.dataGroup(2) != null && read.dataGroup(11) != null,
                        "$name: the union is read",
                    )
                    assertTrue(read.checks.none { "EfCom" in it.kind.toString() }, "$name: no fact compares EF.COM with EF.SOD today")
                }
                // The only trace of a difference today: a group EF.SOD does not hash fails its own hash fact.
                assertEquals(
                    CheckOutcome.Failed(ChipCheckReason.NO_HASH_IN_SECURITY_OBJECT),
                    readOf(fewer, groups = setOf(1, 2, 11)).outcomeOf(ChipCheckKind.DataGroupHashMatches(11)),
                )
            }
        }

    @Test
    fun e19_openDecision_noFactChecksAHashOfEfCardSecurity() =
        runTest {
            withChips { c, keys ->
                // OPEN DECISION (catalogue E-19; B51 LDS_H_93). PassiveAuthentication hashes data groups
                // only; EF.CardSecurity is checked by its own signature, and no check names its hash.
                // Options: add a fact for an EF.CardSecurity hash in EF.SOD, or accept as out of scope.
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(FakePaceSuite(FakeReadFlowChip.ECDH_CAM_AES128, 13)),
                        cam =
                            FakePaceCam(
                                FakePaceMath.randomPrivateKey(
                                    c,
                                    StandardDomainParameterValues.forId(13) as EcDomainParameters,
                                    c::randomBytes,
                                ),
                            ),
                    )
                val read = readOf(flow)
                assertNotNull(read.masterFile(0x011D), "EF.CardSecurity was read")
                val hashKinds =
                    read.checks
                        .map { it.kind }
                        .filterIsInstance<ChipCheckKind.DataGroupHashMatches>()
                        .map { it.dataGroup }
                assertTrue(hashKinds.all { it in 1..16 }, "hash facts name data groups only: $hashKinds")
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.CARD_SECURITY)))
                assertIs<CheckOutcome>(read.outcomeOf(ChipCheckKind.PaceChipAuthenticationMapping))
            }
        }

    // --- E-16: DG14's hash, and what rests on it --------------------------------------------------------

    @Test
    fun e16_aWrongOrMissingDg14Hash_failsItsFactAndChipAuthenticationIsNotPassed() =
        runTest {
            withChips { c, keys ->
                // [DIFF] P4 LDS_D_33, D_34. Chip authentication passes only when the key file (DG14)
                // passes its checks (design 5.2; ReadFlow keyFileChecksPassed).
                val ca = ChipCheckKind.ChipAuthentication(io.lightine.tessera.emrtd.ChipFileContext.EMRTD_APPLICATION)
                val wrong =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        caInApplication = true,
                        sodHashOverrides =
                            mapOf(14 to ByteArray(32) { 0x5A }),
                    )
                val read = readOf(wrong)
                assertEquals(CheckOutcome.Failed(ChipCheckReason.HASH_DIFFERS), read.outcomeOf(ChipCheckKind.DataGroupHashMatches(14)))
                assertTrue(read.outcomeOf(ca) != CheckOutcome.Passed, "${read.outcomeOf(ca)}")
                val missing = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true, sodGroups = listOf(1, 2))
                val read2 = readOf(missing)
                assertEquals(
                    CheckOutcome.Failed(ChipCheckReason.NO_HASH_IN_SECURITY_OBJECT),
                    read2.outcomeOf(ChipCheckKind.DataGroupHashMatches(14)),
                )
                assertTrue(read2.outcomeOf(ca) != CheckOutcome.Passed, "${read2.outcomeOf(ca)}")
                // The control: the same chip with its real hash passes.
                val good = readOf(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true))
                assertEquals(CheckOutcome.Passed, good.outcomeOf(ca))
            }
        }

    // --- E-29: the data and the failed signature in the same ending ------------------------------------

    @Test
    fun e29_aWrongSodSignature_givesTheDataGroupsAndTheFailedFactInTheSameEnding() =
        runTest {
            withChips { c, keys ->
                // [DIFF] B51 LDS_H_69: no display order; the bytes and the fact come together.
                val read =
                    readOf(
                        bac(c, keys) { pki, g, certs ->
                            pki.sod(
                                g,
                                certificates = certs,
                                signing = FakePki.Signing(keys.signer, keys.rsaOther, FakePki.rsaPkcs1(HashAlgorithm.SHA256)),
                            )
                        },
                    )
                assertEquals(CheckOutcome.Failed(ChipCheckReason.SIGNATURE_DOES_NOT_VERIFY), read.outcomeOf(signature))
                assertNotNull(read.dataGroup(1))
                assertNotNull(read.dg1)
                assertHashPassed(read, 1, 2)
            }
        }
}
