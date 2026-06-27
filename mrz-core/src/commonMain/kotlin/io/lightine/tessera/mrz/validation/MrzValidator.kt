package io.lightine.tessera.mrz.validation

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractFrom
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.types.errors.MrzBirthDateImplausiblyOld
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzExpiryDateImplausiblyFar
import io.lightine.tessera.types.errors.MrzExpiryDatePast
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzNameTruncated
import io.lightine.tessera.types.errors.MrzPersonalNumberCheckDigitFiller
import io.lightine.tessera.types.errors.MrzSexCharacterX
import io.lightine.tessera.types.errors.MrzUnknownCountryCode
import io.lightine.tessera.types.errors.MrzUnknownDocumentTypeCode
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.MrzField
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Validates parsed [`MrzDocument`][MrzDocument] objects against the per-field and
 * cross-field checks defined by ICAO Doc 9303 and by the SDK's documented warnings.
 *
 * Returns a [ValidationResult] carrying any
 * [`MrzValidationError`][io.lightine.tessera.types.errors.MrzValidationError]s (per-field
 * structural failures: check digit mismatches, calendar-invalid dates, out-of-range sex
 * characters) and any
 * [`MrzWarning`][io.lightine.tessera.types.errors.MrzWarning]s (informational
 * observations: implausible dates, name truncation, unknown country/document codes).
 *
 * `MrzParser` invokes the validator internally and folds the result into
 * [`ResultMetadata`][io.lightine.tessera.mrz.parsing.ResultMetadata]; consumers reading
 * parser output rarely need to call the validator directly. Direct callers are typically
 * tests or callers re-validating a document constructed from primitive inputs.
 *
 * See
 * [`docs/features/mrz-validation.md`](https://lightine.youtrack.cloud/articles/TES-A-32)
 * for the full feature description.
 */
public object MrzValidator {
    private val VALID_SEX_CHARACTERS = setOf('M', 'F', '<', 'X')
    private const val EXPIRY_IMPLAUSIBLY_FAR_THRESHOLD_YEARS = 10

    /**
     * Validates [document], using [referenceTime] for date-window checks (expiry past,
     * expiry implausibly far in the future, birth date implausibly old). Defaults to the
     * current system time; the parser passes its own `referenceTime` through so parser
     * and validator agree on the date interpretation.
     */
    public fun validate(
        document: MrzDocument,
        referenceTime: Instant = Clock.System.now(),
    ): ValidationResult =
        when (document) {
            is TD3 -> validateTD3(document, referenceTime)
            is TD2 -> validateTD2(document, referenceTime)
            is MrvA -> validateMrvA(document, referenceTime)
            is MrvB -> validateMrvB(document, referenceTime)
            is TD1 -> validateTD1(document, referenceTime)
        }

    private fun validateTD3(
        document: TD3,
        referenceTime: Instant,
    ): ValidationResult {
        val rawLines = document.rawLines
        val failures = mutableListOf<MrzValidationError>()
        val warnings = mutableListOf<MrzWarning>()

        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td3FormatSpec.documentNumber.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.documentNumber,
            field = MrzField.DOCUMENT_NUMBER,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.documentNumberCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td3FormatSpec.dateOfBirth.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.dateOfBirthCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td3FormatSpec.dateOfExpiry.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.dateOfExpiryCheckDigit),
        )
        // TD3 personal-number check digit: distinguish "filler with populated personal number"
        // (a documented real-world deviation surfaced as a warning) from "wrong digit" (a strict
        // ICAO non-conformance surfaced as a validation failure). See
        // `docs/features/mrz-parsing.md` "Personal Number Check Digit as Filler" and
        // [ADR-013](../../../decisions/0013-recognition-failures-are-warnings.md).
        val rawPersonalNumber = Td3FormatSpec.personalNumber.extractFrom(rawLines)
        val personalNumberCheck = document.personalNumberCheckDigit
        val personalNumberHasContent = rawPersonalNumber.any { it != '<' }
        if (personalNumberCheck == '<' && personalNumberHasContent) {
            // Documented deviation: issuer left the check digit as filler while populating the
            // personal number itself. Per Principle 1 and [ADR-013], not a failure — surfaced
            // as a typed warning so consumers can decide whether to treat the deviation as
            // disqualifying for their use case.
            warnings +=
                MrzPersonalNumberCheckDigitFiller(
                    rawPersonalNumber = rawPersonalNumber,
                    position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.personalNumberCheckDigit),
                )
        } else {
            addCheckDigitFailureIfMismatch(
                into = failures,
                input = rawPersonalNumber,
                observed = personalNumberCheck,
                field = MrzField.OPTIONAL_DATA,
                position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.personalNumberCheckDigit),
            )
        }

        // TD3 always carries a composite check digit per ICAO Doc 9303 Part 4; the parser
        // populates it unconditionally, so the model-level `Char?` is structurally non-null here.
        val compositeInput = Td3FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(rawLines) }
        document.commonFields.checkDigits.composite?.let { composite ->
            addCheckDigitFailureIfMismatch(
                into = failures,
                input = compositeInput,
                observed = composite,
                field = MrzField.COMPOSITE,
                position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.compositeCheckDigit),
            )
        }

        checkSexCharacter(
            rawSex = document.commonFields.rawSex,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.sex),
            failures = failures,
            warnings = warnings,
        )

        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.dateOfBirth),
        )
        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.dateOfExpiry),
        )

        addExpiryWarningsIfApplicable(
            into = warnings,
            expiryComputedDate = document.commonFields.dateOfExpiry.computedDate,
            referenceTime = referenceTime,
        )
        addBirthAgeWarningIfApplicable(
            into = warnings,
            birthDate = document.commonFields.dateOfBirth,
            referenceTime = referenceTime,
        )
        addUnknownDocumentTypeCodeWarningIfApplicable(
            into = warnings,
            documentType = document.commonFields.documentType,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.documentType),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.issuingState,
            field = MrzField.ISSUING_STATE,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.issuingState),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.nationality,
            field = MrzField.NATIONALITY,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.nationality),
        )
        addNameTruncatedWarningIfApplicable(
            into = warnings,
            nameTruncated = document.commonFields.nameTruncated,
            rawNameField = document.commonFields.rawNameField,
            position = Td3FormatSpec.globalPositionOf(Td3FormatSpec.rawNameField),
        )

        return ValidationResult(validationFailures = failures.toList(), warnings = warnings.toList())
    }

    private fun validateTD2(
        document: TD2,
        referenceTime: Instant,
    ): ValidationResult {
        val rawLines = document.rawLines
        val failures = mutableListOf<MrzValidationError>()
        val warnings = mutableListOf<MrzWarning>()

        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td2FormatSpec.documentNumber.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.documentNumber,
            field = MrzField.DOCUMENT_NUMBER,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.documentNumberCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td2FormatSpec.dateOfBirth.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.dateOfBirthCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td2FormatSpec.dateOfExpiry.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.dateOfExpiryCheckDigit),
        )
        // TD2 has no per-field check digit on optional data (ICAO Doc 9303 Part 6); only the
        // composite digit covers it.

        // TD2 always carries a composite check digit per ICAO Doc 9303 Part 6; the parser
        // populates it unconditionally, so the model-level `Char?` is structurally non-null here.
        val compositeInput = Td2FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(rawLines) }
        document.commonFields.checkDigits.composite?.let { composite ->
            addCheckDigitFailureIfMismatch(
                into = failures,
                input = compositeInput,
                observed = composite,
                field = MrzField.COMPOSITE,
                position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.compositeCheckDigit),
            )
        }

        checkSexCharacter(
            rawSex = document.commonFields.rawSex,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.sex),
            failures = failures,
            warnings = warnings,
        )

        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.dateOfBirth),
        )
        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.dateOfExpiry),
        )

        addExpiryWarningsIfApplicable(
            into = warnings,
            expiryComputedDate = document.commonFields.dateOfExpiry.computedDate,
            referenceTime = referenceTime,
        )
        addBirthAgeWarningIfApplicable(
            into = warnings,
            birthDate = document.commonFields.dateOfBirth,
            referenceTime = referenceTime,
        )
        addUnknownDocumentTypeCodeWarningIfApplicable(
            into = warnings,
            documentType = document.commonFields.documentType,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.documentType),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.issuingState,
            field = MrzField.ISSUING_STATE,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.issuingState),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.nationality,
            field = MrzField.NATIONALITY,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.nationality),
        )
        addNameTruncatedWarningIfApplicable(
            into = warnings,
            nameTruncated = document.commonFields.nameTruncated,
            rawNameField = document.commonFields.rawNameField,
            position = Td2FormatSpec.globalPositionOf(Td2FormatSpec.rawNameField),
        )

        return ValidationResult(validationFailures = failures.toList(), warnings = warnings.toList())
    }

    private fun validateMrvA(
        document: MrvA,
        referenceTime: Instant,
    ): ValidationResult {
        val rawLines = document.rawLines
        val failures = mutableListOf<MrzValidationError>()
        val warnings = mutableListOf<MrzWarning>()

        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvAFormatSpec.documentNumber.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.documentNumber,
            field = MrzField.DOCUMENT_NUMBER,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.documentNumberCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvAFormatSpec.dateOfBirth.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.dateOfBirthCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvAFormatSpec.dateOfExpiry.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.dateOfExpiryCheckDigit),
        )
        // MRV-A has neither a per-field check digit on optional data nor a composite check digit
        // (ICAO Doc 9303 Part 7). No per-field OPTIONAL_DATA or COMPOSITE failures are emitted.

        checkSexCharacter(
            rawSex = document.commonFields.rawSex,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.sex),
            failures = failures,
            warnings = warnings,
        )

        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.dateOfBirth),
        )
        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.dateOfExpiry),
        )

        addExpiryWarningsIfApplicable(
            into = warnings,
            expiryComputedDate = document.commonFields.dateOfExpiry.computedDate,
            referenceTime = referenceTime,
        )
        addBirthAgeWarningIfApplicable(
            into = warnings,
            birthDate = document.commonFields.dateOfBirth,
            referenceTime = referenceTime,
        )
        addUnknownDocumentTypeCodeWarningIfApplicable(
            into = warnings,
            documentType = document.commonFields.documentType,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.documentType),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.issuingState,
            field = MrzField.ISSUING_STATE,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.issuingState),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.nationality,
            field = MrzField.NATIONALITY,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.nationality),
        )
        addNameTruncatedWarningIfApplicable(
            into = warnings,
            nameTruncated = document.commonFields.nameTruncated,
            rawNameField = document.commonFields.rawNameField,
            position = MrvAFormatSpec.globalPositionOf(MrvAFormatSpec.rawNameField),
        )

        return ValidationResult(validationFailures = failures.toList(), warnings = warnings.toList())
    }

    private fun validateTD1(
        document: TD1,
        referenceTime: Instant,
    ): ValidationResult {
        val rawLines = document.rawLines
        val failures = mutableListOf<MrzValidationError>()
        val warnings = mutableListOf<MrzWarning>()

        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td1FormatSpec.documentNumber.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.documentNumber,
            field = MrzField.DOCUMENT_NUMBER,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.documentNumberCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td1FormatSpec.dateOfBirth.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.dateOfBirthCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = Td1FormatSpec.dateOfExpiry.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.dateOfExpiryCheckDigit),
        )
        // TD1 has no per-field check digit on optional data 1 or optional data 2 (ICAO Doc 9303
        // Part 5); the composite digit covers both optional slots.

        // TD1 always carries a composite check digit per ICAO Doc 9303 Part 5; the parser
        // populates it unconditionally, so the model-level `Char?` is structurally non-null here.
        val compositeInput = Td1FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(rawLines) }
        document.commonFields.checkDigits.composite?.let { composite ->
            addCheckDigitFailureIfMismatch(
                into = failures,
                input = compositeInput,
                observed = composite,
                field = MrzField.COMPOSITE,
                position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.compositeCheckDigit),
            )
        }

        checkSexCharacter(
            rawSex = document.commonFields.rawSex,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.sex),
            failures = failures,
            warnings = warnings,
        )

        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.dateOfBirth),
        )
        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.dateOfExpiry),
        )

        addExpiryWarningsIfApplicable(
            into = warnings,
            expiryComputedDate = document.commonFields.dateOfExpiry.computedDate,
            referenceTime = referenceTime,
        )
        addBirthAgeWarningIfApplicable(
            into = warnings,
            birthDate = document.commonFields.dateOfBirth,
            referenceTime = referenceTime,
        )
        addUnknownDocumentTypeCodeWarningIfApplicable(
            into = warnings,
            documentType = document.commonFields.documentType,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.documentType),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.issuingState,
            field = MrzField.ISSUING_STATE,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.issuingState),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.nationality,
            field = MrzField.NATIONALITY,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.nationality),
        )
        addNameTruncatedWarningIfApplicable(
            into = warnings,
            nameTruncated = document.commonFields.nameTruncated,
            rawNameField = document.commonFields.rawNameField,
            position = Td1FormatSpec.globalPositionOf(Td1FormatSpec.rawNameField),
        )

        return ValidationResult(validationFailures = failures.toList(), warnings = warnings.toList())
    }

    private fun validateMrvB(
        document: MrvB,
        referenceTime: Instant,
    ): ValidationResult {
        val rawLines = document.rawLines
        val failures = mutableListOf<MrzValidationError>()
        val warnings = mutableListOf<MrzWarning>()

        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvBFormatSpec.documentNumber.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.documentNumber,
            field = MrzField.DOCUMENT_NUMBER,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.documentNumberCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvBFormatSpec.dateOfBirth.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.dateOfBirthCheckDigit),
        )
        addCheckDigitFailureIfMismatch(
            into = failures,
            input = MrvBFormatSpec.dateOfExpiry.extractFrom(rawLines),
            observed = document.commonFields.checkDigits.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.dateOfExpiryCheckDigit),
        )
        // MRV-B has neither a per-field check digit on optional data nor a composite check digit
        // (ICAO Doc 9303 Part 7). No per-field OPTIONAL_DATA or COMPOSITE failures are emitted.

        checkSexCharacter(
            rawSex = document.commonFields.rawSex,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.sex),
            failures = failures,
            warnings = warnings,
        )

        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfBirth,
            field = MrzField.DATE_OF_BIRTH,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.dateOfBirth),
        )
        addDateNotInCalendarFailureIfApplicable(
            into = failures,
            date = document.commonFields.dateOfExpiry,
            field = MrzField.DATE_OF_EXPIRY,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.dateOfExpiry),
        )

        addExpiryWarningsIfApplicable(
            into = warnings,
            expiryComputedDate = document.commonFields.dateOfExpiry.computedDate,
            referenceTime = referenceTime,
        )
        addBirthAgeWarningIfApplicable(
            into = warnings,
            birthDate = document.commonFields.dateOfBirth,
            referenceTime = referenceTime,
        )
        addUnknownDocumentTypeCodeWarningIfApplicable(
            into = warnings,
            documentType = document.commonFields.documentType,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.documentType),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.issuingState,
            field = MrzField.ISSUING_STATE,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.issuingState),
        )
        addUnknownCountryCodeWarningIfApplicable(
            into = warnings,
            countryCode = document.commonFields.nationality,
            field = MrzField.NATIONALITY,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.nationality),
        )
        addNameTruncatedWarningIfApplicable(
            into = warnings,
            nameTruncated = document.commonFields.nameTruncated,
            rawNameField = document.commonFields.rawNameField,
            position = MrvBFormatSpec.globalPositionOf(MrvBFormatSpec.rawNameField),
        )

        return ValidationResult(validationFailures = failures.toList(), warnings = warnings.toList())
    }

    private fun addNameTruncatedWarningIfApplicable(
        into: MutableList<MrzWarning>,
        nameTruncated: Boolean,
        rawNameField: String,
        position: Int,
    ) {
        if (!nameTruncated) return
        into += MrzNameTruncated(rawNameField = rawNameField, position = position)
    }

    private fun addUnknownDocumentTypeCodeWarningIfApplicable(
        into: MutableList<MrzWarning>,
        documentType: DocumentType,
        position: Int,
    ) {
        if (documentType.isRecognized) return
        into += MrzUnknownDocumentTypeCode(rawCode = documentType.rawCode, position = position)
    }

    private fun addUnknownCountryCodeWarningIfApplicable(
        into: MutableList<MrzWarning>,
        countryCode: CountryCode,
        field: MrzField,
        position: Int,
    ) {
        if (countryCode.isRecognized) return
        into += MrzUnknownCountryCode(field = field, rawCode = countryCode.rawCode, position = position)
    }

    private fun addBirthAgeWarningIfApplicable(
        into: MutableList<MrzWarning>,
        birthDate: MrzDate,
        referenceTime: Instant,
    ) {
        if (birthDate.componentsExceedBirthAgeLimit != true) return
        val referenceDate = referenceTime.toLocalDateTime(TimeZone.UTC).date
        into +=
            MrzBirthDateImplausiblyOld(
                rawYear = birthDate.rawYear,
                rawMonth = birthDate.rawMonth,
                rawDay = birthDate.rawDay,
                referenceDate = referenceDate,
                thresholdYears = MrzDate.MAX_PLAUSIBLE_AGE_YEARS,
            )
    }

    private fun addDateNotInCalendarFailureIfApplicable(
        into: MutableList<MrzValidationError>,
        date: MrzDate,
        field: MrzField,
        position: Int,
    ) {
        if (date.componentsFormCalendarDate == false) {
            into +=
                MrzDateNotInCalendar(
                    field = field,
                    rawYear = date.rawYear,
                    rawMonth = date.rawMonth,
                    rawDay = date.rawDay,
                    position = position,
                )
        }
    }

    private fun checkSexCharacter(
        rawSex: Char,
        position: Int,
        failures: MutableList<MrzValidationError>,
        warnings: MutableList<MrzWarning>,
    ) {
        if (rawSex !in VALID_SEX_CHARACTERS) {
            failures += MrzInvalidSexValue(observed = rawSex, position = position)
        } else if (rawSex == 'X') {
            // Per ICAO Doc 9303 Part 4 §4.2.2.2 (and Parts 5/6/7 equivalents), the MRZ
            // sex character is canonically F / M / < only — X is for the VIZ per Note p/f.
            // We accept X (real-world practice on some non-binary / unspecified-sex
            // documents) but surface the deviation. See MrzSexCharacterX.
            warnings += MrzSexCharacterX(observed = rawSex, position = position)
        }
    }

    private fun addCheckDigitFailureIfMismatch(
        into: MutableList<MrzValidationError>,
        input: String,
        observed: Char,
        field: MrzField,
        position: Int,
    ) {
        val expected = computeCheckDigit(input)
        if (expected != observed) {
            into += MrzCheckDigitMismatch(field = field, expected = expected, observed = observed, position = position)
        }
    }

    private fun addExpiryWarningsIfApplicable(
        into: MutableList<MrzWarning>,
        expiryComputedDate: LocalDate?,
        referenceTime: Instant,
    ) {
        if (expiryComputedDate == null) return
        val referenceDate = referenceTime.toLocalDateTime(TimeZone.UTC).date
        if (expiryComputedDate < referenceDate) {
            into += MrzExpiryDatePast(expiryDate = expiryComputedDate, referenceDate = referenceDate)
            return
        }
        val implausiblyFarThreshold = referenceDate.plus(EXPIRY_IMPLAUSIBLY_FAR_THRESHOLD_YEARS, DateTimeUnit.YEAR)
        if (expiryComputedDate > implausiblyFarThreshold) {
            into +=
                MrzExpiryDateImplausiblyFar(
                    expiryDate = expiryComputedDate,
                    referenceDate = referenceDate,
                    thresholdYears = EXPIRY_IMPLAUSIBLY_FAR_THRESHOLD_YEARS,
                )
        }
    }
}
