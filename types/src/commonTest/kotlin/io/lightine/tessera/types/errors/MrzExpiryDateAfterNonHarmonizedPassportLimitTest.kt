package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzExpiryDateAfterNonHarmonizedPassportLimitTest {
    @Test
    fun stores_code_and_expiry_date_verbatim() {
        val warning = MrzExpiryDateAfterNonHarmonizedPassportLimit(rawDocumentCode = "P<", expiryDate = LocalDate(2040, 3, 14))
        assertEquals("P<", warning.rawDocumentCode)
        assertEquals(LocalDate(2040, 3, 14), warning.expiryDate)
    }

    @Test
    fun description_carries_no_document_values() {
        val warning = MrzExpiryDateAfterNonHarmonizedPassportLimit(rawDocumentCode = "P<", expiryDate = LocalDate(2040, 3, 14))
        assertEquals(
            "Passport whose document code has no harmonized second letter expires on or after 1 January 2038, " +
                "the limit in ICAO Doc 9303 Part 4, section 4.4",
            warning.description,
        )
        assertEquals("MrzExpiryDateAfterNonHarmonizedPassportLimit: ${warning.description}", warning.toString())
    }

    @Test
    fun is_a_warning() {
        val warning: MrzWarning = MrzExpiryDateAfterNonHarmonizedPassportLimit("P<", LocalDate(2038, 1, 1))
        assertTrue(warning is MrzExpiryDateAfterNonHarmonizedPassportLimit)
    }
}
