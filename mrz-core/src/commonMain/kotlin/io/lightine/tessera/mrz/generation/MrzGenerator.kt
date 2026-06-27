package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractFrom
import io.lightine.tessera.mrz.model.CommonFields
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzCheckDigits
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.ResultMetadata
import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.mrz.transliteration.TransliteratedField
import io.lightine.tessera.mrz.transliteration.TransliterationDetails
import io.lightine.tessera.mrz.transliteration.TransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationResult
import io.lightine.tessera.mrz.transliteration.transliterate
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.errors.MrzGenerationNumericInNameField
import io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlinx.datetime.LocalDate

/**
 * Encodes [`MrzDocument`][MrzDocument] objects (or sets of raw field values) into MRZ
 * lines per ICAO Doc 9303. The encoded MRZ is returned as a list of strings (one per
 * line) inside [`GenerationResult.Success`][GenerationResult.Success]; encoding failures
 * (field overflow, missing required fields, characters outside the MRZ alphabet) are
 * returned as [`GenerationResult.Failure`][GenerationResult.Failure].
 *
 * Two families of entry points:
 *
 * 1. **Document-input methods** — [`generate(document: MrzDocument)`][generate] and the
 *    type-specific overloads. Encodes a fully-constructed document; the consumer is
 *    responsible for ensuring the document's text fields already contain only MRZ-alphabet
 *    characters. Returns
 *    [`MrzGenerationUnsupportedCharacters`][io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters]
 *    when they don't (this closes a silent-failure gap on the existing path).
 *
 * 2. **Primitive-input methods** — [generateTD3], [generateTD2], [generateTD1],
 *    [generateMrvA], [generateMrvB]. Take individual field values plus an optional
 *    [`TransliterationProfile`][TransliterationProfile]; when a profile is supplied, the
 *    primary and secondary identifiers are run through it before encoding, and the
 *    per-field audit trail is surfaced on
 *    [`ResultMetadata.transliterationDetails`][io.lightine.tessera.mrz.parsing.ResultMetadata]
 *    per Principle 5 ([ADR-014](https://lightine.youtrack.cloud/articles/TES-A-47)).
 *
 * See
 * [`docs/features/mrz-generation.md`](https://lightine.youtrack.cloud/articles/TES-A-26)
 * for the full feature description.
 */
public object MrzGenerator {
    /**
     * Polymorphic dispatch over the [MrzDocument] sealed hierarchy. Consumers holding a typed
     * `MrzDocument` reference (typically from `MrzParser.parse(input).document`) can call this
     * directly without narrowing to the specific variant.
     */
    public fun generate(document: MrzDocument): GenerationResult =
        when (document) {
            is TD1 -> generate(document)
            is TD2 -> generate(document)
            is TD3 -> generate(document)
            is MrvA -> generate(document)
            is MrvB -> generate(document)
        }

    /** Encodes a TD3 passport document. See [generate]. */
    public fun generate(document: TD3): GenerationResult = generateTd3(document)

    /** Encodes a TD2 identity document. See [generate]. */
    public fun generate(document: TD2): GenerationResult = generateTd2(document)

    /** Encodes a TD1 identity card. See [generate]. */
    public fun generate(document: TD1): GenerationResult = generateTd1(document)

    /** Encodes an MRV-A Type-A visa. See [generate]. */
    public fun generate(document: MrvA): GenerationResult = generateMrvA(document)

    /** Encodes an MRV-B Type-B visa. See [generate]. */
    public fun generate(document: MrvB): GenerationResult = generateMrvB(document)

    // ----------------------------------------------------------------
    // Primitive-input methods
    //
    // These methods build the corresponding `MrzDocument` from raw field values
    // and then delegate to the `generate(document)` path. When a transliteration
    // profile is provided, the primary and secondary identifiers are run through
    // it before the document is constructed; the post-normalization and
    // post-transliteration forms are surfaced on the result metadata per
    // Principle 5 (ADR-014).
    // ----------------------------------------------------------------

