@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
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
            listOf(ChipMalformedFile(null, ChipMalformedFile.Reason.NOT_VALID_TLV)) +
            ChipMalformedFile.Reason.entries.map { ChipMalformedFile(0x0101, it) }

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
        assertEquals(4, errors.map { it::class }.toSet().size)
    }

    private fun exhaustive(e: ChipError): Boolean =
        when (e) {
            is NfcTagLost,
            is NfcTimeout,
            is NfcUnavailable,
            is ChipMalformedFile,
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
