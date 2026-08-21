package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 *
 * TES-100 removed the Auto / Passport / ID card format-hint chip row: reading always auto-detects the format
 * ([ManualMrzReader.read]), and the per-line observation is always the plain character count (the behaviour
 * AUTO already had).
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
                    )
                }
            }
        }

        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        // The observation is always the plain per-line character count (TES-100 — no format chips to fix an
        // expected length anymore).
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput("P<UTOERIKS")
        composeRule.onNodeWithText("Line 1: 10 characters").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_one_character_delta_still_renders_as_a_singular_character_count() {
        // A 43-character line (one short of the TD3 length) has no "expected length" to compare against
        // anymore (TES-100) — it just reports its own count, singular where that count is 1.
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw("X"),
                        onTextChange = {},
                        onRead = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Line 1: 1 character").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun no_format_hint_chips_or_hint_note_are_shown() {
        // TES-100: the Auto / Passport / ID card chip row and the "buttons above just say which document type
        // to expect" note are removed entirely.
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(state = ScannerUiState.ManualRaw(specimen), onTextChange = {}, onRead = {})
                }
            }
        }

        composeRule.onNodeWithText("Passport").assertDoesNotExist()
        composeRule.onNodeWithText("ID card").assertDoesNotExist()
        composeRule.onNodeWithText("Auto").assertDoesNotExist()
        composeRule.onNodeWithText("The buttons above just say which document type to expect.").assertDoesNotExist()
    }

    @Test
    fun read_is_disabled_while_the_field_is_blank() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(text = ""),
                        onTextChange = {},
                        onRead = {},
                    )
                }
            }
        }

        // Nothing to read — the primary action is disabled, so an empty input can never be reported as a
        // malformed "couldn't read" (it is simply absent).
        composeRule.onNodeWithText("Read this").assertIsNotEnabled()
    }

    @Test
    fun a_parse_failed_state_shows_the_inline_note_and_keeps_the_typed_text() {
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(text = "NOT AN MRZ", parseFailed = true),
                        onTextChange = {},
                        onRead = {},
                    )
                }
            }
        }

        // The failure stays on THIS screen with an inline note (no jump to the camera-flavoured read-failed
        // screen); the typed text is preserved in the field.
        composeRule.onNodeWithText("That didn't match a known MRZ format - check the lines and lengths above.").assertIsDisplayed()
    }

    @Test
    fun length_observation_is_always_the_plain_character_count() {
        val shortLine2 = specimenLine2.dropLast(1) // 43 chars
        assertEquals(
            listOf(
                ManualLengthNote(lineNumber = 1, chars = 44),
                ManualLengthNote(lineNumber = 2, chars = 43),
            ),
            manualObservationParts("$specimenLine1\n$shortLine2"),
            "TES-100: no expected-length delta — every line only ever reports its own character count",
        )
    }

    @Test
    fun a_valid_typed_td3_specimen_assembles_to_a_decoded_success_stamped_manual_entry() {
        // The provenance guarantee: a typed-in valid MRZ parses to Success AND reports MANUAL_ENTRY, so the
        // review screen shows "Read by manual entry" with no extra wiring.
        val decoded = assembleManualDecoded(specimen, referenceTime)
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
    fun garbage_text_assembles_to_a_failure_that_routes_to_read_failed() {
        // Garbage → Failure, which routeDecode sends to the read-failed screen exactly as a failed camera
        // decode does (reader, not oracle: the parser's verdict is reported verbatim, never "invalid").
        val decoded = assembleManualDecoded("not an mrz at all", referenceTime)
        assertTrue(decoded.parse is ParseResult.Failure, "unparseable text must assemble to a Failure decode")
        assertTrue(
            routeDecode(decoded, ReviewMode.REVIEW) is DecodeRoute.ShowReadFailed,
            "a Failure decode routes to the read-failed screen",
        )
    }

    @Test
    fun typing_past_the_cap_leaves_the_field_at_exactly_max_chars() {
        // TES-137: MANUAL_ENTRY_MAX_CHARS bounds the draft — a paste (or, here, one big performTextInput) far
        // past the cap must truncate, never reject and never overflow past it. Garbage repeats of a single
        // character, well past any real MRZ (never real document data).
        var latestText = ""
        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            latestText = text
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(text),
                        onTextChange = {
                            text = it
                            latestText = it
                        },
                        onRead = {},
                    )
                }
            }
        }

        val pasted = "A".repeat(MANUAL_ENTRY_MAX_CHARS + 500)
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput(pasted)

        assertEquals(
            MANUAL_ENTRY_MAX_CHARS,
            latestText.length,
            "a paste past the cap must truncate the draft to exactly MANUAL_ENTRY_MAX_CHARS, not reject it or " +
                "let it overflow",
        )
    }

    @Test
    fun read_this_fires_on_read() {
        var readCalls = 0
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    ManualRawContent(
                        state = ScannerUiState.ManualRaw(specimen),
                        onTextChange = {},
                        onRead = { readCalls++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Read this").performClick()
        assertEquals(1, readCalls, "Read this must fire onRead")
    }
}
