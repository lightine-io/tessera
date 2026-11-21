// Sample for the page TES-A-24, Transliterate names to the MRZ alphabet in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-24. Region text matches the page; code outside regions only makes it compile.
package samples.tesa24

// region TES-A-24 block 2
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.transliteration.AzeTransliterationProfile
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationProfileRegistry
import io.lightine.tessera.mrz.transliteration.TransliterationResult
import io.lightine.tessera.mrz.transliteration.transliterate
import io.lightine.tessera.types.vocabulary.Sex
import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlinx.datetime.LocalDate
// endregion

// region TES-A-24 block 6
object LettersOnlyProfile : TransliterationProfile {
    override val identifier: String = "LETTERS-ONLY"

    override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
        val unmapped = normalizedInput.withIndex()
            .filter { (_, char) -> char !in 'A'..'Z' && char != ' ' }
            .map { (index, char) -> UnmappedCharacter(char, index) }
        return if (unmapped.isEmpty()) {
            TransliterationResult.Success(normalizedInput.replace(' ', '<'))
        } else {
            TransliterationResult.Failure(unmapped)
        }
    }
}

fun printLettersOnly() {
    println(LettersOnlyProfile.transliterate("ANNA MARIA").result)
    println(LettersOnlyProfile.transliterate("ŁUKASZ").result)
}
// endregion

fun main() {
    // region TES-A-24 block 3
    // "u" followed by U+0308, a combining diaeresis. It looks like "ü" but is two characters.
    val outcome = IcaoDefaultTransliterationProfile.transliterate("Mu" + Char(0x0308) + "ller")
    println("${outcome.originalInput.length} ${outcome.normalizedInput.length}")
    println((outcome.result as TransliterationResult.Success).output)
    // endregion

    // region TES-A-24 block 4
    fun output(profile: TransliterationProfile, name: String): String =
        (profile.transliterate(name).result as TransliterationResult.Success).output

    println(output(IcaoDefaultTransliterationProfile, "Çiçək"))
    println(output(AzeTransliterationProfile, "Çiçək"))
    // endregion

    // region TES-A-24 block 5
    println(TransliterationProfileRegistry.all().map { it.identifier })
    println(TransliterationProfileRegistry.lookup("AZE") === AzeTransliterationProfile)
    println(TransliterationProfileRegistry.lookup("XYZ"))
    // endregion

    // region TES-A-24 block 7
    TransliterationProfileRegistry.register(LettersOnlyProfile)
    // endregion

    // region TES-A-24 block 8
    val generated = MrzGenerator.generateTD3(
        documentType = "P", issuingState = "UTO", documentNumber = "L898902C3",
        primaryIdentifier = "ERIKSSÖN", secondaryIdentifier = "ANNA MARÍA", nationality = "UTO",
        dateOfBirth = LocalDate(1974, 8, 12), sex = Sex.FEMALE, dateOfExpiry = LocalDate(2012, 4, 15),
        personalNumber = "ZE184226B", transliteration = IcaoDefaultTransliterationProfile,
    )
    generated.metadata.transliterationDetails?.transliteratedFields?.forEach {
        println("${it.originalInput} -> ${it.transliteratedOutput}")
    }
    // endregion
}

internal fun configure() {
    // region TES-A-24 block 9
    val result = MrzGenerator.generateTD3(
        documentType = "P", issuingState = "AZE", documentNumber = "AB1234567",
        primaryIdentifier = "ƏLİYEV", secondaryIdentifier = "ƏLİ", nationality = "AZE",
        dateOfBirth = LocalDate(1990, 1, 15), sex = Sex.MALE, dateOfExpiry = LocalDate(2030, 1, 14),
        personalNumber = "", transliteration = AzeTransliterationProfile,
    )
    // endregion
}
