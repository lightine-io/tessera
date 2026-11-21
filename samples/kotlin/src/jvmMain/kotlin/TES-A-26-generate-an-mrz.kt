// Sample for the page TES-A-26, Generate an MRZ in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-26. Region text matches the page; code outside regions only makes it compile.
package samples.tesa26

// region TES-A-26 block 2
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-26 block 3
    val result = MrzGenerator.generateTD3(
        documentType = "P",
        issuingState = "UTO",
        documentNumber = "T22000129",
        primaryIdentifier = "SPECIMEN",
        secondaryIdentifier = "ALEX MORGAN",
        nationality = "UTO",
        dateOfBirth = LocalDate(1990, 3, 15),
        sex = Sex.FEMALE,
        dateOfExpiry = LocalDate(2034, 3, 14),
        personalNumber = "S98765432",
    )
    // endregion

    // region TES-A-26 block 4
    when (result) {
        is GenerationResult.Success -> result.mrz.forEach { println(it) }
        is GenerationResult.Failure -> println("Not generated: ${result.error.description}")
    }
    // endregion

    // region TES-A-26 block 5
    val tooLong = MrzGenerator.generateTD3(
        documentType = "P", issuingState = "UTO", documentNumber = "T220001293",
        primaryIdentifier = "SPECIMEN", secondaryIdentifier = "ALEX MORGAN", nationality = "UTO",
        dateOfBirth = LocalDate(1990, 3, 15), sex = Sex.FEMALE, dateOfExpiry = LocalDate(2034, 3, 14),
        personalNumber = "S98765432",
    ) as GenerationResult.Failure
    println(tooLong.error.description)
    // endregion

    // region TES-A-26 block 6
    val lines = listOf(
        "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
        "T220001293UTO9003152F3403141S98765432<<<<<20",
    )
    val parsed = MrzParser.parse(lines, Instant.parse("2026-11-21T00:00:00Z")) as ParseResult.Success
    val again = MrzGenerator.generate(parsed.document) as GenerationResult.Success
    println(again.mrz == lines)
    // endregion
}

internal fun configure() {
    // region TES-A-26 block 7
    val result = MrzGenerator.generateTD3(
        documentType = "P", issuingState = "UTO", documentNumber = "T22000129",
        primaryIdentifier = "SPÉCIMEN", secondaryIdentifier = "ALEX MORGAN", nationality = "UTO",
        dateOfBirth = LocalDate(1990, 3, 15), sex = Sex.FEMALE, dateOfExpiry = LocalDate(2034, 3, 14),
        personalNumber = "S98765432", transliteration = IcaoDefaultTransliterationProfile,
    )
    // endregion
}
