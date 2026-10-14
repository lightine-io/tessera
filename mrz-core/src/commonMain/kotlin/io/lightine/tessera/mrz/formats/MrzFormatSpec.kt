package io.lightine.tessera.mrz.formats

/**
 * The field positions of one MRZ format.
 *
 * @since 0.1.0
 */
public sealed interface MrzFormatSpec {
    /** Number of lines in the MRZ. */
    public val lineCount: Int

    /** Number of characters on each line. */
    public val lineLength: Int

    /** Position of the document type. */
    public val documentType: FieldSpec

    /** Position of the issuing state. */
    public val issuingState: FieldSpec

    /** Position of the name field. */
    public val rawNameField: FieldSpec

    /** Position of the document number. */
    public val documentNumber: FieldSpec

    /** Position of the document number check digit. */
    public val documentNumberCheckDigit: FieldSpec

    /** Position of the nationality. */
    public val nationality: FieldSpec

    /** Position of the date of birth. */
    public val dateOfBirth: FieldSpec

    /** Position of the date of birth check digit. */
    public val dateOfBirthCheckDigit: FieldSpec

    /** Position of the sex character. */
    public val sex: FieldSpec

    /** Position of the date of expiry. */
    public val dateOfExpiry: FieldSpec

    /** Position of the date of expiry check digit. */
    public val dateOfExpiryCheckDigit: FieldSpec

    /**
     * Returns the index of a field's first character in the lines joined without a separator.
     *
     * @param field The field position, relative to its line.
     * @return `field.line * lineLength + field.startInLine`.
     */
    public fun globalPositionOf(field: FieldSpec): Int = field.line * lineLength + field.startInLine
}

/**
 * A format spec for formats with a composite check digit: TD1, TD2, and TD3.
 *
 * @since 0.1.0
 */
public sealed interface MrzFormatSpecWithComposite : MrzFormatSpec {
    /** Position of the composite check digit. */
    public val compositeCheckDigit: FieldSpec

    /** Positions whose text, joined in order, is the input of the composite check digit. */
    public val compositeInputFields: List<FieldSpec>
}
