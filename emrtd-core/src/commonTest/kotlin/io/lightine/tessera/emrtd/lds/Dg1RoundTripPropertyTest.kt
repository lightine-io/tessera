package io.lightine.tessera.emrtd.lds

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** A DG1 built from `MrzGenerator` output reads back to the document the MRZ parser gives for the same lines. */
class Dg1RoundTripPropertyTest {
    private val reference = Instant.parse("2024-06-01T00:00:00Z")
    private val letters: Arb<String> = Arb.list(Arb.element(('A'..'Z').toList()), 1..10).map { it.joinToString("") }
    private val numbers: Arb<String> = Arb.list(Arb.element(('0'..'9').toList() + ('A'..'Z').toList()), 1..9).map { it.joinToString("") }
    private val years = Arb.int(1950..2030)
    private val months = Arb.int(1..12)
    private val days = Arb.int(1..28)

    private fun dg1Of(lines: List<String>): ByteArray =
        TlvWriter.constructed(0x61, TlvWriter.encode(0x5F1F, lines.joinToString("").encodeToByteArray()))

    private fun check(lines: List<String>) {
        val expected = MrzParser.parse(lines, reference)
        val document =
            when (expected) {
                is ParseResult.Success -> expected.document
                is ParseResult.PartialSuccess -> expected.document
                is ParseResult.Failure -> error("the generator made an MRZ the parser rejects")
            }
        val view = assertIs<LdsParseResult.Success<Dg1View>>(Dg1View.parse(dg1Of(lines), reference)).view
        assertEquals(document, view.document)
        assertEquals(ReadMethod.NFC_CHIP, view.parseResult.metadata.readMethod)
        assertEquals(lines.joinToString(""), view.mrzText)
    }

    private fun lines(result: GenerationResult): List<String> = assertIs<GenerationResult.Success>(result).mrz

    @Test
    fun td3_td2_and_td1_round_trip() {
        repeat(200) {
            val birth = LocalDate(years.next(), months.next(), days.next())
            val expiry = LocalDate(years.next(), months.next(), days.next())
            val sex = Arb.element(Sex.entries).next()
            val primary = letters.next()
            val secondary = letters.next()
            val number = numbers.next()
            check(lines(MrzGenerator.generateTD3("P", "UTO", number, primary, secondary, "UTO", birth, sex, expiry, "")))
            check(lines(MrzGenerator.generateTD2("I", "UTO", number, primary, secondary, "UTO", birth, sex, expiry, "")))
            check(lines(MrzGenerator.generateTD1("I", "UTO", number, primary, secondary, "UTO", birth, sex, expiry, "", "")))
        }
    }
}
