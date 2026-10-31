package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrzGenerationInvalidSexValueTest {
    @Test
    fun stores_every_property_verbatim() {
        val error = MrzGenerationInvalidSexValue(MrzFormat.TD3, 'A')
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals('A', error.observed)
    }

    @Test
    fun description_names_format_and_accepted_values_but_not_the_value() {
        val error = MrzGenerationInvalidSexValue(MrzFormat.TD1, 'Q')
        assertTrue(error.description.contains("TD1"))
        assertFalse(error.description.contains("Q"))
        assertEquals("MrzGenerationInvalidSexValue: ${error.description}", error.toString())
    }

    @Test
    fun is_a_generation_error_and_an_mrz_error() {
        val error: MrzError = MrzGenerationInvalidSexValue(MrzFormat.TD3, 'A')
        assertTrue(error is MrzGenerationError)
    }
}
