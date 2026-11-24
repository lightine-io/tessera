package io.lightine.tessera.emrtd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The small conveniences of the polish round: check-kind codes, link-loss shortcuts, option builders. */
class ChipPolishApiTest {
    private val shape = Regex("[a-z0-9]+(-[a-z0-9]+)*(\\.[a-z0-9]+(-[a-z0-9]+)*)?")

    @Test
    fun everyCheckKindHasAStableCodeOfTheDocumentedShape() {
        val kinds: List<Pair<ChipCheckKind, String>> =
            listOf(
                ChipCheckKind.DataGroupHashMatches(1) to "data-group-hash-matches.dg1",
                ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT) to "signature-verifies.security-object",
                ChipCheckKind.SignerChainsToSuppliedRoot(ChipSignedFile.CARD_SECURITY) to "signer-chains-to-supplied-root.card-security",
                ChipCheckKind.SignerWithinValidityPeriod(ChipSignedFile.SECURITY_OBJECT) to "signer-within-validity-period.security-object",
                ChipCheckKind.SignerCriticalExtensionsRecognized(ChipSignedFile.SECURITY_OBJECT) to
                    "signer-critical-extensions-recognized.security-object",
                ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.DG14) to "card-access-matches-signed-copy.dg14",
                ChipCheckKind.CardAccessMatchesSignedCopy(null) to "card-access-matches-signed-copy.none",
                ChipCheckKind.EfDirMatchesSignedCopy to "ef-dir-matches-signed-copy",
                ChipCheckKind.SignerDocumentTypeMatches to "signer-document-type-matches",
                ChipCheckKind.SignerKeyUsageIsDigitalSignatureOnly to "signer-key-usage-is-digital-signature-only",
                ChipCheckKind.SignerCountryMatchesRootCountry to "signer-country-matches-root-country",
                ChipCheckKind.IssueDateInSignerUsagePeriod to "issue-date-in-signer-usage-period",
                ChipCheckKind.SignerNotRevoked to "signer-not-revoked",
                ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.CARD_SECURITY) to "pace-offer-matches-signed-copy.card-security",
                ChipCheckKind.ChipAuthentication(ChipFileContext.MASTER_FILE) to "chip-authentication.master-file",
                ChipCheckKind.ChipAuthentication(null) to "chip-authentication.none",
                ChipCheckKind.PaceChipAuthenticationMapping to "pace-chip-authentication-mapping",
                ChipCheckKind.ActiveAuthentication to "active-authentication",
                ChipCheckKind.Dg1MatchesPrintedMrz to "dg1-matches-printed-mrz",
            )
        kinds.forEach { (kind, code) ->
            assertEquals(code, kind.code)
            assertTrue(shape.matches(kind.code), kind.code)
        }
        assertEquals(kinds.size, kinds.map { it.second }.toSet().size)
    }

    @Test
    fun theLinkLossShortcutsMakeTheSamePoliciesAsTheClasses() {
        assertEquals(LinkLossPolicy.End, LinkLossPolicy.end())
        assertEquals(LinkLossPolicy.ReadAgain(2), LinkLossPolicy.readAgain(2))
        assertEquals(LinkLossPolicy.Resume(3), LinkLossPolicy.resume(3))
        assertIs<LinkLossPolicy.Resume>(LinkLossPolicy.resume(1))
        assertEquals(0, LinkLossPolicy.end().maxRestarts)
        assertFailsWith<IllegalArgumentException> { LinkLossPolicy.resume(0) }
        assertFailsWith<IllegalArgumentException> { LinkLossPolicy.readAgain(4) }
    }

    @Test
    fun theOptionsBuilderTakesDataGroupsAsNumbersOrASet() {
        val key = ChipAccessKey.Can("123456")
        val spread = ChipReadOptions.Builder(key).dataGroups(1, 2, 11).build()
        assertEquals(setOf(1, 2, 11), spread.dataGroups)
        assertEquals(
            setOf(1, 7),
            ChipReadOptions
                .Builder(key)
                .dataGroups(setOf(1, 7))
                .build()
                .dataGroups,
        )
        assertFailsWith<IllegalArgumentException> { ChipReadOptions.Builder(key).dataGroups(1, 17).build() }
        // No number given: no data group beyond the always-read files.
        assertTrue(
            ChipReadOptions
                .Builder(key)
                .dataGroups()
                .build()
                .dataGroups
                .isEmpty(),
        )
    }
}
