// Sample for the page TES-A-25, Parse an MRZ in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-25. Region text matches the page; code outside regions only makes it compile.
package samples.tesa25

// region TES-A-25 block 2
import io.lightine.tessera.mrz.parsing.ManualMrzReader
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-25 block 3
    val mrz = """
        P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
        L898902C36UTO7408122F1204159ZE184226B<<<<<10
    """.trimIndent()
    val referenceTime = Instant.parse("2010-06-01T00:00:00Z")
    // endregion

    // region TES-A-25 block 4
    val result = MrzParser.parse(mrz, referenceTime)
    println("${result::class.simpleName} ${result.metadata.readMethod}")
    // endregion

    // region TES-A-25 block 5
    val wrong = MrzParser.parseTD2(mrz, referenceTime) as ParseResult.Failure
    println(wrong.error.description)
    // endregion

    // region TES-A-25 block 6
    println(ManualMrzReader.read(mrz, referenceTime).metadata.readMethod)
    // endregion
}

internal fun configure(mrz: String) {
    // region TES-A-25 block 7
    val lines = mrz.lines()
    val result = MrzParser.parseTD3(lines, referenceTime = Instant.parse("2013-01-01T00:00:00Z"))
    // endregion
}
