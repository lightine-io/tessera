// Sample for the page TES-A-33, Read fields and dates from a parsed MRZ in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-33. Region text matches the page; code outside regions only makes it compile.
package samples.tesa33

// region TES-A-33 block 2
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-33 block 3
    val referenceTime = Instant.parse("2026-11-21T00:00:00Z")
    val document = when (val result = MrzParser.parse(
        listOf(
            "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
            "T220001293UTO9003152F3403141S98765432<<<<<20",
        ),
        referenceTime,
    )) {
        is ParseResult.Success -> result.document
        is ParseResult.PartialSuccess -> result.document
        is ParseResult.Failure -> error("Not readable: ${result.error.description}")
    }
    val fields = document.commonFields
    // endregion

    // region TES-A-33 block 4
    println("${fields.primaryIdentifier}, ${fields.secondaryIdentifier}")
    println(fields.documentNumber)
    // endregion

    // region TES-A-33 block 5
    println("${fields.documentType.rawCode} ${fields.issuingState.rawCode} ${fields.nationality.rawCode}")
    // endregion

    // region TES-A-33 block 6
    println("${fields.sex} ${fields.rawSex}")
    // endregion

    // region TES-A-33 block 7
    val born = fields.dateOfBirth
    println("${born.rawYear}${born.rawMonth}${born.rawDay} ${born.computedDate} ${born.inferenceMethod}")
    // endregion

    // region TES-A-33 block 8
    val late = MrzDate.parseExpiry("34", "03", "14", Instant.parse("2046-01-01T00:00:00Z"))
    println("${late.computedDate} ${late.inferenceMethod} ${late.componentsFormCalendarDate}")
    // endregion

    // region TES-A-33 block 9
    println(fields.checkDigits)
    if (document is TD3) println(document.personalNumber)
    // endregion
}

internal fun configure() {
    // region TES-A-33 block 10
    val born = MrzDate.parseBirth("90", "03", "15", Instant.parse("2026-10-14T00:00:00Z"))
    println(born.computedDate)
    // endregion
}
