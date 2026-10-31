@file:Suppress("DEPRECATION") // MrzBirthDateImplausiblyOld is deprecated, never reported (TES-202)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every observation `description` is plain text with no long dash (TES-206).
 *
 * The three `when` functions below are exhaustive over the sealed roots. Adding a new
 * observation type breaks the build here until it is added to the matching sample list.
 */
class ObservationDescriptionTextTest {
    private val date = LocalDate(2030, 1, 1)

    private val warnings: List<MrzWarning> =
        listOf(
            MrzBirthDateImplausiblyOld("00", "01", "01", date, 120),
            MrzExpiryDateImplausiblyFar(date, date, 20),
            MrzExpiryDatePast(date, date),
            MrzNameTruncated("SMITH<<JOHN", 5),
            MrzPersonalNumberCheckDigitFiller("AB123", 42),
            MrzSexCharacterX('X', 20),
            MrzUnknownCountryCode(MrzField.NATIONALITY, "ZZZ", 10),
            MrzUnknownDocumentTypeCode("PX", 0),
        )

    private val validationErrors: List<MrzValidationError> =
        listOf(
            MrzCheckDigitMismatch(MrzField.DOCUMENT_NUMBER, '1', '2', 9),
            MrzDateNotInCalendar(MrzField.DATE_OF_BIRTH, "99", "13", "40", 13),
            MrzInvalidSexValue('Q', 20),
        )

    private val errors: List<MrzError> =
        listOf(
            MrzCharacterSetViolation('a', 3),
            MrzFormatNotDetected(2, listOf(10, 12)),
            MrzInvalidLength(MrzFormat.TD3, 2, 44, 2, listOf(44, 40)),
            MrzGenerationFieldOverflow(MrzFormat.TD3, MrzField.DOCUMENT_NUMBER, 9, 10, "1234567890"),
            MrzGenerationFieldTooShort(MrzFormat.TD3, MrzField.DATE_OF_BIRTH, 6, 5, "00010"),
            MrzGenerationInvalidSexValue(MrzFormat.TD3, 'A'),
            MrzGenerationNumericInNameField(MrzFormat.TD3, "J0HN", listOf('0')),
            MrzGenerationUnsupportedCharacters(
                MrzFormat.TD3,
                MrzField.NAME_FIELD,
                listOf(UnmappedCharacter('?', 2)),
                "AB?",
            ),
        )

    @Test
    fun no_description_contains_a_long_dash() {
        val descriptions = warnings.map { it.description } + validationErrors.map { it.description } + errors.map { it.description }
        descriptions.forEach { text ->
            assertFalse('—' in text, "em dash in: $text")
            assertFalse('–' in text, "en dash in: $text")
        }
    }

    @Test
    fun sample_lists_cover_every_concrete_type() {
        assertEquals(warnings.size, warnings.map { it::class }.toSet().size)
        assertEquals(warnings.size, warnings.count { exhaustive(it) })
        assertEquals(validationErrors.size, validationErrors.count { exhaustive(it) })
        assertEquals(errors.size, errors.count { exhaustive(it) })
    }

    // Exhaustive `when`s: a new subtype is a compile error until it is listed here and in the samples.
    private fun exhaustive(w: MrzWarning): Boolean =
        when (w) {
            is MrzBirthDateImplausiblyOld,
            is MrzExpiryDateImplausiblyFar,
            is MrzExpiryDatePast,
            is MrzNameTruncated,
            is MrzPersonalNumberCheckDigitFiller,
            is MrzSexCharacterX,
            is MrzUnknownCountryCode,
            is MrzUnknownDocumentTypeCode,
            -> true
        }

    private fun exhaustive(e: MrzValidationError): Boolean =
        when (e) {
            is MrzCheckDigitMismatch,
            is MrzDateNotInCalendar,
            is MrzInvalidSexValue,
            -> true
        }

    private fun exhaustive(e: MrzError): Boolean =
        when (e) {
            is MrzCharacterSetViolation,
            is MrzFormatNotDetected,
            is MrzInvalidLength,
            is MrzGenerationFieldOverflow,
            is MrzGenerationFieldTooShort,
            is MrzGenerationInvalidSexValue,
            is MrzGenerationNumericInNameField,
            is MrzGenerationUnsupportedCharacters,
            -> true
        }
}
