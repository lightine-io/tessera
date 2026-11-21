@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ChipImageGroupMalformedTest {
    @Test
    fun text_names_group_problem_and_offset_only() {
        val e = ChipImageGroupMalformed(ChipImageGroup.DG5, ChipImageGroupProblem.LENGTH_EXCEEDS_DATA, 7)
        assertEquals("The DG5 file is malformed: LENGTH_EXCEEDS_DATA at offset 7", e.description)
        assertEquals("ChipImageGroupMalformed: ${e.description}", e.toString())
    }

    @Test
    fun text_without_an_offset() {
        val e = ChipImageGroupMalformed(ChipImageGroup.DG2, ChipImageGroupProblem.MISSING_OBJECT, null)
        assertEquals("The DG2 file is malformed: MISSING_OBJECT", e.description)
    }
}
