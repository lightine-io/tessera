package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzExpiryDateImplausiblyFarTest {
    @Test
    fun stores_dates_and_threshold_verbatim() {
        val warning =
            MrzExpiryDateImplausiblyFar(
                expiryDate = LocalDate(2040, 1, 1),
                referenceDate = LocalDate(2026, 5, 4),
                thresholdYears = 10,
            )
        assertEquals(LocalDate(2040, 1, 1), warning.expiryDate)
        assertEquals(LocalDate(2026, 5, 4), warning.referenceDate)
        assertEquals(10, warning.thresholdYears)
    }

    @Test
    fun description_names_threshold_but_not_dates() {
        val warning =
            MrzExpiryDateImplausiblyFar(
                expiryDate = LocalDate(2040, 1, 1),
                referenceDate = LocalDate(2026, 5, 4),
                thresholdYears = 10,
            )
        assertEquals(
            "Document expiry date is more than 10 years after the reference date",
            warning.description,
        )
        assertEquals("MrzExpiryDateImplausiblyFar: ${warning.description}", warning.toString())
    }

    @Test
    fun is_a_warning() {
        val warning: MrzWarning =
            MrzExpiryDateImplausiblyFar(
                expiryDate = LocalDate(2050, 1, 1),
                referenceDate = LocalDate(2026, 1, 1),
                thresholdYears = 10,
            )
        assertTrue(warning is MrzExpiryDateImplausiblyFar)
    }
}
