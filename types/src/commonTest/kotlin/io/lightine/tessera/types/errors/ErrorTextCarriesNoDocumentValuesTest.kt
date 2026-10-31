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
 * The `description` and `toString()` of every error and warning carry no document values (TES-234).
 *
 * Every document-value property below holds a sentinel that appears nowhere in the fixed text.
 * The three `when` functions are exhaustive over the sealed roots, so a new subtype is a compile
 * error until it is listed here and in the samples.
 */
class ErrorTextCarriesNoDocumentValuesTest {
    // Letters and digits that no fixed message text contains.
    private val text = "ZZQSENTINEL"
    private val sentinelDate = LocalDate(1987, 6, 5)
    private val sentinelChar = 'Ж'
    private val sentinelDigit = '7'
    private val sentinelYear = "73"
    private val sentinelMonth = "58"
    private val sentinelDay = "94"

    private val sentinels: List<String> =
        listOf(text, "1987", "06-05", sentinelChar.toString(), sentinelYear, sentinelMonth, sentinelDay, "7")

    private val warnings: List<MrzWarning> =
        listOf(
            MrzBirthDateImplausiblyOld(sentinelYear, sentinelMonth, sentinelDay, sentinelDate, 131),
            MrzExpiryDateImplausiblyFar(sentinelDate, sentinelDate, 11),
            MrzExpiryDatePast(sentinelDate, sentinelDate),
            MrzNameTruncated(text, 5),
            MrzPersonalNumberCheckDigitFiller(text, 42),
            MrzSexCharacterX(sentinelChar, 20),
            MrzUnknownCountryCode(MrzField.NATIONALITY, text, 10),
            MrzUnknownDocumentTypeCode(text, 0),
        )

    private val validationErrors: List<MrzValidationError> =
        listOf(
            MrzCheckDigitMismatch(MrzField.DOCUMENT_NUMBER, sentinelChar, sentinelDigit, 9),
            MrzDateNotInCalendar(MrzField.DATE_OF_BIRTH, sentinelYear, sentinelMonth, sentinelDay, 13),
            MrzInvalidSexValue(sentinelChar, 20),
        )

    private val errors: List<MrzError> =
        listOf(
            MrzCharacterSetViolation(sentinelChar, 3),
            MrzFormatNotDetected(2, listOf(10, 12)),
            MrzInvalidLength(MrzFormat.TD3, 2, 44, 2, listOf(44, 40)),
            MrzGenerationFieldOverflow(MrzFormat.TD3, MrzField.NAME_FIELD, 39, 45, text),
            MrzGenerationFieldTooShort(MrzFormat.TD3, MrzField.DATE_OF_BIRTH, 6, 5, text),
            MrzGenerationNumericInNameField(MrzFormat.TD3, text, listOf(sentinelDigit)),
            MrzGenerationUnsupportedCharacters(
                MrzFormat.TD3,
                MrzField.NAME_FIELD,
                listOf(UnmappedCharacter(sentinelChar, 2)),
                text,
            ),
        )

    @Test
    fun no_text_contains_a_sentinel() {
        val all: List<Pair<String, String>> =
            warnings.map { it.description to it.toString() } +
                validationErrors.map { it.description to it.toString() } +
                errors.map { it.description to it.toString() }
        all.forEach { (description, string) ->
            sentinels.forEach { sentinel ->
                assertFalse(sentinel in description, "description carries '$sentinel': $description")
                assertFalse(sentinel in string, "toString carries '$sentinel': $string")
            }
        }
    }

    @Test
    fun to_string_is_the_class_name_then_the_description() {
        warnings.forEach { assertEquals("${it::class.simpleName}: ${it.description}", it.toString()) }
        validationErrors.forEach { assertEquals("${it::class.simpleName}: ${it.description}", it.toString()) }
        errors.forEach { assertEquals("${it::class.simpleName}: ${it.description}", it.toString()) }
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
            is MrzGenerationNumericInNameField,
            is MrzGenerationUnsupportedCharacters,
            -> true
        }
}
