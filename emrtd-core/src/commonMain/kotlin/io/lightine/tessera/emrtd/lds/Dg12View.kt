package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile
import kotlin.jvm.JvmStatic

/**
 * EF.DG12: additional document details (ICAO Doc 9303 Part 10, section 4.7.12).
 *
 * Text fields are read as UTF-8 and left as they are. The date of issue and the personalization
 * time are read from 8 or 14 ASCII digits, or from packed BCD (4 or 7 bytes), as Part 10
 * recommends; when a value is neither, the date is `null` and the raw bytes are still in
 * [elements]. The images of the document front and rear are raw bytes only.
 *
 * `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property tagList The tags in the chip's tag list (`5C`), in list order, or empty when the chip
 *   has none.
 * @property issuingAuthority Issuing authority (`5F19`), or `null`.
 * @property dateOfIssue Date of issue (`5F26`), or `null` when absent or not a date.
 * @property otherPersons Names of other persons (`5F1A`), in file order, from the content-specific
 *   template (`A0`) or directly in the file.
 * @property endorsements Endorsements and observations (`5F1B`), or `null`.
 * @property taxOrExitRequirements Tax or exit requirements (`5F1C`), or `null`.
 * @property personalizationTime Date and time of personalization (`5F55`), or `null` when absent
 *   or not a date and time.
 * @property personalizationSystemSerial Serial number of the personalization system (`5F56`), or
 *   `null`.
 * @property elements All data objects of the file, in file order, with their raw values.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class Dg12View private constructor(
    private val rawBytes: ByteArray,
    public val tagList: List<Int>,
    public val elements: List<ChipDataElement>,
    public val otherPersons: List<ChipDataElement>,
) {
    public val issuingAuthority: String? get() = text(0x5F19)
    public val dateOfIssue: ChipDate? get() = element(0x5F26)?.let { ChipDate.decode(it.value()) }
    public val endorsements: String? get() = text(0x5F1B)
    public val taxOrExitRequirements: String? get() = text(0x5F1C)
    public val personalizationTime: ChipDateTime? get() = element(0x5F55)?.let { ChipDateTime.decode(it.value()) }
    public val personalizationSystemSerial: String? get() = text(0x5F56)

    /** The first top-level data object with [tag], or `null`. */
    public fun element(tag: Int): ChipDataElement? = elements.firstOrNull { it.tag == tag }

    private fun text(tag: Int): String? = element(tag)?.text

    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** The file's size; never its content. */
    override fun toString(): String = "Dg12View(${rawBytes.size} bytes)"

    /**
     * Reads EF.DG12.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.DG12 starts with. */
        public const val TAG: Int = 0x6C

        private const val FILE_ID = 0x010C

        /**
         * Reads the bytes of EF.DG12. Fails with a [ChipMalformedFile] result, never an exception,
         * when the bytes are not a DG12 (bad TLV or a different first tag).
         */
        @JvmStatic
        public fun parse(bytes: ByteArray): LdsParseResult<Dg12View> =
            LdsReader.guard(FILE_ID) {
                val outer = LdsReader.open(bytes, FILE_ID, TAG)
                val children = outer.children()
                val elements = children.map { ChipDataElement(it.tag, it.value()) }
                val list = elements.firstOrNull { it.tag == 0x5C }
                val persons = ArrayList<ChipDataElement>()
                for (child in children) {
                    when {
                        child.tag == 0x5F1A -> {
                            persons.add(ChipDataElement(child.tag, child.value()))
                        }

                        child.tag == 0xA0 -> {
                            child.children().filter { it.tag == 0x5F1A }.forEach {
                                persons.add(
                                    ChipDataElement(it.tag, it.value()),
                                )
                            }
                        }
                    }
                }
                Dg12View(
                    rawBytes = bytes.copyOf(),
                    tagList = list?.let { LdsReader.tagList(it.value(), FILE_ID) } ?: emptyList(),
                    elements = elements,
                    otherPersons = persons,
                )
            }
    }
}
