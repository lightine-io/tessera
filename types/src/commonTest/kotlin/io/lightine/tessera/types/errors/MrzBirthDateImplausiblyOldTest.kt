@file:Suppress("DEPRECATION") // MrzBirthDateImplausiblyOld is deprecated, never reported (TES-202)

package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun description_names_components_reference_and_threshold() {
        val warning =
            MrzBirthDateImplausiblyOld(
                rawYear = "00",
                rawMonth = "06",
                rawDay = "15",
                referenceDate = LocalDate(2200, 1, 1),
                thresholdYears = 130,
            )
        val description = warning.description
        assertTrue("00" in description, "Description should mention rawYear; got '$description'")
        assertTrue("06" in description, "Description should mention rawMonth; got '$description'")
        assertTrue("15" in description, "Description should mention rawDay; got '$description'")
        assertTrue("2200-01-01" in description, "Description should mention reference date; got '$description'")
        assertTrue("130" in description, "Description should mention threshold; got '$description'")
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
