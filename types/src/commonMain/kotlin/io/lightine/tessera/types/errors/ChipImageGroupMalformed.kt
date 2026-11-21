package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Content error: the bytes of a chip's image data group (DG2, DG5 or DG7) are not built the way
 * ICAO Doc 9303 Part 10 defines.
 *
 * The error says which group, what kind of fault, and the byte offset where it was found. It
 * never carries bytes from the file.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property group The group that was being read.
 * @property problem What is wrong with the bytes.
 * @property offset Where in the file's bytes the fault was found, or `null` when it is not a
 *   single place.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipImageGroupMalformed(
    val group: ChipImageGroup,
    val problem: ChipImageGroupProblem,
    val offset: Int?,
) : ChipError() {
    /** English text for logs: the group, the kind of fault and the offset. */
    override val description: String
        get() =
            "The $group file is malformed: $problem" +
                if (offset != null) " at offset $offset" else ""

    /** `ChipImageGroupMalformed: ` followed by [description]. */
    override fun toString(): String = "ChipImageGroupMalformed: $description"
}

/**
 * The image data groups of the chip's logical data structure.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipImageGroup {
    /** DG2: encoded identification features, face. */
    DG2,

    /** DG5: displayed portrait. */
    DG5,

    /** DG7: displayed signature or usual mark. */
    DG7,
}

/**
 * What is wrong with the bytes of an image data group.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipImageGroupProblem {
    /** A data object's declared length is larger than the bytes that remain inside its parent. */
    LENGTH_EXCEEDS_DATA,

    /** A length is not one this reader accepts: the indefinite form, or more than four length bytes. */
    INVALID_LENGTH,

    /** A tag is not valid: a forbidden first byte, too many bytes, or the bytes end inside it. */
    INVALID_TAG,

    /** Data objects are nested deeper than this reader follows. */
    NESTING_TOO_DEEP,

    /** The file does not start with the tag its group is defined to start with. */
    WRONG_FILE_TAG,

    /** A data object the group needs is absent. */
    MISSING_OBJECT,

    /** A data object is where the group does not define it, for example a primitive one where a constructed one is defined. */
    UNEXPECTED_STRUCTURE,
}
