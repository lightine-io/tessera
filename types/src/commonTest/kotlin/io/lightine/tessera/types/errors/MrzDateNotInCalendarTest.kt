package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzDateNotInCalendarTest {
    @Test
    fun stores_field_raw_components_and_position_verbatim() {
        val error =
            MrzDateNotInCalendar(
                field = MrzField.DATE_OF_BIRTH,
                rawYear = "90",
                rawMonth = "02",
                rawDay = "30",
                position = 57,
            )
        assertEquals(MrzField.DATE_OF_BIRTH, error.field)
        assertEquals("90", error.rawYear)
        assertEquals("02", error.rawMonth)
        assertEquals("30", error.rawDay)
        assertEquals(57, error.position)
    }

    @Test
    fun description_mentions_field_and_position_but_not_raw_components() {
        val error =
            MrzDateNotInCalendar(
                field = MrzField.DATE_OF_EXPIRY,
                rawYear = "30",
                rawMonth = "13",
                rawDay = "01",
                position = 65,
            )
        assertEquals(
            "Date in DATE_OF_EXPIRY at position 65 is not a real calendar date",
            error.description,
        )
        assertEquals("MrzDateNotInCalendar: ${error.description}", error.toString())
    }

    @Test
    fun is_a_validation_error() {
        val error: MrzValidationError =
            MrzDateNotInCalendar(
                field = MrzField.DATE_OF_BIRTH,
                rawYear = "90",
                rawMonth = "02",
                rawDay = "30",
                position = 57,
            )
        assertTrue(error is MrzDateNotInCalendar)
    }
}
