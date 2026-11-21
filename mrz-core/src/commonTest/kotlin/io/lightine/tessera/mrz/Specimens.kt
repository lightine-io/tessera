package io.lightine.tessera.mrz

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpecWithComposite
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractFrom

/**
 * The project's own specimens (TES-24): a fictional person, SPECIMEN, ALEX MORGAN; sex F;
 * nationality and issuer UTO; born 1990-03-15; expires 2034-03-14. Each set parses as Success
 * with no warnings at a 2026 reference time, and the generator reproduces each line exactly.
 *
 * ICAO's published examples live only in the conformance test. A test that needs a variant
 * builds it from these with [overwrite] and [withCheckDigitsRecomputed], never with a
 * hand-typed check digit.
 */
internal object Specimens {
    /** TD3 (passport), document number T22000129, personal number S98765432. */
    val TD3: List<String> =
        listOf(
            "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
            "T220001293UTO9003152F3403141S98765432<<<<<20",
        )

    /** TD1 (ID card), document number T42000137. */
    val TD1: List<String> =
        listOf(
            "I<UTOT420001370<<<<<<<<<<<<<<<",
            "9003152F3403141UTO<<<<<<<<<<<2",
            "SPECIMEN<<ALEX<MORGAN<<<<<<<<<",
        )

    /** TD2, document number T62000145. */
    val TD2: List<String> =
        listOf(
            "I<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<",
            "T620001457UTO9003152F3403141<<<<<<<8",
        )

    /** MRV-A (visa), document number T82000153. */
    val MRV_A: List<String> =
        listOf(
            "V<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
            "T820001534UTO9003152F3403141<<<<<<<<<<<<<<<<",
        )

    /** MRV-B (visa), document number T82000153. */
    val MRV_B: List<String> =
        listOf(
            "V<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<",
            "T820001534UTO9003152F3403141<<<<<<<<",
        )
}

/** Returns these lines with [text] written over [field], starting at its first character. Check digits are left as they are. */
internal fun List<String>.overwrite(
    field: FieldSpec,
    text: String,
): List<String> = overwrite(field.line, field.startInLine, text)

/** Returns these lines with [text] written over line [line] from index [start]. Check digits are left as they are. */
internal fun List<String>.overwrite(
    line: Int,
    start: Int,
    text: String,
): List<String> =
    mapIndexed { index, value ->
        if (index != line) value else value.substring(0, start) + text + value.substring(start + text.length)
    }

/**
 * Returns these lines with every check digit of [spec] recomputed with the SDK's [computeCheckDigit]:
 * document number, birth, expiry, the TD3 personal number, then the composite.
 * The TD3 personal-number check digit comes out as computed ('0' for an all-filler number).
 */
internal fun List<String>.withCheckDigitsRecomputed(spec: MrzFormatSpec): List<String> {
    var lines = this

    fun recompute(
        data: FieldSpec,
        check: FieldSpec,
    ) {
        lines = lines.overwrite(check, computeCheckDigit(data.extractFrom(lines)).toString())
    }
    recompute(spec.documentNumber, spec.documentNumberCheckDigit)
    recompute(spec.dateOfBirth, spec.dateOfBirthCheckDigit)
    recompute(spec.dateOfExpiry, spec.dateOfExpiryCheckDigit)
    if (spec is Td3FormatSpec) recompute(spec.personalNumber, spec.personalNumberCheckDigit)
    return lines.withCompositeRecomputed(spec)
}

/** Returns these lines with the composite check digit of [spec] recomputed; unchanged for a format without one. */
internal fun List<String>.withCompositeRecomputed(spec: MrzFormatSpec): List<String> {
    if (spec !is MrzFormatSpecWithComposite) return this
    val input = spec.compositeInputFields.joinToString("") { it.extractFrom(this) }
    return overwrite(spec.compositeCheckDigit, computeCheckDigit(input).toString())
}
