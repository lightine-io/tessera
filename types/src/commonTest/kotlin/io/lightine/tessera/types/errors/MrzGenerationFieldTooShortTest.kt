package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzGenerationFieldTooShortTest {
    @Test
    fun stores_every_property_verbatim() {
        val error = MrzGenerationFieldTooShort(MrzFormat.TD3, MrzField.DATE_OF_BIRTH, 2, 1, "6")
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals(MrzField.DATE_OF_BIRTH, error.field)
        assertEquals(2, error.expectedLength)
        assertEquals(1, error.observedLength)
        assertEquals("6", error.observedValue)
    }

    @Test
    fun description_names_format_field_lengths_and_value() {
        val error = MrzGenerationFieldTooShort(MrzFormat.TD1, MrzField.DATE_OF_EXPIRY, 2, 0, "")
        assertTrue("TD1" in error.description)
        assertTrue("DATE_OF_EXPIRY" in error.description)
        assertTrue("exactly 2" in error.description)
        assertTrue("observed 0" in error.description)
    }

    @Test
    fun is_a_generation_error_and_an_mrz_error() {
        val error: MrzError = MrzGenerationFieldTooShort(MrzFormat.TD3, MrzField.DATE_OF_BIRTH, 2, 1, "6")
        assertTrue(error is MrzGenerationError)
    }
}
