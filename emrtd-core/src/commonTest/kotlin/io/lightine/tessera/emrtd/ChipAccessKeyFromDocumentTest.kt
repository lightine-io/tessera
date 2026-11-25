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
 * The MRZ key from a parsed document and from typed values. Fixtures are the project's own
 * specimen (SPECIMEN, ALEX MORGAN, Utopia; the same lines as mrz-core's test specimens) and
 * synthetic TD1 variants whose number continues in the optional data, with every check digit
 * computed here (ICAO Doc 9303 Part 3, 4.9), never typed by hand. ICAO's published examples stay
 * in the conformance tests.
 */
class ChipAccessKeyFromDocumentTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun line(
        text: String,
        length: Int,
    ) = text.padEnd(length, '<')

    /** The check digit of [field]: weights 7, 3, 1; A to Z count 10 to 35; the filler counts 0. */
    private fun cd(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        val sum =
            field.withIndex().sumOf { (i, c) ->
                val value =
                    when (c) {
                        in '0'..'9' -> c - '0'
                        in 'A'..'Z' -> c - 'A' + 10
                        else -> 0
                    }
                value * weights[i % 3]
            }
        return '0' + sum % 10
    }

    private val td3 =
        "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<\nT220001293UTO9003152F3403141S98765432<<<<<20"
    private val td2 =
        "I<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<\nT620001457UTO9003152F3403141<<<<<<<8"
    private val td1 =
        "I<UTOT420001370<<<<<<<<<<<<<<<\n9003152F3403141UTO<<<<<<<<<<<2\nSPECIMEN<<ALEX<MORGAN<<<<<<<<<"

    /** A TD1 whose number, [longNumber], is longer than 9 characters (Part 5, Note j). */
    private fun td1WithContinuation(continuation: String): String {
        val line1 = line("I<UTOT42000137<$continuation", 30)
        val line2Start = "900315" + cd("900315") + "F" + "340314" + cd("340314") + "UTO" + "<".repeat(11)
        val composite = cd(line1.substring(5, 30) + line2Start.substring(0, 7) + line2Start.substring(8, 15) + line2Start.substring(18, 29))
        return line1 + "\n" + line2Start + composite + "\n" + line("SPECIMEN<<ALEX<MORGAN", 30)
    }

    private val longNumber = "T42000137123"
    private val td1Long = td1WithContinuation("123" + cd(longNumber))
    private val td1NoContinuation = td1WithContinuation("")

    private fun built(result: MrzKeyResult): ChipAccessKey.Mrz = assertIs<MrzKeyResult.Built>(result).key

    private fun parse(text: String): ParseResult = MrzParser.parse(text, at)

    @Test
    fun a_td3_document_gives_the_key_from_its_printed_fields() {
        val key = built(ChipAccessKey.Mrz.from(parse(td3)))
        assertContentEquals("T22000129390031523403141".encodeToByteArray(), key.secretCopy())
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
        assertContentEquals((longNumber + cd(longNumber) + "9003152" + "3403141").encodeToByteArray(), key.secretCopy())
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
            "V<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<\nT820001534UTO9003152F3403141<<<<<<<<<<<<<<<<"
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
        val wrong = td3.replace("T220001293", "T220001294")
        val key = built(ChipAccessKey.Mrz.from(parse(wrong)))
        assertEquals(setOf(MrzField.DOCUMENT_NUMBER), key.checkDigitFindings)
        assertContentEquals("T22000129490031523403141".encodeToByteArray(), key.secretCopy())
    }

    @Test
    fun typed_fields_are_padded_and_get_computed_check_digits() {
        // An eight-character number is padded to nine with the filler before its check digit.
        val key = built(ChipAccessKey.Mrz.fromTypedFields("T2200012", "900315", "340314"))
        val expected = "T2200012<" + cd("T2200012<") + "900315" + cd("900315") + "340314" + cd("340314")
        assertContentEquals(expected.encodeToByteArray(), key.secretCopy())
        assertTrue(key.checkDigitFindings.isEmpty())
    }

    @Test
    fun a_typed_number_of_nine_or_more_characters_is_kept_whole() {
        val nine = built(ChipAccessKey.Mrz.fromTypedFields("T42000137", "900315", "340314"))
        assertTrue(nine.secretCopy().decodeToString().startsWith("T42000137" + cd("T42000137")))
        val twelve = built(ChipAccessKey.Mrz.fromTypedFields(longNumber, "900315", "340314"))
        assertTrue(twelve.secretCopy().decodeToString().startsWith(longNumber))
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
        assertEquals(MrzField.DOCUMENT_NUMBER to MrzKeyProblem.EMPTY, problem("", "900315", "340314"))
        assertEquals(MrzField.DOCUMENT_NUMBER to MrzKeyProblem.CHARACTER_NOT_ALLOWED, problem("t2200012", "900315", "340314"))
        assertEquals(MrzField.DATE_OF_BIRTH to MrzKeyProblem.WRONG_LENGTH, problem("T2200012", "9003", "340314"))
        assertEquals(MrzField.DATE_OF_BIRTH to MrzKeyProblem.CHARACTER_NOT_ALLOWED, problem("T2200012", "90O315", "340314"))
        assertEquals(MrzField.DATE_OF_EXPIRY to MrzKeyProblem.EMPTY, problem("T2200012", "900315", ""))
    }

    @Test
    fun the_answers_never_show_a_value() {
        val notBuilt = ChipAccessKey.Mrz.fromTypedFields("T2200012", "900315", "34O314")
        assertFalse("T2200012" in notBuilt.toString() || "900315" in notBuilt.toString())
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
