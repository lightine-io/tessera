package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorMrvATest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // The project's MRV-A specimen.
    private val specimenLine1 = Specimens.MRV_A[0]
    private val specimenLine2 = Specimens.MRV_A[1]
    private val specimenLines = listOf(specimenLine1, specimenLine2)

    private fun specimenMrvA(): MrvA {
        val result = MrzParser.parseMRVA(specimenLines, referenceTime = ref2026)
        return assertIs<MrvA>(assertIs<ParseResult.Success>(result).document)
    }

    // --- Round-trip happy path ---

    @Test
    fun generate_produces_specimen_lines_verbatim() {
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenMrvA()))
        assertEquals(specimenLines, regenerated.mrz)
    }

    @Test
    fun generate_then_parse_round_trips_raw_fields() {
        val mrvA = specimenMrvA()
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(mrvA))
        val reparsed = assertIs<ParseResult.Success>(MrzParser.parseMRVA(regenerated.mrz, referenceTime = ref2026))
        val mrvARoundTripped = assertIs<MrvA>(reparsed.document)

        assertEquals(mrvA.commonFields.documentNumber, mrvARoundTripped.commonFields.documentNumber)
        assertEquals(mrvA.commonFields.dateOfBirth.rawYear, mrvARoundTripped.commonFields.dateOfBirth.rawYear)
        assertEquals(mrvA.commonFields.rawSex, mrvARoundTripped.commonFields.rawSex)
        assertEquals(mrvA.optionalData, mrvARoundTripped.optionalData)
    }

    @Test
    fun generated_lines_have_correct_dimensions() {
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenMrvA()))
        assertEquals(2, success.mrz.size)
        success.mrz.forEach { assertEquals(44, it.length) }
    }

    // --- MRV-A specific: no composite check digit; optional data spans positions 28-43 (16 chars) ---

    @Test
    fun line_2_ends_with_optional_data_not_a_composite_check_digit() {
        // The last character of line 2 must be part of optionalData (filler `<` for our
        // specimen), not a computed composite digit. ICAO Doc 9303 Part 7 defines no
        // composite for visas.
        val success = assertIs<GenerationResult.Success>(MrzGenerator.generate(specimenMrvA()))
        assertEquals("<<<<<<<<<<<<<<<<", success.mrz[1].substring(28, 44))
        assertEquals('<', success.mrz[1][43])
    }

    @Test
    fun mutating_optional_data_changes_line_2_tail_without_affecting_any_check_digit() {
        // No composite digit means the optional-data slot is structurally invisible to the
        // round-trip check-digit story: change optional data, change only that slot.
        val mrvA = specimenMrvA().copy(optionalData = "ABCDEFGHIJKLMNOP")
        val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(mrvA))
        assertEquals("ABCDEFGHIJKLMNOP", regenerated.mrz[1].substring(28, 44))
        // The first 28 chars of line 2 (which carry doc number / DOB / sex / DOE and their
        // check digits) are unchanged.
        assertEquals(specimenLine2.substring(0, 28), regenerated.mrz[1].substring(0, 28))
    }

    // --- Field overflow ---

    @Test
    fun fails_with_overflow_when_optional_data_exceeds_sixteen_characters() {
        val mrvA = specimenMrvA().copy(optionalData = "X".repeat(17))
        val failure = assertIs<GenerationResult.Failure>(MrzGenerator.generate(mrvA))
        val error = assertIs<MrzGenerationFieldOverflow>(failure.error)
        assertEquals(MrzFormat.MRV_A, error.format)
        assertEquals(MrzField.OPTIONAL_DATA, error.field)
        assertEquals(16, error.maxLength)
        assertEquals(17, error.observedLength)
    }
}
