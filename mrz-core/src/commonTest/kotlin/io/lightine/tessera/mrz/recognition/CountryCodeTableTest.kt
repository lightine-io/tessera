package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.CountryCodeCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountryCodeTableTest {
    // --- Lookup smoke tests across each category ---

    @Test
    fun lookup_returns_entry_for_usa() {
        val entry = CountryCodeTable.lookup("USA")
        assertNotNull(entry)
        assertEquals("United States of America", entry.displayName)
        assertEquals(CountryCodeCategory.STATE, entry.category)
    }

    @Test
    fun lookup_returns_entry_for_a_recently_added_iso_code_aze() {
        // AZE is an ISO 3166-1 alpha-3 code; verifies the post-PR-5 expansion covers
        // entries beyond the original starter set.
        val entry = CountryCodeTable.lookup("AZE")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.STATE, entry.category)
    }

    @Test
    fun lookup_returns_organization_entry_for_united_nations_uno() {
        // Per ICAO Doc 9303 Part 3 §5 Part C.
        val entry = CountryCodeTable.lookup("UNO")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.ORGANIZATION, entry.category)
    }

    @Test
    fun lookup_returns_stateless_entry_for_xxa() {
        // Per ICAO Doc 9303 Part 3 §5 Part E (Stateless person, 1954 Convention).
        val entry = CountryCodeTable.lookup("XXA")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.STATELESS, entry.category)
    }

    @Test
    fun lookup_returns_refugee_entry_for_xxb_and_xxc() {
        // Per ICAO Doc 9303 Part 3 §5 Part E (1951 Refugee Convention + other refugees).
        val xxb = CountryCodeTable.lookup("XXB")
        assertNotNull(xxb)
        assertEquals(CountryCodeCategory.REFUGEE, xxb.category)
        val xxc = CountryCodeTable.lookup("XXC")
        assertNotNull(xxc)
        assertEquals(CountryCodeCategory.REFUGEE, xxc.category)
    }

    @Test
    fun lookup_returns_historical_entry_for_deprecated_ant() {
        // Per ICAO Doc 9303 Part 3 §5 Part F (Netherlands Antilles, deprecated).
        val entry = CountryCodeTable.lookup("ANT")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.HISTORICAL, entry.category)
    }

    @Test
    fun lookup_returns_synthetic_icao_specimen_code_uto_as_other() {
        // Per ICAO Doc 9303 Part 3 §5 Part G — UTO ("Utopia") is the specimen code
        // for sample documents. Categorized as OTHER (not a real country).
        val entry = CountryCodeTable.lookup("UTO")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.OTHER, entry.category)
    }

    @Test
    fun lookup_returns_organization_entry_for_eu_per_part_b() {
        // Per ICAO Doc 9303 Part 3 §5 Part B — EUE for the European Union.
        val entry = CountryCodeTable.lookup("EUE")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.ORGANIZATION, entry.category)
    }

    @Test
    fun lookup_returns_state_entry_for_british_nationality_class_gbn() {
        // Per ICAO Doc 9303 Part 3 §5 Part A — British National (Overseas).
        val entry = CountryCodeTable.lookup("GBN")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.STATE, entry.category)
    }

    @Test
    fun lookup_returns_state_entry_for_kosovo_rks() {
        // Per ICAO Doc 9303 Part 3 §5 Part A — Kosovo (3-letter operational form).
        val entry = CountryCodeTable.lookup("RKS")
        assertNotNull(entry)
        assertEquals(CountryCodeCategory.STATE, entry.category)
    }

    @Test
    fun lookup_returns_null_for_unrecognized_code() {
        // ZZZ is not assigned by ISO 3166-1 or ICAO §5.
        assertNull(CountryCodeTable.lookup("ZZZ"))
    }

    @Test
    fun lookup_is_case_sensitive_per_icao_alphabet() {
        // MRZ alphabet is uppercase only; lowercase 'usa' is not a valid MRZ country code.
        assertNull(CountryCodeTable.lookup("usa"))
    }

    // --- Coverage shape tests (lock approximate set sizes against accidental regressions) ---

    @Test
    fun all_returns_iso_3166_1_codes_plus_icao_part_5_extensions() {
        val all = CountryCodeTable.all()
        // ISO 3166-1 alpha-3 has ~249 codes; ICAO §5 extensions add ~23 entries.
        // Lock the range so accidental removals or duplicate insertions surface.
        assertTrue(
            all.size in 260..290,
            "Expected ~270 entries (ISO 3166-1 + ICAO §5); got ${all.size}",
        )
        // Spot-check that no duplicate codes were inserted.
        val codes = all.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "Duplicate code(s) detected in CountryCodeTable")
    }

    @Test
    fun by_category_state_contains_well_known_iso_3166_1_codes() {
        val stateCodes = CountryCodeTable.byCategory(CountryCodeCategory.STATE).map { it.code }.toSet()
        // Spot-check a representative subset across continents.
        for (code in listOf("USA", "GBR", "DEU", "FRA", "JPN", "BRA", "ZAF", "AUS", "IND", "RUS")) {
            assertTrue(code in stateCodes, "Expected $code in STATE category; got ${stateCodes.size} entries")
        }
        // British nationality classes from ICAO §5 Part A should also be STATE.
        for (code in listOf("GBD", "GBN", "GBO", "GBP", "GBS")) {
            assertTrue(code in stateCodes, "Expected $code (ICAO §5 Part A) in STATE category")
        }
    }

    @Test
    fun by_category_organization_returns_expected_icao_extensions() {
        val orgCodes = CountryCodeTable.byCategory(CountryCodeCategory.ORGANIZATION).map { it.code }.toSet()
        // EUE, UNO, UNA from Parts B/C; all eleven codes of Part D (8th edition); IAO from Part H.
        val expectedAtLeast =
            setOf(
                "EUE",
                "UNO",
                "UNA",
                "XBA",
                "XIM",
                "XCC",
                "XCE",
                "XCO",
                "XEC",
                "XPO",
                "XES",
                "XMP",
                "XOM",
                "XDC",
                "IAO",
            )
        for (code in expectedAtLeast) {
            assertTrue(code in orgCodes, "Expected $code in ORGANIZATION category; got $orgCodes")
        }
    }

    @Test
    fun by_category_stateless_contains_xxa() {
        val statelessCodes = CountryCodeTable.byCategory(CountryCodeCategory.STATELESS).map { it.code }.toSet()
        assertEquals(setOf("XXA"), statelessCodes)
    }

    @Test
    fun by_category_refugee_contains_xxb_and_xxc() {
        val refugeeCodes = CountryCodeTable.byCategory(CountryCodeCategory.REFUGEE).map { it.code }.toSet()
        assertEquals(setOf("XXB", "XXC"), refugeeCodes)
    }

    @Test
    fun by_category_historical_contains_deprecated_part_f_entries() {
        val historicalCodes = CountryCodeTable.byCategory(CountryCodeCategory.HISTORICAL).map { it.code }.toSet()
        // ANT (Netherlands Antilles), NTZ (Neutral Zone) — ICAO §5 Part F.
        assertEquals(setOf("ANT", "NTZ"), historicalCodes)
    }

    @Test
    fun by_category_other_contains_xxx_unspecified_and_uto_specimen() {
        val otherCodes = CountryCodeTable.byCategory(CountryCodeCategory.OTHER).map { it.code }.toSet()
        // XXX is "unspecified nationality" per §5 Part E; UTO is the specimen code per §5 Part G.
        assertEquals(setOf("XXX", "UTO"), otherCodes)
    }
}
