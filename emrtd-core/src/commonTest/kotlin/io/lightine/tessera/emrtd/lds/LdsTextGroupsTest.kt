package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * ICAO Doc 9303 Part 10, Appendix A examples A.1, A.2 (TD1 and TD2), A.5 and A.6. Hex values are
 * the ones printed in the appendix; where the appendix prints text, the bytes are that text in ASCII,
 * built with the TLV writer and checked against the lengths the appendix prints.
 */
class LdsTextGroupsTest {
    private val reference = Instant.parse("2010-01-01T00:00:00Z")

    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun ascii(s: String): ByteArray = s.encodeToByteArray()

    private fun tlv(
        tag: Int,
        value: String,
    ): ByteArray = TlvWriter.encode(tag, ascii(value))

    private fun <T> ok(result: LdsParseResult<T>): T = assertIs<LdsParseResult.Success<T>>(result).view

    // --- A.1 EF.COM ---

    @Test
    fun a1_ef_com_in_full_hexadecimal() {
        val bytes = hex("60 16 5F01 04 30313037 5F36 06 303430303030 5C 04 6175766C")
        val view = ok(EfComView.parse(bytes))
        assertEquals("0107", view.ldsVersion)
        assertEquals("040000", view.unicodeVersion)
        assertEquals(listOf(0x61, 0x75, 0x76, 0x6C), view.tagList)
        assertEquals(listOf(1, 2, 4, 12), view.dataGroups)
        assertContentEquals(bytes, view.raw())
    }

    @Test
    fun a1_hypothetical_lds_15_99() {
        val view = ok(EfComView.parse(hex("60 16 5F01 04 31353939 5F36 06 303430303030 5C 04 6175766C")))
        assertEquals("1599", view.ldsVersion)
    }