    /**
     * Encodes a TD3 (passport) MRZ from primitive field values. Date fields take
     * [`LocalDate`][kotlinx.datetime.LocalDate]; the sex field takes
     * [`Sex`][io.lightine.tessera.types.vocabulary.Sex]; everything else is a string in
     * the consumer's preferred representation.
     *
     * If [transliteration] is non-null, [primaryIdentifier] and [secondaryIdentifier] are
     * run through the profile (with Unicode normalization first, per ADR-014). The
     * per-field audit trail is exposed on the result's
     * [`ResultMetadata.transliterationDetails`][io.lightine.tessera.mrz.parsing.ResultMetadata]
     * field; any unmapped characters produce
     * [`MrzGenerationUnsupportedCharacters`][io.lightine.tessera.types.errors.MrzGenerationUnsupportedCharacters].
     * If [transliteration] is null, the identifier strings are used verbatim and must
     * already contain only MRZ-alphabet characters.
     *
     * Returns [`GenerationResult.Failure`][GenerationResult.Failure] for field overflow,
     * missing required fields, or unsupported characters; otherwise
     * [`GenerationResult.Success`][GenerationResult.Success] with the two encoded MRZ lines.
     */
    public fun generateTD3(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
        personalNumber: String,
        transliteration: TransliterationProfile? = null,
    ): GenerationResult {
        val processed =
            when (val r = processIdentifiers(MrzFormat.TD3, primaryIdentifier, secondaryIdentifier, transliteration)) {
                is IdentifierProcessing.Failure -> return r.result
                is IdentifierProcessing.Success -> r
            }
        val rawNameField = buildRawNameField(processed.primary, processed.secondary)
        val document =
            TD3(
                rawLines = emptyList(),
                commonFields =
                    buildCommonFields(
                        documentType = documentType,
                        issuingState = issuingState,
                        primaryIdentifier = processed.primary,
                        secondaryIdentifier = processed.secondary,
                        rawNameField = rawNameField,
                        documentNumber = documentNumber,
                        nationality = nationality,
                        dateOfBirth = dateOfBirth,
                        sex = sex,
                        dateOfExpiry = dateOfExpiry,
                    ),
                personalNumber = personalNumber,
                personalNumberCheckDigit = PLACEHOLDER_CHECK_DIGIT,
            )
        return attachTransliterationDetails(generate(document), processed.details)
    }

    /**
     * Encodes a TD2 (smaller identity document) MRZ from primitive field values.
     * Parameter semantics — including the optional [transliteration] profile — match
     * [generateTD3]; the format-specific extra is [optionalData].
     */
    public fun generateTD2(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
        optionalData: String,
        transliteration: TransliterationProfile? = null,
    ): GenerationResult {
        val processed =
            when (val r = processIdentifiers(MrzFormat.TD2, primaryIdentifier, secondaryIdentifier, transliteration)) {
                is IdentifierProcessing.Failure -> return r.result
                is IdentifierProcessing.Success -> r
            }
        val rawNameField = buildRawNameField(processed.primary, processed.secondary)
        val document =
            TD2(
                rawLines = emptyList(),
                commonFields =
                    buildCommonFields(
                        documentType = documentType,
                        issuingState = issuingState,
                        primaryIdentifier = processed.primary,
                        secondaryIdentifier = processed.secondary,
                        rawNameField = rawNameField,
                        documentNumber = documentNumber,
                        nationality = nationality,
                        dateOfBirth = dateOfBirth,
                        sex = sex,
                        dateOfExpiry = dateOfExpiry,
                    ),
                optionalData = optionalData,
            )
        return attachTransliterationDetails(generate(document), processed.details)
    }

    /**
     * Encodes a TD1 (identity card) MRZ from primitive field values. Parameter semantics
     * — including the optional [transliteration] profile — match [generateTD3]; the
     * format-specific extras are [optionalData1] (line 1) and [optionalData2] (line 2).
     */
    public fun generateTD1(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
        optionalData1: String,
        optionalData2: String,
        transliteration: TransliterationProfile? = null,
    ): GenerationResult {
        val processed =
            when (val r = processIdentifiers(MrzFormat.TD1, primaryIdentifier, secondaryIdentifier, transliteration)) {
                is IdentifierProcessing.Failure -> return r.result
                is IdentifierProcessing.Success -> r
            }
        val rawNameField = buildRawNameField(processed.primary, processed.secondary)
        val document =
            TD1(
                rawLines = emptyList(),
                commonFields =
                    buildCommonFields(
                        documentType = documentType,
                        issuingState = issuingState,
                        primaryIdentifier = processed.primary,
                        secondaryIdentifier = processed.secondary,
                        rawNameField = rawNameField,
                        documentNumber = documentNumber,
                        nationality = nationality,
                        dateOfBirth = dateOfBirth,
                        sex = sex,
                        dateOfExpiry = dateOfExpiry,
                    ),
                optionalData1 = optionalData1,
                optionalData2 = optionalData2,
            )
        return attachTransliterationDetails(generate(document), processed.details)
    }

