import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.random.Random

// Writes the ground truth of the two benchmarks: one row per synthetic MRZ, made by Tessera's
// own generator from invented field values. Same mode, seed and count, same rows. The photo set
// is the OCR set without TD2: the photo renderer draws whole TD3 and TD1 documents only.
//
// Usage: GenerateTestSet <ocr|photo> <out-file> [per-format count] [seed]
//   ocr   - the OCR benchmark (TES-149): TD3, TD1 and TD2 rows; default 20 per format, seed 149
//   photo - the full-page photo benchmark (TES-131): TD3 and TD1 rows; default 8 per format, seed 131
// Row: id <TAB> format <TAB> line1|line2[|line3]

private val syllables =
    listOf("KA", "LO", "RI", "VEN", "TAS", "MO", "DU", "SER", "PEL", "NA", "TOR", "BI", "QUE", "ZAL", "FEN", "OR")

private fun inventedName(random: Random): String = (1..random.nextInt(2, 5)).joinToString("") { syllables.random(random) }

private fun alnum(
    random: Random,
    length: Int,
): String = (1..length).joinToString("") { "ABCDEFGHJKLMNPRSTUVWXYZ0123456789".random(random).toString() }

private fun digits(
    random: Random,
    length: Int,
): String = (1..length).joinToString("") { random.nextInt(10).toString() }

private fun date(
    random: Random,
    fromYear: Int,
    toYear: Int,
): LocalDate = LocalDate(random.nextInt(fromYear, toYear + 1), random.nextInt(1, 13), random.nextInt(1, 29))

private fun sex(random: Random): Sex = listOf(Sex.MALE, Sex.FEMALE, Sex.UNSPECIFIED).random(random)

private fun lines(result: GenerationResult): List<String> =
    when (result) {
        is GenerationResult.Success -> result.mrz
        is GenerationResult.Failure -> error("generation failed: ${result.error}")
    }

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: error("usage: GenerateTestSet <ocr|photo> <out-file> [per-format count] [seed]")
    val (defaultCount, defaultSeed) =
        when (mode) {
            "ocr" -> 20 to 149L // TES-149: the OCR benchmark's line set
            "photo" -> 8 to 131L // TES-131: the full-page photo benchmark
            else -> error("unknown mode '$mode': expected 'ocr' or 'photo'")
        }
    val out = File(args.getOrElse(1) { "manifest.tsv" })
    val perFormat = args.getOrNull(2)?.toInt() ?: defaultCount
    val random = Random(args.getOrNull(3)?.toLong() ?: defaultSeed)
    val rows = mutableListOf<String>()
    repeat(perFormat) { i ->
        val td3 =
            MrzGenerator.generateTD3(
                documentType = "P",
                issuingState = "UTO",
                documentNumber = alnum(random, 9),
                primaryIdentifier = inventedName(random),
                secondaryIdentifier = inventedName(random) + " " + inventedName(random),
                nationality = "UTO",
                dateOfBirth = date(random, 1950, 2008),
                sex = sex(random),
                dateOfExpiry = date(random, 2027, 2036),
                personalNumber = if (random.nextBoolean()) digits(random, random.nextInt(4, 15)) else "",
            )
        rows += "td3-%02d\tTD3\t%s".format(i, lines(td3).joinToString("|"))
        val td1 =
            MrzGenerator.generateTD1(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = alnum(random, 9),
                primaryIdentifier = inventedName(random),
                secondaryIdentifier = inventedName(random),
                nationality = "UTO",
                dateOfBirth = date(random, 1950, 2008),
                sex = sex(random),
                dateOfExpiry = date(random, 2027, 2036),
                optionalData1 = if (random.nextBoolean()) digits(random, random.nextInt(3, 15)) else "",
                optionalData2 = if (random.nextBoolean()) digits(random, random.nextInt(3, 11)) else "",
            )
        rows += "td1-%02d\tTD1\t%s".format(i, lines(td1).joinToString("|"))
        if (mode == "ocr") {
            val td2 =
                MrzGenerator.generateTD2(
                    documentType = "I",
                    issuingState = "UTO",
                    documentNumber = alnum(random, 9),
                    primaryIdentifier = inventedName(random),
                    secondaryIdentifier = inventedName(random),
                    nationality = "UTO",
                    dateOfBirth = date(random, 1950, 2008),
                    sex = sex(random),
                    dateOfExpiry = date(random, 2027, 2036),
                    optionalData = if (random.nextBoolean()) digits(random, random.nextInt(3, 8)) else "",
                )
            rows += "td2-%02d\tTD2\t%s".format(i, lines(td2).joinToString("|"))
        }
    }
    out.parentFile?.mkdirs()
    out.writeText(rows.joinToString("\n", postfix = "\n"))
    println("wrote ${rows.size} rows to ${out.absolutePath}")
}
