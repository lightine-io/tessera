// Sample for the page TES-A-10, Read your first MRZ on the JVM: https://lightine.youtrack.cloud/articles/TES-A-10. The region is the whole file the page shows.
// region TES-A-10 block 3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.time.Instant

fun main() {
    val mrz = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    // The specimen expires in 2012. A fixed reference time keeps the output the same on every run.
    val referenceTime = Instant.parse("2010-06-01T00:00:00Z")

    when (val result = MrzParser.parse(mrz, referenceTime)) {
        is ParseResult.Success -> {
            val document = result.document
            val fields = document.commonFields
            println("Format: ${document.format}")
            println("Name: ${fields.primaryIdentifier}, ${fields.secondaryIdentifier}")
            println("Document number: ${fields.documentNumber}")
            println("Born: ${fields.dateOfBirth.computedDate}")
            println("Expires: ${fields.dateOfExpiry.computedDate}")
            println("Warnings: ${result.metadata.warnings.size}")
        }
        is ParseResult.PartialSuccess -> println("Read, but ${result.metadata.validationFailures.size} check(s) failed")
        is ParseResult.Failure -> println("Not readable: ${result.error.description}")
    }
}
// endregion
