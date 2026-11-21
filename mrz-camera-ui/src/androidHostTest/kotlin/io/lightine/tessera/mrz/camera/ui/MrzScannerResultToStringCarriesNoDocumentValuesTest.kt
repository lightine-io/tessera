package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * [MrzScannerResult.Confirmed] holds the decoded reading, which holds document values, so its
 * `toString()` shows nothing of it.
 *
 * The reading below is built from the project's specimen data (the SDK's own synthetic person, never
 * a real document), and the string must contain none of the specimen's values.
 */
class MrzScannerResultToStringCarriesNoDocumentValuesTest {
    private val specimen =
        "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<\nT220001293UTO9003152F3403141S98765432<<<<<20"

    private val forbidden =
        listOf(
            "SPECIMEN",
            "ALEX",
            "MORGAN",
            "T22000129",
            "S98765432",
            "900315",
            "340314",
        )

    @Test
    fun confirmed_shows_no_document_values() {
        val decoded = assembleManualDecoded(specimen)
        assertIs<ParseResult.Success>(decoded.parse)
        val rendered = MrzScannerResult.Confirmed(decoded).toString()
        forbidden.forEach { value ->
            assertFalse(value in rendered, "toString carries the specimen value '$value': $rendered")
        }
        assertEquals("Confirmed", rendered)
    }
}
