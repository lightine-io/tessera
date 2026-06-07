package io.lightine.tessera.types.vocabulary

/**
 * The provenance of the MRZ data this result is derived from. Reported on
 * [`ResultMetadata.readMethod`][io.lightine.tessera.mrz.parsing.ResultMetadata] so
 * consumers can branch on how the data reached the SDK.
 *
 * The string parser and generator produce [BACKEND_STRING_INPUT]; live-camera reading produces
 * [LIVE_CAMERA]. The remaining values are committed for use as their reading methods activate in
 * later releases. See
 * [`docs/scope.md`](https://github.com/lightine-io/tessera/blob/main/docs/scope.md)
 * for the release timeline.
 */
public enum class ReadMethod {
    /** Data captured from a live camera feed (camera reading, release 0.2.0+). */
    LIVE_CAMERA,

    /** Data captured from a pre-existing image file (image reading, release 0.3.0+). */
    PRE_CAPTURED_IMAGE,

    /** Data typed in by a human (manual entry, release 0.4.0+). */
    MANUAL_ENTRY,

    /** Data read from a passport's embedded NFC chip (NFC reading, release 0.6.0+). */
    NFC_CHIP,

    /**
     * Data provided to the SDK as a plain string by the calling application — typically
     * because the application read or produced the MRZ outside the SDK. This is the
     * value reported for results produced by [`MrzParser.parse`][io.lightine.tessera.mrz.parsing.MrzParser]
     * and [`MrzGenerator`][io.lightine.tessera.mrz.generation.MrzGenerator] in 0.1.0.
     */
    BACKEND_STRING_INPUT,

    /**
     * The result combines data from multiple reading methods (for example a camera capture
     * cross-checked against an NFC chip read). Used by higher-level reading flows in later
     * releases; not produced by 0.1.0.
     */
    MIXED,
}
