package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Content error: a file read from the chip is not built the way ICAO Doc 9303 Part 10 describes,
 * so it cannot be turned into a typed view.
 *
 * This is the one error for every malformed chip file: the text data groups, the image data groups
 * (DG2, DG5, DG7), the security object (EF.SOD) and a file whose bytes end before the length its
 * header declares. The bytes the chip sent are still available to your app when they were read;
 * only the view could not be made. The description names the problem, never a value from the file.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fileId The two-byte file identifier of the file (for example `0x0101` for EF.DG1), or
 *   `null` when it is not known.
 * @property reason What is wrong with the file.
 * @property offset Where in the file's bytes the fault was found, or `null` when it is not a single
 *   place or not known.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipMalformedFile(
    val fileId: Int?,
    val reason: Reason,
    val offset: Int? = null,
) : ChipError() {
    /**
     * What is wrong with a file.
     *
     * May gain members in a later release: a `when` over it needs an `else` branch.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /**
         * The bytes are not valid BER-TLV (or DER, for the security object): a tag or length is cut
         * off or not allowed, or a data object is longer than the bytes left.
         */
        NOT_VALID_TLV,

        /** Data objects are nested deeper than the reader follows. */
        NESTING_TOO_DEEP,

        /** The file does not start with the tag its file type requires. */
        UNEXPECTED_FILE_TAG,

        /** A data object the file type requires is missing. */
        MISSING_ELEMENT,

        /** A data object has a length, content or form (primitive or constructed) its definition does not allow. */
        INVALID_ELEMENT,

        /** The security object is ASN.1 but not a CMS SignedData. */
        NOT_SIGNED_DATA,

        /** The SignedData does not hold the content type the file needs, or holds no content. */
        UNEXPECTED_CONTENT_TYPE,
    }

    /** English text for logs: the reason, the file identifier and the offset when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.NOT_VALID_TLV -> "its bytes are not valid BER-TLV"
                    Reason.NESTING_TOO_DEEP -> "its data objects are nested too deep"
                    Reason.UNEXPECTED_FILE_TAG -> "it does not start with the tag its file type requires"
                    Reason.MISSING_ELEMENT -> "a required data object is missing"
                    Reason.INVALID_ELEMENT -> "a data object has a length or content that is not allowed"
                    Reason.NOT_SIGNED_DATA -> "it is not a CMS SignedData"
                    Reason.UNEXPECTED_CONTENT_TYPE -> "its signed content is not of the type the file needs"
                }
            val which =
                if (fileId == null) {
                    "A chip file"
                } else {
                    "The chip file with identifier ${fileId.toString(16).uppercase().padStart(4, '0')}"
                }
            val where = if (offset == null) "" else " (at byte offset $offset)"
            return "$which cannot be read: $what$where"
        }

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "malformed-file." + slug(reason.name)

    /** `ChipMalformedFile: ` followed by [description]. */
    override fun toString(): String = "ChipMalformedFile: $description"
}
