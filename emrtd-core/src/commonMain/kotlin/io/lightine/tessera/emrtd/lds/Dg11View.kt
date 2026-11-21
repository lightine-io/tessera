package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * EF.DG11: additional personal details (ICAO Doc 9303 Part 10, section 4.7.11).
 *
 * Every text field is the chip's value read as UTF-8 and left as it is: names keep their `<`
 * fillers, nothing is trimmed or folded. An element the chip does not hold is `null`. The raw file
 * bytes and every data object's raw value stay available through [raw], [elements] and
 * [element]; images (the proof of citizenship) are only available as raw bytes.
 *
 * `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property tagList The tags in the chip's tag list (`5C`), in list order, or empty when the chip
 *   has none.
 * @property fullName Full name of the holder (`5F0E`), or `null`.
 * @property otherNames Other names (`5F0F`), in file order, from the content-specific template
 *   (`A0`) or directly in the file.
 * @property personalNumber Personal number (`5F10`), or `null`.
 * @property dateOfBirth Full date of birth (`5F2B`, 8 ASCII digits or 4 bytes of BCD), or `null`
 *   when absent or not a date. Unknown parts are `null` inside the date.
 * @property placeOfBirth Place of birth (`5F11`), or `null`.
 * @property address Permanent address (`5F42`), or `null`.
 * @property telephone Telephone (`5F12`), or `null`.
 * @property profession Profession (`5F13`), or `null`.
 * @property title Title (`5F14`), or `null`.
 * @property personalSummary Personal summary (`5F15`), or `null`.
 * @property otherTravelDocuments Other valid travel document numbers (`5F17`), or `null`.
 * @property custodyInformation Custody information (`5F18`), or `null`.
 * @property elements All data objects of the file, in file order, with their raw values.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class Dg11View private constructor(
    private val rawBytes: ByteArray,
    public val tagList: List<Int>,
    public val elements: List<ChipDataElement>,
    public val otherNames: List<ChipDataElement>,
) {
    public val fullName: String? get() = text(0x5F0E)
    public val personalNumber: String? get() = text(0x5F10)
    public val dateOfBirth: ChipDate? get() = element(0x5F2B)?.let { ChipDate.decode(it.value()) }
    public val placeOfBirth: String? get() = text(0x5F11)
    public val address: String? get() = text(0x5F42)
    public val telephone: String? get() = text(0x5F12)
    public val profession: String? get() = text(0x5F13)
    public val title: String? get() = text(0x5F14)
    public val personalSummary: String? get() = text(0x5F15)
    public val otherTravelDocuments: String? get() = text(0x5F17)
    public val custodyInformation: String? get() = text(0x5F18)

    /** The first top-level data object with [tag], or `null`. */
    public fun element(tag: Int): ChipDataElement? = elements.firstOrNull { it.tag == tag }

    private fun text(tag: Int): String? = element(tag)?.text

    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** The file's size; never its content. */
    override fun toString(): String = "Dg11View(${rawBytes.size} bytes)"

    /**
     * Reads EF.DG11.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.DG11 starts with. */
        public const val TAG: Int = 0x6B

        private const val FILE_ID = 0x010B

        /**
         * Reads the bytes of EF.DG11. Fails with a [ChipMalformedFile] result, never an exception,
         * when the bytes are not a DG11 (bad TLV or a different first tag).
         */
        public fun parse(bytes: ByteArray): LdsParseResult<Dg11View> =
            LdsReader.guard(FILE_ID) {
                val outer = LdsReader.open(bytes, FILE_ID, TAG)
                val children = outer.children()
                val elements = children.map { ChipDataElement(it.tag, it.value()) }
                val list = elements.firstOrNull { it.tag == 0x5C }
                val names = ArrayList<ChipDataElement>()
                for (child in children) {
                    when {
                        child.tag == 0x5F0F -> {
                            names.add(ChipDataElement(child.tag, child.value()))
                        }

                        child.tag == 0xA0 -> {
                            child.children().filter { it.tag == 0x5F0F }.forEach {
                                names.add(
                                    ChipDataElement(it.tag, it.value()),
                                )
                            }
                        }
                    }
                }
                Dg11View(
                    rawBytes = bytes.copyOf(),
                    tagList = list?.let { LdsReader.tagList(it.value(), FILE_ID) } ?: emptyList(),
                    elements = elements,
                    otherNames = names,
                )
            }
    }
}
