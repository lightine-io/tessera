@file:Suppress("DEPRECATION") // MrzBirthDateImplausiblyOld is deprecated, never reported (TES-202)

package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrzBirthDateImplausiblyOldTest {
    @Test
    fun stores_raw_components_reference_and_threshold_verbatim() {
        val warning =
            MrzBirthDateImplausiblyOld(
                rawYear = "00",
                rawMonth = "06",
                rawDay = "15",
                referenceDate = LocalDate(2200, 1, 1),
                thresholdYears = 130,
            )
        assertEquals("00", warning.rawYear)
        assertEquals("06", warning.rawMonth)
        assertEquals("15", warning.rawDay)
        assertEquals(LocalDate(2200, 1, 1), warning.referenceDate)
        assertEquals(130, warning.thresholdYears)
    }

    @Test
    fun description_names_threshold_but_not_components_or_reference() {
        val warning =
            MrzBirthDateImplausiblyOld(
                rawYear = "00",
                rawMonth = "06",
                rawDay = "15",
                referenceDate = LocalDate(2200, 1, 1),
                thresholdYears = 130,
            )
        val description = warning.description
        assertTrue("130" in description, "Description should mention threshold; got '$description'")
        assertFalse("2200" in description, "Description must not carry the reference date; got '$description'")
        assertEquals("MrzBirthDateImplausiblyOld: $description", warning.toString())
    }

    @Test
    fun is_a_warning() {
        val warning: MrzWarning =
            MrzBirthDateImplausiblyOld(
                rawYear = "00",
                rawMonth = "01",
                rawDay = "01",
                referenceDate = LocalDate(2200, 1, 1),
                thresholdYears = 130,
            )
        assertTrue(warning is MrzBirthDateImplausiblyOld)
    }
}
