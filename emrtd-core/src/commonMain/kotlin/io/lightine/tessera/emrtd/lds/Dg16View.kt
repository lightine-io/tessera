package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * One person to notify in EF.DG16: a template `A1`, `A2` and so on with the date the entry was
 * recorded, a name, a telephone number and an address.
 *
 * Text is read as UTF-8 and left as it is. `toString()` never shows content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property dateRecorded Date the entry was recorded (`5F50`), or `null` when absent or not a date.
 * @property name Name of the person (`5F51`), with `<` fillers as the chip has them, or `null`.
 * @property telephone Telephone number (`5F52`), or `null`.
 * @property address Address (`5F53`), or `null`.
 * @property elements All data objects of the template, in order, with their raw values.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class PersonToNotify internal constructor(
    public val elements: List<ChipDataElement>,
) {
    public val dateRecorded: ChipDate? get() = element(0x5F50)?.let { ChipDate.decode(it.value()) }
    public val name: String? get() = element(0x5F51)?.text
    public val telephone: String? get() = element(0x5F52)?.text
    public val address: String? get() = element(0x5F53)?.text

    /** The first data object with [tag], or `null`. */
    public fun element(tag: Int): ChipDataElement? = elements.firstOrNull { it.tag == tag }

    /** The number of data objects; never their content. */
    override fun toString(): String = "PersonToNotify(${elements.size} elements)"
}

/**
 * EF.DG16: persons to notify (ICAO Doc 9303 Part 10, section 4.7.16).
 *
 * [persons] holds one entry per template found in the file. [declaredCount] is the number the chip
 * states; the SDK does not compare it with [persons] and does not correct either.
 *
 * `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property declaredCount The number of templates the chip declares (tag `02`), or `null` when it
 *   declares none. The count may sit beside the templates or inside the first one.
 * @property persons The templates, in file order.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class Dg16View private constructor(
    private val rawBytes: ByteArray,
    public val declaredCount: Int?,
    public val persons: List<PersonToNotify>,
) {
    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** The file's size; never its content. */
    override fun toString(): String = "Dg16View(${rawBytes.size} bytes)"

    /**
     * Reads EF.DG16.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.DG16 starts with. */
        public const val TAG: Int = 0x70

        private const val FILE_ID = 0x0110

        /**
         * Reads the bytes of EF.DG16. Fails with a [ChipMalformedFile] result, never an exception,
         * when the bytes are not a DG16 (bad TLV or a different first tag).
         */
        public fun parse(bytes: ByteArray): LdsParseResult<Dg16View> =
            LdsReader.guard(FILE_ID) {
                val outer = LdsReader.open(bytes, FILE_ID, TAG)
                var count: Int? = null
                val persons = ArrayList<PersonToNotify>()
                for (child in outer.children()) {
                    if (child.tag == 0x02) {
                        if (child.length != 1) LdsReader.fail(FILE_ID, ChipMalformedFile.Reason.INVALID_ELEMENT)
                        count = child.value()[0].toInt() and 0xFF
                    } else if (child.tag in 0xA1..0xAF) {
                        val inner = child.children()
                        val counter = inner.firstOrNull { it.tag == 0x02 }
                        if (counter != null && counter.length == 1 && count == null) count = counter.value()[0].toInt() and 0xFF
                        persons.add(PersonToNotify(inner.filter { it.tag != 0x02 }.map { ChipDataElement(it.tag, it.value()) }))
                    }
                }
                Dg16View(bytes.copyOf(), count, persons)
            }
    }
}
