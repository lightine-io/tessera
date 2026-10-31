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
    fun description_names_format_field_and_lengths_but_not_value() {
        val error = MrzGenerationFieldTooShort(MrzFormat.TD1, MrzField.DATE_OF_EXPIRY, 2, 1, "7")
        assertEquals(
            "Format TD1 field DATE_OF_EXPIRY expected exactly 2 characters; observed 1 characters",
            error.description,
        )
        assertEquals("MrzGenerationFieldTooShort: ${error.description}", error.toString())
    }

    @Test
    fun is_a_generation_error_and_an_mrz_error() {
        val error: MrzError = MrzGenerationFieldTooShort(MrzFormat.TD3, MrzField.DATE_OF_BIRTH, 2, 1, "6")
        assertTrue(error is MrzGenerationError)
    }
}
