// Sample for the page TES-A-130, Branch on a parse result in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-130. Region text matches the page; code outside regions only makes it compile.
package samples.tesa130

// region TES-A-130 block 2
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-130 block 3
    fun describe(result: ParseResult): String =
        when (result) {
            is ParseResult.Success -> "Success: ${result.document.format}"
            is ParseResult.PartialSuccess -> "PartialSuccess: ${result.metadata.validationFailures.size} failure(s)"
            is ParseResult.Failure -> "Failure: ${result.error.description}"
        }
    // endregion

    // region TES-A-130 block 4
    val referenceTime = Instant.parse("2026-11-21T00:00:00Z")
    val line1 = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
    val line2 = "T220001293UTO9003152F3403141S98765432<<<<<20"

    println(describe(MrzParser.parse(listOf(line1, line2), referenceTime)))
    println(describe(MrzParser.parse(listOf(line1, line2.dropLast(1) + "1"), referenceTime)))
    println(describe(MrzParser.parse("P<UTO", referenceTime)))
    // endregion

    // region TES-A-130 block 5
    val later = MrzParser.parse(listOf(line1, line2), Instant.parse("2035-01-01T00:00:00Z"))
    println(later.metadata.readMethod)
    println(later.metadata.warnings.map { it.description })
    println(later.metadata.validationFailures.size)
    // endregion

    // region TES-A-130 block 6
    val clean = later is ParseResult.Success && later.metadata.warnings.isEmpty()
    println(clean)
    // endregion

    // region TES-A-130 block 7
    val failure = MrzParser.parse("P<UTO", referenceTime) as ParseResult.Failure
    println(failure.error::class.simpleName)
    // endregion
}
