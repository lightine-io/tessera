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
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side test for [ScannerUiStateSaver] / [encodeScannerUiState] / [decodeScannerUiState] /
 * [gateRestoredState] (TES-102) — the `rememberSaveable` codec that makes the flow's `uiState` survive a
 * configuration change, run on the JVM via Robolectric (no device), same shape as the module's other host
 * tests.
 *
 * Most of these drive the pure codec functions directly (no Bundle, no Activity, no Compose runtime needed);
 * the [StateRestorationTester] tests near the end drive the real `Saver` — built via [scannerUiStateSaver] —
 * through `rememberSaveable`, to prove the wiring end to end rather than only the pure encode/decode halves.
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
    // fixed referenceTime here (unlike those tests): decodeReview now re-parses at the ENCODE-TIME clock
    // (TES-102's referenceTime-pin fix — see ScannerUiStateSaver.kt), so the ORIGINAL decode in these tests
    // is likewise built at the wall clock's current instant — both parses land in the same date-resolution
    // window (they run milliseconds apart), so the restored document is byte-for-byte equal to the original
    // without pinning the test to a specific calendar date.
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    // A synthetic specimen with the document-number check digit deliberately flipped (index 9 of line 2: the
    // clean specimen's own recorded check digit '3' → '9') — every other check digit is untouched, so the
    // parse still succeeds SHAPE-wise but the document-number check fails, forcing PartialSuccess rather than
    // Success. Used by the PartialSuccess round-trip test below (TES-A-72 covers the module's PartialSuccess
    // fixtures elsewhere the same way — via one altered check digit on an otherwise-clean specimen).
    private val partialSuccessLine2 = "L898902C<9UTO6908061F9406236ZE184226B<<<<<14"

    // The saver used by the StateRestorationTester tests below: all three methods enabled, so none of the
    // Review / ReadFailed / ManualRaw fixtures those tests restore get gated away by gateRestoredState's
    // config-aware check (TES-102) — those tests are about the round trip through rememberSaveable, not the
    // gate, which has its own dedicated unit tests further down.
    private val allMethodsSaver =
        scannerUiStateSaver(setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY))

    // ---------------------------------------------------------------------------------------------------
    // Scanning — TES-102 gate review: NEITHER overlay is honest to restore, so Scanning always comes back
    // with both false, never whatever it was showing before the recreation.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun scanning_round_trip_always_restores_with_both_overlays_reset_never_resurrecting_struggling() {
        // struggling used to be treated as a latched hint worth restoring, but that contradicts the TES-97
        // gate it exists to serve: showing it requires OCR to have seen text AND the struggle timeout to have
        // elapsed THIS session — both plain (non-Saveable) flags that reset to false on every recreation
        // along with the camera itself. A restored struggling=true would show "still looking" over a session
        // that has analyzed zero frames, which is worse than the plain framing guide it would replace — so
        // Scanning now always restores fresh, and the new session's own gates re-latch it honestly.
        val beforeRestore = ScannerUiState.Scanning(struggling = true, gathering = true)
        val restored = decodeScannerUiState(encodeScannerUiState(beforeRestore))
        assertEquals(
            ScannerUiState.Scanning(struggling = false, gathering = false),
            restored,
            "neither overlay describes anything true about a session that has not analyzed a single frame " +
                "yet, so both must reset — not just gathering",
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

    @Test
    fun manual_raw_encode_truncates_an_over_length_draft_to_max_chars() {
        // TES-137: ManualRawContent's own onValueChange already caps typed/pasted input, but this is the
        // codec's own defensive backstop — a ManualRaw constructed directly (bypassing the Compose field)
        // with an over-length draft must still be bounded before it reaches the Bundle. Garbage repeats, never
        // real document data.
        val overLength = ScannerUiState.ManualRaw(text = "A".repeat(MANUAL_ENTRY_MAX_CHARS + 500), parseFailed = false)
        val encoded = encodeScannerUiState(overLength)
        assertEquals(
            MANUAL_ENTRY_MAX_CHARS,
            (encoded[1] as String).length,
            "encode must truncate an over-length draft to exactly MANUAL_ENTRY_MAX_CHARS before it reaches the Bundle",
        )
    }

    @Test
    fun manual_raw_decode_truncates_an_over_length_saved_payload() {
        // TES-137: a saved payload from a pre-cap app version (or a Bundle a host constructed directly) could
        // still carry an over-length draft; decode must not resurrect one.
        val overLengthPayload = listOf("ManualRaw", "B".repeat(MANUAL_ENTRY_MAX_CHARS + 500), false)
        val restored = decodeScannerUiState(overLengthPayload) as? ScannerUiState.ManualRaw

        assertEquals(
            MANUAL_ENTRY_MAX_CHARS,
            restored?.text?.length,
            "decode must truncate an over-length saved draft to exactly MANUAL_ENTRY_MAX_CHARS, not restore it verbatim",
        )
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

    @Test
    fun review_round_trip_reproduces_a_partial_success_check_digit_mismatch_verbatim() {
        // TES-102's finding rationale: a Review is not always a clean Success — a check-digit mismatch still
        // routes to review (Principle 1, routeDecode), so the round trip must reproduce a PartialSuccess just
        // as faithfully, not just the clean-Success path the other Review tests exercise.
        val manualDecoded = assembleManualDecoded("$specimenLine1\n$partialSuccessLine2")
        assertTrue(
            manualDecoded.parse is ParseResult.PartialSuccess,
            "the flipped document-number check digit must force a check-digit mismatch, not Success or Failure",
        )
        // Restamp to LIVE_CAMERA (the public copy(), exactly as the Success-path provenance test above does)
        // so this test also exercises the restamp — a check-digit mismatch reaching Review by way of the live
        // camera, not manual entry — rather than just reusing assembleManualDecoded's own MANUAL_ENTRY stamp.
        val manualPartial = manualDecoded.parse as ParseResult.PartialSuccess
        val cameraParse = manualPartial.copy(metadata = manualPartial.metadata.copy(readMethod = ReadMethod.LIVE_CAMERA))
        val decoded = manualDecoded.copy(parse = cameraParse)
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.CAMERA, expanded = false)

        val restored = decodeScannerUiState(encodeScannerUiState(review)) as? ScannerUiState.Review
        val restoredParse = restored?.decoded?.parse

        assertTrue(restoredParse is ParseResult.PartialSuccess, "must restore as PartialSuccess, not silently become Success or Failure")
        val originalParse = decoded.parse as ParseResult.PartialSuccess
        assertEquals(originalParse.document, restoredParse.document, "the document must round-trip equal")
        assertEquals(
            originalParse.metadata.validationFailures,
            restoredParse.metadata.validationFailures,
            "the validation failures (the check-digit mismatch itself) must round-trip equal",
        )
        assertEquals(ReadMethod.LIVE_CAMERA, restoredParse.metadata.readMethod, "the camera provenance must still be restamped")
    }

    @Test
    fun review_payload_now_carries_arity_eleven_including_the_encode_time_reference_instant() {
        // TES-102's referenceTime-pin fix (Fix 4) appended ONE slot — the encode-time clock reading, in
        // epoch milliseconds — at the END of the payload, so every earlier index (readMethod at 7, source at
        // 8, expanded at 9) is undisturbed; only the arity itself grows, from 10 to 11.
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.MANUAL_ENTRY, expanded = false)
        assertEquals(11, encodeScannerUiState(review).size)
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
        // Scanning now carries NO fields (both overlays always reset on restore — see the Scanning tests
        // above), so its only valid payload is the tag alone; any extra element must not decode.
        assertNull(decodeScannerUiState(listOf("Scanning", true)), "Scanning no longer has a struggling slot to accept")
    }

    @Test
    fun a_wrong_typed_element_decodes_to_null() {
        // parseFailed (index 2) must be a Boolean, not a String.
        assertNull(decodeScannerUiState(listOf("ManualRaw", "typed text", "not-a-boolean")))
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
    // gateRestoredState — TES-102's config-aware restore gate. Each ScannerUiState variant's owning method,
    // gated against enabledMethods; the Review variant is gated by its OWN source, not a fixed method.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun scanning_and_camera_status_states_gate_on_camera() {
        val allowed = setOf(ScanMethod.CAMERA)
        val disallowed = setOf(ScanMethod.MANUAL_ENTRY, ScanMethod.SAVED_IMAGE)

        assertEquals(ScannerUiState.Scanning(), gateRestoredState(ScannerUiState.Scanning(), allowed))
        assertNull(gateRestoredState(ScannerUiState.Scanning(), disallowed))

        assertEquals(ScannerUiState.CameraInUse, gateRestoredState(ScannerUiState.CameraInUse, allowed))
        assertNull(gateRestoredState(ScannerUiState.CameraInUse, disallowed))

        assertEquals(ScannerUiState.CameraUnavailable, gateRestoredState(ScannerUiState.CameraUnavailable, allowed))
        assertNull(gateRestoredState(ScannerUiState.CameraUnavailable, disallowed))
    }

    @Test
    fun saved_image_states_gate_on_saved_image_including_read_failed() {
        val allowed = setOf(ScanMethod.SAVED_IMAGE)
        val disallowed = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)

        assertEquals(
            ScannerUiState.AwaitingSavedImagePick,
            gateRestoredState(ScannerUiState.AwaitingSavedImagePick, allowed),
        )
        assertNull(gateRestoredState(ScannerUiState.AwaitingSavedImagePick, disallowed))

        assertEquals(ScannerUiState.SavedImageEmpty, gateRestoredState(ScannerUiState.SavedImageEmpty, allowed))
        assertNull(gateRestoredState(ScannerUiState.SavedImageEmpty, disallowed))

        // ReadFailed is only ever reached from the saved-image flow (never live camera, never manual entry —
        // see backEffect's KDoc in MrzScannerScreen.kt), so it gates on SAVED_IMAGE too, not CAMERA.
        val readFailed = ScannerUiState.ReadFailed(RecognizedText(listOf(RecognizedLine("X", null))))
        assertEquals(readFailed, gateRestoredState(readFailed, allowed))
        assertNull(gateRestoredState(readFailed, disallowed))

        // SavedImageAnalyzing is unreachable via decodeScannerUiState in practice (see the file header), but
        // gateRestoredState's `when` is exhaustive over it regardless — pin its owning method too.
        assertEquals(ScannerUiState.SavedImageAnalyzing, gateRestoredState(ScannerUiState.SavedImageAnalyzing, allowed))
        assertNull(gateRestoredState(ScannerUiState.SavedImageAnalyzing, disallowed))
    }

    @Test
    fun manual_raw_gates_on_manual_entry() {
        val manualRaw = ScannerUiState.ManualRaw(text = "ABC", parseFailed = false)
        assertEquals(manualRaw, gateRestoredState(manualRaw, setOf(ScanMethod.MANUAL_ENTRY)))
        assertNull(gateRestoredState(manualRaw, setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE)))
    }

    @Test
    fun review_gates_on_its_own_source_not_a_fixed_method() {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")

        val cameraReview = ScannerUiState.Review(decoded = decoded, source = ScanMethod.CAMERA, expanded = false)
        assertEquals(cameraReview, gateRestoredState(cameraReview, setOf(ScanMethod.CAMERA)))
        assertNull(
            gateRestoredState(cameraReview, setOf(ScanMethod.MANUAL_ENTRY, ScanMethod.SAVED_IMAGE)),
            "a review a camera reading produced must not restore once the host has disabled the camera — " +
                "not-yet-accepted, so a fresh start on an allowed method beats offering a Rescan into a " +
                "method that no longer exists",
        )

        val manualReview = ScannerUiState.Review(decoded = decoded, source = ScanMethod.MANUAL_ENTRY, expanded = true)
        assertEquals(manualReview, gateRestoredState(manualReview, setOf(ScanMethod.MANUAL_ENTRY)))
        assertNull(gateRestoredState(manualReview, setOf(ScanMethod.CAMERA)))

        val savedImageReview = ScannerUiState.Review(decoded = decoded, source = ScanMethod.SAVED_IMAGE, expanded = false)
        assertEquals(savedImageReview, gateRestoredState(savedImageReview, setOf(ScanMethod.SAVED_IMAGE)))
        assertNull(gateRestoredState(savedImageReview, setOf(ScanMethod.MANUAL_ENTRY)))
    }

    @Test
    fun every_variant_passes_through_unchanged_when_all_methods_are_enabled() {
        val allMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY)
        for (state in representativeStates().values) {
            assertEquals(state, gateRestoredState(state, allMethods), "every variant must pass through unchanged when nothing is disabled")
        }
    }

    @Test
    fun an_empty_enabled_methods_set_gates_every_variant_to_null_with_no_special_casing() {
        // No special-casing of an empty enabledMethods: every owning method fails the `in` check against an
        // empty set, so every variant gates to null uniformly — initialState's own defensive "empty set →
        // camera" fallback already owns that edge for a FRESH flow, so gateRestoredState does not duplicate it.
        for (state in representativeStates().values) {
            assertNull(
                gateRestoredState(state, emptySet()),
                "an empty enabledMethods must gate every variant to null, not pass anything through",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The Saver wired through rememberSaveable, via StateRestorationTester — proves the real Compose
    // integration, not just the pure codec above. The live-camera composables need a real device (per the
    // module's testing-layers rule), so this drives a minimal composable holding the flow's exact
    // rememberSaveable(stateSaver = scannerUiStateSaver(...)) call, rather than the whole MrzScannerScreen.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun the_saver_survives_a_real_state_restoration_through_remember_saveable() {
        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null

        restorationTester.setContent {
            current =
                rememberSaveable(stateSaver = allMethodsSaver) {
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

    @Test
    fun the_saver_survives_a_real_state_restoration_of_a_review_state() {
        // Unlike ManualRaw's flat String + Boolean payload, Review's payload carries the nested
        // ArrayList<String> / ArrayList<Float> shapes (rawLines, texts, confidences) through the REAL
        // SaveableStateRegistry, which the module's only prior restoration test (ManualRaw, above) never
        // exercised.
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        assertTrue(decoded.parse is ParseResult.Success)
        val review = ScannerUiState.Review(decoded = decoded, source = ScanMethod.CAMERA, expanded = true)

        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null

        restorationTester.setContent {
            current = rememberSaveable(stateSaver = allMethodsSaver) { mutableStateOf<ScannerUiState>(review) }.value
        }

        assertEquals(review, current)

        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(
            review,
            current,
            "the nested ArrayList payloads (rawLines / texts / confidences) must survive a real Bundle round " +
                "trip through SaveableStateRegistry, not just the pure codec",
        )
    }

    @Test
    fun the_saver_survives_a_real_state_restoration_of_a_read_failed_state() {
        val captured =
            RecognizedText(
                lines =
                    listOf(
                        RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<", 0.42f),
                        RecognizedLine("L8989 2C36UT0 74O8122F32O41 9<<<<", null),
                    ),
            )
        val readFailed = ScannerUiState.ReadFailed(captured)

        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null

        restorationTester.setContent {
            current = rememberSaveable(stateSaver = allMethodsSaver) { mutableStateOf<ScannerUiState>(readFailed) }.value
        }

        assertEquals(readFailed, current)

        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(
            readFailed,
            current,
            "ReadFailed's nested ArrayList<String> / ArrayList<Float> payload (including the null-confidence " +
                "NaN sentinel) must survive a real Bundle round trip, not just the pure codec",
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // Variant-completeness guard — kotlin-reflect enumerates every ScannerUiState variant at TEST TIME, so
    // a tenth variant added to the sealed interface without extending representativeStates() (and, if it
    // restores as something other than itself, expectedSubstitution()) below AND the codec in
    // ScannerUiStateSaver.kt fails loudly here rather than silently shipping unencodable. kotlin-reflect is
    // a test-only dependency added to androidHostTest for exactly this structural guard — see the comment on
    // it in build.gradle.kts.
    // ---------------------------------------------------------------------------------------------------

    /**
     * One representative, fully-populated instance per [ScannerUiState] variant, keyed by [KClass] — the
     * enumeration [every_scanner_ui_state_variant_has_a_representative_and_encodes_decodably] checks against
     * [ScannerUiState]'s actual sealed subclasses, and the same map several `gateRestoredState` tests above
     * reuse for their "every variant" sweeps.
     */
    private fun representativeStates(): Map<KClass<out ScannerUiState>, ScannerUiState> {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        check(decoded.parse is ParseResult.Success) { "fixture specimen must parse to Success" }
        return mapOf(
            ScannerUiState.Scanning::class to ScannerUiState.Scanning(struggling = true, gathering = true),
            ScannerUiState.CameraInUse::class to ScannerUiState.CameraInUse,
            ScannerUiState.CameraUnavailable::class to ScannerUiState.CameraUnavailable,
            ScannerUiState.AwaitingSavedImagePick::class to ScannerUiState.AwaitingSavedImagePick,
            ScannerUiState.SavedImageAnalyzing::class to ScannerUiState.SavedImageAnalyzing,
            ScannerUiState.SavedImageEmpty::class to ScannerUiState.SavedImageEmpty,
            ScannerUiState.ManualRaw::class to ScannerUiState.ManualRaw(text = "ABC", parseFailed = true),
            ScannerUiState.ReadFailed::class to
                ScannerUiState.ReadFailed(RecognizedText(listOf(RecognizedLine("X", 0.5f)))),
            ScannerUiState.Review::class to
                ScannerUiState.Review(decoded = decoded, source = ScanMethod.CAMERA, expanded = true),
        )
    }

    /**
     * What [original] (a representative instance of the variant keyed by [kClass]) is expected to decode
     * back AS. Most variants restore to an equal copy of themselves; the two documented exceptions in
     * [ScannerUiStateSaver]'s file header substitute something else:
     *  * [ScannerUiState.SavedImageAnalyzing] → [ScannerUiState.AwaitingSavedImagePick] (no coroutine left to
     *    finish the analysis);
     *  * [ScannerUiState.Scanning] → both overlays reset to `false` (neither is honest to resurrect — see the
     *    Scanning tests at the top of this file).
     */
    private fun expectedSubstitution(
        kClass: KClass<out ScannerUiState>,
        original: ScannerUiState,
    ): ScannerUiState? =
        when (kClass) {
            ScannerUiState.SavedImageAnalyzing::class -> ScannerUiState.AwaitingSavedImagePick
            ScannerUiState.Scanning::class -> ScannerUiState.Scanning(struggling = false, gathering = false)
            else -> original
        }

    @Test
    fun every_scanner_ui_state_variant_has_a_representative_and_encodes_decodably() {
        val representatives = representativeStates()
        val allVariants = ScannerUiState::class.sealedSubclasses.toSet()
        val coveredVariants = representatives.keys.toSet()

        assertEquals(
            allVariants,
            coveredVariants,
            "ScannerUiState gained or lost a variant since this guard was written. Extend representativeStates() " +
                "(and expectedSubstitution() if the new variant restores as something other than itself) in " +
                "ScannerUiStateSaverTest.kt, and encodeScannerUiState / decodeScannerUiState / gateRestoredState " +
                "in ScannerUiStateSaver.kt, to cover it.",
        )

        for ((kClass, state) in representatives) {
            val expected = expectedSubstitution(kClass, state)
            val restored = decodeScannerUiState(encodeScannerUiState(state))
            assertEquals(expected, restored, "variant ${kClass.simpleName} must encode/decode to $expected, but got $restored")
        }
    }
}
