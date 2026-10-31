package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.parsing.ParseResult
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
 * Host-side test for [scannerUiStateSaver] / [encodeScannerUiState] / [decodeScannerUiState] /
 * [gateRestoredState] / [withoutDocumentData] / [settleAfterRecreation] - the `rememberSaveable` codec behind
 * the flow's process-death fallback, run on the JVM via Robolectric (no device), same shape as the module's
 * other host tests.
 *
 * TES-240's contract: the saved form holds NO document data. A state carrying document data (Review,
 * ReadFailed, ManualRaw's text) is saved as the data-free start screen of its method, so after process death
 * the reader scans or types again. Surviving a configuration change in memory is `ScannerFlowStateTest`'s job.
 *
 * The [ScannerUiState.Review] fixtures use the clean synthetic ICAO TD3 specimen the rest of this module's
 * tests use (synthetic; UTO = Utopia, a reserved ICAO test issuer) - never real document data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerUiStateSaverTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    private val allMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY)

    // The only strings a saved payload may contain: the variant tags. Anything else would be data.
    private val knownTags =
        setOf(
            "Scanning",
            "CameraInUse",
            "CameraUnavailable",
            "AwaitingSavedImagePick",
            "SavedImageAnalyzing",
            "SavedImageEmpty",
            "ManualRaw",
        )

    private fun review(source: ScanMethod): ScannerUiState.Review {
        val decoded = assembleManualDecoded("$specimenLine1\n$specimenLine2")
        check(decoded.parse is ParseResult.Success) { "fixture specimen must parse to Success" }
        return ScannerUiState.Review(decoded = decoded, source = source, expanded = true)
    }

    private val readFailed =
        ScannerUiState.ReadFailed(
            RecognizedText(
                listOf(
                    RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<<<<<<<<<", 0.42f),
                    RecognizedLine("L8989 2C36UT0 74O8122F32O41 9<<<<", null),
                ),
            ),
        )

    private val typed = ScannerUiState.ManualRaw(text = "$specimenLine1\n$specimenLine2", parseFailed = true)

    /** Asserts [payload] is only a known tag: one element, no typed text, no recognized line, no reading. */
    private fun assertNoDocumentData(
        payload: Any?,
        what: String,
    ) {
        val flat = (payload as List<*>).flatMap { if (it is List<*>) it else listOf(it) }
        assertEquals(1, flat.size, "$what: the saved payload must be the tag alone, got $flat")
        assertTrue(flat.single() in knownTags, "$what: the saved payload must hold only a variant tag, got $flat")
    }

    // ---------------------------------------------------------------------------------------------------
    // TES-240: the saved form holds no document data.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun a_review_is_saved_without_its_reading_for_every_source() {
        for (source in ScanMethod.entries) {
            assertNoDocumentData(encodeScannerUiState(review(source)), "Review from $source")
        }
    }

    @Test
    fun read_failed_is_saved_without_its_recognized_lines() {
        assertNoDocumentData(encodeScannerUiState(readFailed), "ReadFailed")
    }

    @Test
    fun manual_raw_is_saved_without_its_typed_text() {
        assertNoDocumentData(encodeScannerUiState(typed), "ManualRaw")
    }

    @Test
    fun the_real_saver_writes_no_document_data_for_any_variant() {
        // Through the Saver rememberSaveable actually calls, not only the pure encoder.
        val saver = scannerUiStateSaver(allMethods)
        val scope = SaverScope { true }
        for ((kClass, state) in representativeStates()) {
            assertNoDocumentData(with(saver) { scope.save(state) }, "Saver for ${kClass.simpleName}")
        }
    }

    @Test
    fun without_document_data_maps_each_data_variant_to_its_methods_start_screen() {
        assertEquals(ScannerUiState.Scanning(), withoutDocumentData(review(ScanMethod.CAMERA)))
        assertEquals(ScannerUiState.AwaitingSavedImagePick, withoutDocumentData(review(ScanMethod.SAVED_IMAGE)))
        assertEquals(ScannerUiState.ManualRaw(), withoutDocumentData(review(ScanMethod.MANUAL_ENTRY)))
        // ReadFailed is only ever reached from the saved-image flow, so the reader picks a photo again.
        assertEquals(ScannerUiState.AwaitingSavedImagePick, withoutDocumentData(readFailed))
        assertEquals(ScannerUiState.ManualRaw(text = "", parseFailed = false), withoutDocumentData(typed))
    }

    @Test
    fun without_document_data_leaves_data_free_states_unchanged() {
        val dataFree =
            listOf(
                ScannerUiState.Scanning(struggling = true, gathering = true),
                ScannerUiState.CameraInUse,
                ScannerUiState.CameraUnavailable,
                ScannerUiState.AwaitingSavedImagePick,
                ScannerUiState.SavedImageAnalyzing,
                ScannerUiState.SavedImageEmpty,
                ScannerUiState.ManualRaw(),
            )
        for (state in dataFree) assertEquals(state, withoutDocumentData(state))
    }

    @Test
    fun a_payload_from_before_tes_240_with_document_data_is_never_restored() {
        // An older app version saved the typed text, the recognized lines and the raw MRZ lines. Such a payload
        // must decode to null (the Saver then starts the first screen), never back into the data.
        assertNull(decodeScannerUiState(listOf("ManualRaw", "typed text", false)))
        assertNull(decodeScannerUiState(listOf("ReadFailed", arrayListOf("X"), arrayListOf(0.5f))))
        assertNull(decodeScannerUiState(listOf("Review", arrayListOf(specimenLine1, specimenLine2))))
        val saver = scannerUiStateSaver(allMethods)
        assertEquals(initialState(allMethods), saver.restore(listOf("ManualRaw", "typed text", false)))
    }

    // ---------------------------------------------------------------------------------------------------
    // settleAfterRecreation and the data-free round trip.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun scanning_round_trip_always_restores_with_both_overlays_reset() {
        // Neither overlay describes the fresh camera session a recreation starts, so both reset.
        val restored = decodeScannerUiState(encodeScannerUiState(ScannerUiState.Scanning(struggling = true, gathering = true)))
        assertEquals(ScannerUiState.Scanning(struggling = false, gathering = false), restored)
    }

    @Test
    fun tag_only_states_round_trip_to_themselves() {
        for (state in listOf(
            ScannerUiState.CameraInUse,
            ScannerUiState.CameraUnavailable,
            ScannerUiState.AwaitingSavedImagePick,
            ScannerUiState.SavedImageEmpty,
            ScannerUiState.ManualRaw(),
        )) {
            assertEquals(state, decodeScannerUiState(encodeScannerUiState(state)))
        }
    }

    @Test
    fun saved_image_analyzing_restores_as_awaiting_saved_image_pick_not_itself() {
        // The analysis coroutine dies with the old composition, so "Analyzing..." would spin forever.
        assertEquals(
            ScannerUiState.AwaitingSavedImagePick,
            decodeScannerUiState(encodeScannerUiState(ScannerUiState.SavedImageAnalyzing)),
        )
    }

    @Test
    fun settle_after_recreation_changes_only_session_bound_states() {
        assertEquals(ScannerUiState.Scanning(), settleAfterRecreation(ScannerUiState.Scanning(struggling = true, gathering = true)))
        assertEquals(ScannerUiState.AwaitingSavedImagePick, settleAfterRecreation(ScannerUiState.SavedImageAnalyzing))
        // Document data is kept verbatim on the in-memory path: a recreation never alters what was read.
        val reviewState = review(ScanMethod.CAMERA)
        assertEquals(reviewState, settleAfterRecreation(reviewState))
        assertEquals(readFailed, settleAfterRecreation(readFailed))
        assertEquals(typed, settleAfterRecreation(typed))
    }

    // ---------------------------------------------------------------------------------------------------
    // Garbage payloads - decode must return null, never throw.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun an_unknown_tag_or_an_empty_payload_decodes_to_null() {
        assertNull(decodeScannerUiState(listOf("NotARealTag")))
        assertNull(decodeScannerUiState(emptyList()))
        assertNull(decodeScannerUiState(listOf(42)))
    }

    @Test
    fun the_wrong_arity_for_a_known_tag_decodes_to_null() {
        assertNull(decodeScannerUiState(listOf("Scanning", true)), "Scanning has no slot to accept")
        assertNull(decodeScannerUiState(listOf("ManualRaw", "typed text")), "ManualRaw no longer carries text")
    }

    // ---------------------------------------------------------------------------------------------------
    // gateRestoredState - TES-102's config-aware restore gate. Each variant's owning method, gated against
    // enabledMethods; the Review variant is gated by its OWN source, not a fixed method.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun scanning_and_camera_status_states_gate_on_camera() {
        val allowed = setOf(ScanMethod.CAMERA)
        val disallowed = setOf(ScanMethod.MANUAL_ENTRY, ScanMethod.SAVED_IMAGE)
        for (state in listOf(ScannerUiState.Scanning(), ScannerUiState.CameraInUse, ScannerUiState.CameraUnavailable)) {
            assertEquals(state, gateRestoredState(state, allowed))
            assertNull(gateRestoredState(state, disallowed))
        }
    }

    @Test
    fun saved_image_states_gate_on_saved_image_including_read_failed() {
        val allowed = setOf(ScanMethod.SAVED_IMAGE)
        val disallowed = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
        for (state in listOf(
            ScannerUiState.AwaitingSavedImagePick,
            ScannerUiState.SavedImageEmpty,
            ScannerUiState.SavedImageAnalyzing,
            readFailed,
        )) {
            assertEquals(state, gateRestoredState(state, allowed))
            assertNull(gateRestoredState(state, disallowed))
        }
    }

    @Test
    fun manual_raw_gates_on_manual_entry() {
        val manualRaw = ScannerUiState.ManualRaw()
        assertEquals(manualRaw, gateRestoredState(manualRaw, setOf(ScanMethod.MANUAL_ENTRY)))
        assertNull(gateRestoredState(manualRaw, setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE)))
    }

    @Test
    fun review_gates_on_its_own_source_not_a_fixed_method() {
        for (source in ScanMethod.entries) {
            val state = review(source)
            assertEquals(state, gateRestoredState(state, setOf(source)))
            assertNull(gateRestoredState(state, allMethods - source))
        }
    }

    @Test
    fun the_start_screen_a_data_variant_is_saved_as_gates_on_the_same_method() {
        // The data-free stand-in must belong to the same method, so the gate decides it the same way.
        for ((_, state) in representativeStates()) {
            for (method in ScanMethod.entries) {
                val enabled = setOf(method)
                assertEquals(
                    gateRestoredState(state, enabled) == null,
                    gateRestoredState(withoutDocumentData(state), enabled) == null,
                    "${state::class.simpleName} and its saved stand-in must gate alike for $enabled",
                )
            }
        }
    }

    @Test
    fun an_empty_enabled_methods_set_gates_every_variant_to_null_with_no_special_casing() {
        for (state in representativeStates().values) assertNull(gateRestoredState(state, emptySet()))
    }

    // ---------------------------------------------------------------------------------------------------
    // The Saver wired through rememberSaveable, via StateRestorationTester - the real Compose integration.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun a_review_restored_through_remember_saveable_comes_back_as_its_start_screen_without_the_reading() {
        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null
        val manualReview = review(ScanMethod.MANUAL_ENTRY)

        restorationTester.setContent {
            current =
                rememberSaveable(stateSaver = scannerUiStateSaver(allMethods)) {
                    mutableStateOf<ScannerUiState>(manualReview)
                }.value
        }
        assertEquals(manualReview, current)

        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(ScannerUiState.ManualRaw(), current, "a saved Review must come back as the empty entry screen")
    }

    @Test
    fun a_restored_state_the_gate_rejects_starts_on_the_first_screen_through_remember_saveable() {
        // TES-222: rememberSaveable keeps a null from restore instead of using its init lambda, which crashed
        // the screen. The saver itself must hand back the initial state. Saved while manual entry is enabled,
        // restored while only the camera is: the gate rejects the saved ManualRaw.
        var enabled = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
        val restorationTester = StateRestorationTester(composeRule)
        var current: ScannerUiState? = null

        restorationTester.setContent {
            val methods = enabled
            current =
                rememberSaveable(stateSaver = scannerUiStateSaver(methods)) {
                    mutableStateOf(ScannerUiState.ManualRaw() as ScannerUiState)
                }.value
        }
        assertEquals(ScannerUiState.ManualRaw(), current)

        enabled = setOf(ScanMethod.CAMERA)
        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(initialState(setOf(ScanMethod.CAMERA)), current, "a rejected restored state must become the first screen, never null")
    }

    @Test
    fun an_unreadable_saved_state_restores_as_the_first_screen_not_null() {
        val methods = setOf(ScanMethod.CAMERA, ScanMethod.MANUAL_ENTRY)
        val saver = scannerUiStateSaver(methods)
        assertEquals(initialState(methods), saver.restore(listOf("no-such-tag")))
        assertEquals(initialState(methods), saver.restore(emptyList<Any?>()))
    }

    // ---------------------------------------------------------------------------------------------------
    // Variant-completeness guard - kotlin-reflect enumerates every ScannerUiState variant at TEST TIME, so a
    // new variant added without extending representativeStates() and expectedAfterProcessDeath() below fails
    // loudly here rather than silently shipping with an unchecked saved form.
    // ---------------------------------------------------------------------------------------------------

    /** One representative, fully-populated instance per [ScannerUiState] variant, keyed by [KClass]. */
    private fun representativeStates(): Map<KClass<out ScannerUiState>, ScannerUiState> =
        mapOf(
            ScannerUiState.Scanning::class to ScannerUiState.Scanning(struggling = true, gathering = true),
            ScannerUiState.CameraInUse::class to ScannerUiState.CameraInUse,
            ScannerUiState.CameraUnavailable::class to ScannerUiState.CameraUnavailable,
            ScannerUiState.AwaitingSavedImagePick::class to ScannerUiState.AwaitingSavedImagePick,
            ScannerUiState.SavedImageAnalyzing::class to ScannerUiState.SavedImageAnalyzing,
            ScannerUiState.SavedImageEmpty::class to ScannerUiState.SavedImageEmpty,
            ScannerUiState.ManualRaw::class to typed,
            ScannerUiState.ReadFailed::class to readFailed,
            ScannerUiState.Review::class to review(ScanMethod.CAMERA),
        )

    /** What each representative comes back as after process death (see the ScannerUiStateSaver.kt header). */
    private fun expectedAfterProcessDeath(kClass: KClass<out ScannerUiState>): ScannerUiState =
        when (kClass) {
            ScannerUiState.Scanning::class -> ScannerUiState.Scanning()

            ScannerUiState.CameraInUse::class -> ScannerUiState.CameraInUse

            ScannerUiState.CameraUnavailable::class -> ScannerUiState.CameraUnavailable

            ScannerUiState.AwaitingSavedImagePick::class -> ScannerUiState.AwaitingSavedImagePick

            ScannerUiState.SavedImageAnalyzing::class -> ScannerUiState.AwaitingSavedImagePick

            ScannerUiState.SavedImageEmpty::class -> ScannerUiState.SavedImageEmpty

            ScannerUiState.ManualRaw::class -> ScannerUiState.ManualRaw()

            ScannerUiState.ReadFailed::class -> ScannerUiState.AwaitingSavedImagePick

            ScannerUiState.Review::class -> ScannerUiState.Scanning()

            // the representative's source is CAMERA
            else -> error("no expectation for ${kClass.simpleName}")
        }

    @Test
    fun every_scanner_ui_state_variant_has_a_representative_and_a_data_free_saved_form() {
        val representatives = representativeStates()
        assertEquals(
            ScannerUiState::class.sealedSubclasses.toSet(),
            representatives.keys,
            "ScannerUiState gained or lost a variant. Extend representativeStates() and expectedAfterProcessDeath() " +
                "in ScannerUiStateSaverTest.kt, and withoutDocumentData / encodeScannerUiState / decodeScannerUiState / " +
                "gateRestoredState in ScannerUiStateSaver.kt, to cover it.",
        )
        for ((kClass, state) in representatives) {
            val encoded = encodeScannerUiState(state)
            assertNoDocumentData(encoded, kClass.simpleName ?: "?")
            assertEquals(expectedAfterProcessDeath(kClass), decodeScannerUiState(encoded), "variant ${kClass.simpleName}")
        }
    }
}
