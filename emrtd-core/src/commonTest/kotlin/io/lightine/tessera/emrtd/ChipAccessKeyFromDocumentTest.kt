package io.lightine.tessera.emrtd

import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.MrzField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The MRZ key from a parsed document and from typed values. Fixtures are the published ICAO
 * Doc 9303 specimen lines (Parts 4 to 6) and a synthetic TD1 whose number continues in the optional data.
 */
class ChipAccessKeyFromDocumentTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun line(
        text: String,
        length: Int,
    ) = text.padEnd(length, '<')

    private val td3 =
        line("P<UTOERIKSSON<<ANNA<MARIA", 44) + "\n" + "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    private val td2 =
        line("I<UTOERIKSSON<<ANNA<MARIA", 36) + "\n" + "D231458907UTO7408122F1204159" + "<<<<<<<6"
    private val td1 =
        line("I<UTOD231458907", 30) + "\n" + line("7408122F1204159UTO", 29) + "6\n" + line("ERIKSSON<<ANNA<MARIA", 30)
    private val td1Long =
        line("I<UTOD23145890<7349", 30) + "\n" + line("3407127M9507122UTO", 29) + "6\n" + line("ERIKSSON<<ANNA<MARIA", 30)
    private val td1NoContinuation =
        line("I<UTOD23145890<", 30) + "\n" + line("3407127M9507122UTO", 29) + "6\n" + line("ERIKSSON<<ANNA<MARIA", 30)

    private fun built(result: MrzKeyResult): ChipAccessKey.Mrz = assertIs<MrzKeyResult.Built>(result).key

    private fun parse(text: String): ParseResult = MrzParser.parse(text, at)

    @Test
    fun a_td3_document_gives_the_key_from_its_printed_fields() {
        val key = built(ChipAccessKey.Mrz.from(parse(td3)))
        assertContentEquals("L898902C3674081221204159".encodeToByteArray(), key.secretCopy())
        assertTrue(key.checkDigitFindings.isEmpty())
    }

    @Test
    fun a_td2_and_a_td1_document_give_a_key_too() {
        assertTrue(built(ChipAccessKey.Mrz.from(parse(td2))).checkDigitFindings.isEmpty())
        assertTrue(built(ChipAccessKey.Mrz.from(parse(td1))).checkDigitFindings.isEmpty())
    }

    @Test
    fun a_long_td1_number_is_joined_from_the_optional_data() {
        val key = built(ChipAccessKey.Mrz.from(parse(td1Long)))
        assertContentEquals("D23145890734934071279507122".encodeToByteArray(), key.secretCopy())
        assertTrue(key.checkDigitFindings.isEmpty())
    }

    @Test
    fun the_parse_result_overload_gives_the_same_key_and_a_failed_parse_is_typed() {
        val a = built(ChipAccessKey.Mrz.from(parse(td3)))
        val b = built(ChipAccessKey.Mrz.from(parse(td3)))
        assertContentEquals(a.secretCopy(), b.secretCopy())
        val failed = ChipAccessKey.Mrz.from(parse("not an mrz"))
        val notBuilt = assertIs<MrzKeyResult.NotBuilt>(failed)
        assertEquals(MrzKeyProblem.NO_DOCUMENT, notBuilt.problem)
        assertEquals(null, notBuilt.field)
    }

    @Test
    fun a_visa_has_no_chip() {
        val visa =
            "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL8988901C4XXX4009078F96121096ZE184226B<<<<<<"
        val parsed = parse(visa)
        val notBuilt = assertIs<MrzKeyResult.NotBuilt>(ChipAccessKey.Mrz.from(parsed))
        assertEquals(MrzKeyProblem.DOCUMENT_HAS_NO_CHIP, notBuilt.problem)
    }

    @Test
    fun a_number_that_continues_with_nothing_to_continue_from_is_typed_not_thrown() {
        // The check-digit place holds a filler but the optional data holds nothing.
        val notBuilt = assertIs<MrzKeyResult.NotBuilt>(ChipAccessKey.Mrz.from(parse(td1NoContinuation)))
        assertEquals(MrzField.DOCUMENT_NUMBER, notBuilt.field)
        assertEquals(MrzKeyProblem.NUMBER_CONTINUATION_MISSING, notBuilt.problem)
    }

    @Test
    fun a_printed_check_digit_that_does_not_match_stays_as_printed_and_shows_as_a_finding() {
        val wrong = td3.replace("L898902C36", "L898902C35")
        val key = built(ChipAccessKey.Mrz.from(parse(wrong)))
        assertEquals(setOf(MrzField.DOCUMENT_NUMBER), key.checkDigitFindings)
        assertContentEquals("L898902C3574081221204159".encodeToByteArray(), key.secretCopy())
    }

    @Test
    fun typed_fields_are_padded_and_get_computed_check_digits() {
        // Doc 9303 Part 11, Appendix D: L898902C< with check digit 3, 690806 with 1, 940623 with 6.
        val key = built(ChipAccessKey.Mrz.fromTypedFields("L898902C", "690806", "940623"))
        assertContentEquals("L898902C<3690806194062 36".replace(" ", "").encodeToByteArray(), key.secretCopy())
        assertTrue(key.checkDigitFindings.isEmpty())
    }

    @Test
    fun a_typed_number_of_nine_or_more_characters_is_kept_whole() {
        val nine = built(ChipAccessKey.Mrz.fromTypedFields("D23145890", "340712", "950712"))
        assertTrue(nine.secretCopy().decodeToString().startsWith("D231458907"))
        val twelve = built(ChipAccessKey.Mrz.fromTypedFields("D23145890734", "340712", "950712"))
        assertTrue(twelve.secretCopy().decodeToString().startsWith("D23145890734"))
    }

    @Test
    fun typed_values_that_cannot_be_used_are_typed_with_their_field_and_never_thrown() {
        fun problem(
            number: String,
            birth: String,
            expiry: String,
        ): Pair<MrzField?, MrzKeyProblem> =
            assertIs<MrzKeyResult.NotBuilt>(ChipAccessKey.Mrz.fromTypedFields(number, birth, expiry)).let {
                it.field to
                    it.problem
            }
        assertEquals(MrzField.DOCUMENT_NUMBER to MrzKeyProblem.EMPTY, problem("", "740812", "120415"))
        assertEquals(MrzField.DOCUMENT_NUMBER to MrzKeyProblem.CHARACTER_NOT_ALLOWED, problem("l898902c", "740812", "120415"))
        assertEquals(MrzField.DATE_OF_BIRTH to MrzKeyProblem.WRONG_LENGTH, problem("L898902C", "7408", "120415"))
        assertEquals(MrzField.DATE_OF_BIRTH to MrzKeyProblem.CHARACTER_NOT_ALLOWED, problem("L898902C", "74O812", "120415"))
        assertEquals(MrzField.DATE_OF_EXPIRY to MrzKeyProblem.EMPTY, problem("L898902C", "740812", ""))
    }

    @Test
    fun the_answers_never_show_a_value() {
        val notBuilt = ChipAccessKey.Mrz.fromTypedFields("L898902C", "740812", "12O415")
        assertFalse("L898902C" in notBuilt.toString() || "740812" in notBuilt.toString())
        assertEquals(
            "Built",
            MrzKeyResult
                .Built(
                    ChipAccessKey.Can("1").let {
                        ChipAccessKey.Mrz("A", '0', "000000", '0', "000000", '0')
                    },
                ).toString(),
        )
    }
}
