package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * One data object of a chip file: its tag, its raw value bytes, and the value read as UTF-8 text.
 *
 * The raw bytes are always kept. [text] is `null` when the bytes are not valid UTF-8, for example
 * an image. The SDK never trims, folds or corrects the text: `<` fillers stay as the chip has them.
 *
 * `toString()` shows the tag and the byte count, never the value.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property tag The tag as one number, for example `0x5F0E`.
 * @property text The value decoded as UTF-8, or `null` when it is not valid UTF-8.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipDataElement internal constructor(
    public val tag: Int,
    value: ByteArray,
) {
    private val bytes: ByteArray = value.copyOf()

    /** The value decoded as UTF-8, or `null` when it is not valid UTF-8. */
    public val text: String? =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }

    /** A copy of the raw value bytes. */
    public fun value(): ByteArray = bytes.copyOf()

    /** Same tag and same value bytes. */
    override fun equals(other: Any?): Boolean = other is ChipDataElement && tag == other.tag && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * tag + bytes.contentHashCode()

    /** The tag and the byte count; never the value. */
    override fun toString(): String = "ChipDataElement(tag=${tag.toString(16)}, ${bytes.size} bytes)"
}

/**
 * A date as a chip file holds it: year, month and day as numbers, each `null` when the chip marks it
 * unknown (`0000` for the year, `00` for month or day; Doc 9303 Part 10, section 4.7.11).
 *
 * The SDK does not check the date against the calendar. `toString()` shows no document values; they
 * are in the properties.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property year The four-digit year, or `null` when unknown.
 * @property month The month number as written, or `null` when unknown.
 * @property day The day number as written, or `null` when unknown.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipDate(
    val year: Int?,
    val month: Int?,
    val day: Int?,
) {
    /** Which parts the chip wrote down; never a date value. */
    override fun toString(): String = "ChipDate(year=${year != null}, month=${month != null}, day=${day != null})"

    internal companion object {
        /** From 8 ASCII digits (`yyyymmdd`) or 4 bytes of BCD; `null` for any other bytes. */
        fun decode(value: ByteArray): ChipDate? = LdsReader.digits(value, 8)?.let { fromDigits(it) }

        fun fromDigits(d: String): ChipDate =
            ChipDate(
                year = d.substring(0, 4).toInt().takeIf { it != 0 },
                month = d.substring(4, 6).toInt().takeIf { it != 0 },
                day = d.substring(6, 8).toInt().takeIf { it != 0 },
            )
    }
}

/**
 * A date and time of day as a chip file holds them (`yyyymmddhhmmss`).
 *
 * The numbers are as written; the SDK does not check them against the calendar or the clock.
 * `toString()` shows no document values; they are in the properties.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property date The date part.
 * @property hour The hour as written.
 * @property minute The minute as written.
 * @property second The second as written.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipDateTime(
    val date: ChipDate,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    /** The date part's shape; never a date or time value. */
    override fun toString(): String = "ChipDateTime($date)"

    internal companion object {
        /** From 14 ASCII digits or 7 bytes of BCD; `null` for any other bytes. */
        fun decode(value: ByteArray): ChipDateTime? {
            val d = LdsReader.digits(value, 14) ?: return null
            return ChipDateTime(
                ChipDate.fromDigits(d.substring(0, 8)),
                d.substring(8, 10).toInt(),
                d.substring(10, 12).toInt(),
                d.substring(12, 14).toInt(),
            )
        }
    }
}
