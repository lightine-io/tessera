package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzCheckDigitMismatchTest {
    @Test
    fun stores_field_expected_observed_and_position_verbatim() {
        val error =
            MrzCheckDigitMismatch(
                field = MrzField.DOCUMENT_NUMBER,
                expected = '3',
                observed = '7',
                position = 9,
            )
        assertEquals(MrzField.DOCUMENT_NUMBER, error.field)
        assertEquals('3', error.expected)
        assertEquals('7', error.observed)
        assertEquals(9, error.position)
    }

    @Test
    fun description_names_field_and_position_but_not_the_digits() {
        val error =
            MrzCheckDigitMismatch(
                field = MrzField.DATE_OF_EXPIRY,
                expected = '6',
                observed = '5',
                position = 71,
            )
        assertEquals("Check digit for DATE_OF_EXPIRY at position 71 does not match", error.description)
        assertEquals("MrzCheckDigitMismatch: ${error.description}", error.toString())
    }

    @Test
    fun is_a_validation_error() {
        val error: MrzValidationError =
            MrzCheckDigitMismatch(
                field = MrzField.COMPOSITE,
                expected = '4',
                observed = '0',
                position = 87,
            )
        assertTrue(error is MrzCheckDigitMismatch)
    }
}
