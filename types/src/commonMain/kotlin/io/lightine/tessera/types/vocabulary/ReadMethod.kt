package io.lightine.tessera.types.vocabulary

/**
 * How the MRZ text reached the SDK. Reported on `ResultMetadata.readMethod`.
 *
 * @since 0.1.0
 */
public enum class ReadMethod {
    /** The text came from a live camera feed. */
    LIVE_CAMERA,

    /** The text came from a saved image. */
    PRE_CAPTURED_IMAGE,

    /** A person typed the text. Reported by `ManualMrzReader`. */
    MANUAL_ENTRY,

    /** The text came from a document's chip (its DG1), read over NFC. Reported by `emrtd-core`. */
    NFC_CHIP,

    /** Your app passed the text as a string. Reported by `MrzParser` and `MrzGenerator`. */
    BACKEND_STRING_INPUT,

    /**
     * The chip's DG1 values, combined with a printed MRZ the app also read: `ChipMrzComparison.combine`
     * in `emrtd-core` reports it. The values are the chip's, and each field carries a fact saying
     * whether the printed MRZ shows the same characters. Nothing is corrected, and neither source is
     * picked over the other.
     */
    MIXED,
}
