package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzExpiryDateCenturyNotInferredTest {
    @Test
    fun stores_raw_parts_and_reference_date_verbatim() {
        val warning =
            MrzExpiryDateCenturyNotInferred(
                rawYear = "12",
                rawMonth = "04",
                rawDay = "15",
                referenceDate = LocalDate(2026, 11, 19),
            )
        assertEquals("12", warning.rawYear)
        assertEquals("04", warning.rawMonth)
        assertEquals("15", warning.rawDay)
        assertEquals(LocalDate(2026, 11, 19), warning.referenceDate)
    }

    @Test
    fun description_carries_no_dates() {
        val warning =
            MrzExpiryDateCenturyNotInferred(
                rawYear = "12",
                rawMonth = "04",
                rawDay = "15",
                referenceDate = LocalDate(2026, 11, 19),
            )
        assertEquals("Document expiry year could not be placed in a century", warning.description)
        assertEquals(
            "MrzExpiryDateCenturyNotInferred: Document expiry year could not be placed in a century",
            warning.toString(),
        )
    }

    @Test
    fun is_a_warning() {
        val warning: MrzWarning =
            MrzExpiryDateCenturyNotInferred(
                rawYear = "12",
                rawMonth = "01",
                rawDay = "01",
                referenceDate = LocalDate(2026, 1, 1),
            )
        assertTrue(warning is MrzExpiryDateCenturyNotInferred)
    }
}
