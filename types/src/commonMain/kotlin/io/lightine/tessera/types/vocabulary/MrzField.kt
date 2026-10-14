package io.lightine.tessera.types.vocabulary

/**
 * A named MRZ field, used by observations to say which field they refer to.
 *
 * @since 0.1.0
 */
public enum class MrzField {
    /** The document type code. */
    DOCUMENT_TYPE,

    /** The issuing state code. */
    ISSUING_STATE,

    /** The name field: primary and secondary identifiers. */
    NAME_FIELD,

    /** The document number. */
    DOCUMENT_NUMBER,

    /** The nationality code. */
    NATIONALITY,

    /** The date of birth. */
    DATE_OF_BIRTH,

    /** The date of expiry. */
    DATE_OF_EXPIRY,

    /** The optional data field, or the personal number on a TD3. */
    OPTIONAL_DATA,

    /** The composite check digit, which covers several fields. */
    COMPOSITE,
}
