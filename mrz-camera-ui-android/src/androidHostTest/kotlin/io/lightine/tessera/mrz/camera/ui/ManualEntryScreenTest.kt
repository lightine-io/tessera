package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side test for the manual raw-MRZ entry screen ([ManualRawContent]) and its pure text→Decoded
 * assembly ([assembleManualDecoded] / [manualObservationParts]) — the manual-entry path of
 * [MrzScannerScreen], run on the JVM via Robolectric (no device), same shape as [ReviewScreenTest].
 *
 * The Compose tests drive [ManualRawContent] directly; the parsing / provenance assertions go through the
 * pure [assembleManualDecoded] seam (extracted so the text→Decoded pipeline is host-testable without the
 * camera, mirroring [routeDecode]). The fixtures use the same synthetic ICAO TD3 specimen the parser tests
 * and [ReviewScreenTest] use, parsed at a fixed [referenceTime] so date inference is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualEntryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Fixed so 2-digit year inference is deterministic; at 1994 the specimen parses cleanly to Success.
    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")

    // The clean ICAO TD3 specimen (synthetic; UTO = Utopia, a reserved ICAO test issuer). 2 lines × 44.
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
    private val specimen = "$specimenLine1\n$specimenLine2"

    @Test
    fun typing_text_updates_the_length_observation() {
        composeRule.setContent {
            // Hoist the text into Compose state so onTextChange drives a recomposition — the observation must
            // re-derive from the new input (this is exactly the flow wiring: onTextChange updates ManualRaw).
            var text by remember { mutableStateOf("") }
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(text),
                        onTextChange = { text = it },
                        onRead = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        // Auto (default) shows a per-line char count. Type a 10-char line and confirm the observation reflects
        // it (Auto → char count only, neutral, no verdict).
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput("P<UTOERIKS")
        composeRule.onNodeWithText("Line 1: 10 chars").performScrollTo().assertIsDisplayed()
        // The standing hint-not-fixes note is always present (honesty is never implicit).
        composeRule
            .onNodeWithText("Format chips are parser hints, not fixes — nothing is auto-corrected")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun length_observation_states_a_neutral_delta_when_a_format_chip_fixes_the_expected_length() {
        // A TD3 (Passport, expected 44) line one character short states "1 character short" — neutral, INFO,
        // never MISMATCH and never "invalid" (reader, not oracle). The exact-length line is silent (no "OK").
        val shortLine2 = specimenLine2.dropLast(1) // 43 chars
        val parts = manualObservationParts("$specimenLine1\n$shortLine2", ManualFormatHint.PASSPORT)
        assertEquals(
            listOf(ManualLengthNote.Short(lineNumber = 2, by = 1)),
            parts,
            "an exact line is silent; a short line states a neutral delta, never a mismatch",
        )
        // Auto never states a delta — only the raw char counts.
        assertEquals(
            listOf(
                ManualLengthNote.CharCount(lineNumber = 1, chars = 44),
                ManualLengthNote.CharCount(lineNumber = 2, chars = 43),
            ),
            manualObservationParts("$specimenLine1\n$shortLine2", ManualFormatHint.AUTO),
            "Auto shows only char counts, no delta",
        )
    }

    @Test
    fun a_valid_typed_td3_specimen_assembles_to_a_decoded_success_stamped_manual_entry() {
        // The provenance guarantee: a typed-in valid MRZ parses to Success AND reports MANUAL_ENTRY, so the
        // review screen shows "Read by manual entry" with no extra wiring.
        val decoded = assembleManualDecoded(specimen, ManualFormatHint.AUTO, referenceTime)
        assertTrue(decoded.parse is ParseResult.Success, "the clean specimen must assemble to a Success decode")
        assertEquals(
            ReadMethod.MANUAL_ENTRY,
            decoded.parse.metadata.readMethod,
            "manual entry must stamp MANUAL_ENTRY provenance (not BACKEND_STRING_INPUT)",
        )
        // The typed lines travel back as the recognized text, verbatim (Principle 5).
        assertEquals(
            listOf(specimenLine1, specimenLine2),
            decoded.recognizedText.lines.map { it.text },
            "the typed lines are carried through as the recognized text, verbatim",
        )
        assertEquals(2, decoded.quality.recognizedLineCount)
    }

    @Test
    fun the_passport_chip_runs_the_td3_reader_and_parses_the_specimen_to_success() {
        // Passport → readTD3 path parses the TD3 specimen to Success (and still stamps MANUAL_ENTRY).
        val decoded = assembleManualDecoded(specimen, ManualFormatHint.PASSPORT, referenceTime)
        assertTrue(decoded.parse is ParseResult.Success, "Passport (readTD3) must parse the TD3 specimen to Success")
        assertEquals(ReadMethod.MANUAL_ENTRY, decoded.parse.metadata.readMethod)
    }

    @Test
    fun garbage_text_assembles_to_a_failure_that_routes_to_read_failed() {
        // Garbage → Failure, which routeDecode sends to the read-failed screen exactly as a failed camera
        // decode does (reader, not oracle: the parser's verdict is reported verbatim, never "invalid").
        val decoded = assembleManualDecoded("not an mrz at all", ManualFormatHint.AUTO, referenceTime)
        assertTrue(decoded.parse is ParseResult.Failure, "unparseable text must assemble to a Failure decode")
        assertTrue(
            routeDecode(decoded, ReviewMode.REVIEW) is DecodeRoute.ShowReadFailed,
            "a Failure decode routes to the read-failed screen",
        )
    }

    @Test
    fun read_what_i_typed_fires_on_read_with_the_selected_hint() {
        var readHint: ManualFormatHint? = null
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(specimen),
                        onTextChange = {},
                        onRead = { readHint = it },
                        onBack = {},
                    )
                }
            }
        }

        // Select the Passport chip, then read — the primary action reports the selected hint (default Auto
        // otherwise). The chips never change the text; they only choose the parser.
        composeRule.onNodeWithText("Passport").performClick()
        composeRule.onNodeWithText("Read what I typed").performClick()
        assertEquals(ManualFormatHint.PASSPORT, readHint, "Read what I typed must fire onRead with the selected chip")
    }
}
