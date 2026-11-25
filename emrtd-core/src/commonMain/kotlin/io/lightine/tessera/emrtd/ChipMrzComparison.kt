package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.lds.Dg1View
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.jvm.JvmStatic

/**
 * Puts a printed MRZ (read by the camera or typed in) beside the chip's DG1.
 *
 * The result has `ReadMethod.MIXED`: the values are the chip's, and each field carries a fact
 * saying whether the printed MRZ shows the same characters. Nothing is corrected, and neither
 * source is picked over the other (ICAO Doc 9303 Part 11, section 5.1.1, best practice 2 makes the
 * comparison optional for the inspection system; what a difference means stays with your app).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public object ChipMrzComparison {
    /**
     * Combines [printed] with the DG1 of [chipRead].
     *
     * @return The combined result, or `null` when the read has no parsed DG1.
     */
    @JvmStatic
    public fun combine(
        printed: MrzDocument,
        chipRead: ChipRead,
    ): CombinedMrzRead? = chipRead.dg1?.let { combine(printed, it) }

    /** Combines [printed] with the chip's [dg1]. */
    @JvmStatic
    public fun combine(
        printed: MrzDocument,
        dg1: Dg1View,
    ): CombinedMrzRead {
        val chip = dg1.document
        val fields = MrzField.entries.mapNotNull { field -> compare(field, printed, chip) }
        val sexSame = printed.commonFields.rawSex == chip.commonFields.rawSex
        val check = printedCheck(printed, chip)
        val mixed =
            when (val r = dg1.parseResult) {
                is ParseResult.Success -> r.copy(metadata = r.metadata.copy(readMethod = ReadMethod.MIXED))
                is ParseResult.PartialSuccess -> r.copy(metadata = r.metadata.copy(readMethod = ReadMethod.MIXED))
                is ParseResult.Failure -> r
            }
        return CombinedMrzRead(mixed, printed, printed.format == chip.format, fields, sexSame, check)
    }

    /**
     * The fact "the chip's DG1 matches the printed MRZ", with its per-field detail and no value.
     * Passed when the formats are the same and no field differs; failed otherwise. The read makes
     * the same check when [ChipReadOptions.printedDocument] is given.
     */
    internal fun printedCheck(
        printed: MrzDocument,
        chip: MrzDocument,
    ): ChipCheck {
        val fields =
            MrzField.entries.associateWith { field ->
                val a = valueOf(field, printed)
                val b = valueOf(field, chip)
                when {
                    a == null || b == null -> ChipFieldMatch.NOT_COMPARABLE
                    a == b -> ChipFieldMatch.SAME
                    else -> ChipFieldMatch.DIFFERENT
                }
            }
        val sameFormat = printed.format == chip.format
        val outcome =
            if (sameFormat && fields.values.none { it == ChipFieldMatch.DIFFERENT }) {
                CheckOutcome.Passed
            } else {
                CheckOutcome.Failed(ChipCheckReason.FIELDS_DIFFER)
            }
        return ChipCheck(ChipCheckKind.Dg1MatchesPrintedMrz, outcome, ChipCheckDetail.PrintedMrzFields(sameFormat, fields))
    }

    private fun compare(
        field: MrzField,
        printed: MrzDocument,
        chip: MrzDocument,
    ): MrzFieldComparison? {
        val a = valueOf(field, printed)
        val b = valueOf(field, chip)
        if (a == null && b == null) return null
        return MrzFieldComparison(field, a == b)
    }

    /** The characters of [field] as printed in [d], check digit included; `null` when the format has no such field. */
    private fun valueOf(
        field: MrzField,
        d: MrzDocument,
    ): String? {
        val c = d.commonFields
        return when (field) {
            MrzField.DOCUMENT_TYPE -> {
                c.documentType.rawCode
            }

            MrzField.ISSUING_STATE -> {
                c.issuingState.rawCode
            }

            MrzField.NAME_FIELD -> {
                c.rawNameField
            }

            MrzField.DOCUMENT_NUMBER -> {
                c.documentNumber + c.checkDigits.documentNumber
            }

            MrzField.NATIONALITY -> {
                c.nationality.rawCode
            }

            MrzField.DATE_OF_BIRTH -> {
                c.dateOfBirth.rawYear + c.dateOfBirth.rawMonth + c.dateOfBirth.rawDay + c.checkDigits.dateOfBirth
            }

            MrzField.DATE_OF_EXPIRY -> {
                c.dateOfExpiry.rawYear + c.dateOfExpiry.rawMonth + c.dateOfExpiry.rawDay + c.checkDigits.dateOfExpiry
            }

            MrzField.OPTIONAL_DATA -> {
                when (d) {
                    is TD1 -> d.optionalData1 + "|" + d.optionalData2
                    is TD2 -> d.optionalData
                    is TD3 -> d.personalNumber + d.personalNumberCheckDigit
                    is MrvA -> d.optionalData
                    is MrvB -> d.optionalData
                }
            }

            MrzField.COMPOSITE -> {
                c.checkDigits.composite?.toString()
            }

            MrzField.SEX -> {
                c.rawSex.toString()
            }
        }
    }
}

/**
 * The chip's DG1 combined with a printed MRZ ([ChipMrzComparison.combine]).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property parseResult The chip's parse, with `ReadMethod.MIXED` in its metadata. Its document
 *   holds the chip's values, never the printed ones.
 * @property printed The printed MRZ as your app gave it.
 * @property sameFormat Whether both are the same MRZ format.
 * @property fieldComparisons One fact per field both MRZs have: whether the characters are the
 *   same, check digits included.
 * @property sexSame Whether the sex character is the same.
 * @property check The [ChipCheckKind.Dg1MatchesPrintedMrz] fact: passed when the format, every
 *   field and the sex character are the same.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CombinedMrzRead internal constructor(
    public val parseResult: ParseResult,
    public val printed: MrzDocument,
    public val sameFormat: Boolean,
    public val fieldComparisons: List<MrzFieldComparison>,
    public val sexSame: Boolean,
    public val check: ChipCheck,
) {
    /** The fields whose characters differ, in [MrzField] order. */
    public val differingFields: List<MrzField>
        get() = fieldComparisons.filter { !it.sameAsPrinted }.map { it.field }

    /** Counts only, never values. */
    override fun toString(): String = "CombinedMrzRead(sameFormat=$sameFormat, differingFields=$differingFields, sexSame=$sexSame)"
}

/**
 * Whether one MRZ field shows the same characters on the chip and in print.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property field The field.
 * @property sameAsPrinted `true` when the characters are the same, check digit included.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
@ConsistentCopyVisibility
public data class MrzFieldComparison internal constructor(
    public val field: MrzField,
    public val sameAsPrinted: Boolean,
)