    @Test
    fun ef_com_without_a_tag_list_is_a_missing_element() {
        val failure = assertIs<LdsParseResult.Failure>(EfComView.parse(hex("60 0A 5F01 04 30313037 5F36 00")))
        assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, failure.error.reason)
    }

    // --- A.2 DG1 ---

    private fun dg1Of(mrz: String): ByteArray = TlvWriter.constructed(0x61, TlvWriter.encode(0x5F1F, ascii(mrz)))

    @Test
    fun a2_1_td1_dg1() {
        val mrz =
            "I<NLDXI85935F86999999990<<<<<<7208148F1108268NLD<<<<<<<<<<<4VAN<DER<STEEN<<MARI" + "ANNE<LOUISE"
        assertEquals(90, mrz.length)
        val bytes = dg1Of(mrz)
        assertContentEquals(hex("61 5D 5F1F 5A"), bytes.copyOfRange(0, 5))
        val view = ok(Dg1View.parse(bytes, reference))
        assertEquals(mrz, view.mrzText)
        val document = assertIs<TD1>(view.document)
        assertEquals(
            "VAN DER STEEN",
            document.commonFields.primaryIdentifier
                .replace("<", " ")
                .trim(),
        )
        assertEquals(ReadMethod.NFC_CHIP, view.parseResult.metadata.readMethod)
        assertContentEquals(bytes, view.raw())
    }

    // The line printed in A.2.2 is 81 characters long, not the 72 the appendix says: the first line has
    // the 44 characters of a TD3 line and the second has one character too many. The test takes the
    // printed text with line 1 cut to 36 characters and the last character of line 2 dropped.
    private val a22Printed = "I<ATASMITH<<JOHN<T<<<<<<<<<<<<<<<<<<<<<<<<<<123456789<HMD7406222M10123130121<<<54"

    @Test
    fun a2_2_td2_dg1() {
        val mrz = a22Printed.substring(0, 36) + a22Printed.substring(44, 44 + 36)
        assertEquals(72, mrz.length, "the appendix says the MRZ is 72 bytes")
        val bytes = dg1Of(mrz)
        assertContentEquals(hex("61 4B 5F1F 48"), bytes.copyOfRange(0, 5))
        val view = ok(Dg1View.parse(bytes, reference))
        assertIs<TD2>(view.document)
        assertEquals(ReadMethod.NFC_CHIP, view.parseResult.metadata.readMethod)
        assertTrue(view.parseResult is ParseResult.Success || view.parseResult is ParseResult.PartialSuccess)
    }

    @Test
    fun a2_2_as_printed_has_a_length_no_format_uses_and_is_a_typed_failure() {
        val failure = assertIs<LdsParseResult.Failure>(Dg1View.parse(dg1Of(a22Printed), reference))
        assertEquals(ChipMalformedFile.Reason.INVALID_ELEMENT, failure.error.reason)
    }

    @Test
    fun dg1_with_an_mrz_length_no_format_uses_is_malformed() {
        val failure = assertIs<LdsParseResult.Failure>(Dg1View.parse(dg1Of("I<UTOSPECIMEN<<ALEX"), reference))
        assertEquals(ChipMalformedFile.Reason.INVALID_ELEMENT, failure.error.reason)
        assertEquals(0x0101, failure.error.fileId)
    }

    @Test
    fun dg1_with_a_character_outside_the_mrz_alphabet_is_malformed() {
        val mrz = "i<NLDXI85935F86999999990<<<<<<7208148F1108268NLD<<<<<<<<<<<4VAN<DER<STEEN<<MARIANNE<LOUISE"
        assertIs<LdsParseResult.Failure>(Dg1View.parse(dg1Of(mrz), reference))
    }

    // --- A.5 DG11 ---

    @Test
    fun a5_dg11() {
        val tagList = TlvWriter.encode(0x5C, hex("5F0E 5F11 5F42 5F12 5F13"))
        val bytes =
            TlvWriter.constructed(
                0x6B,
                tagList,
                tlv(0x5F0E, "SMITH<<JOHN<J"),
                tlv(0x5F11, "ANYTOWN<MN"),
                tlv(0x5F42, "123 MAPLE RD<ANYTOWN<MN"),
                tlv(0x5F12, "1-612-555-1212"),
                tlv(0x5F13, "TRAVEL<AGENT"),
            )
        assertContentEquals(hex("6B 63"), bytes.copyOfRange(0, 2), "the appendix says the template is 99 bytes")
        val view = ok(Dg11View.parse(bytes))
        assertEquals(listOf(0x5F0E, 0x5F11, 0x5F42, 0x5F12, 0x5F13), view.tagList)
        assertEquals("SMITH<<JOHN<J", view.fullName)
        assertEquals("ANYTOWN<MN", view.placeOfBirth)
        assertEquals("123 MAPLE RD<ANYTOWN<MN", view.address)
        assertEquals("1-612-555-1212", view.telephone)
        assertEquals("TRAVEL<AGENT", view.profession)
        assertNull(view.title)
        assertNull(view.dateOfBirth)
        assertContentEquals(ascii("ANYTOWN<MN"), view.element(0x5F11)!!.value())
        assertContentEquals(bytes, view.raw())
    }

    @Test
    fun dg11_keeps_utf8_names_other_names_and_unknown_date_parts() {
        val bytes =
            TlvWriter.constructed(
                0x6B,
                TlvWriter.encode(0x5F0E, "ŞƏFİYEV<<ƏLİ".encodeToByteArray()),
                TlvWriter.encode(0x5F2B, ascii("19720000")),
                TlvWriter.constructed(
                    0xA0,
                    TlvWriter.encode(0x02, byteArrayOf(2)),
                    tlv(0x5F0F, "ONE<<A"),
                    tlv(0x5F0F, "TWO<<B"),
                ),
                TlvWriter.encode(0x5F16, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            )
        val view = ok(Dg11View.parse(bytes))
        assertEquals("ŞƏFİYEV<<ƏLİ", view.fullName)
        assertEquals(ChipDate(1972, null, null), view.dateOfBirth)
        assertEquals(listOf("ONE<<A", "TWO<<B"), view.otherNames.map { it.text })
        // An image is not text: no text, raw bytes kept.
        assertNull(view.element(0x5F16)!!.text)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), view.element(0x5F16)!!.value())
    }

    // --- DG12 ---

    @Test
    fun dg12_dates_in_ascii_and_in_bcd() {
        val ascii =
            TlvWriter.constructed(
                0x6C,
                tlv(0x5F19, "UTOPIA OFFICE"),
                tlv(0x5F26, "20200315"),
                tlv(0x5F55, "20200315133045"),
                tlv(0x5F56, "SN-1"),
            )
        val bcd =
            TlvWriter.constructed(
                0x6C,
                TlvWriter.encode(0x5F26, hex("20200315")),
                TlvWriter.encode(0x5F55, hex("20200315133045")),
            )
        val a = ok(Dg12View.parse(ascii))
        val b = ok(Dg12View.parse(bcd))
        assertEquals(ChipDate(2020, 3, 15), a.dateOfIssue)
        assertEquals(ChipDateTime(ChipDate(2020, 3, 15), 13, 30, 45), a.personalizationTime)
        assertEquals(a.dateOfIssue, b.dateOfIssue)
        assertEquals(a.personalizationTime, b.personalizationTime)
        assertEquals("UTOPIA OFFICE", a.issuingAuthority)
        assertEquals("SN-1", a.personalizationSystemSerial)
        assertContentEquals(hex("20200315"), b.element(0x5F26)!!.value())
    }

    @Test
    fun dg12_bad_date_bytes_give_no_date_but_keep_the_raw_value() {
        val view = ok(Dg12View.parse(TlvWriter.constructed(0x6C, tlv(0x5F26, "2020-3-15"), TlvWriter.encode(0x5F55, hex("2020AB")))))
        assertNull(view.dateOfIssue)
        assertNull(view.personalizationTime)
        assertContentEquals(ascii("2020-3-15"), view.element(0x5F26)!!.value())
    }

    @Test
    fun dg12_other_persons_come_from_the_template() {
        val view =
            ok(
                Dg12View.parse(
                    TlvWriter.constructed(
                        0x6C,
                        TlvWriter.constructed(0xA0, TlvWriter.encode(0x02, byteArrayOf(1)), tlv(0x5F1A, "DOE<<JANE")),
                    ),
                ),
            )
        assertEquals(listOf("DOE<<JANE"), view.otherPersons.map { it.text })
    }

    // --- DG13 ---

    @Test
    fun dg13_is_raw_only() {
        val content = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F)
        val bytes = TlvWriter.encode(0x6D, content)
        val view = ok(Dg13View.parse(bytes))
        assertContentEquals(content, view.content())
        assertContentEquals(bytes, view.raw())
    }

    // --- A.6 DG16 ---

    @Test
    fun a6_dg16_two_entries() {
        val first =
            TlvWriter.constructed(
                0xA1,
                tlv(0x5F50, "20020101"),
                tlv(0x5F51, "SMITH<<CHARLES<R"),
                tlv(0x5F52, "19525551212"),
                tlv(0x5F53, "123 MAPLE RD<ANYTOWN<MN<55100"),
            )
        val second =
            TlvWriter.constructed(
                0xA2,
                tlv(0x5F50, "20020315"),
                tlv(0x5F51, "BROWN<<MARY<J"),
                tlv(0x5F52, "14155551212"),
                tlv(0x5F53, "49 REDWOOD LN<OCEAN BREEZE<CA<94000"),
            )
        assertEquals(0x4C, first.size - 2)
        assertEquals(0x4F, second.size - 2)
        val bytes = TlvWriter.constructed(0x70, TlvWriter.encode(0x02, byteArrayOf(2)), first, second)
        assertContentEquals(hex("70 81A2"), bytes.copyOfRange(0, 3), "the appendix says the template is 162 bytes")
        val view = ok(Dg16View.parse(bytes))
        assertEquals(2, view.declaredCount)
        assertEquals(2, view.persons.size)
        assertEquals("SMITH<<CHARLES<R", view.persons[0].name)
        assertEquals(ChipDate(2002, 1, 1), view.persons[0].dateRecorded)
        assertEquals("19525551212", view.persons[0].telephone)
        assertEquals("123 MAPLE RD<ANYTOWN<MN<55100", view.persons[0].address)
        assertEquals("BROWN<<MARY<J", view.persons[1].name)
        assertEquals("49 REDWOOD LN<OCEAN BREEZE<CA<94000", view.persons[1].address)
        assertContentEquals(bytes, view.raw())
    }

    // --- hostile input ---

    @Test
    fun wrong_first_tag_and_broken_tlv_give_typed_failures_for_every_view() {
        val wrongTag = TlvWriter.constructed(0x6F, tlv(0x5F0E, "X"))
        val truncated = hex("6B 63 5F0E 0D 41")
        val parsers: List<(ByteArray) -> LdsParseResult<Any>> =
            listOf(
                { EfComView.parse(it) },
                { Dg1View.parse(it, reference) },
                { Dg11View.parse(it) },
                { Dg12View.parse(it) },
                { Dg13View.parse(it) },
                { Dg16View.parse(it) },
            )
        for (p in parsers) {
            val a = assertIs<LdsParseResult.Failure>(p(wrongTag))
            assertEquals(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, a.error.reason)
            val b = assertIs<LdsParseResult.Failure>(p(truncated))
            assertTrue(
                b.error.reason == ChipMalformedFile.Reason.NOT_VALID_TLV || b.error.reason == ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG,
            )
            assertIs<LdsParseResult.Failure>(p(ByteArray(0)))
        }
    }

    @Test
    fun random_bytes_never_throw() {
        val random = kotlin.random.Random(7)
        repeat(300) {
            val bytes = ByteArray(random.nextInt(0, 80)).also { random.nextBytes(it) }
            // Make a plausible start so deeper code runs too.
            if (bytes.size > 2 && it % 2 == 0) bytes[0] = intArrayOf(0x60, 0x61, 0x6B, 0x6C, 0x6D, 0x70)[it % 6].toByte()
            EfComView.parse(bytes)
            Dg1View.parse(bytes, reference)
            Dg11View.parse(bytes)
            Dg12View.parse(bytes)
            Dg13View.parse(bytes)
            Dg16View.parse(bytes)
        }
    }

    @Test
    fun to_string_never_shows_content() {
        val view = ok(Dg11View.parse(TlvWriter.constructed(0x6B, tlv(0x5F0E, "SMITH<<JOHN<J"))))
        assertTrue("SMITH" !in view.toString())
        assertTrue("SMITH" !in view.element(0x5F0E).toString())
    }
}
