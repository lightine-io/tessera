package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Content error: a file read from the chip is not built the way ICAO Doc 9303 Part 10 describes,
 * so it cannot be turned into a typed view.
 *
 * The bytes the chip sent are still available to your app; only the view could not be made. The
 * description names the problem, never a value from the file.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fileId The two-byte file identifier of the file (for example `0x0101` for EF.DG1), or
 *   `null` when it is not known.
 * @property reason What is wrong with the file.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipMalformedFile(
    val fileId: Int?,
    val reason: Reason,
) : ChipError() {
    /**
     * What is wrong with a file.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** The bytes are not valid BER-TLV: a tag or length is cut off or not allowed. */
        NOT_VALID_TLV,

        /** The file does not start with the tag its file type requires. */
        UNEXPECTED_FILE_TAG,

        /** A data object the file type requires is missing. */
        MISSING_ELEMENT,

        /** A data object has a length or content its definition does not allow. */
        INVALID_ELEMENT,
    }

    /** English text for logs: the reason, and the file identifier when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.NOT_VALID_TLV -> "its bytes are not valid BER-TLV"
                    Reason.UNEXPECTED_FILE_TAG -> "it does not start with the tag its file type requires"
                    Reason.MISSING_ELEMENT -> "a required data object is missing"
                    Reason.INVALID_ELEMENT -> "a data object has a length or content that is not allowed"
                }
            val which =
                if (fileId == null) {
                    "A chip file"
                } else {
                    "The chip file with identifier ${fileId.toString(16).uppercase().padStart(4, '0')}"
                }
            return "$which cannot be read: $what"
        }

    /** `ChipMalformedFile: ` followed by [description]. */
    override fun toString(): String = "ChipMalformedFile: $description"
}
