package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificate
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificateParse
import io.lightine.tessera.emrtd.trust.TrustAnchorProblem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The signer facts of passive authentication as the read reports them: where the signer
 * certificate came from (the file, or the app through [ChipReadOptions.signerCertificate]), how the
 * root's name matched (byte for byte, or the RFC 5280 comparison), and where each country was read
 * from. Synthetic Utopia certificates made at test time.
 */
class ChipReaderSignerFactsTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")
    private val sod = ChipSignedFile.SECURITY_OBJECT

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private suspend fun read(
        flow: FlowChip,
        signer: DocumentSignerCertificate? = null,
    ): ChipRead {
        val options =
            ChipReadOptions
                .Builder(FakeReadFlowChip.mrzKey())
                .trustAnchors(listOf(flow.anchor))
                .checkTime(checkTime)
                .signerCertificate(signer)
                .build()
        return assertIs<ChipReadResult.Complete>(ChipReader().read(flow.chip, options)).read
    }

    private fun ChipRead.check(kind: ChipCheckKind): ChipCheck = checks.single { it.kind == kind }

    @Test
    fun aSignerStoredInTheFileWithAByteEqualRootAndSubjectCountries() =
        runTest {
            withChips { c, keys ->
                val read = read(FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN))
                val signature = read.check(ChipCheckKind.SignatureVerifies(sod))
                assertEquals(CheckOutcome.Passed, signature.outcome)
                assertEquals(ChipCheckDetail.SignerCertificate(ChipSignerOrigin.STORED_IN_FILE), signature.detail)
                val chain = read.check(ChipCheckKind.SignerChainsToSuppliedRoot(sod))
                assertEquals(ChipCheckDetail.IssuerNameComparison(ChipNameComparison.BYTE_EQUAL), chain.detail)
                val country = read.check(ChipCheckKind.SignerCountryMatchesRootCountry)
                assertEquals(CheckOutcome.Passed, country.outcome)
                assertEquals(ChipCheckDetail.CountrySources(ChipCountrySource.SUBJECT_NAME, ChipCountrySource.SUBJECT_NAME), country.detail)
                assertNull(read.check(ChipCheckKind.DataGroupHashMatches(1)).detail)
            }
        }

    @Test
    fun aSignerCertificateTheAppSuppliesIsUsedForAFileThatCarriesNone() =
        runTest {
            withChips { c, keys ->
                val pki = Pki.rsa(keys)
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, pki = pki, sodCertificates = emptyList())
                val without = read(flow)
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.NO_SIGNER_CERTIFICATE),
                    without.outcomeOf(ChipCheckKind.SignatureVerifies(sod)),
                )
                assertNull(without.check(ChipCheckKind.SignatureVerifies(sod)).detail)

                val signer = assertIs<DocumentSignerCertificateParse.Parsed>(DocumentSignerCertificate.fromDer(pki.ds)).certificate
                val with = read(FakeReadFlowChip.build(c, keys, FlowAccess.BAC, pki = pki, sodCertificates = emptyList()), signer)
                val signature = with.check(ChipCheckKind.SignatureVerifies(sod))
                assertEquals(CheckOutcome.Passed, signature.outcome)
                assertEquals(ChipCheckDetail.SignerCertificate(ChipSignerOrigin.SUPPLIED_BY_APP), signature.detail)
                assertEquals(CheckOutcome.Passed, with.outcomeOf(ChipCheckKind.SignerChainsToSuppliedRoot(sod)))
            }
        }

    @Test
    fun aRootNameThatDiffersOnlyInEncodingMatchesByTheRfc5280Comparison() =
        runTest {
            withChips { c, keys ->
                val pki = Pki.rsa(keys, dsIssuer = FakePki.nameUtf8("ut", "utopia  csca"))
                val read = read(FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN, pki = pki))
                val chain = read.check(ChipCheckKind.SignerChainsToSuppliedRoot(sod))
                assertEquals(CheckOutcome.Passed, chain.outcome)
                assertEquals(ChipCheckDetail.IssuerNameComparison(ChipNameComparison.RFC_5280_NORMALIZED), chain.detail)
            }
        }

    @Test
    fun theSignerCountryFromTheSubjectAltNameIsReportedAsSuch() =
        runTest {
            withChips { c, keys ->
                val pki =
                    Pki.rsa(
                        keys,
                        dsSubject = FakePki.nameWithoutCountry("Utopia Document Signer 01"),
                        dsExtensions = Pki.dsExtensions(extra = listOf(FakePki.subjectAltNameDirectory("UT"))),
                    )
                val read = read(FakeReadFlowChip.build(c, keys, FlowAccess.PLAIN, pki = pki))
                val country = read.check(ChipCheckKind.SignerCountryMatchesRootCountry)
                assertEquals(CheckOutcome.Passed, country.outcome)
                assertEquals(
                    ChipCheckDetail.CountrySources(ChipCountrySource.SUBJECT_ALT_NAME, ChipCountrySource.SUBJECT_NAME),
                    country.detail,
                )
            }
        }

    @Test
    fun anUnreadableSignerCertificateIsReportedWithoutThrowing() {
        val r = assertIs<DocumentSignerCertificateParse.Unreadable>(DocumentSignerCertificate.fromDer(byteArrayOf(0x30, 0x03, 0x02)))
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, r.problem)
    }
}