    /**
     * Encodes an MRV-A (Type-A visa) MRZ from primitive field values. Parameter semantics
     * — including the optional [transliteration] profile — match [generateTD3]; the
     * format-specific extra is [optionalData]. MRV-A has no composite check digit.
     */
    public fun generateMrvA(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
        optionalData: String,
        transliteration: TransliterationProfile? = null,
    ): GenerationResult {
        val processed =
            when (val r = processIdentifiers(MrzFormat.MRV_A, primaryIdentifier, secondaryIdentifier, transliteration)) {
                is IdentifierProcessing.Failure -> return r.result
                is IdentifierProcessing.Success -> r
            }
        val rawNameField = buildRawNameField(processed.primary, processed.secondary)
        val document =
            MrvA(
                rawLines = emptyList(),
                commonFields =
                    buildCommonFields(
                        documentType = documentType,
                        issuingState = issuingState,
                        primaryIdentifier = processed.primary,
                        secondaryIdentifier = processed.secondary,
                        rawNameField = rawNameField,
                        documentNumber = documentNumber,
                        nationality = nationality,
                        dateOfBirth = dateOfBirth,
                        sex = sex,
                        dateOfExpiry = dateOfExpiry,
                    ),
                optionalData = optionalData,
            )
        return attachTransliterationDetails(generate(document), processed.details)
    }

    /**
     * Encodes an MRV-B (Type-B visa) MRZ from primitive field values. Parameter semantics
     * — including the optional [transliteration] profile — match [generateTD3]; the
     * format-specific extra is [optionalData]. MRV-B has no composite check digit.
     */
    public fun generateMrvB(
        documentType: String,
        issuingState: String,
        documentNumber: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
        optionalData: String,
        transliteration: TransliterationProfile? = null,
    ): GenerationResult {
        val processed =
            when (val r = processIdentifiers(MrzFormat.MRV_B, primaryIdentifier, secondaryIdentifier, transliteration)) {
                is IdentifierProcessing.Failure -> return r.result
                is IdentifierProcessing.Success -> r
            }
        val rawNameField = buildRawNameField(processed.primary, processed.secondary)
        val document =
            MrvB(
                rawLines = emptyList(),
                commonFields =
                    buildCommonFields(
                        documentType = documentType,
                        issuingState = issuingState,
                        primaryIdentifier = processed.primary,
                        secondaryIdentifier = processed.secondary,
                        rawNameField = rawNameField,
                        documentNumber = documentNumber,
                        nationality = nationality,
                        dateOfBirth = dateOfBirth,
                        sex = sex,
                        dateOfExpiry = dateOfExpiry,
                    ),
                optionalData = optionalData,
            )
        return attachTransliterationDetails(generate(document), processed.details)
    }

    // ----------------------------------------------------------------
    // Per-format generation paths
    //
    // Each path validates field widths against the format spec, pads short fields with the
    // filler character `<`, recomputes every check digit from the field data, and assembles
    // the lines per ICAO Doc 9303 Parts 4–7. The composite check digit (where the format
    // defines one) is computed via the same `compositeInputFields` ranges the parser and
    // validator consume — generator, parser, and validator agree on the composite-input
    // definition by construction.
    // ----------------------------------------------------------------

    private fun generateTd3(document: TD3): GenerationResult {
        validateLength(
            MrzFormat.TD3,
            MrzField.DOCUMENT_TYPE,
            document.commonFields.documentType.rawCode,
            Td3FormatSpec.documentType.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD3,
            MrzField.ISSUING_STATE,
            document.commonFields.issuingState.rawCode,
            Td3FormatSpec.issuingState.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD3,
            MrzField.NAME_FIELD,
            document.commonFields.rawNameField,
            Td3FormatSpec.rawNameField.width,
        )?.let { return it }
        validateLength(
            MrzFormat.TD3,
            MrzField.DOCUMENT_NUMBER,
            document.commonFields.documentNumber,
            Td3FormatSpec.documentNumber.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD3,
            MrzField.NATIONALITY,
            document.commonFields.nationality.rawCode,
            Td3FormatSpec.nationality.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD3,
            MrzField.OPTIONAL_DATA,
            document.personalNumber,
            Td3FormatSpec.personalNumber.width,
        )?.let { return it }

        validateCommonFieldChars(MrzFormat.TD3, document)?.let { return it }
        validateMrzAlphabet(MrzFormat.TD3, MrzField.OPTIONAL_DATA, document.personalNumber)?.let { return it }

        val docType =
            document.commonFields.documentType.rawCode
                .padEnd(Td3FormatSpec.documentType.width, '<')
        val state =
            document.commonFields.issuingState.rawCode
                .padEnd(Td3FormatSpec.issuingState.width, '<')
        val name = document.commonFields.rawNameField.padEnd(Td3FormatSpec.rawNameField.width, '<')
        val line1 = docType + state + name

        val docNum = document.commonFields.documentNumber.padEnd(Td3FormatSpec.documentNumber.width, '<')
        val docNumCheck = computeCheckDigit(docNum)
        val nationality =
            document.commonFields.nationality.rawCode
                .padEnd(Td3FormatSpec.nationality.width, '<')
        val dob = document.commonFields.dateOfBirth.run { rawYear + rawMonth + rawDay }
        val dobCheck = computeCheckDigit(dob)
        val sex = document.commonFields.rawSex.toString()
        val doe = document.commonFields.dateOfExpiry.run { rawYear + rawMonth + rawDay }
        val doeCheck = computeCheckDigit(doe)
        val personalNum = document.personalNumber.padEnd(Td3FormatSpec.personalNumber.width, '<')
        val personalNumCheck = computeCheckDigit(personalNum)

        val line2Prefix =
            docNum + docNumCheck + nationality +
                dob + dobCheck + sex +
                doe + doeCheck +
                personalNum + personalNumCheck

        val placeholderLines = listOf(line1, line2Prefix + '<')
        val compositeInput = Td3FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(placeholderLines) }
        val composite = computeCheckDigit(compositeInput)
        val line2 = line2Prefix + composite

