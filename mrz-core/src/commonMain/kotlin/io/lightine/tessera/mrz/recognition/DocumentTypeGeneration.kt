package io.lightine.tessera.mrz.recognition

/**
 * The generation of document type code convention that a code belongs to.
 *
 * @since 0.1.0
 */
public enum class DocumentTypeGeneration {
    /** A single-character code, such as `P`, `V`, or `I`. */
    LEGACY_SINGLE_CHARACTER,

    /** A two-character code, such as `PP`, `PD`, or `AC`. */
    CURRENT_TWO_CHARACTER,
}
