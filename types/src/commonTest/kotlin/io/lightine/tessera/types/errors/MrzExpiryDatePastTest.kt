package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzExpiryDatePastTest {
    @Test
    fun stores_expiry_and_reference_dates_verbatim() {
        val warning =
            MrzExpiryDatePast(
                expiryDate = LocalDate(2020, 6, 23),
                referenceDate = LocalDate(2026, 5, 4),
            )
        assertEquals(LocalDate(2020, 6, 23), warning.expiryDate)
        assertEquals(LocalDate(2026, 5, 4), warning.referenceDate)
    }

    @Test
    fun description_carries_no_dates() {
        val warning =
            MrzExpiryDatePast(
                expiryDate = LocalDate(2020, 6, 23),
                referenceDate = LocalDate(2026, 5, 4),
            )
        assertEquals("Document expiry date is before the reference date", warning.description)
        assertEquals("MrzExpiryDatePast: Document expiry date is before the reference date", warning.toString())
    }

    @Test
    fun is_a_warning() {
        val warning: MrzWarning =
            MrzExpiryDatePast(
                expiryDate = LocalDate(2020, 1, 1),
                referenceDate = LocalDate(2026, 1, 1),
            )
        assertTrue(warning is MrzExpiryDatePast)
    }
}
