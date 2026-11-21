package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Passive authentication on the fake PKI: each check kind has a Passed and a Failed case, with real
 * signatures made at test time (Doc 9303 Part 11, App E gives the steps and no values). Runs on
 * every platform: the JVM, the Android host and the iOS simulator.
 */
class PassiveAuthenticationTest {
    private val pa = PassiveAuthentication()
    private val groups = mapOf(1 to FakeGroups.dg1, 2 to FakeGroups.dg2)

    private fun List<PaCheck>.outcome(kind: PaCheckKind): PaOutcome = single { it.kind == kind }.outcome

    private fun signature(origin: SignerCertificateOrigin?) = PaCheckKind.SignatureVerifies(SignedObject.SECURITY_OBJECT, origin)

    private fun chain(match: IssuerNameMatch?) = PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.SECURITY_OBJECT, match)

    private val sodSignature = signature(SignerCertificateOrigin.STORED_IN_FILE)

    private val sodChain = chain(IssuerNameMatch.BYTE_EQUAL)

    private fun failed(reason: PaFailure) = PaOutcome.Failed(reason)

    private fun notRun(reason: PaNotRun) = PaOutcome.NotRun(reason)

    private fun run(
        pki: Pki,
        sod: ByteArray = pki.sod(groups),
        dataGroups: Map<Int, ByteArray> = groups,
        roots: List<ByteArray> = listOf(pki.csca),
        at: CertTime? = Pki.IN_VALIDITY,
        cardAccess: ByteArray? = null,
        cardSecurity: ByteArray? = null,
        efDir: ByteArray? = null,
        documentCode: String? = "P<",
        printedDocumentCode: String? = null,
        issueDate: CertTime? = null,
        signerCertificate: ByteArray? = null,
    ) = pa.run(
        PaInputs(
            securityObject = sod,
            dataGroups = dataGroups,
            cardAccess = cardAccess,
            cardSecurity = cardSecurity,
            efDir = efDir,
            roots = roots.map { X509Parser.parse(it) },
            at = at,
            documentCode = documentCode,
            printedDocumentCode = printedDocumentCode,
            issueDate = issueDate,
            signerCertificate = signerCertificate?.let { X509Parser.parse(it) },
        ),
    )

    // ---- data group hashes ----

    @Test
    fun dataGroupHashesPassWhenTheGroupsAreAsSigned() =
        withKeys { keys ->
            val checks = run(Pki.rsa(keys))
            assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
            assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.DataGroupHashMatches(2)))
        }

    @Test
    fun aChangedDataGroupFailsItsHashAndOnlyItsHash() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val changed = FakeGroups.dg2.copyOf().also { it[10] = (it[10] + 1).toByte() }
            val checks = run(pki, sod = pki.sod(groups), dataGroups = mapOf(1 to FakeGroups.dg1, 2 to changed))
            assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
            assertEquals(failed(PaFailure.HASH_DIFFERS), checks.outcome(PaCheckKind.DataGroupHashMatches(2)))
            // The signature over the security object is a separate fact and still passes.
            assertEquals(PaOutcome.Passed, checks.outcome(sodSignature))
        }

    @Test
    fun aGroupWithNoStoredHashFailsAndAStoredGroupNotReadDoesNotRun() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val checks =
                run(
                    pki,
                    sod = pki.sod(mapOf(1 to FakeGroups.dg1, 2 to FakeGroups.dg2)),
                    dataGroups =
                        mapOf(
                            1 to FakeGroups.dg1,
                            11 to FakeGroups.group(0x6B, 11),
                        ),
                )
            assertEquals(notRun(PaNotRun.DATA_GROUP_NOT_READ), checks.outcome(PaCheckKind.DataGroupHashMatches(2)))
            assertEquals(failed(PaFailure.NO_HASH_IN_SECURITY_OBJECT), checks.outcome(PaCheckKind.DataGroupHashMatches(11)))
        }

    @Test
    fun everyHashAlgorithmIsComputed() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            for (h in HashAlgorithm.entries) {
                val checks = run(pki, sod = pki.sod(groups, hash = h))
                assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.DataGroupHashMatches(2)), "$h")
                assertEquals(PaOutcome.Passed, checks.outcome(sodSignature), "$h")
            }
        }

    // ---- the security object's signature, per algorithm ----

    @Test
    fun theSignatureVerifiesForEveryRequiredAlgorithm() =
        withKeys { keys ->
            val sha256 = HashAlgorithm.SHA256
            val cases =
                listOf(
                    "RSA PKCS#1 v1.5" to
                        Pki.build(keys, keys.rsaCsca, FakePki.rsaPkcs1(sha256), keys.rsaDs, FakePki.rsaPkcs1(HashAlgorithm.SHA512)),
                    "RSA PSS" to Pki.build(keys, keys.rsaCsca, FakePki.rsaPss(sha256), keys.rsaDs, FakePki.rsaPss(HashAlgorithm.SHA384)),
                    "bare rsaEncryption" to
                        Pki.build(keys, keys.rsaCsca, FakePki.rsaPkcs1(sha256), keys.rsaDs, FakePki.rsaEncryption(sha256)),
                    "DSA" to Pki.build(keys, keys.dsaCsca, FakePki.dsa(sha256), keys.dsaDs, FakePki.dsa(sha256)),
                    "DSA, DS parameters inherited" to
                        Pki.build(keys, keys.dsaCsca, FakePki.dsa(sha256), keys.dsaDs, FakePki.dsa(sha256), inheritDsParameters = true),
                    "ECDSA BrainpoolP256r1 explicit" to
                        Pki.build(keys, keys.brainpool256, FakePki.ecdsa(sha256), keys.brainpool256b, FakePki.ecdsa(sha256)),
                    "ECDSA BrainpoolP256r1, DS parameters inherited" to
                        Pki.build(
                            keys,
                            keys.brainpool256,
                            FakePki.ecdsa(sha256),
                            keys.brainpool256b,
                            FakePki.ecdsa(sha256),
                            inheritDsParameters = true,
                        ),
                    "ECDSA P-256 named curve" to
                        Pki.build(
                            keys,
                            keys.p256,
                            FakePki.ecdsa(sha256),
                            keys.p256,
                            FakePki.ecdsa(sha256),
                            ec = FakePki.EcParameters.NAMED,
                            namedCurveOid = FakePki.OID_PRIME256V1,
                        ),
                    "ECDSA P-384 plain" to
                        Pki.build(keys, keys.p384, FakePki.ecdsa(HashAlgorithm.SHA384), keys.p384, FakePki.ecdsaPlain(sha256)),
                    "RSA CSCA, ECDSA DS" to
                        Pki.build(keys, keys.rsaCsca, FakePki.rsaPkcs1(sha256), keys.brainpool256b, FakePki.ecdsa(sha256)),
                )
            for ((name, pki) in cases) {
                val checks = run(pki)
                assertEquals(PaOutcome.Passed, checks.outcome(sodSignature), name)
                assertEquals(PaOutcome.Passed, checks.outcome(sodChain), name)
            }
        }

    @Test
    fun aSignatureWithoutSignedAttributesCoversTheContent() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            assertEquals(PaOutcome.Passed, run(pki, sod = pki.sod(groups, signedAttributes = false)).outcome(sodSignature))
        }

    @Test
    fun aSignatureByAnotherKeyFails() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val other = FakePki.Signing(keys.signer, keys.rsaOther, FakePki.rsaPkcs1(HashAlgorithm.SHA256))
            val checks = run(pki, sod = pki.sod(groups, signing = other))
            assertEquals(failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY), checks.outcome(sodSignature))
            // The signer certificate is still the CSCA's: its path is its own fact.
            assertEquals(PaOutcome.Passed, checks.outcome(sodChain))
        }

    @Test
    fun aWrongMessageDigestOrContentTypeAttributeFails() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val badDigest = run(pki, sod = pki.sod(groups, messageDigestOverride = ByteArray(32) { 7 }))
            assertEquals(failed(PaFailure.MESSAGE_DIGEST_DIFFERS), badDigest.outcome(sodSignature))
            val badType = run(pki, sod = pki.sod(groups, contentTypeAttribute = "2.23.136.1.1.2"))
            assertEquals(failed(PaFailure.CONTENT_TYPE_ATTRIBUTE_DIFFERS), badType.outcome(sodSignature))
        }

    @Test
    fun aKeyOfTheWrongTypeForTheAlgorithmFails() =
        withKeys { keys ->
            // The DS key is EC but the SignerInfo says RSA.
            val pki =
                Pki.build(
                    keys,
                    keys.rsaCsca,
                    FakePki.rsaPkcs1(HashAlgorithm.SHA256),
                    keys.brainpool256b,
                    FakePki.ecdsa(HashAlgorithm.SHA256),
                )
            val lying = FakePki.Signing(keys.signer, keys.rsaOther, FakePki.rsaPkcs1(HashAlgorithm.SHA256))
            assertEquals(
                failed(PaFailure.KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM),
                run(pki, sod = pki.sod(groups, signing = lying)).outcome(sodSignature),
            )
        }

    @Test
    fun withoutTheSignerCertificateTheSignerChecksDoNotRun() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val checks = run(pki, sod = pki.sod(groups, certificates = emptyList()))
            assertEquals(notRun(PaNotRun.NO_SIGNER_CERTIFICATE), checks.outcome(signature(null)))
            assertEquals(notRun(PaNotRun.NO_SIGNER_CERTIFICATE), checks.outcome(chain(null)))
            // The hashes are still compared.
            assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
        }

    // ---- a signer certificate the app supplies (Part 10, App D) ----

    @Test
    fun aSignerCertificateTheAppSuppliesForAnEmptyFileIsUsedAndReported() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val empty = pki.sod(groups, certificates = emptyList())
            val fromApp = signature(SignerCertificateOrigin.SUPPLIED_BY_APP)
            // Without it, nothing about the signer runs; with it, every signer fact runs and the
            // signature fact says the certificate came from the app.
            assertEquals(notRun(PaNotRun.NO_SIGNER_CERTIFICATE), run(pki, sod = empty).outcome(signature(null)))
            val checks = run(pki, sod = empty, signerCertificate = pki.ds)
            assertEquals(PaOutcome.Passed, checks.outcome(fromApp))
            assertEquals(PaOutcome.Passed, checks.outcome(chain(IssuerNameMatch.BYTE_EQUAL)))
            assertEquals(
                PaOutcome.Passed,
                checks.outcome(PaCheckKind.SignerCountryMatchesRootCountry(CountrySource.SUBJECT_NAME, CountrySource.SUBJECT_NAME)),
            )
            assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, Pki.IN_VALIDITY)))
        }

    @Test
    fun theAppSuppliedCertificateIsNotUsedWhenItIsNotTheOneTheFileNames() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val empty = pki.sod(groups, certificates = emptyList())
            // Another serial number: the SignerInfo does not name it.
            assertEquals(
                notRun(PaNotRun.NO_SIGNER_CERTIFICATE),
                run(pki, sod = empty, signerCertificate = FakePki.cscaCertificate()).outcome(signature(null)),
            )
        }

    @Test
    fun aSignatureUnderTheAppSuppliedCertificateStillHasToVerify() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val empty = pki.sod(groups, certificates = emptyList())
            // The SignerInfo names it (issuer and serial), but the key is not the one that signed.
            val wrongKey =
                FakePki.certificate(
                    subject = Pki.DS_NAME,
                    issuer = Pki.CSCA_NAME,
                    serial = Pki.DS_SERIAL,
                    publicKeyInfo = FakePki.publicKeyInfo(keys.rsaOther.publicKey),
                )
            assertEquals(
                failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY),
                run(pki, sod = empty, signerCertificate = wrongKey).outcome(signature(SignerCertificateOrigin.SUPPLIED_BY_APP)),
            )
        }

    @Test
    fun theFileOwnsTheFirstClaimOnTheSignerCertificateTheAppAlsoSuppliesOneFor() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val wrongKey =
                FakePki.certificate(
                    subject = Pki.DS_NAME,
                    issuer = Pki.CSCA_NAME,
                    serial = Pki.DS_SERIAL,
                    publicKeyInfo = FakePki.publicKeyInfo(keys.rsaOther.publicKey),
                )
            // The SOD carries the right certificate; the app-supplied one is not used.
            assertEquals(PaOutcome.Passed, run(pki, signerCertificate = wrongKey).outcome(sodSignature))
        }

    // ---- signer to root ----

    @Test
    fun withNoRootsTheChainDoesNotRunButTheSignatureStillVerifies() =
        withKeys { keys ->
            val checks = run(Pki.rsa(keys), roots = emptyList())
            assertEquals(notRun(PaNotRun.NO_ROOTS_SUPPLIED), checks.outcome(chain(null)))
            assertEquals(PaOutcome.Passed, checks.outcome(sodSignature))
        }

    @Test
    fun aRootWithNeitherTheKeyIdNorTheNameMatchesNothing() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val stranger =
                Pki.csca(
                    keys.signer,
                    keys.rsaOther,
                    FakePki.rsaPkcs1(
                        HashAlgorithm.SHA256,
                    ),
                    FakePki.name("UU", "Elsewhere CSCA"),
                    ByteArray(20) {
                        0x33
                    },
                )
            assertEquals(failed(PaFailure.NO_SUPPLIED_ROOT_MATCHES), run(pki, roots = listOf(stranger)).outcome(chain(null)))
        }

    @Test
    fun aRootWithTheRightKeyIdButAnotherKeyFails() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val impostor = Pki.csca(keys.signer, keys.rsaOther, FakePki.rsaPkcs1(HashAlgorithm.SHA256))
            assertEquals(failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY), run(pki, roots = listOf(impostor)).outcome(chain(null)))
        }

    @Test
    fun theRootTheKeyIdNamesIsUsedAmongSeveral() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val stranger =
                Pki.csca(
                    keys.signer,
                    keys.rsaOther,
                    FakePki.rsaPkcs1(
                        HashAlgorithm.SHA256,
                    ),
                    FakePki.name("UU", "Elsewhere CSCA"),
                    ByteArray(20) {
                        0x33
                    },
                )
            assertEquals(PaOutcome.Passed, run(pki, roots = listOf(stranger, pki.csca)).outcome(sodChain))
        }

    @Test
    fun aSignerWhoseIssuerNameIsNotTheRootSubjectFails() =
        withKeys { keys ->
            val pki = Pki.rsa(keys, dsIssuer = FakePki.name("UT", "Another Name"))
            assertEquals(failed(PaFailure.ISSUER_NAME_DIFFERS), run(pki).outcome(chain(null)))
        }

    @Test
    fun withoutAnAuthorityKeyIdTheRootIsFoundByName() =
        withKeys { keys ->
            val ext = Pki.dsExtensions().filter { it.oid != FakePki.OID_AKI }
            assertEquals(PaOutcome.Passed, run(Pki.rsa(keys, dsExtensions = ext)).outcome(sodChain))
        }

    @Test
    fun aRootWhoseSubjectDiffersOnlyInEncodingMatchesByTheFallbackComparison() =
        withKeys { keys ->
            // The DS issuer name is the CSCA subject in UTF8String, lower case, with a double space:
            // the bytes differ, the names are the same under RFC 5280, section 7.1.
            val issuerName = FakePki.nameUtf8("ut", "utopia  csca")
            assertEquals(PaOutcome.Passed, run(Pki.rsa(keys, dsIssuer = issuerName)).outcome(chain(IssuerNameMatch.NORMALIZED)))
            // The same fallback finds the root by name when there is no AuthorityKeyId.
            val ext = Pki.dsExtensions().filter { it.oid != FakePki.OID_AKI }
            assertEquals(
                PaOutcome.Passed,
                run(Pki.rsa(keys, dsExtensions = ext, dsIssuer = issuerName)).outcome(chain(IssuerNameMatch.NORMALIZED)),
            )
        }

    @Test
    fun aRootWhoseSubjectIsAnotherOrderOfAttributesDoesNotMatch() =
        withKeys { keys ->
            val swapped = Pki.rsa(keys, dsIssuer = FakePki.nameSwapped("UT", "Utopia CSCA"))
            assertEquals(failed(PaFailure.ISSUER_NAME_DIFFERS), run(swapped).outcome(chain(null)))
        }

    // ---- validity and critical extensions ----

    @Test
    fun validityIsCheckedAtTheCallersTimeOnly() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val inside = PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, Pki.IN_VALIDITY)
            assertEquals(PaOutcome.Passed, run(pki).outcome(inside))
            val late = CertTime(2034, 1, 1, 0, 0, 1)
            assertEquals(
                failed(PaFailure.OUTSIDE_VALIDITY),
                run(pki, at = late).outcome(PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, late)),
            )
            val early = CertTime(2023, 12, 31, 23, 59, 59)
            assertEquals(
                failed(PaFailure.OUTSIDE_VALIDITY),
                run(pki, at = early).outcome(PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, early)),
            )
            assertEquals(
                notRun(PaNotRun.NO_TIME_GIVEN),
                run(pki, at = null).outcome(PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, null)),
            )
        }

    @Test
    fun anUnknownCriticalExtensionIsReported() =
        withKeys { keys ->
            val kind = PaCheckKind.SignerCriticalExtensionsRecognized(SignedObject.SECURITY_OBJECT)
            assertEquals(PaOutcome.Passed, run(Pki.rsa(keys)).outcome(kind))
            val odd = FakePki.Ext("1.3.6.1.4.1.99999.1", true, DerWriter.nullValue())
            val checks = run(Pki.rsa(keys, dsExtensions = Pki.dsExtensions(extra = listOf(odd))))
            assertEquals(failed(PaFailure.UNRECOGNIZED_CRITICAL_EXTENSION), checks.outcome(kind))
            // Reported, not acted on: the signature and the path are separate facts.
            assertEquals(PaOutcome.Passed, checks.outcome(sodChain))
        }

    // ---- EF.CardAccess and EF.DIR against their signed copies ----

    @Test
    fun cardAccessIsComparedWithDg14() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val dg14 = FakeGroups.dg14(FakeGroups.paceInfo, FakeGroups.aaInfo)
            val all = groups + (14 to dg14)
            val kind = PaCheckKind.CardAccessMatchesSignedCopy(SignedCopy.DG14)
            val same = run(pki, sod = pki.sod(all), dataGroups = all, cardAccess = FakeGroups.securityInfos(FakeGroups.paceInfo))
            assertEquals(PaOutcome.Passed, same.outcome(kind))
            assertEquals(PaOutcome.Passed, same.outcome(PaCheckKind.DataGroupHashMatches(14)))
            val extra = FakeGroups.securityInfos(FakeGroups.paceInfo, FakeGroups.otherPaceInfo)
            assertEquals(
                failed(PaFailure.ENTRIES_MISSING_FROM_SIGNED_COPY),
                run(pki, sod = pki.sod(all), dataGroups = all, cardAccess = extra).outcome(kind),
            )
        }

    @Test
    fun cardSecurityIsSignedAndHoldsTheCopy() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val cardSecurity = pki.cardSecurity(FakeGroups.securityInfos(FakeGroups.paceInfo, FakeGroups.aaInfo))
            val kind = PaCheckKind.CardAccessMatchesSignedCopy(SignedCopy.CARD_SECURITY)
            val signature = PaCheckKind.SignatureVerifies(SignedObject.CARD_SECURITY, SignerCertificateOrigin.STORED_IN_FILE)
            val checks = run(pki, cardAccess = FakeGroups.securityInfos(FakeGroups.paceInfo), cardSecurity = cardSecurity)
            assertEquals(PaOutcome.Passed, checks.outcome(kind))
            assertEquals(PaOutcome.Passed, checks.outcome(signature))
            assertEquals(
                PaOutcome.Passed,
                checks.outcome(PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.CARD_SECURITY, IssuerNameMatch.BYTE_EQUAL)),
            )
            val missing = run(pki, cardAccess = FakeGroups.securityInfos(FakeGroups.otherPaceInfo), cardSecurity = cardSecurity)
            assertEquals(failed(PaFailure.ENTRIES_MISSING_FROM_SIGNED_COPY), missing.outcome(kind))

            // A changed byte in the signed content breaks its signature (the message digest no longer matches).
            val tampered = cardSecurity.copyOf()
            val at = tampered.indexOfSequence(FakeGroups.aaInfo) + FakeGroups.aaInfo.size - 1
            tampered[at] = (tampered[at] + 1).toByte()
            assertEquals(failed(PaFailure.MESSAGE_DIGEST_DIFFERS), run(pki, cardSecurity = tampered).outcome(signature))
        }

    @Test
    fun withoutCardAccessOrASignedCopyTheComparisonDoesNotApplyOrRun() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            assertEquals(
                PaOutcome.NotApplicable(PaNotApplicable.NO_CARD_ACCESS),
                run(pki).outcome(PaCheckKind.CardAccessMatchesSignedCopy(null)),
            )
            assertEquals(
                notRun(PaNotRun.NO_SIGNED_COPY),
                run(pki, cardAccess = FakeGroups.securityInfos(FakeGroups.paceInfo)).outcome(PaCheckKind.CardAccessMatchesSignedCopy(null)),
            )
        }

    @Test
    fun efDirIsComparedWithItsCopyInCardSecurity() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val efDir = DerWriter.tlv(0x61, DerWriter.tlv(0x4F, byteArrayOf(0xA0.toByte(), 0, 0, 2, 0x47, 0x10, 1)))
            val efDirInfo = DerWriter.sequence(DerWriter.oid("2.23.136.1.1.13"), DerWriter.octetString(efDir))
            val cardSecurity = pki.cardSecurity(FakeGroups.securityInfos(FakeGroups.paceInfo, efDirInfo))
            val kind = PaCheckKind.EfDirMatchesSignedCopy
            assertEquals(PaOutcome.Passed, run(pki, cardSecurity = cardSecurity, efDir = efDir).outcome(kind))
            val other = efDir.copyOf().also { it[it.size - 1] = 2 }
            assertEquals(failed(PaFailure.DIFFERS_FROM_SIGNED_COPY), run(pki, cardSecurity = cardSecurity, efDir = other).outcome(kind))
            assertEquals(PaOutcome.NotApplicable(PaNotApplicable.NO_EF_DIR), run(pki, cardSecurity = cardSecurity).outcome(kind))
        }

    // ---- best practice (Part 11, 5.1.1) ----

    @Test
    fun theDocumentCodeIsComparedWithTheDocumentTypeExtension() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val kind = PaCheckKind.SignerDocumentTypeMatches
            assertEquals(PaOutcome.Passed, run(pki, documentCode = "P<").outcome(kind))
            assertEquals(PaOutcome.Passed, run(pki, documentCode = "PM", printedDocumentCode = "PM").outcome(kind))
            assertEquals(failed(PaFailure.DOCUMENT_TYPE_NOT_LISTED), run(pki, documentCode = "ID").outcome(kind))
            assertEquals(
                failed(PaFailure.DOCUMENT_TYPE_NOT_LISTED),
                run(pki, documentCode = "P<", printedDocumentCode = "ID").outcome(kind),
            )
            assertEquals(notRun(PaNotRun.NO_DOCUMENT_CODE), run(pki, documentCode = null).outcome(kind))
            // A two-letter entry covers that code only.
            val two = Pki.rsa(keys, dsExtensions = Pki.dsExtensions(documentTypes = listOf("ID")))
            assertEquals(PaOutcome.Passed, run(two, documentCode = "ID").outcome(kind))
            assertEquals(failed(PaFailure.DOCUMENT_TYPE_NOT_LISTED), run(two, documentCode = "IP").outcome(kind))
            assertEquals(
                PaOutcome.NotApplicable(PaNotApplicable.HAS_DOCUMENT_TYPE_EXTENSION),
                run(pki).outcome(PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly),
            )
        }

    @Test
    fun withoutTheDocumentTypeExtensionTheKeyUsageIsReported() =
        withKeys { keys ->
            val kind = PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly
            val plain = Pki.rsa(keys, dsExtensions = Pki.dsExtensions(documentTypes = null))
            assertEquals(PaOutcome.Passed, run(plain).outcome(kind))
            assertEquals(
                PaOutcome.NotApplicable(PaNotApplicable.NO_DOCUMENT_TYPE_EXTENSION),
                run(plain).outcome(PaCheckKind.SignerDocumentTypeMatches),
            )
            val eku = FakePki.Ext("2.5.29.37", false, DerWriter.sequence(DerWriter.oid("1.3.6.1.5.5.7.3.4")))
            val withEku = Pki.rsa(keys, dsExtensions = Pki.dsExtensions(documentTypes = null, extra = listOf(eku)))
            assertEquals(failed(PaFailure.EXTENDED_KEY_USAGE_PRESENT), run(withEku).outcome(kind))
            val wider = Pki.rsa(keys, dsExtensions = Pki.dsExtensions(documentTypes = null, keyUsage = FakePki.keyUsage(0, 1)))
            assertEquals(failed(PaFailure.KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY), run(wider).outcome(kind))
        }

    @Test
    fun theSignerCountryIsComparedWithTheMatchedRoot() =
        withKeys { keys ->
            val kind = PaCheckKind.SignerCountryMatchesRootCountry(CountrySource.SUBJECT_NAME, CountrySource.SUBJECT_NAME)
            assertEquals(PaOutcome.Passed, run(Pki.rsa(keys)).outcome(kind))
            val elsewhere = Pki.rsa(keys, dsSubject = FakePki.name("UU", "Document Signer 01"))
            assertEquals(failed(PaFailure.COUNTRY_DIFFERS), run(elsewhere).outcome(kind))
            assertEquals(
                notRun(PaNotRun.NO_MATCHED_ROOT),
                run(Pki.rsa(keys), roots = emptyList()).outcome(PaCheckKind.SignerCountryMatchesRootCountry(null, null)),
            )
        }

    @Test
    fun theSignerCountryIsReadFromTheAltNameExtensionsWhenTheSubjectHasNone() =
        withKeys { keys ->
            val noCountrySubject = FakePki.nameWithoutCountry("Utopia Document Signer 01")
            val fromSan = PaCheckKind.SignerCountryMatchesRootCountry(CountrySource.SUBJECT_ALT_NAME, CountrySource.SUBJECT_NAME)
            val san =
                Pki.rsa(
                    keys,
                    dsSubject = noCountrySubject,
                    dsExtensions = Pki.dsExtensions(extra = listOf(FakePki.subjectAltNameDirectory("UT"))),
                )
            assertEquals(PaOutcome.Passed, run(san).outcome(fromSan))
            val otherCountry =
                Pki.rsa(
                    keys,
                    dsSubject = noCountrySubject,
                    dsExtensions = Pki.dsExtensions(extra = listOf(FakePki.subjectAltNameDirectory("UU"))),
                )
            assertEquals(failed(PaFailure.COUNTRY_DIFFERS), run(otherCountry).outcome(fromSan))
            val fromIan = PaCheckKind.SignerCountryMatchesRootCountry(CountrySource.ISSUER_ALT_NAME, CountrySource.SUBJECT_NAME)
            val ian =
                Pki.rsa(
                    keys,
                    dsSubject = noCountrySubject,
                    dsExtensions = Pki.dsExtensions(extra = listOf(FakePki.issuerAltNameDirectory("UT"))),
                )
            assertEquals(PaOutcome.Passed, run(ian).outcome(fromIan))
            // A directoryName without a country attribute is no source at all.
            val none =
                Pki.rsa(
                    keys,
                    dsSubject = noCountrySubject,
                    dsExtensions = Pki.dsExtensions(extra = listOf(FakePki.subjectAltNameDirectory(null))),
                )
            assertEquals(
                notRun(PaNotRun.COUNTRY_NAME_ABSENT),
                run(none).outcome(PaCheckKind.SignerCountryMatchesRootCountry(null, CountrySource.SUBJECT_NAME)),
            )
        }

    @Test
    fun theRootCountryCanBeReadFromItsAltNameToo() =
        withKeys { keys ->
            val rootWithSan =
                Pki.csca(
                    keys.signer,
                    keys.rsaCsca,
                    FakePki.rsaPkcs1(HashAlgorithm.SHA256),
                    name = FakePki.nameWithoutCountry("Utopia CSCA"),
                    extensions =
                        listOf(
                            FakePki.subjectKeyIdentifier(Pki.CSCA_SKI),
                            FakePki.keyUsage(5, 6),
                            FakePki.basicConstraints(true, 0),
                            FakePki.subjectAltNameDirectory("UT"),
                        ),
                )
            val kind = PaCheckKind.SignerCountryMatchesRootCountry(CountrySource.SUBJECT_NAME, CountrySource.SUBJECT_ALT_NAME)
            assertEquals(PaOutcome.Passed, run(Pki.rsa(keys), roots = listOf(rootWithSan)).outcome(kind))
        }

    @Test
    fun theIssueDateIsComparedWithThePrivateKeyUsagePeriod() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val kind = PaCheckKind.IssueDateInSignerUsagePeriod
            assertEquals(PaOutcome.Passed, run(pki, issueDate = CertTime(2024, 6, 15, 0, 0, 0)).outcome(kind))
            assertEquals(PaOutcome.Passed, run(pki, issueDate = CertTime(2025, 1, 1, 0, 0, 0)).outcome(kind))
            assertEquals(failed(PaFailure.OUTSIDE_USAGE_PERIOD), run(pki, issueDate = CertTime(2025, 1, 2, 0, 0, 0)).outcome(kind))
            assertEquals(notRun(PaNotRun.NO_ISSUE_DATE), run(pki).outcome(kind))
        }

    @Test
    fun noPrivateKeyUsagePeriodIsNotApplicable() =
        withKeys { keys ->
            val exts =
                listOf(
                    FakePki.authorityKeyIdentifier(Pki.CSCA_SKI),
                    FakePki.subjectKeyIdentifier(Pki.DS_SKI),
                    FakePki.keyUsage(0),
                )
            val pki = Pki.rsa(keys, dsExtensions = exts)
            assertEquals(
                PaOutcome.NotApplicable(PaNotApplicable.NO_PRIVATE_KEY_USAGE_PERIOD),
                run(pki).outcome(PaCheckKind.IssueDateInSignerUsagePeriod),
            )
        }

    @Test
    fun anUnreadableSignedCopyDoesNotRun() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val cardAccess = FakeGroups.securityInfos(FakeGroups.paceInfo)
            // A CardSecurity whose signed content is not a SecurityInfos SET.
            val cardSecurity = pki.cardSecurity(DerWriter.sequence(DerWriter.oid("1.2.3.4")))
            assertEquals(
                notRun(PaNotRun.UNREADABLE_SIGNED_COPY),
                run(pki, cardAccess = cardAccess, cardSecurity = cardSecurity)
                    .outcome(PaCheckKind.CardAccessMatchesSignedCopy(SignedCopy.CARD_SECURITY)),
            )
        }

    @Test
    fun anAbsentCountryNameDoesNotRun() =
        withKeys { keys ->
            val noCountry =
                DerWriter.sequence(
                    DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.3"), DerWriter.printableString("Utopia Document Signer 01"))),
                )
            val pki = Pki.rsa(keys, dsSubject = noCountry)
            assertEquals(
                notRun(PaNotRun.COUNTRY_NAME_ABSENT),
                run(pki).single { it.kind is PaCheckKind.SignerCountryMatchesRootCountry }.outcome,
            )
        }

    @Test
    fun revocationIsAlwaysReportedAsNotRun() =
        withKeys { keys ->
            assertEquals(notRun(PaNotRun.NOT_SUPPORTED), run(Pki.rsa(keys)).outcome(PaCheckKind.RevocationChecked))
        }

    // ---- paths that need no signature: these run on every platform ----

    @Test
    fun withoutCryptoEveryCalculationIsNotRunAndTheRestIsStillReported() {
        val placeholderSod =
            FakePki.sod(
                FakePki.ldsSecurityObject(0, groups = listOf(1 to FakePki.fakeHash(1))),
                listOf(FakePki.documentSignerCertificate()),
            )
        val noCrypto = PassiveAuthentication { throw ChipCryptoUnavailableException() }
        val checks = noCrypto.run(PaInputs(placeholderSod, mapOf(1 to FakeGroups.dg1), at = Pki.IN_VALIDITY))
        // The placeholder SignerInfo names the DS certificate by issuer and serial 0x1234, which the placeholder certificate has.
        assertEquals(notRun(PaNotRun.CRYPTO_UNAVAILABLE), checks.outcome(sodSignature))
        assertEquals(notRun(PaNotRun.CRYPTO_UNAVAILABLE), checks.outcome(chain(null)))
        assertEquals(notRun(PaNotRun.CRYPTO_UNAVAILABLE), checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
        assertEquals(PaOutcome.Passed, checks.outcome(PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, Pki.IN_VALIDITY)))
        assertEquals(notRun(PaNotRun.NOT_SUPPORTED), checks.outcome(PaCheckKind.RevocationChecked))
    }

    @Test
    fun withoutASecurityObjectEverySignerFactIsNotRun() {
        val checks = pa.run(PaInputs(null, mapOf(1 to FakeGroups.dg1)))
        assertEquals(notRun(PaNotRun.FILE_NOT_READ), checks.outcome(signature(null)))
        assertEquals(notRun(PaNotRun.FILE_NOT_READ), checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
        assertEquals(notRun(PaNotRun.FILE_NOT_READ), checks.outcome(PaCheckKind.SignerDocumentTypeMatches))
    }

    @Test
    fun anUnreadableSecurityObjectIsNotRunNeverAnException() {
        val checks = pa.run(PaInputs(byteArrayOf(0x77, 0x03, 0x30, 0x01, 0x00), mapOf(1 to FakeGroups.dg1)))
        assertEquals(notRun(PaNotRun.UNREADABLE_FILE), checks.outcome(signature(null)))
        assertEquals(notRun(PaNotRun.UNREADABLE_SECURITY_OBJECT), checks.outcome(PaCheckKind.DataGroupHashMatches(1)))
    }

    @Test
    fun noCheckNameClaimsTheDocumentIsGenuine() {
        val names =
            PaFailure.entries.map { it.name } + PaNotRun.entries.map { it.name } + PaNotApplicable.entries.map { it.name } +
                checkKindNames()
        for (word in listOf("GENUINE", "AUTHENTIC", "TRUSTED", "ACCEPT")) {
            assertTrue(names.none { it.uppercase().contains(word) }, word)
        }
    }
}

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    error("not found")
}

/** The simple names of the check kinds (KClass.sealedSubclasses is JVM-only, so they are listed by instance). */
private fun checkKindNames(): List<String> =
    listOf(
        PaCheckKind.DataGroupHashMatches(1),
        PaCheckKind.SignatureVerifies(SignedObject.SECURITY_OBJECT, null),
        PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.SECURITY_OBJECT, null),
        PaCheckKind.SignerValidAt(SignedObject.SECURITY_OBJECT, null),
        PaCheckKind.SignerCriticalExtensionsRecognized(SignedObject.SECURITY_OBJECT),
        PaCheckKind.CardAccessMatchesSignedCopy(null),
        PaCheckKind.EfDirMatchesSignedCopy,
        PaCheckKind.SignerDocumentTypeMatches,
        PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly,
        PaCheckKind.SignerCountryMatchesRootCountry(null, null),
        PaCheckKind.IssueDateInSignerUsagePeriod,
        PaCheckKind.RevocationChecked,
    ).map { it::class.simpleName ?: "" }