        return GenerationResult.Success(mrz = listOf(line1, line2), metadata = emptyMetadata())
    }

    private fun generateTd2(document: TD2): GenerationResult {
        validateLength(
            MrzFormat.TD2,
            MrzField.DOCUMENT_TYPE,
            document.commonFields.documentType.rawCode,
            Td2FormatSpec.documentType.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD2,
            MrzField.ISSUING_STATE,
            document.commonFields.issuingState.rawCode,
            Td2FormatSpec.issuingState.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD2,
            MrzField.NAME_FIELD,
            document.commonFields.rawNameField,
            Td2FormatSpec.rawNameField.width,
        )?.let { return it }
        validateLength(
            MrzFormat.TD2,
            MrzField.DOCUMENT_NUMBER,
            document.commonFields.documentNumber,
            Td2FormatSpec.documentNumber.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD2,
            MrzField.NATIONALITY,
            document.commonFields.nationality.rawCode,
            Td2FormatSpec.nationality.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.TD2, MrzField.OPTIONAL_DATA, document.optionalData, Td2FormatSpec.optionalData.width)?.let { return it }

        validateCommonFieldChars(MrzFormat.TD2, document)?.let { return it }
        validateMrzAlphabet(MrzFormat.TD2, MrzField.OPTIONAL_DATA, document.optionalData)?.let { return it }

        val docType =
            document.commonFields.documentType.rawCode
                .padEnd(Td2FormatSpec.documentType.width, '<')
        val state =
            document.commonFields.issuingState.rawCode
                .padEnd(Td2FormatSpec.issuingState.width, '<')
        val name = document.commonFields.rawNameField.padEnd(Td2FormatSpec.rawNameField.width, '<')
        val line1 = docType + state + name

        // TD2 has no per-field optional-data check digit (Part 6). Composite covers DOE+check+optionalData.
        val docNum = document.commonFields.documentNumber.padEnd(Td2FormatSpec.documentNumber.width, '<')
        val docNumCheck = computeCheckDigit(docNum)
        val nationality =
            document.commonFields.nationality.rawCode
                .padEnd(Td2FormatSpec.nationality.width, '<')
        val dob = document.commonFields.dateOfBirth.run { rawYear + rawMonth + rawDay }
        val dobCheck = computeCheckDigit(dob)
        val sex = document.commonFields.rawSex.toString()
        val doe = document.commonFields.dateOfExpiry.run { rawYear + rawMonth + rawDay }
        val doeCheck = computeCheckDigit(doe)
        val optionalData = document.optionalData.padEnd(Td2FormatSpec.optionalData.width, '<')

        val line2Prefix =
            docNum + docNumCheck + nationality +
                dob + dobCheck + sex +
                doe + doeCheck +
                optionalData

        val placeholderLines = listOf(line1, line2Prefix + '<')
        val compositeInput = Td2FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(placeholderLines) }
        val composite = computeCheckDigit(compositeInput)
        val line2 = line2Prefix + composite

        return GenerationResult.Success(mrz = listOf(line1, line2), metadata = emptyMetadata())
    }

    private fun generateTd1(document: TD1): GenerationResult {
        validateLength(
            MrzFormat.TD1,
            MrzField.DOCUMENT_TYPE,
            document.commonFields.documentType.rawCode,
            Td1FormatSpec.documentType.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD1,
            MrzField.ISSUING_STATE,
            document.commonFields.issuingState.rawCode,
            Td1FormatSpec.issuingState.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.TD1,
            MrzField.DOCUMENT_NUMBER,
            document.commonFields.documentNumber,
            Td1FormatSpec.documentNumber.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.TD1, MrzField.OPTIONAL_DATA, document.optionalData1, Td1FormatSpec.optionalData1.width)?.let { return it }
        validateLength(
            MrzFormat.TD1,
            MrzField.NATIONALITY,
            document.commonFields.nationality.rawCode,
            Td1FormatSpec.nationality.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.TD1, MrzField.OPTIONAL_DATA, document.optionalData2, Td1FormatSpec.optionalData2.width)?.let { return it }
        validateLength(
            MrzFormat.TD1,
            MrzField.NAME_FIELD,
            document.commonFields.rawNameField,
            Td1FormatSpec.rawNameField.width,
        )?.let { return it }

        validateCommonFieldChars(MrzFormat.TD1, document)?.let { return it }
        validateMrzAlphabet(MrzFormat.TD1, MrzField.OPTIONAL_DATA, document.optionalData1)?.let { return it }
        validateMrzAlphabet(MrzFormat.TD1, MrzField.OPTIONAL_DATA, document.optionalData2)?.let { return it }

        // Line 1: docType(2) + state(3) + docNum(9) + docCheck(1) + optionalData1(15) = 30
        val docType =
            document.commonFields.documentType.rawCode
                .padEnd(Td1FormatSpec.documentType.width, '<')
        val state =
            document.commonFields.issuingState.rawCode
                .padEnd(Td1FormatSpec.issuingState.width, '<')
        val docNum = document.commonFields.documentNumber.padEnd(Td1FormatSpec.documentNumber.width, '<')
        val docNumCheck = computeCheckDigit(docNum)
        val optionalData1 = document.optionalData1.padEnd(Td1FormatSpec.optionalData1.width, '<')
        val line1 = docType + state + docNum + docNumCheck + optionalData1

        // Line 2 prefix: dob(6) + dobCheck(1) + sex(1) + doe(6) + doeCheck(1) + nat(3) + optionalData2(11) = 29
        val dob = document.commonFields.dateOfBirth.run { rawYear + rawMonth + rawDay }
        val dobCheck = computeCheckDigit(dob)
        val sex = document.commonFields.rawSex.toString()
        val doe = document.commonFields.dateOfExpiry.run { rawYear + rawMonth + rawDay }
        val doeCheck = computeCheckDigit(doe)
        val nationality =
            document.commonFields.nationality.rawCode
                .padEnd(Td1FormatSpec.nationality.width, '<')
        val optionalData2 = document.optionalData2.padEnd(Td1FormatSpec.optionalData2.width, '<')
        val line2Prefix = dob + dobCheck + sex + doe + doeCheck + nationality + optionalData2

        // Line 3: name field (30 chars)
        val line3 = document.commonFields.rawNameField.padEnd(Td1FormatSpec.rawNameField.width, '<')

        // Composite input spans line 1 [5,30) (doc num + check + optionalData1), line 2 [0,7)
        // (dob + dobCheck), line 2 [8,15) (doe + doeCheck), line 2 [18,29) (optionalData2).
        val placeholderLines = listOf(line1, line2Prefix + '<', line3)
        val compositeInput = Td1FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(placeholderLines) }
        val composite = computeCheckDigit(compositeInput)
        val line2 = line2Prefix + composite

        return GenerationResult.Success(mrz = listOf(line1, line2, line3), metadata = emptyMetadata())
    }

    private fun generateMrvA(document: MrvA): GenerationResult {
        validateLength(
            MrzFormat.MRV_A,
            MrzField.DOCUMENT_TYPE,
            document.commonFields.documentType.rawCode,
            MrvAFormatSpec.documentType.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_A,
            MrzField.ISSUING_STATE,
            document.commonFields.issuingState.rawCode,
            MrvAFormatSpec.issuingState.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.MRV_A, MrzField.NAME_FIELD, document.commonFields.rawNameField, MrvAFormatSpec.rawNameField.width)?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_A,
            MrzField.DOCUMENT_NUMBER,
            document.commonFields.documentNumber,
            MrvAFormatSpec.documentNumber.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_A,
            MrzField.NATIONALITY,
            document.commonFields.nationality.rawCode,
            MrvAFormatSpec.nationality.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.MRV_A, MrzField.OPTIONAL_DATA, document.optionalData, MrvAFormatSpec.optionalData.width)?.let { return it }

        validateCommonFieldChars(MrzFormat.MRV_A, document)?.let { return it }
        validateMrzAlphabet(MrzFormat.MRV_A, MrzField.OPTIONAL_DATA, document.optionalData)?.let { return it }

        val docType =
            document.commonFields.documentType.rawCode
                .padEnd(MrvAFormatSpec.documentType.width, '<')
        val state =
            document.commonFields.issuingState.rawCode
                .padEnd(MrvAFormatSpec.issuingState.width, '<')
        val name = document.commonFields.rawNameField.padEnd(MrvAFormatSpec.rawNameField.width, '<')
        val line1 = docType + state + name

        // MRV-A has no composite check digit (Part 7); line 2 ends with the optional-data tail.
        val docNum = document.commonFields.documentNumber.padEnd(MrvAFormatSpec.documentNumber.width, '<')
        val docNumCheck = computeCheckDigit(docNum)
        val nationality =
            document.commonFields.nationality.rawCode
                .padEnd(MrvAFormatSpec.nationality.width, '<')
        val dob = document.commonFields.dateOfBirth.run { rawYear + rawMonth + rawDay }
        val dobCheck = computeCheckDigit(dob)
        val sex = document.commonFields.rawSex.toString()
        val doe = document.commonFields.dateOfExpiry.run { rawYear + rawMonth + rawDay }
        val doeCheck = computeCheckDigit(doe)
        val optionalData = document.optionalData.padEnd(MrvAFormatSpec.optionalData.width, '<')

        val line2 =
            docNum + docNumCheck + nationality +
                dob + dobCheck + sex +
                doe + doeCheck +
                optionalData

        return GenerationResult.Success(mrz = listOf(line1, line2), metadata = emptyMetadata())
    }

    private fun generateMrvB(document: MrvB): GenerationResult {
        validateLength(
            MrzFormat.MRV_B,
            MrzField.DOCUMENT_TYPE,
            document.commonFields.documentType.rawCode,
            MrvBFormatSpec.documentType.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_B,
            MrzField.ISSUING_STATE,
            document.commonFields.issuingState.rawCode,
            MrvBFormatSpec.issuingState.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.MRV_B, MrzField.NAME_FIELD, document.commonFields.rawNameField, MrvBFormatSpec.rawNameField.width)?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_B,
            MrzField.DOCUMENT_NUMBER,
            document.commonFields.documentNumber,
            MrvBFormatSpec.documentNumber.width,
        )?.let {
            return it
        }
        validateLength(
            MrzFormat.MRV_B,
            MrzField.NATIONALITY,
            document.commonFields.nationality.rawCode,
            MrvBFormatSpec.nationality.width,
        )?.let {
            return it
        }
        validateLength(MrzFormat.MRV_B, MrzField.OPTIONAL_DATA, document.optionalData, MrvBFormatSpec.optionalData.width)?.let { return it }

        validateCommonFieldChars(MrzFormat.MRV_B, document)?.let { return it }
        validateMrzAlphabet(MrzFormat.MRV_B, MrzField.OPTIONAL_DATA, document.optionalData)?.let { return it }

        val docType =
            document.commonFields.documentType.rawCode
                .padEnd(MrvBFormatSpec.documentType.width, '<')
        val state =
            document.commonFields.issuingState.rawCode
                .padEnd(MrvBFormatSpec.issuingState.width, '<')
        val name = document.commonFields.rawNameField.padEnd(MrvBFormatSpec.rawNameField.width, '<')
        val line1 = docType + state + name

        // MRV-B has no composite check digit (Part 7); line 2 ends with the optional-data tail.
        val docNum = document.commonFields.documentNumber.padEnd(MrvBFormatSpec.documentNumber.width, '<')
        val docNumCheck = computeCheckDigit(docNum)
        val nationality =
            document.commonFields.nationality.rawCode
                .padEnd(MrvBFormatSpec.nationality.width, '<')
        val dob = document.commonFields.dateOfBirth.run { rawYear + rawMonth + rawDay }
        val dobCheck = computeCheckDigit(dob)
        val sex = document.commonFields.rawSex.toString()
        val doe = document.commonFields.dateOfExpiry.run { rawYear + rawMonth + rawDay }
        val doeCheck = computeCheckDigit(doe)
        val optionalData = document.optionalData.padEnd(MrvBFormatSpec.optionalData.width, '<')

        val line2 =
            docNum + docNumCheck + nationality +
                dob + dobCheck + sex +
                doe + doeCheck +
                optionalData

        return GenerationResult.Success(mrz = listOf(line1, line2), metadata = emptyMetadata())
    }

    private const val PLACEHOLDER_CHECK_DIGIT: Char = '<'

    private sealed class IdentifierProcessing {
        data class Success(
            val primary: String,
            val secondary: String,
            val details: TransliterationDetails?,
        ) : IdentifierProcessing()

        data class Failure(
            val result: GenerationResult.Failure,
        ) : IdentifierProcessing()
    }

    private fun processIdentifiers(
        format: MrzFormat,
        primary: String,
        secondary: String,
        profile: TransliterationProfile?,
    ): IdentifierProcessing {
        // Per ICAO Doc 9303 Part 3 §4.6: "Numeric characters shall not be used in the
        // name fields of the MRZ." Reject before applying any profile so the error
        // references the consumer's original input rather than a transliterated form.
        rejectIfNameFieldContainsDigits(format, primary)?.let { return IdentifierProcessing.Failure(it) }
        rejectIfNameFieldContainsDigits(format, secondary)?.let { return IdentifierProcessing.Failure(it) }
        if (profile == null) {
            return IdentifierProcessing.Success(primary, secondary, details = null)
        }
        val fields = mutableListOf<TransliteratedField>()
        val processedPrimary =
            when (val outcome = applyProfile(format, MrzField.NAME_FIELD, primary, profile, fields)) {
                is TransliterationApply.Success -> outcome.output
                is TransliterationApply.Failure -> return IdentifierProcessing.Failure(outcome.result)
            }
        val processedSecondary =
            when (val outcome = applyProfile(format, MrzField.NAME_FIELD, secondary, profile, fields)) {
                is TransliterationApply.Success -> outcome.output
                is TransliterationApply.Failure -> return IdentifierProcessing.Failure(outcome.result)
            }
        val details =
            TransliterationDetails(
                profileIdentifier = profile.identifier,
                transliteratedFields = fields,
            )
        return IdentifierProcessing.Success(processedPrimary, processedSecondary, details)
    }

    private fun rejectIfNameFieldContainsDigits(
        format: MrzFormat,
        value: String,
    ): GenerationResult.Failure? {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return GenerationResult.Failure(
            error =
                MrzGenerationNumericInNameField(
                    format = format,
                    observedValue = value,
                    numericCharacters = digits.toList(),
                ),
            metadata = emptyMetadata(),
        )
    }

    private sealed class TransliterationApply {
        data class Success(
            val output: String,
        ) : TransliterationApply()

        data class Failure(
            val result: GenerationResult.Failure,
        ) : TransliterationApply()
    }

    private fun applyProfile(
        format: MrzFormat,
        field: MrzField,
        input: String,
        profile: TransliterationProfile,
        log: MutableList<TransliteratedField>,
    ): TransliterationApply {
        val outcome = profile.transliterate(input)
        return when (val result = outcome.result) {
            is TransliterationResult.Success -> {
                if (input.isNotEmpty()) {
                    log +=
                        TransliteratedField(
                            field = field,
                            originalInput = outcome.originalInput,
                            normalizedInput = outcome.normalizedInput,
                            transliteratedOutput = result.output,
                        )
                }
                TransliterationApply.Success(result.output)
            }

            is TransliterationResult.Failure -> {
                TransliterationApply.Failure(
                    GenerationResult.Failure(
                        error =
                            MrzGenerationUnsupportedCharacters(
                                format = format,
                                field = field,
                                unmappedCharacters = result.unmappedCharacters,
                                observedValue = outcome.normalizedInput,
                            ),
                        metadata = emptyMetadata(),
                    ),
                )
            }
        }
    }

    private fun buildRawNameField(
        primary: String,
        secondary: String,
    ): String {
        val primaryMrz = primary.replace(' ', '<')
        val secondaryMrz = secondary.replace(' ', '<')
        return if (secondaryMrz.isEmpty()) primaryMrz else "$primaryMrz<<$secondaryMrz"
    }

    private fun buildCommonFields(
        documentType: String,
        issuingState: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        rawNameField: String,
        documentNumber: String,
        nationality: String,
        dateOfBirth: LocalDate,
        sex: Sex,
        dateOfExpiry: LocalDate,
    ): CommonFields =
        CommonFields(
            documentType = DocumentType(documentType),
            issuingState = CountryCode(issuingState),
            primaryIdentifier = primaryIdentifier,
            secondaryIdentifier = secondaryIdentifier,
            nameTruncated = false,
            rawNameField = rawNameField,
            documentNumber = documentNumber,
            nationality = CountryCode(nationality),
            dateOfBirth = mrzDateFrom(dateOfBirth),
            sex = sex,
            rawSex = sexToChar(sex),
            dateOfExpiry = mrzDateFrom(dateOfExpiry),
            checkDigits =
                MrzCheckDigits(
                    documentNumber = PLACEHOLDER_CHECK_DIGIT,
                    dateOfBirth = PLACEHOLDER_CHECK_DIGIT,
                    dateOfExpiry = PLACEHOLDER_CHECK_DIGIT,
                    optionalData = null,
                    composite = null,
                ),
        )

    @Suppress("DEPRECATION")
    private fun mrzDateFrom(date: LocalDate): MrzDate {
        val rawYear = (date.year % 100).toString().padStart(2, '0')
        // `monthNumber` / `dayOfMonth` are deprecated in kotlinx-datetime 0.8.0 in favor of the
        // `Month` enum / `day` properties, but the deprecated accessors still return Int directly
        // and work across all targets. Migrate when the replacement API stabilizes across targets.
        val rawMonth = date.monthNumber.toString().padStart(2, '0')
        val rawDay = date.dayOfMonth.toString().padStart(2, '0')
        return MrzDate(rawYear = rawYear, rawMonth = rawMonth, rawDay = rawDay)
    }

    private fun sexToChar(sex: Sex): Char =
        when (sex) {
            Sex.MALE -> 'M'
            Sex.FEMALE -> 'F'
            Sex.UNSPECIFIED -> '<'
        }

    private fun attachTransliterationDetails(
        result: GenerationResult,
        details: TransliterationDetails?,
    ): GenerationResult {
        if (details == null || details.transliteratedFields.isEmpty()) return result
        return when (result) {
            is GenerationResult.Success -> {
                result.copy(metadata = result.metadata.copy(transliterationDetails = details))
            }

            is GenerationResult.Failure -> {
                result.copy(metadata = result.metadata.copy(transliterationDetails = details))
            }
        }
    }

    private fun validateLength(
        format: MrzFormat,
        field: MrzField,
        value: String,
        maxLength: Int,
    ): GenerationResult.Failure? {
        if (value.length <= maxLength) return null
        return GenerationResult.Failure(
            error =
                MrzGenerationFieldOverflow(
                    format = format,
                    field = field,
                    maxLength = maxLength,
                    observedLength = value.length,
                    observedValue = value,
                ),
            metadata = emptyMetadata(),
        )
    }

    private fun validateMrzAlphabet(
        format: MrzFormat,
        field: MrzField,
        value: String,
    ): GenerationResult.Failure? {
        val unmapped =
            buildList {
                for ((position, char) in value.withIndex()) {
                    if (!isMrzAlphabetCharacter(char)) add(UnmappedCharacter(char, position))
                }
            }
        if (unmapped.isEmpty()) return null
        return GenerationResult.Failure(
            error =
                MrzGenerationUnsupportedCharacters(
                    format = format,
                    field = field,
                    unmappedCharacters = unmapped,
                    observedValue = value,
                ),
            metadata = emptyMetadata(),
        )
    }

    private fun validateCommonFieldChars(
        format: MrzFormat,
        document: MrzDocument,
    ): GenerationResult.Failure? {
        val cf = document.commonFields
        validateMrzAlphabet(format, MrzField.DOCUMENT_TYPE, cf.documentType.rawCode)?.let { return it }
        validateMrzAlphabet(format, MrzField.ISSUING_STATE, cf.issuingState.rawCode)?.let { return it }
        validateMrzAlphabet(format, MrzField.NAME_FIELD, cf.rawNameField)?.let { return it }
        validateMrzAlphabet(format, MrzField.DOCUMENT_NUMBER, cf.documentNumber)?.let { return it }
        validateMrzAlphabet(format, MrzField.NATIONALITY, cf.nationality.rawCode)?.let { return it }
        return null
    }

    // Pragmatic placeholder: `ReadMethod` was designed for parser results (which read data from
    // some source) but `GenerationResult` carries it too because `ResultMetadata` is shared.
    // BACKEND_STRING_INPUT is the closest fit pre-0.1.0. A future refinement may either widen
    // `ReadMethod` (e.g., `GENERATED`) or split `ResultMetadata` into parse- and generate-
    // specific variants. Tracked in the next session handoff.
    private fun emptyMetadata(): ResultMetadata =
        ResultMetadata(
            readMethod = ReadMethod.BACKEND_STRING_INPUT,
            warnings = emptyList(),
            validationFailures = emptyList(),
        )
}
