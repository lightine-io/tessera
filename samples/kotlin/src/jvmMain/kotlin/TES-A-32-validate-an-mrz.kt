// Sample for the page TES-A-32, Validate an MRZ in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-32. Region text matches the page; code outside regions only makes it compile.
package samples.tesa32

// region TES-A-32 block 2
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.validation.MrzValidator
import io.lightine.tessera.types.vocabulary.MrzFormat
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-32 block 3
    val referenceTime = Instant.parse("2010-06-01T00:00:00Z")
    val report = MrzValidator.validate(
        """
        P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
        L898902C36UTO7408122F1204159ZE184226B<<<<<11
        """.trimIndent(),
        referenceTime,
    )
    // endregion

    // region TES-A-32 block 4
    val structural = report.structuralFailure
    if (structural != null) {
        println("Not readable: ${structural.description}")
    } else {
        report.validationFailures.forEach { println("Failure: ${it.description}") }
        report.warnings.forEach { println("Warning: ${it.description}") }
    }
    // endregion

    // region TES-A-32 block 5
    val wrongShape = MrzValidator.validate(listOf("TOO", "SHORT"), MrzFormat.TD3, referenceTime)
    println(wrongShape.structuralFailure?.description)
    // endregion

    // region TES-A-32 block 6
    val parsed = MrzParser.parse(
        listOf(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
        ),
        referenceTime,
    ) as ParseResult.Success
    val later = MrzValidator.validate(parsed.document, Instant.parse("2013-01-01T00:00:00Z"))
    later.warnings.forEach { println("Warning: ${it.description}") }
    // endregion
}

internal fun configure() {
    // region TES-A-32 block 7
    val lines = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    val report = MrzValidator.validate(lines, MrzFormat.TD3, referenceTime = Instant.parse("2013-01-01T00:00:00Z"))
    // endregion
}
