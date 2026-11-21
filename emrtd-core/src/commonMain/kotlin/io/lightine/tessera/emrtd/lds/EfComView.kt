package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * EF.COM: the header file of the chip's LDS (ICAO Doc 9303 Part 10, section 4.6.1). It holds the LDS
 * version, the Unicode version and the list of data groups the chip says it has.
 *
 * This is the chip's own claim and is not signed. Part 10 recommends that inspection systems use
 * the security object (EF.SOD) instead of EF.COM, so do not trust [dataGroups] over it.
 *
 * The raw file bytes stay available through [raw]. `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property ldsVersion The LDS version as the four ASCII digits the chip holds (for example the
 *   text `0107`), or `null` when the element is absent or is not four ASCII digits.
 * @property unicodeVersion The Unicode version as the six ASCII digits the chip holds, or `null`
 *   when the element is absent or is not six ASCII digits.
 * @property tagList The tags in the chip's tag list, in the order it lists them (for example `0x61`).
 * @property dataGroups The data group numbers (1 to 16) the tag list names, in list order. Tags that
 *   are not a data group tag are left out here and stay in [tagList].
 * @property elements The data objects of the file, in file order, with their raw values.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class EfComView private constructor(
    private val rawBytes: ByteArray,
    public val ldsVersion: String?,
    public val unicodeVersion: String?,
    public val tagList: List<Int>,
    public val dataGroups: List<Int>,
    public val elements: List<ChipDataElement>,
) {
    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** The file's identifier and size; never its content. */
    override fun toString(): String = "EfComView(${rawBytes.size} bytes)"

    /**
     * Reads EF.COM.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.COM starts with. */
        public const val TAG: Int = 0x60

        private const val TAG_LDS_VERSION = 0x5F01
        private const val TAG_UNICODE_VERSION = 0x5F36
        private const val TAG_LIST = 0x5C

        // Data group tags of Doc 9303 Part 10: 61 is DG1, 75 DG2, 63 DG3, 76 DG4, 65 to 6F DG5 to DG15, 70 DG16.
        private val dataGroupOfTag: Map<Int, Int> =
            mapOf(
                0x61 to 1,
                0x75 to 2,
                0x63 to 3,
                0x76 to 4,
                0x65 to 5,
                0x66 to 6,
                0x67 to 7,
                0x68 to 8,
                0x69 to 9,
                0x6A to 10,
                0x6B to 11,
                0x6C to 12,
                0x6D to 13,
                0x6E to 14,
                0x6F to 15,
                0x70 to 16,
            )

        /**
         * Reads the bytes of EF.COM. Fails with a [ChipMalformedFile] result, never an exception,
         * when the bytes are not an EF.COM (no tag list, or bad TLV).
         */
        public fun parse(bytes: ByteArray): LdsParseResult<EfComView> =
            LdsReader.guard(LdsReader.FILE_ID_EF_COM) {
                val fileId = LdsReader.FILE_ID_EF_COM
                val outer = LdsReader.open(bytes, fileId, TAG)
                val elements = LdsReader.elementsOf(outer)
                val list = elements.firstOrNull { it.tag == TAG_LIST } ?: LdsReader.fail(fileId, ChipMalformedFile.Reason.MISSING_ELEMENT)
                val tags = LdsReader.tagList(list.value(), fileId)

                fun digits(
                    tag: Int,
                    length: Int,
                ): String? = elements.firstOrNull { it.tag == tag }?.text?.takeIf { s -> s.length == length && s.all { it in '0'..'9' } }
                EfComView(
                    rawBytes = bytes.copyOf(),
                    ldsVersion = digits(TAG_LDS_VERSION, 4),
                    unicodeVersion = digits(TAG_UNICODE_VERSION, 6),
                    tagList = tags,
                    dataGroups = tags.mapNotNull { dataGroupOfTag[it] },
                    elements = elements,
                )
            }
    }
}
