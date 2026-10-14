package io.lightine.tessera.mrz.formats

/**
 * Field positions of the TD1 format: three lines of 30 characters.
 *
 * @since 0.1.0
 */
public object Td1FormatSpec : MrzFormatSpecWithComposite {
    /** Number of lines: 3. */
    override val lineCount: Int = 3

    /** Characters per line: 30. */
    override val lineLength: Int = 30

    // Line 1 (30 chars): doc type, issuing state, document number + check, optional data 1

    /** Document type: line 1, characters 1-2. */
    override val documentType: FieldSpec = FieldSpec(line = 0, startInLine = 0, endInLineExclusive = 2)

    /** Issuing state: line 1, characters 3-5. */
    override val issuingState: FieldSpec = FieldSpec(line = 0, startInLine = 2, endInLineExclusive = 5)

    /** Document number: line 1, characters 6-14. */
    override val documentNumber: FieldSpec = FieldSpec(line = 0, startInLine = 5, endInLineExclusive = 14)

    /** Document number check digit: line 1, character 15. */
    override val documentNumberCheckDigit: FieldSpec = FieldSpec(line = 0, startInLine = 14, endInLineExclusive = 15)

    /** First optional-data field: line 1, characters 16-30. */
    public val optionalData1: FieldSpec = FieldSpec(line = 0, startInLine = 15, endInLineExclusive = 30)

    // Line 2 (30 chars): DOB + check, sex, DOE + check, nationality, optional data 2, composite

    /** Date of birth: line 2, characters 1-6. */
    override val dateOfBirth: FieldSpec = FieldSpec(line = 1, startInLine = 0, endInLineExclusive = 6)

    /** Date of birth check digit: line 2, character 7. */
    override val dateOfBirthCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 6, endInLineExclusive = 7)

    /** Sex: line 2, character 8. */
    override val sex: FieldSpec = FieldSpec(line = 1, startInLine = 7, endInLineExclusive = 8)

    /** Date of expiry: line 2, characters 9-14. */
    override val dateOfExpiry: FieldSpec = FieldSpec(line = 1, startInLine = 8, endInLineExclusive = 14)

    /** Date of expiry check digit: line 2, character 15. */
    override val dateOfExpiryCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 14, endInLineExclusive = 15)

    /** Nationality: line 2, characters 16-18. */
    override val nationality: FieldSpec = FieldSpec(line = 1, startInLine = 15, endInLineExclusive = 18)

    /** Second optional-data field: line 2, characters 19-29. */
    public val optionalData2: FieldSpec = FieldSpec(line = 1, startInLine = 18, endInLineExclusive = 29)

    /** Composite check digit: line 2, character 30. */
    override val compositeCheckDigit: FieldSpec = FieldSpec(line = 1, startInLine = 29, endInLineExclusive = 30)

    // Line 3 (30 chars): name field

    /** Name field: line 3, characters 1-30. */
    override val rawNameField: FieldSpec = FieldSpec(line = 2, startInLine = 0, endInLineExclusive = 30)

    // Per ICAO Doc 9303 Part 5: composite check digit covers line 1 positions 6-30 (doc number
    // + its check digit + optional data 1) and line 2 positions 1-7 (DOB + its check digit),
    // 9-15 (DOE + its check digit), 19-29 (optional data 2). Sex (line 2 position 8), nationality
    // (line 2 positions 16-18), and the composite digit itself (line 2 position 30) are excluded.

    /**
     * Input of the composite check digit: line 1 characters 6-30, then line 2 characters 1-7, 9-15,
     * and 19-29.
     */
    override val compositeInputFields: List<FieldSpec> =
        listOf(
            FieldSpec(line = 0, startInLine = 5, endInLineExclusive = 30),
            FieldSpec(line = 1, startInLine = 0, endInLineExclusive = 7),
            FieldSpec(line = 1, startInLine = 8, endInLineExclusive = 15),
            FieldSpec(line = 1, startInLine = 18, endInLineExclusive = 29),
        )
}
