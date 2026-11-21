// Sample for the page TES-A-131, Compute check digits and test the MRZ alphabet in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-131. Region text matches the page; code outside regions only makes it compile.
package samples.tesa131

// region TES-A-131 block 2
import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.parsing.isMrzAlphabetCharacter
// endregion

fun main() {
    // region TES-A-131 block 3
    println(computeCheckDigit("L898902C<"))
    println(computeCheckDigit("740812"))
    // endregion

    // region TES-A-131 block 4
    val printed = '2'
    println(computeCheckDigit("740812") == printed)
    // endregion

    // region TES-A-131 block 5
    println(isMrzAlphabetCharacter('K'))
    println(isMrzAlphabetCharacter('k'))
    println(isMrzAlphabetCharacter('<'))
    // endregion

    // region TES-A-131 block 6
    fun checkDigitOrNull(field: String): Char? =
        if (field.all(::isMrzAlphabetCharacter)) computeCheckDigit(field) else null

    println(checkDigitOrNull("L898902C<"))
    println(checkDigitOrNull("l898902c<"))
    // endregion

    // region TES-A-131 block 7
    val composite = computeCheckDigit("L898902C3674081221204159ZE184226B<<<<<1")
    println(composite)
    // endregion
}
