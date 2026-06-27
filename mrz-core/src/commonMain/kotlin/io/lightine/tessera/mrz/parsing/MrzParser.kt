package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractCharFrom
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
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.mrz.validation.MrzValidator
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Parses MRZ strings into [`MrzDocument`][io.lightine.tessera.mrz.model.MrzDocument]
 * objects per ICAO Doc 9303. Provides both auto-detecting and format-specific entry
 * points.
 *
 * Two families of entry points:
 *
 * 1. **Auto-detecting** — [parse]. Identifies the format from the input's line count and
 *    per-line lengths and dispatches to the matching format-specific parser. Returns
 *    [`MrzFormatNotDetected`][io.lightine.tessera.types.errors.MrzFormatNotDetected]
 *    when the input shape does not match any supported format. Inputs whose shape is
 *    ambiguous between a non-visa and a visa format are disambiguated by the leading
 *    `V` character on line 1 (per Principle 1: dispatch is deterministic, never
 *    "ambiguous").
 *
 * 2. **Format-specific** — [parseTD3], [parseTD2], [parseTD1], [parseMRVA], [parseMRVB].
 *    Use these when the consumer already knows the expected format. Format-specific
 *    parsers return [`MrzInvalidLength`][io.lightine.tessera.types.errors.MrzInvalidLength]
 *    (not [`MrzFormatNotDetected`][io.lightine.tessera.types.errors.MrzFormatNotDetected])
 *    when the input doesn't match the format's dimensions.
 *
 * Every entry point optionally accepts a [`referenceTime`][kotlin.time.Instant] used for
 * date-window inference (2-digit year disambiguation, expiry-past warnings, etc.). The
 * default is the current system time; tests typically pass an explicit reference so they
 * don't depend on the wall clock.
 *
 * See
 * [`docs/features/mrz-parsing.md`](https://lightine.youtrack.cloud/articles/TES-A-25)
 * for the full feature description.
 */
public object MrzParser {
    /**
     * Parses [input] by auto-detecting the format from its shape. Line endings (`\n`,
     * `\r\n`, `\r`) are normalized; leading empty lines are dropped; trailing whitespace
     * is trimmed before detection. See the dispatch rules in the class-level KDoc.
     */
    public fun parse(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parse(splitLines(input), referenceTime)

    /** Convenience overload of [parse] accepting pre-split lines. */
    public fun parse(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val detected = detectFormat(input)
        return when (detected) {
            MrzFormat.TD1 -> {
                parseTD1(input, referenceTime)
            }

            MrzFormat.TD2 -> {
                parseTD2(input, referenceTime)
            }

            MrzFormat.TD3 -> {
                parseTD3(input, referenceTime)
            }

            MrzFormat.MRV_A -> {
                parseMRVA(input, referenceTime)
            }

            MrzFormat.MRV_B -> {
                parseMRVB(input, referenceTime)
            }

            null -> {
                ParseResult.Failure(
                    error =
                        MrzFormatNotDetected(
                            observedLineCount = input.size,
                            observedLineLengths = input.map { it.length },
                        ),
                    rawInput = input.joinToString("\n"),
                    metadata =
                        ResultMetadata(
                            readMethod = ReadMethod.BACKEND_STRING_INPUT,
                            warnings = emptyList(),
                            validationFailures = emptyList(),
                        ),
                )
            }
        }
    }

    // Auto-detect dispatches by line count + per-line length + leading character of line 1
    // (per docs/features/mrz-parsing.md "Auto-Detect Behavior"). Inputs whose shape does not
    // match any supported format produce MrzFormatNotDetected; the caller can then choose to
    // surface the failure or retry through a format-specific entry point with a corrected input.
    // Conservative by design: when shape plausibly fits multiple formats and the leading
    // character does not disambiguate, Visa codes (V*) take MRV-A / MRV-B; every other leading
    // character takes TD3 / TD2 — there is no "ambiguous" return path because the leading
    // character is always defined for inputs whose shape passed the line-count and -length
    // checks (Principle 1: when in doubt, do not invent — every shipped MRZ has a first character
    // and the dispatch on V vs non-V is total).
    private fun detectFormat(input: List<String>): MrzFormat? {
        val lineCount = input.size
        val lineLengths = input.map { it.length }
        return when {
            lineCount == Td1FormatSpec.lineCount && lineLengths.all { it == Td1FormatSpec.lineLength } -> {
                MrzFormat.TD1
            }

            lineCount == Td2FormatSpec.lineCount && lineLengths.all { it == Td2FormatSpec.lineLength } -> {
                if (startsWithVisaPrefix(input[0])) MrzFormat.MRV_B else MrzFormat.TD2
            }

            lineCount == Td3FormatSpec.lineCount && lineLengths.all { it == Td3FormatSpec.lineLength } -> {
                if (startsWithVisaPrefix(input[0])) MrzFormat.MRV_A else MrzFormat.TD3
            }

            else -> {
                null
            }
        }
    }

    private fun startsWithVisaPrefix(line: String): Boolean = line.isNotEmpty() && line[0] == 'V'

    /**
     * Parses [input] as TD3 (passport, 2 lines × 44 characters per ICAO Doc 9303 Part 4).
     * Returns [`MrzInvalidLength`][io.lightine.tessera.types.errors.MrzInvalidLength] if
     * the input does not match the format's dimensions;
     * [`MrzCharacterSetViolation`][io.lightine.tessera.types.errors.MrzCharacterSetViolation]
     * if any character is outside the MRZ alphabet. Structurally-valid inputs produce
     * [`Success`][ParseResult.Success] or [`PartialSuccess`][ParseResult.PartialSuccess]
     * depending on whether the validator surfaced failures.
     */
    public fun parseTD3(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parseTD3(splitLines(input), referenceTime)

    /** Convenience overload of [parseTD3] accepting pre-split lines. */
    public fun parseTD3(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val emptyMetadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = emptyList(),
                validationFailures = emptyList(),
            )

        validateLineShape(
            input = input,
            format = MrzFormat.TD3,
            expectedLineCount = Td3FormatSpec.lineCount,
            expectedLineLength = Td3FormatSpec.lineLength,
        )?.let { error ->
            return ParseResult.Failure(error = error, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        validateAlphabet(input, lineLength = Td3FormatSpec.lineLength)?.let { violation ->
            return ParseResult.Failure(error = violation, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        val td3 = sliceTd3Fields(input, referenceTime)
        return finalizeParseResult(td3, referenceTime)
    }

    /**
     * Parses [input] as TD2 (smaller identity document, 2 lines × 36 characters per
     * ICAO Doc 9303 Part 6). Error and result semantics match [parseTD3].
     */
    public fun parseTD2(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parseTD2(splitLines(input), referenceTime)

    /** Convenience overload of [parseTD2] accepting pre-split lines. */
    public fun parseTD2(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val emptyMetadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = emptyList(),
                validationFailures = emptyList(),
            )

        validateLineShape(
            input = input,
            format = MrzFormat.TD2,
            expectedLineCount = Td2FormatSpec.lineCount,
            expectedLineLength = Td2FormatSpec.lineLength,
        )?.let { error ->
            return ParseResult.Failure(error = error, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        validateAlphabet(input, lineLength = Td2FormatSpec.lineLength)?.let { violation ->
            return ParseResult.Failure(error = violation, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        val td2 = sliceTd2Fields(input, referenceTime)
        return finalizeParseResult(td2, referenceTime)
    }

    /**
     * Parses [input] as MRV-A (Type-A visa, 2 lines × 44 characters per ICAO Doc 9303
     * Part 7). Error and result semantics match [parseTD3]; MRV-A has no composite check
     * digit per the spec.
     */
    public fun parseMRVA(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parseMRVA(splitLines(input), referenceTime)

    /** Convenience overload of [parseMRVA] accepting pre-split lines. */
    public fun parseMRVA(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val emptyMetadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = emptyList(),
                validationFailures = emptyList(),
            )

        validateLineShape(
            input = input,
            format = MrzFormat.MRV_A,
            expectedLineCount = MrvAFormatSpec.lineCount,
            expectedLineLength = MrvAFormatSpec.lineLength,
        )?.let { error ->
            return ParseResult.Failure(error = error, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        validateAlphabet(input, lineLength = MrvAFormatSpec.lineLength)?.let { violation ->
            return ParseResult.Failure(error = violation, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        val mrvA = sliceMrvAFields(input, referenceTime)
        return finalizeParseResult(mrvA, referenceTime)
    }

    /**
     * Parses [input] as MRV-B (Type-B visa, 2 lines × 36 characters per ICAO Doc 9303
     * Part 7). Error and result semantics match [parseTD3]; MRV-B has no composite check
     * digit per the spec.
     */
    public fun parseMRVB(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parseMRVB(splitLines(input), referenceTime)

    /** Convenience overload of [parseMRVB] accepting pre-split lines. */
    public fun parseMRVB(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val emptyMetadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = emptyList(),
                validationFailures = emptyList(),
            )

        validateLineShape(
            input = input,
            format = MrzFormat.MRV_B,
            expectedLineCount = MrvBFormatSpec.lineCount,
            expectedLineLength = MrvBFormatSpec.lineLength,
        )?.let { error ->
            return ParseResult.Failure(error = error, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        validateAlphabet(input, lineLength = MrvBFormatSpec.lineLength)?.let { violation ->
            return ParseResult.Failure(error = violation, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        val mrvB = sliceMrvBFields(input, referenceTime)
        return finalizeParseResult(mrvB, referenceTime)
    }

    /**
     * Parses [input] as TD1 (identity card, 3 lines × 30 characters per ICAO Doc 9303
     * Part 5). Error and result semantics match [parseTD3].
     */
    public fun parseTD1(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = parseTD1(splitLines(input), referenceTime)

    /** Convenience overload of [parseTD1] accepting pre-split lines. */
    public fun parseTD1(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult {
        val emptyMetadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = emptyList(),
                validationFailures = emptyList(),
            )

        validateLineShape(
            input = input,
            format = MrzFormat.TD1,
            expectedLineCount = Td1FormatSpec.lineCount,
            expectedLineLength = Td1FormatSpec.lineLength,
        )?.let { error ->
            return ParseResult.Failure(error = error, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        validateAlphabet(input, lineLength = Td1FormatSpec.lineLength)?.let { violation ->
            return ParseResult.Failure(error = violation, rawInput = input.joinToString("\n"), metadata = emptyMetadata)
        }

        val td1 = sliceTd1Fields(input, referenceTime)
        return finalizeParseResult(td1, referenceTime)
    }

    private fun finalizeParseResult(
        document: MrzDocument,
        referenceTime: Instant,
    ): ParseResult {
        val validation = MrzValidator.validate(document, referenceTime)
        val metadata =
            ResultMetadata(
                readMethod = ReadMethod.BACKEND_STRING_INPUT,
                warnings = validation.warnings,
                validationFailures = validation.validationFailures,
            )
        return if (validation.validationFailures.isEmpty()) {
            ParseResult.Success(document = document, metadata = metadata)
        } else {
            ParseResult.PartialSuccess(document = document, metadata = metadata)
        }
    }

    private fun validateLineShape(
        input: List<String>,
        format: MrzFormat,
        expectedLineCount: Int,
        expectedLineLength: Int,
    ): MrzInvalidLength? {
        val observedLengths = input.map { it.length }
        val countMatches = input.size == expectedLineCount
        val lengthsMatch = observedLengths.all { it == expectedLineLength }
        return if (countMatches && lengthsMatch) {
            null
        } else {
            MrzInvalidLength(
                format = format,
                expectedLineCount = expectedLineCount,
                expectedLineLength = expectedLineLength,
                observedLineCount = input.size,
                observedLineLengths = observedLengths,
            )
        }
    }

    private fun validateAlphabet(
        input: List<String>,
        lineLength: Int,
    ): MrzCharacterSetViolation? {
        for ((lineIndex, line) in input.withIndex()) {
            for ((charIndex, c) in line.withIndex()) {
                if (!isMrzAlphabetCharacter(c)) {
                    return MrzCharacterSetViolation(
                        offendingCharacter = c,
                        position = lineIndex * lineLength + charIndex,
                    )
                }
            }
        }
        return null
    }

    private fun sliceTd3Fields(
        input: List<String>,
        referenceTime: Instant,
    ): TD3 {
        val documentTypeCode = Td3FormatSpec.documentType.extractFrom(input).trimEnd('<')
        val issuingState = Td3FormatSpec.issuingState.extractFrom(input)
        val rawNameField = Td3FormatSpec.rawNameField.extractFrom(input)

        val documentNumber = Td3FormatSpec.documentNumber.extractFrom(input)
        val docNumberCheckDigit = Td3FormatSpec.documentNumberCheckDigit.extractCharFrom(input)
        val nationality = Td3FormatSpec.nationality.extractFrom(input)
        val rawDob = Td3FormatSpec.dateOfBirth.extractFrom(input)
        val dobCheckDigit = Td3FormatSpec.dateOfBirthCheckDigit.extractCharFrom(input)
        val sexChar = Td3FormatSpec.sex.extractCharFrom(input)
        val rawExpiry = Td3FormatSpec.dateOfExpiry.extractFrom(input)
        val expiryCheckDigit = Td3FormatSpec.dateOfExpiryCheckDigit.extractCharFrom(input)
        val personalNumber = Td3FormatSpec.personalNumber.extractFrom(input)
        val personalNumberCheckDigit = Td3FormatSpec.personalNumberCheckDigit.extractCharFrom(input)
        val compositeCheckDigit = Td3FormatSpec.compositeCheckDigit.extractCharFrom(input)

        val sex =
            when (sexChar) {
                'M' -> Sex.MALE
                'F' -> Sex.FEMALE
                else -> Sex.UNSPECIFIED
            }

        val dateOfBirth =
            MrzDate.parseBirth(
                rawYear = rawDob.substring(0, 2),
                rawMonth = rawDob.substring(2, 4),
                rawDay = rawDob.substring(4, 6),
                referenceTime = referenceTime,
            )

        val dateOfExpiry =
            MrzDate.parseExpiry(
                rawYear = rawExpiry.substring(0, 2),
                rawMonth = rawExpiry.substring(2, 4),
                rawDay = rawExpiry.substring(4, 6),
                referenceTime = referenceTime,
            )

        val checkDigits =
            MrzCheckDigits(
                documentNumber = docNumberCheckDigit,
                dateOfBirth = dobCheckDigit,
                dateOfExpiry = expiryCheckDigit,
                optionalData = personalNumberCheckDigit,
                composite = compositeCheckDigit,
            )

        val nameFields = parseNameField(rawNameField)

        val commonFields =
            CommonFields(
                documentType = DocumentType(documentTypeCode),
                issuingState = CountryCode(issuingState),
                primaryIdentifier = nameFields.primaryIdentifier,
                secondaryIdentifier = nameFields.secondaryIdentifier,
                nameTruncated = nameFields.nameTruncated,
                rawNameField = rawNameField,
                documentNumber = documentNumber,
                nationality = CountryCode(nationality),
                dateOfBirth = dateOfBirth,
                sex = sex,
                rawSex = sexChar,
                dateOfExpiry = dateOfExpiry,
                checkDigits = checkDigits,
            )

        return TD3(
            rawLines = input,
            commonFields = commonFields,
            personalNumber = personalNumber,
            personalNumberCheckDigit = personalNumberCheckDigit,
        )
    }

    private fun sliceTd2Fields(
        input: List<String>,
        referenceTime: Instant,
    ): TD2 {
        val documentTypeCode = Td2FormatSpec.documentType.extractFrom(input).trimEnd('<')
        val issuingState = Td2FormatSpec.issuingState.extractFrom(input)
        val rawNameField = Td2FormatSpec.rawNameField.extractFrom(input)

        val documentNumber = Td2FormatSpec.documentNumber.extractFrom(input)
        val docNumberCheckDigit = Td2FormatSpec.documentNumberCheckDigit.extractCharFrom(input)
        val nationality = Td2FormatSpec.nationality.extractFrom(input)
        val rawDob = Td2FormatSpec.dateOfBirth.extractFrom(input)
        val dobCheckDigit = Td2FormatSpec.dateOfBirthCheckDigit.extractCharFrom(input)
        val sexChar = Td2FormatSpec.sex.extractCharFrom(input)
        val rawExpiry = Td2FormatSpec.dateOfExpiry.extractFrom(input)
        val expiryCheckDigit = Td2FormatSpec.dateOfExpiryCheckDigit.extractCharFrom(input)
        val optionalData = Td2FormatSpec.optionalData.extractFrom(input)
        val compositeCheckDigit = Td2FormatSpec.compositeCheckDigit.extractCharFrom(input)

        val sex =
            when (sexChar) {
                'M' -> Sex.MALE
                'F' -> Sex.FEMALE
                else -> Sex.UNSPECIFIED
            }

        val dateOfBirth =
            MrzDate.parseBirth(
                rawYear = rawDob.substring(0, 2),
                rawMonth = rawDob.substring(2, 4),
                rawDay = rawDob.substring(4, 6),
                referenceTime = referenceTime,
            )

        val dateOfExpiry =
            MrzDate.parseExpiry(
                rawYear = rawExpiry.substring(0, 2),
                rawMonth = rawExpiry.substring(2, 4),
                rawDay = rawExpiry.substring(4, 6),
                referenceTime = referenceTime,
            )

        // TD2 has no per-field check digit on optional data (ICAO Doc 9303 Part 6); the
        // optional-data slot's correctness is covered by the composite check digit only.
        val checkDigits =
            MrzCheckDigits(
                documentNumber = docNumberCheckDigit,
                dateOfBirth = dobCheckDigit,
                dateOfExpiry = expiryCheckDigit,
                optionalData = null,
                composite = compositeCheckDigit,
            )

        val nameFields = parseNameField(rawNameField)

        val commonFields =
            CommonFields(
                documentType = DocumentType(documentTypeCode),
                issuingState = CountryCode(issuingState),
                primaryIdentifier = nameFields.primaryIdentifier,
                secondaryIdentifier = nameFields.secondaryIdentifier,
                nameTruncated = nameFields.nameTruncated,
                rawNameField = rawNameField,
                documentNumber = documentNumber,
                nationality = CountryCode(nationality),
                dateOfBirth = dateOfBirth,
                sex = sex,
                rawSex = sexChar,
                dateOfExpiry = dateOfExpiry,
                checkDigits = checkDigits,
            )

        return TD2(
            rawLines = input,
            commonFields = commonFields,
            optionalData = optionalData,
        )
    }

    private fun sliceMrvAFields(
        input: List<String>,
        referenceTime: Instant,
    ): MrvA {
        val documentTypeCode = MrvAFormatSpec.documentType.extractFrom(input).trimEnd('<')
        val issuingState = MrvAFormatSpec.issuingState.extractFrom(input)
        val rawNameField = MrvAFormatSpec.rawNameField.extractFrom(input)

        val documentNumber = MrvAFormatSpec.documentNumber.extractFrom(input)
        val docNumberCheckDigit = MrvAFormatSpec.documentNumberCheckDigit.extractCharFrom(input)
        val nationality = MrvAFormatSpec.nationality.extractFrom(input)
        val rawDob = MrvAFormatSpec.dateOfBirth.extractFrom(input)
        val dobCheckDigit = MrvAFormatSpec.dateOfBirthCheckDigit.extractCharFrom(input)
        val sexChar = MrvAFormatSpec.sex.extractCharFrom(input)
        val rawExpiry = MrvAFormatSpec.dateOfExpiry.extractFrom(input)
        val expiryCheckDigit = MrvAFormatSpec.dateOfExpiryCheckDigit.extractCharFrom(input)
        val optionalData = MrvAFormatSpec.optionalData.extractFrom(input)

        val sex =
            when (sexChar) {
                'M' -> Sex.MALE
                'F' -> Sex.FEMALE
                else -> Sex.UNSPECIFIED
            }

        val dateOfBirth =
            MrzDate.parseBirth(
                rawYear = rawDob.substring(0, 2),
                rawMonth = rawDob.substring(2, 4),
                rawDay = rawDob.substring(4, 6),
                referenceTime = referenceTime,
            )

        val dateOfExpiry =
            MrzDate.parseExpiry(
                rawYear = rawExpiry.substring(0, 2),
                rawMonth = rawExpiry.substring(2, 4),
                rawDay = rawExpiry.substring(4, 6),
                referenceTime = referenceTime,
            )

        // MRV-A has neither a per-field check digit on optional data nor a composite check digit
        // (ICAO Doc 9303 Part 7). Only the document number, DOB, and expiry carry per-field
        // digits; both `optionalData` and `composite` in MrzCheckDigits are null for visas.
        val checkDigits =
            MrzCheckDigits(
                documentNumber = docNumberCheckDigit,
                dateOfBirth = dobCheckDigit,
                dateOfExpiry = expiryCheckDigit,
                optionalData = null,
                composite = null,
            )

        val nameFields = parseNameField(rawNameField)

        val commonFields =
            CommonFields(
                documentType = DocumentType(documentTypeCode),
                issuingState = CountryCode(issuingState),
                primaryIdentifier = nameFields.primaryIdentifier,
                secondaryIdentifier = nameFields.secondaryIdentifier,
                nameTruncated = nameFields.nameTruncated,
                rawNameField = rawNameField,
                documentNumber = documentNumber,
                nationality = CountryCode(nationality),
                dateOfBirth = dateOfBirth,
                sex = sex,
                rawSex = sexChar,
                dateOfExpiry = dateOfExpiry,
                checkDigits = checkDigits,
            )

        return MrvA(
            rawLines = input,
            commonFields = commonFields,
            optionalData = optionalData,
        )
    }

    private fun sliceMrvBFields(
        input: List<String>,
        referenceTime: Instant,
    ): MrvB {
        val documentTypeCode = MrvBFormatSpec.documentType.extractFrom(input).trimEnd('<')
        val issuingState = MrvBFormatSpec.issuingState.extractFrom(input)
        val rawNameField = MrvBFormatSpec.rawNameField.extractFrom(input)

        val documentNumber = MrvBFormatSpec.documentNumber.extractFrom(input)
        val docNumberCheckDigit = MrvBFormatSpec.documentNumberCheckDigit.extractCharFrom(input)
        val nationality = MrvBFormatSpec.nationality.extractFrom(input)
        val rawDob = MrvBFormatSpec.dateOfBirth.extractFrom(input)
        val dobCheckDigit = MrvBFormatSpec.dateOfBirthCheckDigit.extractCharFrom(input)
        val sexChar = MrvBFormatSpec.sex.extractCharFrom(input)
        val rawExpiry = MrvBFormatSpec.dateOfExpiry.extractFrom(input)
        val expiryCheckDigit = MrvBFormatSpec.dateOfExpiryCheckDigit.extractCharFrom(input)
        val optionalData = MrvBFormatSpec.optionalData.extractFrom(input)

        val sex =
            when (sexChar) {
                'M' -> Sex.MALE
                'F' -> Sex.FEMALE
                else -> Sex.UNSPECIFIED
            }

        val dateOfBirth =
            MrzDate.parseBirth(
                rawYear = rawDob.substring(0, 2),
                rawMonth = rawDob.substring(2, 4),
                rawDay = rawDob.substring(4, 6),
                referenceTime = referenceTime,
            )

        val dateOfExpiry =
            MrzDate.parseExpiry(
                rawYear = rawExpiry.substring(0, 2),
                rawMonth = rawExpiry.substring(2, 4),
                rawDay = rawExpiry.substring(4, 6),
                referenceTime = referenceTime,
            )

        // MRV-B has neither a per-field check digit on optional data nor a composite check digit
        // (ICAO Doc 9303 Part 7). Both `optionalData` and `composite` in MrzCheckDigits are null.
        val checkDigits =
            MrzCheckDigits(
                documentNumber = docNumberCheckDigit,
                dateOfBirth = dobCheckDigit,
                dateOfExpiry = expiryCheckDigit,
                optionalData = null,
                composite = null,
            )

        val nameFields = parseNameField(rawNameField)

        val commonFields =
            CommonFields(
                documentType = DocumentType(documentTypeCode),
                issuingState = CountryCode(issuingState),
                primaryIdentifier = nameFields.primaryIdentifier,
                secondaryIdentifier = nameFields.secondaryIdentifier,
                nameTruncated = nameFields.nameTruncated,
                rawNameField = rawNameField,
                documentNumber = documentNumber,
                nationality = CountryCode(nationality),
                dateOfBirth = dateOfBirth,
                sex = sex,
                rawSex = sexChar,
                dateOfExpiry = dateOfExpiry,
                checkDigits = checkDigits,
            )

        return MrvB(
            rawLines = input,
            commonFields = commonFields,
            optionalData = optionalData,
        )
    }

    private fun sliceTd1Fields(
        input: List<String>,
        referenceTime: Instant,
    ): TD1 {
        val documentTypeCode = Td1FormatSpec.documentType.extractFrom(input).trimEnd('<')
        val issuingState = Td1FormatSpec.issuingState.extractFrom(input)

        val documentNumber = Td1FormatSpec.documentNumber.extractFrom(input)
        val docNumberCheckDigit = Td1FormatSpec.documentNumberCheckDigit.extractCharFrom(input)
        val optionalData1 = Td1FormatSpec.optionalData1.extractFrom(input)

        val rawDob = Td1FormatSpec.dateOfBirth.extractFrom(input)
        val dobCheckDigit = Td1FormatSpec.dateOfBirthCheckDigit.extractCharFrom(input)
        val sexChar = Td1FormatSpec.sex.extractCharFrom(input)
        val rawExpiry = Td1FormatSpec.dateOfExpiry.extractFrom(input)
        val expiryCheckDigit = Td1FormatSpec.dateOfExpiryCheckDigit.extractCharFrom(input)
        val nationality = Td1FormatSpec.nationality.extractFrom(input)
        val optionalData2 = Td1FormatSpec.optionalData2.extractFrom(input)
        val compositeCheckDigit = Td1FormatSpec.compositeCheckDigit.extractCharFrom(input)

        val rawNameField = Td1FormatSpec.rawNameField.extractFrom(input)

        val sex =
            when (sexChar) {
                'M' -> Sex.MALE
                'F' -> Sex.FEMALE
                else -> Sex.UNSPECIFIED
            }

        val dateOfBirth =
            MrzDate.parseBirth(
                rawYear = rawDob.substring(0, 2),
                rawMonth = rawDob.substring(2, 4),
                rawDay = rawDob.substring(4, 6),
                referenceTime = referenceTime,
            )

        val dateOfExpiry =
            MrzDate.parseExpiry(
                rawYear = rawExpiry.substring(0, 2),
                rawMonth = rawExpiry.substring(2, 4),
                rawDay = rawExpiry.substring(4, 6),
                referenceTime = referenceTime,
            )

        // TD1 has no per-field check digit on optional data (neither optional data 1 nor optional
        // data 2 has its own digit per ICAO Doc 9303 Part 5). Both optional slots are covered by
        // the composite check digit only.
        val checkDigits =
            MrzCheckDigits(
                documentNumber = docNumberCheckDigit,
                dateOfBirth = dobCheckDigit,
                dateOfExpiry = expiryCheckDigit,
                optionalData = null,
                composite = compositeCheckDigit,
            )

        val nameFields = parseNameField(rawNameField)

        val commonFields =
            CommonFields(
                documentType = DocumentType(documentTypeCode),
                issuingState = CountryCode(issuingState),
                primaryIdentifier = nameFields.primaryIdentifier,
                secondaryIdentifier = nameFields.secondaryIdentifier,
                nameTruncated = nameFields.nameTruncated,
                rawNameField = rawNameField,
                documentNumber = documentNumber,
                nationality = CountryCode(nationality),
                dateOfBirth = dateOfBirth,
                sex = sex,
                rawSex = sexChar,
                dateOfExpiry = dateOfExpiry,
                checkDigits = checkDigits,
            )

        return TD1(
            rawLines = input,
            commonFields = commonFields,
            optionalData1 = optionalData1,
            optionalData2 = optionalData2,
        )
    }

    private fun splitLines(input: String): List<String> = input.trimEnd().lines().dropWhile { it.isEmpty() }
}
