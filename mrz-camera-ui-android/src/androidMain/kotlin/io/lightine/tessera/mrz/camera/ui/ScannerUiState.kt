package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedText

/**
 * The screen the default scanner UI is currently showing — the single state the flow ([ScannerFlow])
 * dispatches on. Every reading method and every outcome the UI can be in is one variant here, so the flow
 * is one exhaustive `when` over this contract rather than a tangle of booleans. Each variant maps to a
 * numbered mockup state (in `.design/default-ui/`).
 *
 * Every variant is **wired**: [Scanning] (with its `struggling` / `gathering` overlays), [Review],
 * [ReadFailed], [ManualRaw], [CameraInUse], [CameraUnavailable], and the saved-image states. Manual entry is
 * raw-MRZ-line entry ([ManualRaw], mockup 06); field-by-field entry (mockup 06b) was dropped for 0.5.0 as a
 * separate future feature (TES-79) rather than shipped as a half-declared state. The camera-permission screens
 * (mockups 04 / 04b) are NOT flow states here: they are rendered by `CameraCapture` from the live permission
 * status, so there are no permission variants in this contract. Nothing here is public — the frozen surface
 * stays `MrzScannerScreen` + config + result (ADR-007); this is internal wiring.
 *
 * The camera-initializing state (mockup 01b) is deliberately **not** a variant here: it is not a flow state
 * the reducer can produce but a transient of the live preview — the window where the scanner has not yet
 * yielded a preview surface. It is therefore rendered by `CameraPreview` (as `InitializingContent`) keyed on
 * that null surface, not dispatched by [ScannerFlow], so it stays where its real device-driven trigger lives.
 */
internal sealed interface ScannerUiState {
    /**
     * The live camera preview is running and looking for an MRZ (mockup 01). [struggling] flips true once
     * the configured struggle timeout elapses with no decode, to overlay the "still looking / type it
     * instead" hint on the preview (mockup 02). [gathering] flips true while the frame-agreement gate
     * ([`MrzDecodeConsensus`][io.lightine.tessera.mrz.camera.MrzDecodeConsensus]) is confirming a read across
     * frames — it overlays a "hold steady" progress cue so the consensus wait reads as active feedback, not
     * lag. The two are mutually exclusive in effect: an incoming decode (gathering) means we are *not* stuck,
     * so gathering takes render precedence over struggling.
     */
    data class Scanning(
        val struggling: Boolean = false,
        val gathering: Boolean = false,
    ) : ScannerUiState

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
        /**
         * Which reading method produced this review, so Rescan returns to THAT method (camera → live preview,
         * photo → the picker, manual → the entry screen) instead of always jumping to the camera — which would
         * discard a typed MRZ / picked photo and could force the camera even when the consumer disabled it.
         */
        val source: ScanMethod,
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

    /**
     * The saved-image method is the entry point (only [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] is enabled) and
     * the flow is waiting for the photo picker to be launched. A momentary state: the flow triggers
     * `enterSavedImage()` on entering it and the picker takes over — either a pick routes on
     * ([SavedImageAnalyzing] → single decode / empty) or a dismissed picker leaves this showing a neutral
     * "choose a photo" prompt with a re-pick action, so the screen is never blank. Reached only via
     * [initialState] (SAVED_IMAGE-only) or the method switcher's Photo tab — not a mockup state of its own.
     */
    data object AwaitingSavedImagePick : ScannerUiState

    /** A picked photo is being analysed for an MRZ (mockup 07c). */
    data object SavedImageAnalyzing : ScannerUiState

    /** The picked photo contained no readable MRZ (mockup 07b). */
    data object SavedImageEmpty : ScannerUiState

    /**
     * Manual entry of the MRZ lines as raw text (mockup 06). [text] is the in-progress input. [parseFailed] is
     * set when a "Read this" attempt did not parse as any MRZ format — the screen then shows an inline note and
     * keeps the typed text (rather than navigating to the camera-flavoured read-failed screen); it clears on the
     * next edit.
     */
    data class ManualRaw(
        val text: String = "",
        val parseFailed: Boolean = false,
    ) : ScannerUiState
}
