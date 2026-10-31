package io.lightine.tessera.types.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MrzCharacterSetViolationTest {
    @Test
    fun stores_offending_character_and_position_verbatim() {
        val violation = MrzCharacterSetViolation(offendingCharacter = '!', position = 7)
        assertEquals('!', violation.offendingCharacter)
        assertEquals(7, violation.position)
    }

    @Test
    fun description_names_the_position_but_not_the_offending_character() {
        val violation = MrzCharacterSetViolation(offendingCharacter = '#', position = 12)
        assertTrue("12" in violation.description, "Description should name the position; got '${violation.description}'")
        assertFalse('#' in violation.description, "Description must not carry the character; got '${violation.description}'")
        assertEquals("MrzCharacterSetViolation: ${violation.description}", violation.toString())
    }

    @Test
    fun description_references_the_mrz_alphabet_definition() {
        val violation = MrzCharacterSetViolation(offendingCharacter = 'a', position = 0)
        assertTrue(
            "A-Z" in violation.description && "0-9" in violation.description && "<" in violation.description,
            "Description should describe the MRZ alphabet (A-Z, 0-9, <); got '${violation.description}'",
        )
    }

    @Test
    fun is_an_mrz_error_subtype() {
        val violation: MrzError = MrzCharacterSetViolation('!', 0)
        assertTrue(violation is MrzCharacterSetViolation)
    }

    @Test
    fun is_an_mrz_parse_error_subtype() {
        val violation: MrzError = MrzCharacterSetViolation('!', 0)
        assertTrue(violation is MrzParseError)
    }

    @Test
    fun two_violations_with_same_character_and_position_are_equal() {
        assertEquals(
            MrzCharacterSetViolation('a', 5),
            MrzCharacterSetViolation('a', 5),
        )
    }

    @Test
    fun two_violations_at_different_positions_are_not_equal() {
        assertNotEquals(
            MrzCharacterSetViolation('a', 5),
            MrzCharacterSetViolation('a', 6),
        )
    }

    @Test
    fun two_violations_with_different_characters_are_not_equal() {
        assertNotEquals(
            MrzCharacterSetViolation('a', 5),
            MrzCharacterSetViolation('b', 5),
        )
    }
}
