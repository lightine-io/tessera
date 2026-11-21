// Sample for the page TES-A-22, Look up country and document type codes in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-22. Region text matches the page; code outside regions only makes it compile.
package samples.tesa22

// region TES-A-22 block 2
import io.lightine.tessera.mrz.recognition.CountryCode
import io.lightine.tessera.mrz.recognition.CountryCodeTable
import io.lightine.tessera.mrz.recognition.DocumentType
import io.lightine.tessera.mrz.recognition.DocumentTypeCodeTable
import io.lightine.tessera.types.vocabulary.CountryCodeCategory
// endregion

fun main() {
    // region TES-A-22 block 3
    val entry = CountryCodeTable.lookup("UTO")
    println("${entry?.displayName} ${entry?.category}")
    println(CountryCodeTable.lookup("uto"))
    // endregion

    // region TES-A-22 block 4
    val known = CountryCode("XXA")
    println("${known.isRecognized} ${known.displayName} ${known.category}")
    val unknown = CountryCode("ZZZ")
    println("${unknown.isRecognized} ${unknown.displayName} ${unknown.category}")
    // endregion

    // region TES-A-22 block 5
    println(CountryCodeTable.all().size)
    println(CountryCodeTable.byCategory(CountryCodeCategory.REFUGEE).map { it.code })
    // endregion

    // region TES-A-22 block 6
    val type = DocumentTypeCodeTable.lookup("PP")
    println("${type?.displayName} ${type?.category} ${type?.generation}")
    println(DocumentTypeCodeTable.all().size)
    // endregion

    // region TES-A-22 block 7
    val card = DocumentType("IA")
    println("${card.isRecognized} ${card.category} ${card.broadCategory}")
    // endregion
}

internal fun configure() {
    // region TES-A-22 block 8
    val organizations = CountryCodeTable.byCategory(CountryCodeCategory.ORGANIZATION)
    println(organizations.size)
    // endregion
}
