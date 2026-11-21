// Sample for the page TES-A-132, Handle parse errors, validation failures, and warnings in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-132. Region text matches the page; code outside regions only makes it compile.
package samples.tesa132

// region TES-A-132 block 2
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.MrzCharacterSetViolation
import io.lightine.tessera.types.errors.MrzCheckDigitMismatch
import io.lightine.tessera.types.errors.MrzDateNotInCalendar
import io.lightine.tessera.types.errors.MrzExpiryDatePast
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzInvalidSexValue
import io.lightine.tessera.types.errors.MrzParseError
import io.lightine.tessera.types.errors.MrzValidationError
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-132 block 3
    fun explainParseError(error: MrzParseError): String =
        when (error) {
            is MrzFormatNotDetected -> "No format fits ${error.observedLineCount} line(s) of lengths ${error.observedLineLengths}"
            is MrzInvalidLength -> "${error.format} needs ${error.expectedLineCount} lines of ${error.expectedLineLength} characters"
            is MrzCharacterSetViolation -> "Character '${error.offendingCharacter}' at position ${error.position}"
        }

    val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    val line2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    val referenceTime = Instant.parse("2013-01-01T00:00:00Z")
    val bad = MrzParser.parse(listOf(line1.replaceRange(5, 6, "e"), line2), referenceTime) as ParseResult.Failure
    println(explainParseError(bad.error))
    // endregion

    // region TES-A-132 block 4
    fun explainFailure(failure: MrzValidationError): String =
        when (failure) {
            is MrzCheckDigitMismatch -> "${failure.field}: expected ${failure.expected}, observed ${failure.observed}"
            is MrzDateNotInCalendar -> "${failure.field}: ${failure.rawYear}${failure.rawMonth}${failure.rawDay} is not a date"
            is MrzInvalidSexValue -> "Sex character '${failure.observed}' is not accepted"
        }

    val tampered = MrzParser.parse(listOf(line1, line2.dropLast(1) + "1"), referenceTime) as ParseResult.PartialSuccess
    tampered.metadata.validationFailures.forEach { println(explainFailure(it)) }
    // endregion

    // region TES-A-132 block 5
    val parsed = MrzParser.parse(listOf(line1, line2), referenceTime)
    println(parsed.metadata.warnings.any { it is MrzExpiryDatePast })
    // endregion

    // region TES-A-132 block 6
    val strict = parsed.metadata.warnings.isEmpty() && parsed.metadata.validationFailures.isEmpty()
    // endregion
}
