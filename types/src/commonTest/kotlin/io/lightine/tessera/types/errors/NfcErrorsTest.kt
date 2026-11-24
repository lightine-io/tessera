@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import io.lightine.tessera.types.vocabulary.MrzField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NfcTagLostTest {
    @Test
    fun is_an_nfc_error_and_a_chip_error() {
        val error: ChipError = NfcTagLost
        assertIs<NfcError>(error)
        assertEquals("NfcTagLost: ${NfcTagLost.description}", NfcTagLost.toString())
    }
}

class NfcTimeoutTest {
    @Test
    fun stores_the_time_limit_verbatim_and_names_it() {
        val error = NfcTimeout(timeoutMillis = 5000)
        assertEquals(5000L, error.timeoutMillis)
        assertTrue("5000" in error.description, error.description)
        assertIs<NfcError>(error)
    }

    @Test
    fun unknown_time_limit_has_its_own_text() {
        val error = NfcTimeout(timeoutMillis = null)
        assertEquals(null, error.timeoutMillis)
        assertFalse("null" in error.description, error.description)
    }
}

class NfcUnavailableTest {
    @Test
    fun each_reason_has_its_own_text() {
        val texts = NfcUnavailable.Reason.entries.map { NfcUnavailable(it).description }
        assertEquals(texts.size, texts.toSet().size)
        NfcUnavailable.Reason.entries.forEach {
            val error = NfcUnavailable(it)
            assertEquals(it, error.reason)
            assertIs<NfcError>(error)
        }
    }
}

/**
 * Chip error texts follow the MRZ errors' rules (TES-234, TES-206): `toString()` is the class name
 * and the description, and no description has a long dash. Chip errors carry no document values
 * at all yet; the exhaustive `when` makes a new subtype a compile error until it is listed here,
 * where its document-value properties must get sentinels like [ErrorTextCarriesNoDocumentValuesTest].
 */
class ChipErrorTextTest {
    private val errors: List<ChipError> =
        listOf(NfcTagLost, NfcTimeout(1234), NfcTimeout(null)) +
            NfcUnavailable.Reason.entries.map { NfcUnavailable(it) } +
            listOf(
                ChipMalformedFile(null, ChipMalformedFile.Reason.NOT_VALID_TLV),
                ChipMalformedFile(0x0102, ChipMalformedFile.Reason.NOT_VALID_TLV, 17),
            ) +
            ChipMalformedFile.Reason.entries.map { ChipMalformedFile(0x0101, it) } +
            ChipAccessDenied.Reason.entries.map { ChipAccessDenied(ChipAccessProtocol.PACE, it, 0x6300) } +
            listOf(ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED)) +
            ChipSecureMessagingLost.Reason.entries.map { ChipSecureMessagingLost(it) } +
            listOf(ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.CHIP_ENDED_SESSION, 0x6988)) +
            ChipUnsupportedAlgorithm.Reason.entries.map { ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, it) } +
            listOf(ChipFileNotFound(0x0102, true), ChipFileNotFound(0x010B, false), ChipBlank) +
            ChipUnexpectedAnswer.Reason.entries.map { ChipUnexpectedAnswer(it, 0x6F00) } +
            listOf(
                ChipKeyCheckDigitMismatch(setOf(MrzField.DATE_OF_BIRTH)),
                ChipKeyCheckDigitMismatch(setOf(MrzField.DATE_OF_EXPIRY, MrzField.DOCUMENT_NUMBER)),
            )

    @Test
    fun to_string_is_the_class_name_then_the_description() {
        errors.forEach { assertEquals("${it::class.simpleName}: ${it.description}", it.toString()) }
    }

    @Test
    fun no_description_contains_a_long_dash() {
        errors.forEach {
            assertFalse('—' in it.description, "em dash in: ${it.description}")
            assertFalse('–' in it.description, "en dash in: ${it.description}")
        }
    }

    @Test
    fun sample_list_covers_every_concrete_type() {
        assertEquals(errors.size, errors.count { exhaustive(it) })
        assertEquals(11, errors.map { it::class }.toSet().size)
    }

    private fun exhaustive(e: ChipError): Boolean =
        when (e) {
            is NfcTagLost,
            is NfcTimeout,
            is NfcUnavailable,
            is ChipMalformedFile,
            is ChipAccessDenied,
            is ChipSecureMessagingLost,
            is ChipUnsupportedAlgorithm,
            is ChipFileNotFound,
            is ChipUnexpectedAnswer,
            is ChipBlank,
            is ChipKeyCheckDigitMismatch,
            -> true
        }
}

