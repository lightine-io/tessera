package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side test for [ScannerUiStateSaver] / [encodeScannerUiState] / [decodeScannerUiState] (TES-102) — the
 * `rememberSaveable` codec that makes the flow's `uiState` survive a configuration change, run on the JVM via
 * Robolectric (no device), same shape as the module's other host tests.
 *
 * Most of these drive the pure codec functions directly (no Bundle, no Activity, no Compose runtime needed);
 * the last test drives the real `Saver` through [StateRestorationTester], the module's first use of it, to
 * prove the wiring end to end rather than only the pure encode/decode halves.
 *
 * The [ScannerUiState.Review] fixtures use the clean synthetic ICAO TD3 specimen the rest of this module's
 * tests use (synthetic; UTO = Utopia, a reserved ICAO test issuer) — never real document data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerUiStateSaverTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The clean ICAO TD3 specimen used across the module's tests (see e.g. ReviewScreenTest). Built with NO
    // fixed referenceTime here (unlike those tests): decodeReview always re-parses at Clock.System.now()
    // (the accepted "fresh referenceTime" edge documented on ScannerUiStateSaver.kt), so the ORIGINAL decode
    // in these tests is likewise built at the wall clock's current instant — both parses land in the same
    // date-resolution window (they run milliseconds apart), so the restored document is byte-for-byte equal
    // to the original without pinning the test to a specific calendar date.
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // ---------------------------------------------------------------------------------------------------
    // Scanning — struggling (latched) survives; gathering (live-session) always resets to false.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun scanning_round_trip_keeps_the_latched_struggling_hint_but_resets_gathering() {
        val beforeRestore = ScannerUiState.Scanning(struggling = true, gathering = true)
        val restored = decodeScannerUiState(encodeScannerUiState(beforeRestore))
        assertEquals(
            ScannerUiState.Scanning(struggling = true, gathering = false),
            restored,
            "struggling is a latched hint and must survive; gathering describes the live consensus wait over " +
                "a camera stream that died with the old composition, so it must not resurrect as true",
        )
    }

    @Test
    fun scanning_round_trip_with_neither_flag_set() {
        val plain = ScannerUiState.Scanning()
        assertEquals(plain, decodeScannerUiState(encodeScannerUiState(plain)))
    }

    // ---------------------------------------------------------------------------------------------------
    // Data objects — tag-only round trip to themselves, except SavedImageAnalyzing.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun tag_only_states_round_trip_to_themselves() {
        assertEquals(ScannerUiState.CameraInUse, decodeScannerUiState(encodeScannerUiState(ScannerUiState.CameraInUse)))
        assertEquals(
            ScannerUiState.CameraUnavailable,
            decodeScannerUiState(encodeScannerUiState(ScannerUiState.CameraUnavailable)),
        )
        assertEquals(
            ScannerUiState.AwaitingSavedImagePick,
            decodeScannerUiState(encodeScannerUiState(ScannerUiState.AwaitingSavedImagePick)),
        )
        assertEquals(ScannerUiState.SavedImageEmpty, decodeScannerUiState(encodeScannerUiState(ScannerUiState.SavedImageEmpty)))
    }

    @Test
    fun saved_image_analyzing_restores_as_awaiting_saved_image_pick_not_itself() {
        // The analysis coroutine (rememberCoroutineScope) that would deliver a result dies with the old
        // composition, so a restored "Analyzing…" screen would spin forever with nothing left to finish it.
        val restored = decodeScannerUiState(encodeScannerUiState(ScannerUiState.SavedImageAnalyzing))
        assertEquals(
            ScannerUiState.AwaitingSavedImagePick,
            restored,
            "must honestly re-offer the pick rather than resurrect an analysis that can never complete",
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // ManualRaw — both fields round trip.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun manual_raw_round_trip_preserves_text_and_parse_failed() {
        val withFailure = ScannerUiState.ManualRaw(text = "P<UTOERIKSSON<<ANNA<MARIA", parseFailed = true)
        assertEquals(withFailure, decodeScannerUiState(encodeScannerUiState(withFailure)))

        val clean = ScannerUiState.ManualRaw(text = "", parseFailed = false)
        assertEquals(clean, decodeScannerUiState(encodeScannerUiState(clean)))
    }

    // ---------------------------------------------------------------------------------------------------
    // ReadFailed — recognized-text lines round trip, including a null per-line confidence (NaN sentinel).
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun read_failed_round_trip_preserves_lines_with_confidences() {
        val captured =
            RecognizedText(
                lines =
                    listOf(
                        RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<", 0.42f),
                        RecognizedLine("L8989 2C36UT0 74O8122F32O41 9<<<<", 0.81f),
                    ),
            )
        val state = ScannerUiState.ReadFailed(captured)
        assertEquals(state, decodeScannerUiState(encodeScannerUiState(state)))
    }

    @Test
    fun read_failed_round_trip_preserves_a_null_confidence_via_the_nan_sentinel() {
        val captured =
            RecognizedText(
                lines =
                    listOf(
                        RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<", null),
                        RecognizedLine("L8989 2C36UT0 74O8122F32O41 9<<<<", 0.5f),
                    ),
            )
        val state = ScannerUiState.ReadFailed(captured)
        val restored = decodeScannerUiState(encodeScannerUiState(state))
        assertEquals(state, restored, "a null per-line confidence must survive the NaN-sentinel round trip")
        assertNull(
            (restored as ScannerUiState.ReadFailed).capturedText.lines[0].confidence,
            "NaN must decode back to null, never to Float.NaN itself leaking into the public RecognizedLine",
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // Review — re-parse + provenance restamp. The interesting variant.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun review_round_trip_from_manual_entry_reproduces_the_parsed_document_and_provenance() {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        assertTrue(decoded.parse is ParseResult.Success, "the clean synthetic specimen must parse to Success")
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.MANUAL_ENTRY, expanded = true)

        val restored = decodeScannerUiState(encodeScannerUiState(review))

        assertEquals(
            review,
            restored,
            "the round trip must reproduce the review verbatim — parse verdict, document, rawLines, " +
                "recognizedText, quality, source, and expanded all equal the original",
        )
        assertEquals(
            ReadMethod.MANUAL_ENTRY,
            (restored as ScannerUiState.Review)
                .decoded.parse.metadata.readMethod,
            "the re-parse restamps MANUAL_ENTRY back on — a bare MrzParser re-parse alone would report BACKEND_STRING_INPUT",
        )
    }

    @Test
    fun review_provenance_restamp_reports_the_original_read_method_for_a_camera_reading() {
        // Take the same decode but restamp its provenance to LIVE_CAMERA first (the public copy(), exactly as
        // ReviewScreenTest's own fixture helper does) — this is what a genuine camera-sourced Review carries,
        // as opposed to the MANUAL_ENTRY provenance ManualMrzReader stamps by construction above.
        val manualDecoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        assertTrue(manualDecoded.parse is ParseResult.Success)
        val cameraParse =
            when (val p = manualDecoded.parse) {
                is ParseResult.Success -> p.copy(metadata = p.metadata.copy(readMethod = ReadMethod.LIVE_CAMERA))
                is ParseResult.PartialSuccess -> p.copy(metadata = p.metadata.copy(readMethod = ReadMethod.LIVE_CAMERA))
                is ParseResult.Failure -> p.copy(metadata = p.metadata.copy(readMethod = ReadMethod.LIVE_CAMERA))
            }
        val cameraDecoded = manualDecoded.copy(parse = cameraParse)
        val review = ScannerUiState.Review(decoded = cameraDecoded, source = ScanMethod.CAMERA, expanded = false)

        val restored = decodeScannerUiState(encodeScannerUiState(review)) as? ScannerUiState.Review

        assertEquals(
            ReadMethod.LIVE_CAMERA,
            restored
                ?.decoded
                ?.parse
                ?.metadata
                ?.readMethod,
            "must restamp the read method the payload actually recorded — neither BACKEND_STRING_INPUT (what " +
                "a bare re-parse reports) nor MANUAL_ENTRY (the fixture's provenance before the restamp above)",
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // Garbage payloads — decode must return null, never throw.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun an_unknown_tag_decodes_to_null() {
        assertNull(decodeScannerUiState(listOf("NotARealTag")))
    }

    @Test
    fun the_wrong_arity_for_a_known_tag_decodes_to_null() {
        // ManualRaw expects exactly 3 elements (tag, text, parseFailed).
        assertNull(decodeScannerUiState(listOf("ManualRaw", "typed text")), "missing parseFailed must not decode")
        assertNull(
            decodeScannerUiState(listOf("ManualRaw", "typed text", true, "unexpected extra element")),
            "an extra trailing element must not decode",
        )
    }

    @Test
    fun a_wrong_typed_element_decodes_to_null() {
        // struggling (index 1) must be a Boolean, not a String.
        assertNull(decodeScannerUiState(listOf("Scanning", "not-a-boolean")))
    }

    @Test
    fun an_unrecognized_read_method_name_in_a_review_payload_decodes_to_null() {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.MANUAL_ENTRY, expanded = false)
        val corrupted = encodeScannerUiState(review).toMutableList()
        corrupted[7] = "NOT_A_READ_METHOD"
        assertNull(decodeScannerUiState(corrupted), "an enum name ReadMethod no longer recognizes must decode to null, not throw")
    }

    @Test
    fun an_unrecognized_scan_method_name_in_a_review_payload_decodes_to_null() {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.MANUAL_ENTRY, expanded = false)
        val corrupted = encodeScannerUiState(review).toMutableList()
        corrupted[8] = "NOT_A_SCAN_METHOD"
        assertNull(decodeScannerUiState(corrupted), "an enum name ScanMethod no longer recognizes must decode to null, not throw")
    }

    // ---------------------------------------------------------------------------------------------------
    // The Saver wired through rememberSaveable, via StateRestorationTester — proves the real Compose
    // integration, not just the pure codec above. The live-camera composables need a real device (per the
    // module's testing-layers rule), so this drives a minimal composable holding the flow's exact
    // rememberSaveable(stateSaver = ScannerUiStateSaver) call, rather than the whole MrzScannerScreen.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun the_saver_survives_a_real_state_restoration_through_remember_saveable() {
        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null

        restorationTester.setContent {
            current =
                rememberSaveable(stateSaver = ScannerUiStateSaver) {
                    mutableStateOf(ScannerUiState.ManualRaw(text = "ABC123", parseFailed = true))
                }.value
        }

        assertEquals(ScannerUiState.ManualRaw(text = "ABC123", parseFailed = true), current)

        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(
            ScannerUiState.ManualRaw(text = "ABC123", parseFailed = true),
            current,
            "the state must survive emulateSavedInstanceStateRestore — this exercises the real Saver through " +
                "rememberSaveable end to end, not just the pure encode/decode functions above",
        )
    }
}
