package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.LocalRetainedValuesStoreProvider
import androidx.compose.runtime.retain.ManagedRetainedValuesStore
import androidx.compose.runtime.setValue
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Host-side test for [rememberScannerFlowState] (TES-240): document data survives a configuration change in
 * memory and does NOT survive process death. TES-277: when the scanner leaves composition for good, the
 * retained holder is retired and clears its document data.
 *
 * Both are emulated with [StateRestorationTester], which disposes the content and composes it again with the
 * saved instance state restored. The test provides its own [ManagedRetainedValuesStore] (the store the
 * Android ComposeView otherwise backs with a ViewModel):
 *  * configuration change - the store retains exited values across the recreation, as the Activity's
 *    ViewModel store does during a configuration change;
 *  * process death - the store does not retain, so only the saved instance state comes back, as after the
 *    system ends the process.
 *
 * The real ComposeView wiring (its ViewModel-backed store across a real Activity recreation) is not
 * host-testable; it is checked on a device or an emulator. Fixtures are the synthetic ICAO TD3 specimen
 * (UTO = Utopia) - never real document data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerFlowStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val allMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY)
    private val specimen =
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    private fun review(source: ScanMethod): ScannerUiState.Review {
        val decoded = assembleManualDecoded(specimen)
        check(decoded.parse is ParseResult.Success) { "fixture specimen must parse to Success" }
        return ScannerUiState.Review(decoded = decoded, source = source, expanded = true)
    }

    private val readFailed =
        ScannerUiState.ReadFailed(RecognizedText(listOf(RecognizedLine("P<UT0ERlKSS0N<<ANN A<<<<", 0.4f))))

    /** Composes [rememberScannerFlowState] under [store]; returns a getter for the latest state holder. */
    private fun StateRestorationTester.compose(store: ManagedRetainedValuesStore): () -> ScannerFlowState {
        var holder: ScannerFlowState? = null
        setContent {
            LocalRetainedValuesStoreProvider(store) {
                holder = rememberScannerFlowState(allMethods)
            }
        }
        return { checkNotNull(holder) }
    }

    private fun StateRestorationTester.configurationChange(store: ManagedRetainedValuesStore) {
        store.enableRetainingExitedValues()
        emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        store.disableRetainingExitedValues()
    }

    // A ManagedRetainedValuesStore starts out retaining; a test store starts out NOT retaining, so only an
    // explicit configurationChange keeps values and processDeath is a real loss.
    private fun newStore(): ManagedRetainedValuesStore = ManagedRetainedValuesStore().apply { disableRetainingExitedValues() }

    private fun StateRestorationTester.processDeath() {
        emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
    }

    @Test
    fun a_fresh_opening_starts_on_the_initial_state() {
        val flow = StateRestorationTester(composeRule).compose(newStore())
        assertEquals(initialState(allMethods), flow().uiState)
        assertEquals("", flow().manualDraft)
    }

    @Test
    fun a_review_and_the_typed_draft_survive_a_configuration_change_in_memory() {
        val store = newStore()
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(store)
        val reviewState = review(ScanMethod.MANUAL_ENTRY)
        composeRule.runOnIdle {
            flow().uiState = reviewState
            flow().manualDraft = specimen
        }

        tester.configurationChange(store)

        assertSame(reviewState, flow().uiState, "the same in-memory reading must come back, not a re-parse")
        assertEquals(specimen, flow().manualDraft)
    }

    @Test
    fun read_failed_and_typed_manual_text_survive_a_configuration_change_in_memory() {
        val store = newStore()
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(store)

        composeRule.runOnIdle { flow().uiState = readFailed }
        tester.configurationChange(store)
        assertEquals(readFailed, flow().uiState)

        val typed = ScannerUiState.ManualRaw(text = specimen, parseFailed = true)
        composeRule.runOnIdle { flow().uiState = typed }
        tester.configurationChange(store)
        assertEquals(typed, flow().uiState)
    }

    @Test
    fun a_configuration_change_settles_the_session_bound_states() {
        val store = newStore()
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(store)

        composeRule.runOnIdle { flow().uiState = ScannerUiState.Scanning(struggling = true, gathering = true) }
        tester.configurationChange(store)
        assertEquals(ScannerUiState.Scanning(), flow().uiState, "a fresh camera session starts with no overlay")

        composeRule.runOnIdle { flow().uiState = ScannerUiState.SavedImageAnalyzing }
        tester.configurationChange(store)
        assertEquals(ScannerUiState.AwaitingSavedImagePick, flow().uiState, "no coroutine is left to finish the analysis")
    }

    @Test
    fun after_process_death_a_review_comes_back_as_its_methods_start_screen_without_the_reading() {
        val expected =
            mapOf(
                ScanMethod.CAMERA to ScannerUiState.Scanning(),
                ScanMethod.SAVED_IMAGE to ScannerUiState.AwaitingSavedImagePick,
                ScanMethod.MANUAL_ENTRY to ScannerUiState.ManualRaw(),
            )
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(newStore())
        for ((source, startScreen) in expected) {
            composeRule.runOnIdle { flow().uiState = review(source) }

            tester.processDeath()

            assertEquals(startScreen, flow().uiState, "a Review from $source")
        }
    }

    @Test
    fun after_process_death_read_failed_and_typed_text_are_gone() {
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(newStore())
        composeRule.runOnIdle {
            flow().uiState = ScannerUiState.ManualRaw(text = specimen, parseFailed = true)
            flow().manualDraft = specimen
        }

        tester.processDeath()

        assertEquals(ScannerUiState.ManualRaw(), flow().uiState, "the entry screen comes back empty")
        assertEquals("", flow().manualDraft, "the typed draft is never saved")

        composeRule.runOnIdle { flow().uiState = readFailed }
        tester.processDeath()
        assertEquals(ScannerUiState.AwaitingSavedImagePick, flow().uiState, "the reader picks a photo again")
    }

    @Test
    fun after_process_death_a_data_free_state_comes_back_as_itself() {
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(newStore())
        composeRule.runOnIdle { flow().uiState = ScannerUiState.SavedImageEmpty }

        tester.processDeath()

        assertEquals(ScannerUiState.SavedImageEmpty, flow().uiState)
    }

    @Test
    fun a_configuration_change_does_not_retire_the_holder() {
        val store = newStore()
        val tester = StateRestorationTester(composeRule)
        val flow = tester.compose(store)
        val memory = flow().memory
        composeRule.runOnIdle { flow().uiState = review(ScanMethod.CAMERA) }

        tester.configurationChange(store)

        assertSame(memory, flow().memory, "the same holder comes back")
        assertFalse(memory.isRetired)
    }

    // The Android case while the Activity is resumed: the host's store stays in composition, so it does not
    // retain the scanner's values when the scanner leaves (`if (showScanner) MrzScannerScreen(...)`).
    @Test
    fun leaving_for_good_retires_the_holder_and_clears_its_document_data() {
        val store = newStore().apply { enableRetainingExitedValues() }
        var showScanner by mutableStateOf(true)
        var holder: ScannerFlowState? = null
        composeRule.setContent {
            LocalRetainedValuesStoreProvider(store) {
                if (showScanner) holder = rememberScannerFlowState(allMethods)
            }
        }
        val flow = checkNotNull(holder)
        composeRule.runOnIdle {
            flow.uiState = review(ScanMethod.MANUAL_ENTRY)
            flow.manualDraft = specimen
        }

        composeRule.runOnIdle { showScanner = false }
        composeRule.waitForIdle()

        val memory = flow.memory
        assertTrue(memory.isRetired, "the store is in composition, so it does not keep the exited holder")
        assertNull(memory.lastState, "the reading is dropped")
        assertEquals("", memory.manualDraft.value, "the typed text is dropped")
    }

    // The Android case while the Activity is stopped: the store keeps exited values for a possible
    // recreation; when it stops retaining (the frame after onResume) without the scanner coming back, the
    // holder is retired then.
    @Test
    fun a_holder_kept_for_a_recreation_that_never_comes_is_cleared_when_the_store_stops_retaining() {
        val store = newStore().apply { enableRetainingExitedValues() }
        var showScanner by mutableStateOf(true)
        var holder: ScannerFlowState? = null
        composeRule.setContent {
            if (showScanner) {
                LocalRetainedValuesStoreProvider(store) { holder = rememberScannerFlowState(allMethods) }
            }
        }
        val flow = checkNotNull(holder)
        composeRule.runOnIdle {
            flow.uiState = readFailed
            flow.manualDraft = specimen
        }

        composeRule.runOnIdle { showScanner = false }
        composeRule.waitForIdle()
        val memory = flow.memory
        assertFalse(memory.isRetired, "a retaining store keeps the exited holder")
        assertEquals(readFailed, memory.lastState)

        composeRule.runOnIdle { store.disableRetainingExitedValues() }

        assertTrue(memory.isRetired)
        assertNull(memory.lastState)
        assertEquals("", memory.manualDraft.value)
    }

    @Test
    fun disposing_the_store_clears_a_kept_holder() {
        val store = newStore().apply { enableRetainingExitedValues() }
        var showScanner by mutableStateOf(true)
        var holder: ScannerFlowState? = null
        composeRule.setContent {
            if (showScanner) {
                LocalRetainedValuesStoreProvider(store) { holder = rememberScannerFlowState(allMethods) }
            }
        }
        val flow = checkNotNull(holder)
        composeRule.runOnIdle { flow.uiState = review(ScanMethod.CAMERA) }
        composeRule.runOnIdle { showScanner = false }
        composeRule.waitForIdle()

        // The ViewModel behind the Android store disposes it when its owner is cleared.
        composeRule.runOnIdle { store.dispose() }

        assertTrue(flow.memory.isRetired)
        assertNull(flow.memory.lastState)
    }
}
