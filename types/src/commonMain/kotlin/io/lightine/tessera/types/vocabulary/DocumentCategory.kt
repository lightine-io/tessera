package io.lightine.tessera.types.vocabulary

/**
 * Coarse classification of a document, derived from its MRZ document type code. Exposed on
 * [`DocumentType.category`][io.lightine.tessera.mrz.recognition.DocumentType.category] (an exact
 * [`DocumentTypeCodeTable`][io.lightine.tessera.mrz.recognition.DocumentTypeCodeTable] match) and on
 * [`DocumentType.broadCategory`][io.lightine.tessera.mrz.recognition.DocumentType.broadCategory] (the
 * same lookup, widened with an ICAO reserved-leading-character fallback for codes the table does not
 * enumerate exactly).
 *
 * [`DocumentType.category`][io.lightine.tessera.mrz.recognition.DocumentType.category] returns `null`
 * when the document's raw type code is not in the SDK's lookup table — a deliberate starter set, not an
 * exhaustive enumeration of every code in use worldwide. See [OTHER] for documents whose code IS
 * recognized but whose category is none of the specific ones below.
 */
public enum class DocumentCategory {
    PASSPORT,
    IDENTITY_CARD,
    RESIDENCE_PERMIT,
    VISA,

    /**
     * The document type is recognized in the lookup table but does not fit any of the
     * other categories.
     */
    OTHER,
}
