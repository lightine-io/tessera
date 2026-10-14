package io.lightine.tessera.mrz.formats

/**
 * Field positions of the TD3 format: two lines of 44 characters.
 *
 * @since 0.1.0
 */
public object Td3FormatSpec : MrzFormatSpecWithComposite {
    /** Number of lines: 2. */
    override val lineCount: Int = 2

    /** Characters per line: 44. */
    override val lineLength: Int = 44

    /** Document type: line 1, characters 1-2. */
    override val documentType: FieldSpec = FieldSpec(line = 0, startInLine = 0, endInLineExclusive = 2)

    /** Issuing state: line 1, characters 3-5. */
    override val issuingState: FieldSpec = FieldSpec(line = 0, startInLine = 2, endInLineExclusive = 5)

    /** Name field: line 1, characters 6-44. */
    override val rawNameField: FieldSpec = FieldSpec(line = 0, startInLine = 5, endInLineExclusive = 44)

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

    /** Personal number: line 2, characters 29-42. */
    public val personalNumber: FieldSpec = FieldSpec(line = 1, startInLine = 28, endInLineExclusive = 42)

    /** Personal number check digit: line 2, character 43. */
    public val personalNumberCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 42, endInLineExclusive = 43)

    /** Composite check digit: line 2, character 44. */
    override val compositeCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 43, endInLineExclusive = 44)

    // Per ICAO Doc 9303 Part 4: composite check digit covers document number + its check
    // digit, date of birth + its check digit, and date of expiry + its check digit + personal
    // number + its check digit (sex and the composite digit itself are excluded).

    /** Input of the composite check digit: line 2 characters 1-10, 14-20, and 22-43. */
    override val compositeInputFields: List<FieldSpec> =
        listOf(
            FieldSpec(line = 1, startInLine = 0, endInLineExclusive = 10),
            FieldSpec(line = 1, startInLine = 13, endInLineExclusive = 20),
            FieldSpec(line = 1, startInLine = 21, endInLineExclusive = 43),
        )
}
