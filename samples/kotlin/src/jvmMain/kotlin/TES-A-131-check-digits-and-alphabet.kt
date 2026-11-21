// Sample for the page TES-A-131, Compute check digits and test the MRZ alphabet in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-131. Region text matches the page; code outside regions only makes it compile.
package samples.tesa131

// region TES-A-131 block 2
import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter
// endregion

fun main() {
    // region TES-A-131 block 3
    println(computeCheckDigit("T22000129"))
    println(computeCheckDigit("900315"))
    // endregion

    // region TES-A-131 block 4
    val printed = '2'
    println(computeCheckDigit("900315") == printed)
    // endregion

    // region TES-A-131 block 5
    println(isMrzAlphabetCharacter('K'))
    println(isMrzAlphabetCharacter('k'))
    println(isMrzAlphabetCharacter('<'))
    // endregion

    // region TES-A-131 block 6
    fun checkDigitOrNull(field: String): Char? =
        if (field.all(::isMrzAlphabetCharacter)) computeCheckDigit(field) else null

    println(checkDigitOrNull("T22000129"))
    println(checkDigitOrNull("t22000129"))
    // endregion

    // region TES-A-131 block 7
    val composite = computeCheckDigit("T22000129390031523403141S98765432<<<<<2")
    println(composite)
    // endregion
}
