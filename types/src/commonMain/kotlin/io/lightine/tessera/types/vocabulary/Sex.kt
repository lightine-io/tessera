package io.lightine.tessera.types.vocabulary

/**
 * The sex recorded on the document.
 *
 * The parser maps `M` to [MALE], `F` to [FEMALE], and every other character to [UNSPECIFIED].
 * `CommonFields.rawSex` keeps the original character.
 *
 * @since 0.1.0
 */
public enum class Sex {
    /** Encoded as `M`. */
    MALE,

    /** Encoded as `F`. */
    FEMALE,

    /** Parsed from any character other than `M` and `F`. The generator encodes it as `<`. */
    UNSPECIFIED,
}
