package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.ImageGroupReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.jvm.JvmStatic

/**
 * DG2, the chip's face data group, read down to its containers (Doc 9303 Part 10, section 4.7.2).
 *
 * The group holds one or more biometric templates. Each has a header (who made the record and in
 * what format) and a biometric data block. The block is handed over as it is: it is encoded in
 * ISO/IEC 19794 or ISO/IEC 39794, which are paid standards, and this SDK reads and decodes
 * nothing inside them, including the image.
 *
 * Nothing here says the face is the holder's or that the data is genuine. The values are what the
 * file holds, in file order.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property declaredInstances The number the file's integer object says it holds (the first group
 *   template's tag `02`), or `null` when absent or not one to four bytes. It is not checked
 *   against [templates].
 * @property templates The biometric templates in file order. Part 10 says the most recent
 *   internationally interoperable encoding is first.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class BiometricGroup internal constructor(
    public val declaredInstances: Int?,
    public val templates: List<BiometricTemplate>,
) {
    override fun toString(): String = "BiometricGroup(templates=${templates.size})"

    public companion object {
        /**
         * Reads the content of EF.DG2: a `75` object holding the biometric information template
         * group template (`7F61`) with its nested biometric information templates (`7F60`).
         *
         * Bytes after the `75` object are ignored (a file read is cut to the length the first
         * object declares). Bad bytes give [LdsParseResult.Failure] with a [io.lightine.tessera.types.errors.ChipMalformedFile]; this never throws for
         * chip data.
         *
         * @param bytes the whole content of the file
         */
        @JvmStatic
        public fun parse(bytes: ByteArray): LdsParseResult<BiometricGroup> = ImageGroupReader.readDg2(bytes)
    }
}

/**
 * One biometric information template: a [header] and the [block] it describes.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class BiometricTemplate internal constructor(
    public val header: BiometricHeader,
    public val block: BiometricDataBlock,
) {
    override fun toString(): String = "BiometricTemplate(block=$block)"
}

/**
 * The biometric header template (tag `A1`) of a [BiometricTemplate]: the fields Part 10 lists in
 * its Table 44, each as the bytes the file holds, or `null` when absent. Each read of a property
 * returns a new copy.
 *
 * Part 10 marks [formatOwner] and [formatType] as required. A header that lacks them is still
 * returned, with `null`: the SDK reports what is there and judges nothing.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class BiometricHeader internal constructor(
    headerVersion: ByteArray?,
    biometricType: ByteArray?,
    biometricSubType: ByteArray?,
    creationDateTime: ByteArray?,
    validityPeriod: ByteArray?,
    creatorPid: ByteArray?,
    formatOwner: ByteArray?,
    formatType: ByteArray?,
) {
    private val headerVersionBytes = headerVersion
    private val biometricTypeBytes = biometricType
    private val biometricSubTypeBytes = biometricSubType
    private val creationDateTimeBytes = creationDateTime
    private val validityPeriodBytes = validityPeriod
    private val creatorPidBytes = creatorPid
    private val formatOwnerBytes = formatOwner
    private val formatTypeBytes = formatType

    /** Tag `80`: the ICAO header version (two bytes, `0101` in Part 10's example). */
    public val headerVersion: ByteArray? get() = headerVersionBytes?.copyOf()

    /** Tag `81`: the biometric type. */
    public val biometricType: ByteArray? get() = biometricTypeBytes?.copyOf()

    /** Tag `82`: the biometric sub-type. */
    public val biometricSubType: ByteArray? get() = biometricSubTypeBytes?.copyOf()

    /** Tag `83`: the creation date and time, as the seven bytes the file holds. */
    public val creationDateTime: ByteArray? get() = creationDateTimeBytes?.copyOf()

    /** Tag `85`: the validity period (from, through), as the eight bytes the file holds. */
    public val validityPeriod: ByteArray? get() = validityPeriodBytes?.copyOf()

    /** Tag `86`: the creator of the biometric reference data (PID). */
    public val creatorPid: ByteArray? get() = creatorPidBytes?.copyOf()

    /** Tag `87`: the format owner, two bytes. It says who defines how the block is encoded. */
    public val formatOwner: ByteArray? get() = formatOwnerBytes?.copyOf()

    /** Tag `88`: the format type, two bytes. */
    public val formatType: ByteArray? get() = formatTypeBytes?.copyOf()

    override fun toString(): String = "BiometricHeader"
}

/**
 * Which standard family a biometric data block is stored under.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class BiometricStandard {
    /** Tag `5F2E`: ISO/IEC 19794 series, first edition. */
    ISO_19794,

    /** Tag `7F2E`: ISO/IEC 39794 series. */
    ISO_39794,
}

/**
 * Which part of ISO/IEC 39794 a `7F2E` block carries, by the tag Part 10 gives in its Table 3.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class Iso39794Part {
    /** Tag `64`: ISO/IEC 39794-4. */
    PART_4,

    /** Tag `65`: ISO/IEC 39794-5. */
    PART_5,

    /** Tag `66`: ISO/IEC 39794-6. */
    PART_6,
}

/**
 * A biometric data block as an opaque container: the bytes inside tag `5F2E` or `7F2E`, undecoded.
 *
 * For [BiometricStandard.ISO_19794], [bytes] is the value of `5F2E`; the face image sits inside it
 * in the format the header's format owner and type name, and this SDK does not read it. For
 * [BiometricStandard.ISO_39794], [bytes] is the value of `7F2E`. When it has the structure Part
 * 10's Table 2 gives (`A1` holding one `64`, `65` or `66` object), [iso39794Part] says which and
 * [iso39794Data] is the value of that object; otherwise both are `null` and [bytes] is still all
 * there is.
 *
 * Each read of a byte-array property returns a new copy.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property standard The tag the block was stored under.
 * @property iso39794Part The part of ISO/IEC 39794 the container names, or `null`.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class BiometricDataBlock internal constructor(
    public val standard: BiometricStandard,
    bytes: ByteArray,
    public val iso39794Part: Iso39794Part?,
    iso39794Data: ByteArray?,
) {
    private val blockBytes = bytes
    private val partBytes = iso39794Data

    /** The value of the `5F2E` or `7F2E` object. */
    public val bytes: ByteArray get() = blockBytes.copyOf()

    /** The value of the `64`, `65` or `66` object inside `7F2E`, or `null`. */
    public val iso39794Data: ByteArray? get() = partBytes?.copyOf()

    override fun toString(): String = "BiometricDataBlock($standard, ${blockBytes.size} bytes)"
}
