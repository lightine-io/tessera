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

    /** The text came from a passport's NFC chip. No SDK module reports it. */
    NFC_CHIP,

    /** Your app passed the text as a string. Reported by `MrzParser` and `MrzGenerator`. */
    BACKEND_STRING_INPUT,

    /** The text combines several reading methods. No SDK module reports it. */
    MIXED,
}
