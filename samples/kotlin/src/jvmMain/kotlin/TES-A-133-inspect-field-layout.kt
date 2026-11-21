// Sample for the page TES-A-133, Inspect the field layout of each MRZ format in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-133. Region text matches the page; code outside regions only makes it compile.
package samples.tesa133

// region TES-A-133 block 2
import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractCharFrom
import io.lightine.tessera.mrz.formats.extractFrom
// endregion

// region TES-A-133 block 8 part 1
import io.lightine.tessera.mrz.formats.MrzFormatSpecWithComposite
// endregion

fun main() {
    // region TES-A-133 block 3
    val birth = Td3FormatSpec.dateOfBirth
    println(birth)
    println(birth.width)
    // endregion

    // region TES-A-133 block 4
    val lines = listOf(
        "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<",
        "T220001293UTO9003152F3403141S98765432<<<<<20",
    )
    println(Td3FormatSpec.dateOfBirth.extractFrom(lines))
    println(Td3FormatSpec.sex.extractCharFrom(lines))
    // endregion

    // region TES-A-133 block 5
    println(Td3FormatSpec.globalPositionOf(Td3FormatSpec.compositeCheckDigit))
    // endregion

    // region TES-A-133 block 6
    val compositeInput = Td3FormatSpec.compositeInputFields.joinToString("") { it.extractFrom(lines) }
    println(compositeInput)
    println(computeCheckDigit(compositeInput))
    // endregion

    // region TES-A-133 block 7
    val specs: List<MrzFormatSpec> = listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
    specs.forEach { println("${it::class.simpleName} ${it.lineCount} x ${it.lineLength}") }
    // endregion

    // region TES-A-133 block 8 part 2
    for (spec in specs) {
        if (spec is MrzFormatSpecWithComposite) println("${spec::class.simpleName} ${spec.compositeCheckDigit}")
    }
    // endregion
}
