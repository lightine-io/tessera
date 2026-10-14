package io.lightine.tessera.mrz.formats

/**
 * Field positions of the MRV-B format: two lines of 36 characters, no composite check digit.
 *
 * @since 0.1.0
 */
public object MrvBFormatSpec : MrzFormatSpec {
    /** Number of lines: 2. */
    override val lineCount: Int = 2

    /** Characters per line: 36. */
    override val lineLength: Int = 36

    /** Document type: line 1, characters 1-2. */
    override val documentType: FieldSpec = FieldSpec(line = 0, startInLine = 0, endInLineExclusive = 2)

    /** Issuing state: line 1, characters 3-5. */
    override val issuingState: FieldSpec = FieldSpec(line = 0, startInLine = 2, endInLineExclusive = 5)

    /** Name field: line 1, characters 6-36. */
    override val rawNameField: FieldSpec = FieldSpec(line = 0, startInLine = 5, endInLineExclusive = 36)

    /** Document number: line 2, characters 1-9. */
    override val documentNumber: FieldSpec = FieldSpec(line = 1, startInLine = 0, endInLineExclusive = 9)

    /** Document number check digit: line 2, character 10. */
    override val documentNumberCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 9, endInLineExclusive = 10)

    /** Nationality: line 2, characters 11-13. */
    override val nationality: FieldSpec = FieldSpec(line = 1, startInLine = 10, endInLineExclusive = 13)

    /** Date of birth: line 2, characters 14-19. */
    override val dateOfBirth: FieldSpec = FieldSpec(line = 1, startInLine = 13, endInLineExclusive = 19)

    /** Date of birth check digit: line 2, character 20. */
    override val dateOfBirthCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 19, endInLineExclusive = 20)

    /** Sex: line 2, character 21. */
    override val sex: FieldSpec = FieldSpec(line = 1, startInLine = 20, endInLineExclusive = 21)

    /** Date of expiry: line 2, characters 22-27. */
    override val dateOfExpiry: FieldSpec = FieldSpec(line = 1, startInLine = 21, endInLineExclusive = 27)

    /** Date of expiry check digit: line 2, character 28. */
    override val dateOfExpiryCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 27, endInLineExclusive = 28)

    /** Optional data: line 2, characters 29-36. */
    public val optionalData: FieldSpec = FieldSpec(line = 1, startInLine = 28, endInLineExclusive = 36)
}
