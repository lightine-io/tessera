package io.lightine.tessera.types.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrzInvalidSexValueTest {
    @Test
    fun stores_observed_character_and_position_verbatim() {
        val error =
            MrzInvalidSexValue(
                observed = 'Q',
                position = 64,
            )
        assertEquals('Q', error.observed)
        assertEquals(64, error.position)
    }

    @Test
    fun description_names_position_and_not_the_character() {
        val error = MrzInvalidSexValue(observed = '*', position = 64)
        assertTrue("64" in error.description, "Description should mention position; got '${error.description}'")
        assertFalse('*' in error.description, "Description must not carry the character; got '${error.description}'")
        assertEquals("MrzInvalidSexValue: ${error.description}", error.toString())
    }

    @Test
    fun is_a_validation_error() {
        val error: MrzValidationError = MrzInvalidSexValue(observed = '?', position = 0)
        assertTrue(error is MrzInvalidSexValue)
    }
}
