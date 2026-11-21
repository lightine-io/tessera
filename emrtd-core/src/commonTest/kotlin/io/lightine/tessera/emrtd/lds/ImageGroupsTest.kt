package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.types.errors.ChipMalformedFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageGroupsTest {
    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun tlv(
        tag: Int,
        vararg parts: ByteArray,
    ): ByteArray = TlvWriter.encode(tag, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun synthetic(size: Int): ByteArray = ByteArray(size) { (it * 7 + 3).toByte() }

    // Doc 9303 Part 10, Appendix A.3. The appendix prints the length of A1 as 26 (hex), but its
    // fields add up to 28 (hex) and every outer length in the example agrees with 28, so 26 is a
    // misprint; the lengths here are computed from the content. The biometric data block is
    // 12 642 synthetic bytes, the size the appendix gives.
    private val a3Header =
        tlv(
            0xA1,
            hex("80 02 0101"),
            hex("81 01 02"),
            hex("83 07 20020315133000"),
            hex("85 08 2002040120070331"),
            hex("86 04 00010001"),
            hex("87 02 0101"),
            hex("88 02 0008"),
        )
    private val a3Block = synthetic(12_642)
    private val a3Dg2 =
        tlv(0x75, tlv(0x7F61, hex("02 01 01"), tlv(0x7F60, a3Header, tlv(0x5F2E, a3Block))))

    @Test
    fun a3_face_group_parses_with_header_and_opaque_block() {
        assertContentEquals(hex("75 82319E 7F61 823199 02 01 01 7F60 823191 A1 28"), a3Dg2.copyOf(19))
        val parsed = assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(a3Dg2)).view
        assertEquals(1, parsed.declaredInstances)
        val t = parsed.templates.single()
        assertContentEquals(hex("0101"), t.header.headerVersion)
        assertContentEquals(hex("02"), t.header.biometricType)
        assertNull(t.header.biometricSubType)
        assertContentEquals(hex("20020315133000"), t.header.creationDateTime)
        assertContentEquals(hex("2002040120070331"), t.header.validityPeriod)
        assertContentEquals(hex("00010001"), t.header.creatorPid)
        assertContentEquals(hex("0101"), t.header.formatOwner)
        assertContentEquals(hex("0008"), t.header.formatType)
        assertEquals(BiometricStandard.ISO_19794, t.block.standard)
        assertContentEquals(a3Block, t.block.bytes)
        assertNull(t.block.iso39794Part)
        assertFalse(t.toString().contains("0101"))
    }

    @Test
    fun a3_trailing_bytes_after_the_file_object_are_ignored() {
        val padded = a3Dg2 + ByteArray(5)
        assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(padded))
    }

    @Test
    fun returned_bytes_are_copies() {
        val g = assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(a3Dg2)).view
        val b = g.templates[0].block
        b.bytes[0] = 99
        assertContentEquals(a3Block, b.bytes)
    }

    @Test
    fun a4_portrait_group_returns_raw_image_bytes() {
        // Appendix A.4: 65 8207D8 { 02 01 01, 5F40 8207D0 { 2 000 image bytes } }.
        val image = synthetic(2_000)
        val file = tlv(0x65, hex("02 01 01"), tlv(0x5F40, image))
        assertContentEquals(hex("65 8207D8 02 01 01 5F40 8207D0"), file.copyOf(12))
        val g = assertIs<LdsParseResult.Success<DisplayedImageGroup>>(DisplayedImageGroup.parseDg5(file)).view
        assertEquals(1, g.declaredInstances)
        assertContentEquals(image, g.images.single())
    }

    @Test
    fun a4_signature_group_dg7() {
        val image = synthetic(300)
        val file = tlv(0x67, hex("02 01 01"), tlv(0x5F43, image))
        val g = assertIs<LdsParseResult.Success<DisplayedImageGroup>>(DisplayedImageGroup.parseDg7(file)).view
        assertContentEquals(image, g.images.single())
        // The same bytes are not a DG5.
        val e = assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg5(file)).error
        assertEquals(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, e.reason)
        assertEquals(0x0105, e.fileId)
    }

    @Test
    fun several_displayed_images_in_succeeding_templates() {
        val a = synthetic(10)
        val b = synthetic(20)
        val file = tlv(0x65, hex("02 01 02"), tlv(0x5F40, a)) + tlv(0x65, tlv(0x5F40, b))
        val g = assertIs<LdsParseResult.Success<DisplayedImageGroup>>(DisplayedImageGroup.parseDg5(file)).view
        assertEquals(2, g.declaredInstances)
        assertEquals(2, g.images.size)
        assertContentEquals(b, g.images[1])
    }

    @Test
    fun synthetic_39794_container_is_recognized() {
        val payload = synthetic(500)
        for ((tag, part) in listOf(0x64 to Iso39794Part.PART_4, 0x65 to Iso39794Part.PART_5, 0x66 to Iso39794Part.PART_6)) {
            val block = tlv(0x7F2E, tlv(0xA1, tlv(tag, payload)))
            val file = tlv(0x75, tlv(0x7F61, hex("02 01 01"), tlv(0x7F60, a3Header, block)))
            val g = assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(file)).view
            val b = g.templates.single().block
            assertEquals(BiometricStandard.ISO_39794, b.standard)
            assertEquals(part, b.iso39794Part)
            assertContentEquals(payload, b.iso39794Data)
            assertContentEquals(tlv(0xA1, tlv(tag, payload)), b.bytes)
        }
    }

    @Test
    fun a_39794_block_of_another_shape_stays_opaque() {
        val block = tlv(0x7F2E, tlv(0xA1, tlv(0x67, synthetic(4))))
        val file = tlv(0x75, tlv(0x7F61, tlv(0x7F60, a3Header, block)))
        val b =
            assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(file))
                .view.templates
                .single()
                .block
        assertNull(b.iso39794Part)
        assertNull(b.iso39794Data)
        assertNotNull(b.bytes)
    }

    @Test
    fun two_templates_keep_file_order_and_unknown_objects_are_ignored() {
        val t1 = tlv(0x7F60, a3Header, tlv(0x5F2E, hex("AA")))
        val t2 = tlv(0x7F60, tlv(0xA1, hex("87 02 0102")), tlv(0x06, hex("01")), tlv(0x5F2E, hex("BB")))
        val file = tlv(0x75, tlv(0x7F61, hex("02 01 02"), t1, t2))
        val g = assertIs<LdsParseResult.Success<BiometricGroup>>(BiometricGroup.parse(file)).view
        assertEquals(2, g.templates.size)
        assertContentEquals(hex("BB"), g.templates[1].block.bytes)
        assertContentEquals(hex("0102"), g.templates[1].header.formatOwner)
        assertNull(g.templates[1].header.formatType)
    }

    private fun dg2Problem(bytes: ByteArray): ChipMalformedFile.Reason {
        val e = assertIs<LdsParseResult.Failure>(BiometricGroup.parse(bytes)).error
        assertEquals(0x0102, e.fileId)
        assertFalse(e.description.contains("AA"))
        return e.reason
    }

    @Test
    fun hostile_nested_length_larger_than_its_parent_is_rejected() {
        // 7F60 claims 0x1000 bytes but the 7F61 around it holds 4.
        val file = hex("75 0A 7F61 07 7F60 82 1000 AA BB")
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(file))
        // An inner 5F2E that declares 9 bytes but its 7F60 holds 2.
        val inner = hex("7F60 05 5F2E 09 AA AA")
        val file2 = tlv(0x75, tlv(0x7F61, inner))
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(file2))
    }

    @Test
    fun hostile_huge_lengths_do_not_allocate() {
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(hex("75 84 7FFFFFFF 7F61")))
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(hex("75 85 FFFFFFFFFF 00")))
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(hex("75 80 7F61 00")))
        val e = assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg5(hex("65 84 FFFFFFFF 5F40"))).error
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, e.reason)
        val e2 = assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg7(hex("67 83 100000 5F43"))).error
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, e2.reason)
    }

    @Test
    fun hostile_nesting_is_rejected() {
        // The reader only walks to the containers Part 10 defines, so deep junk is never followed:
        // it ends as a missing biometric block, not as a stack overflow.
        var inner = hex("AA")
        repeat(5000) { inner = tlv(0x7F60, inner) }
        val file = tlv(0x75, tlv(0x7F61, inner))
        assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, dg2Problem(file))
    }

    @Test
    fun wrong_or_missing_structure_is_rejected() {
        assertEquals(ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG, dg2Problem(tlv(0x65, hex("AA"))))
        assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, dg2Problem(tlv(0x75, tlv(0x02, hex("01")))))
        assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, dg2Problem(tlv(0x75, tlv(0x7F61, tlv(0x7F60, a3Header)))))
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(hex("00")))
        assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, dg2Problem(ByteArray(0)))
        assertEquals(
            ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG,
            assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg5(tlv(0x67, hex("AA")))).error.reason,
        )
        val noImage = assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg5(tlv(0x65, hex("02 01 01")))).error
        assertEquals(ChipMalformedFile.Reason.MISSING_ELEMENT, noImage.reason)
        assertEquals(
            ChipMalformedFile.Reason.NOT_VALID_TLV,
            assertIs<LdsParseResult.Failure>(DisplayedImageGroup.parseDg5(hex("65 05 5F40"))).error.reason,
        )
    }

    @Test
    fun error_text_names_group_problem_and_offset_only() {
        val e = assertIs<LdsParseResult.Failure>(BiometricGroup.parse(hex("75 80 00"))).error
        assertEquals(
            "ChipMalformedFile: The chip file with identifier 0102 cannot be read: its bytes are not valid BER-TLV (at byte offset 1)",
            e.toString(),
        )
    }

    @Test
    fun random_bytes_never_throw() {
        val rnd = Random(1234)
        repeat(3000) {
            val bytes = ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }
            if (bytes.isNotEmpty()) bytes[0] = intArrayOf(0x75, 0x65, 0x67, bytes[0].toInt())[it % 4].toByte()
            BiometricGroup.parse(bytes)
            DisplayedImageGroup.parseDg5(bytes)
            DisplayedImageGroup.parseDg7(bytes)
        }
    }
}
