package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzCandidate
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedText

/**
 * The screen the default scanner UI is currently showing — the single state the flow ([ScannerFlow])
 * dispatches on. Every reading method and every outcome the UI can be in is one variant here, so the flow
 * is one exhaustive `when` over this contract rather than a tangle of booleans. Each variant maps to a
 * numbered mockup state (in `.design/default-ui/`).
 *
 * Only [Scanning], [Review], and [ReadFailed] are **wired in this slice** (TES-62 — the live-camera review
 * path); the rest are the settled contract the later 0.5.0 slices fill in (saved image, manual entry, the
 * error/permission states), declared now so the flow's `when` and the sibling screens have a stable shape
 * to grow into rather than being reshuffled per slice. Nothing here is public — the frozen surface stays
 * `MrzScannerScreen` + config + result (ADR-007); this is internal wiring.
 */
internal sealed interface ScannerUiState {
    /** Camera is coming up; no preview yet (mockup 01b). */
    data object Initializing : ScannerUiState

    /**
     * The live camera preview is running and looking for an MRZ (mockup 01). [struggling] flips true once
     * the configured struggle timeout elapses with no decode, to surface the "still looking / type it
     * instead" hint (mockup 04b).
     */
    data class Scanning(
        val struggling: Boolean = false,
    ) : ScannerUiState

    /** The `CAMERA` permission is not held and can still be requested (mockup 04). */
    data object PermissionNeeded : ScannerUiState

    /** The `CAMERA` permission was denied with "don't ask again" — only the host settings can grant it now (mockup 04b). */
    data object PermissionPermanentlyDenied : ScannerUiState

    /** Another app holds the camera, so this session cannot open it (mockup 05). */
    data object CameraInUse : ScannerUiState

    /** The camera could not be started for a non-recoverable reason (mockup 05b). */
    data object CameraUnavailable : ScannerUiState

    /**
     * An MRZ decoded and the user is reviewing the parsed fields and observations before accepting (mockup
     * 03 for a clean read, 03b for a check-digit mismatch). [expanded] toggles the all-fields + raw-MRZ view
     * (mockup 03c). [decoded] is the SDK's verbatim result, carried as-is — the UI adds no judgement of its
     * own (Principle 1).
     */
    data class Review(
        val decoded: MrzScanResult.Decoded,
        val expanded: Boolean = false,
    ) : ScannerUiState

    /**
     * OCR produced text that did not parse as any known MRZ format (a [`ParseResult.Failure`][io.lightine.tessera.mrz.parsing.ParseResult.Failure]),
     * so the captured text is shown verbatim for the user to retry or switch to manual entry (mockup 08).
     * [capturedText] is the raw recognized text exposed as-is, garbles preserved (Principle 5).
     */
    data class ReadFailed(
        val capturedText: RecognizedText,
    ) : ScannerUiState

    /** A picked photo is being analysed for an MRZ (mockup 07c). */
    data object SavedImageAnalyzing : ScannerUiState

    /**
     * Tolerant saved-image reading surfaced one or more candidate reconstructions for the user to choose
     * among (mockup 07). [candidates] is the SDK's candidate set, exposed all together — the UI never picks
     * one (Principle 1 / ADR-023).
     */
    data class SavedImageCandidates(
        val candidates: List<MrzCandidate>,
    ) : ScannerUiState

    /** The picked photo contained no readable MRZ (mockup 07b). */
    data object SavedImageEmpty : ScannerUiState

    /** Manual entry of the MRZ lines as raw text (mockup 06). [text] is the in-progress input. */
    data class ManualRaw(
        val text: String = "",
    ) : ScannerUiState

    /**
     * Manual entry as individual fields rather than raw MRZ lines (mockup 06b). The strings are the
     * in-progress field inputs, verbatim; the SDK parses them, it does not correct them.
     */
    data class ManualFields(
        val documentNumber: String = "",
        val dateOfBirth: String = "",
        val dateOfExpiry: String = "",
        val nationality: String = "",
    ) : ScannerUiState
}
