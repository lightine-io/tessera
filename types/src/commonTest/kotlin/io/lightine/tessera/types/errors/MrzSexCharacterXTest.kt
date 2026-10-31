package io.lightine.tessera.types.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzSexCharacterXTest {
    @Test
    fun stores_observed_character_and_position_verbatim() {
        val warning = MrzSexCharacterX(observed = 'X', position = 20)
        assertEquals('X', warning.observed)
        assertEquals(20, warning.position)
    }

    @Test
    fun description_names_position_and_visual_zone_distinction() {
        val warning = MrzSexCharacterX(observed = 'X', position = 65)
        assertTrue("65" in warning.description)
        assertTrue("visual zone" in warning.description)
        assertEquals("MrzSexCharacterX: ${warning.description}", warning.toString())
    }

    @Test
    fun is_a_warning_and_an_mrz_warning() {
        val warning: MrzWarning = MrzSexCharacterX(observed = 'X', position = 20)
        assertTrue(warning is MrzSexCharacterX)
    }
}
