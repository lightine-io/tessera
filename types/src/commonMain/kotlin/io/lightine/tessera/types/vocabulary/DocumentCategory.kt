package io.lightine.tessera.types.vocabulary

/**
 * A coarse class of document, derived from the MRZ document type code.
 *
 * `DocumentType.category` is `null` when the code is not in the SDK's table.
 * `DocumentType.broadCategory` also uses the first character of the code.
 *
 * @since 0.1.0
 */
public enum class DocumentCategory {
    /** A passport. */
    PASSPORT,

    /** An identity card. */
    IDENTITY_CARD,

    /** A residence permit. */
    RESIDENCE_PERMIT,

    /** A visa. */
    VISA,

    /** A recognized document type that fits none of the other categories. */
    OTHER,
}
