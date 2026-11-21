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
        documentNumber = "L898902C3",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        nationality = "UTO",
        dateOfBirth = LocalDate(1974, 8, 12),
        sex = Sex.FEMALE,
        dateOfExpiry = LocalDate(2012, 4, 15),
        personalNumber = "ZE184226B",
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
        documentType = "P", issuingState = "UTO", documentNumber = "L898902C36",
        primaryIdentifier = "ERIKSSON", secondaryIdentifier = "ANNA MARIA", nationality = "UTO",
        dateOfBirth = LocalDate(1974, 8, 12), sex = Sex.FEMALE, dateOfExpiry = LocalDate(2012, 4, 15),
        personalNumber = "ZE184226B",
    ) as GenerationResult.Failure
    println(tooLong.error.description)
    // endregion

    // region TES-A-26 block 6
    val lines = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    val parsed = MrzParser.parse(lines, Instant.parse("2010-06-01T00:00:00Z")) as ParseResult.Success
    val again = MrzGenerator.generate(parsed.document) as GenerationResult.Success
    println(again.mrz == lines)
    // endregion
}

internal fun configure() {
    // region TES-A-26 block 7
    val result = MrzGenerator.generateTD3(
        documentType = "P", issuingState = "UTO", documentNumber = "L898902C3",
        primaryIdentifier = "ERIKSSÖN", secondaryIdentifier = "ANNA MARIA", nationality = "UTO",
        dateOfBirth = LocalDate(1974, 8, 12), sex = Sex.FEMALE, dateOfExpiry = LocalDate(2012, 4, 15),
        personalNumber = "ZE184226B", transliteration = IcaoDefaultTransliterationProfile,
    )
    // endregion
}
