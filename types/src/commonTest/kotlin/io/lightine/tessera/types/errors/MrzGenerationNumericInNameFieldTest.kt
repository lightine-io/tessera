package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrzGenerationNumericInNameFieldTest {
    @Test
    fun stores_format_observed_value_and_numeric_characters_verbatim() {
        val error =
            MrzGenerationNumericInNameField(
                format = MrzFormat.TD3,
                observedValue = "SMITH2",
                numericCharacters = listOf('2'),
            )
        assertEquals(MrzFormat.TD3, error.format)
        assertEquals("SMITH2", error.observedValue)
        assertEquals(listOf('2'), error.numericCharacters)
    }

    @Test
    fun description_names_format_and_rule_but_not_value_or_digits() {
        val error =
            MrzGenerationNumericInNameField(
                format = MrzFormat.TD3,
                observedValue = "ANNA M4RIA",
                numericCharacters = listOf('4'),
            )
        assertTrue("TD3" in error.description)
        assertTrue("4.6" in error.description)
        assertFalse("ANNA M4RIA" in error.description)
        assertFalse("'4'" in error.description)
        assertEquals("MrzGenerationNumericInNameField: ${error.description}", error.toString())
    }

    @Test
    fun is_a_generation_error_and_an_mrz_error() {
        val error: MrzError = MrzGenerationNumericInNameField(MrzFormat.TD3, "X1", listOf('1'))
        assertTrue(error is MrzGenerationError)
    }
}