class ChipMalformedFileTest {
    @Test
    fun is_a_chip_error_that_names_the_file_and_the_reason_without_values() {
        val error: ChipError = ChipMalformedFile(0x0101, ChipMalformedFile.Reason.MISSING_ELEMENT)
        assertTrue("0101" in error.description, error.description)
        assertTrue("missing" in error.description, error.description)
        assertFalse("0101" in ChipMalformedFile(null, ChipMalformedFile.Reason.MISSING_ELEMENT).description)
    }

    @Test
    fun each_reason_has_its_own_text() {
        val texts = ChipMalformedFile.Reason.entries.map { ChipMalformedFile(null, it).description }
        assertEquals(texts.size, texts.toSet().size)
    }
}

class ChipMalformedFileOffsetTest {
    @Test
    fun names_the_offset_when_known() {
        val error = ChipMalformedFile(0x0102, ChipMalformedFile.Reason.NESTING_TOO_DEEP, 42)
        assertEquals(42, error.offset)
        assertTrue("offset 42" in error.description, error.description)
        assertFalse("offset" in ChipMalformedFile(0x0102, ChipMalformedFile.Reason.NESTING_TOO_DEEP).description)
    }
}

class ChipProtocolErrorsTest {
    @Test
    fun access_denied_names_protocol_reason_and_status_word() {
        val error = ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300)
        assertTrue("BAC" in error.description && "6300" in error.description, error.description)
        val texts = ChipAccessDenied.Reason.entries.map { ChipAccessDenied(ChipAccessProtocol.PACE, it).description }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun secure_messaging_lost_has_one_text_per_reason() {
        val texts = ChipSecureMessagingLost.Reason.entries.map { ChipSecureMessagingLost(it).description }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue("6988" in ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.CHIP_ENDED_SESSION, 0x6988).description)
    }

    @Test
    fun unsupported_algorithm_has_one_text_per_reason() {
        val texts = ChipUnsupportedAlgorithm.Reason.entries.map { ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, it).description }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun file_not_found_names_the_file_and_whether_it_was_asked_for() {
        assertTrue("0102" in ChipFileNotFound(0x0102, true).description)
        assertTrue("not read" in ChipFileNotFound(0x0102, false).description)
    }

    @Test
    fun unexpected_answer_and_blank_chip_texts() {
        val texts = ChipUnexpectedAnswer.Reason.entries.map { ChipUnexpectedAnswer(it).description }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue("blank" in ChipBlank.description)
    }
}

class ChipKeyCheckDigitMismatchTest {
    @Test
    fun names_the_fields_in_mrz_order_and_never_a_value() {
        val error = ChipKeyCheckDigitMismatch(setOf(MrzField.DATE_OF_EXPIRY, MrzField.DOCUMENT_NUMBER))
        assertEquals(setOf(MrzField.DOCUMENT_NUMBER, MrzField.DATE_OF_EXPIRY), error.fields)
        assertTrue("document number, date of expiry" in error.description, error.description)
        assertFalse(error.description.any { it.isDigit() }, error.description)
        assertIs<ChipError>(error)
    }

    @Test
    fun a_read_in_progress_has_its_own_reason() {
        val error = NfcUnavailable(NfcUnavailable.Reason.READ_IN_PROGRESS)
        assertTrue("already running" in error.description, error.description)
    }
}
