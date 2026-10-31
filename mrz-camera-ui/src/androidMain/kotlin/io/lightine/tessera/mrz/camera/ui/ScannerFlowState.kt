package io.lightine.tessera.mrz.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot

// TES-240: document data on the scanner screen stays in memory only - it survives a configuration change
// (rotation, font-scale, locale) but NOT process death, the same as the iOS screen (tessera-swift
// `ScannerModel`). Two layers hold the flow's state:
//
//  * In memory: a `retain`ed [ScannerFlowMemory] (Compose runtime-retain; on Android the ComposeView backs it
//    with a ViewModel-scoped store). It keeps the full uiState - a Review's reading, ReadFailed's recognized
//    lines, the typed manual text - across the Activity recreation of a configuration change, and is never
//    serialized, so it is gone when the process is.
//  * Saved: a `rememberSaveable` slot holding ONLY [withoutDocumentData] of the current state, encoded by
//    [scannerUiStateSaver] (with its restore gate, TES-102, and never-null restore, TES-222). It seeds the flow
//    only when the retained memory is gone, i.e. after process death, so the reader lands on the start screen
//    of the method they were using and scans or types again.
//
// If the host gives no retaining store (a ComposeView with no ViewModelStoreOwner), `retain` behaves like
// `remember`: a configuration change then also falls back to the saved, data-free state - never a crash and
// never a blank screen.
//
// TES-277: when the scanner leaves composition for good (not a configuration change), `retain` retires the
// holder: the host's store keeps an exited value only while it is retaining (on Android, from the Activity's
// onStop to the frame after onResume, or while the host's own store is set to retain); otherwise the value
// is retired at once, and a value kept but not taken back is retired when the store stops retaining or is
// disposed. Retiring only drops the store's reference, so the holder clears its own document data then
// ([ScannerFlowMemory.onRetired]) instead of leaving it for the garbage collector.

/**
 * The part of the flow that lives across a configuration change in memory only (TES-240). Created by
 * `retain`, so it holds nothing tied to a composition or an Activity (no Context, View or Lifecycle): only
 * the last flow state and the manual-entry draft.
 *
 * When `retain` retires it (the scanner left for good) or drops it unused, it clears both (TES-277), so the
 * last reading and the typed text do not stay referenced after the user has left the scanner. A
 * configuration change re-enters the same instance without retiring it, so the data survives that.
 */
internal class ScannerFlowMemory : RetainObserver {
    /** The flow state last set in this presentation, or `null` before the first composition seeded it. */
    var lastState: ScannerUiState? = null

    /** TES-93's hoisted manual-entry draft: typed document data, so it is retained, never saved. */
    val manualDraft: MutableState<String> = mutableStateOf("")

    /** Whether `retain` has retired or dropped this holder; it is never used again after that. */
    var isRetired: Boolean = false
        private set

    override fun onRetained() = Unit

    override fun onEnteredComposition() = Unit

    override fun onExitedComposition() = Unit

    override fun onRetired() = clear()

    override fun onUnused() = clear()

    private fun clear() {
        isRetired = true
        lastState = null
        manualDraft.value = ""
    }
}

/**
 * The flow's state for one composition of `ScannerFlow`: [uiState] and [manualDraft]. Setting [uiState] also
 * records it in the retained [ScannerFlowMemory] (for a configuration change) and records its data-free form
 * in the saved slot (for process death), so the two never drift from what is on screen.
 */
@Stable
internal class ScannerFlowState(
    private val current: MutableState<ScannerUiState>,
    internal val memory: ScannerFlowMemory,
    private val saved: MutableState<ScannerUiState>,
) {
    var uiState: ScannerUiState
        get() = current.value
        set(value) {
            current.value = value
            memory.lastState = value
            saved.value = withoutDocumentData(value)
        }

    var manualDraft: String
        get() = memory.manualDraft.value
        set(value) {
            memory.manualDraft.value = value
        }
}

/**
 * Builds the flow's [ScannerFlowState] (TES-240). The first screen is, in order: the retained in-memory state
 * after a configuration change (passed through [settleAfterRecreation], since the camera session and any
 * photo analysis restart with the composition); else the saved data-free state after process death (decoded
 * and gated by [scannerUiStateSaver]); else [initialState] for a fresh opening.
 */
@Composable
internal fun rememberScannerFlowState(enabledMethods: Set<ScanMethod>): ScannerFlowState {
    val saved =
        rememberSaveable(stateSaver = scannerUiStateSaver(enabledMethods)) {
            mutableStateOf(initialState(enabledMethods))
        }
    val memory = retain { ScannerFlowMemory() }
    return remember(memory) {
        // Read without observation: `saved` is written on every state change, and this one-time seed must
        // not make the caller recompose for it.
        val seed = memory.lastState?.let(::settleAfterRecreation) ?: Snapshot.withoutReadObservation { saved.value }
        memory.lastState = seed
        ScannerFlowState(current = mutableStateOf(seed), memory = memory, saved = saved)
    }
}
