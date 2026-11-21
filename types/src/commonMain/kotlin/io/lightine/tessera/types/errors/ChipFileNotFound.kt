package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Content error: a file the reader asked for is not on the chip (status `6A82`), or the chip's
 * own lists (EF.COM, EF.SOD) do not name it, so it was not asked for.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fileId The two-byte file identifier (for example `0x0102` for EF.DG2).
 * @property listedByChip `true` when the chip's EF.COM or EF.SOD lists the file, or the reader had
 *   no list, and the chip answered "file not found"; `false` when the lists do not name the file
 *   and nothing was sent for it.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipFileNotFound(
    val fileId: Int,
    val listedByChip: Boolean,
) : ChipError() {
    /** English text for logs: the file identifier. */
    override val description: String
        get() {
            val id = fileId.toString(16).uppercase().padStart(4, '0')
            return if (listedByChip) {
                "The chip did not find the file with identifier $id"
            } else {
                "The chip does not list the file with identifier $id, so it was not read"
            }
        }

    /** `ChipFileNotFound: ` followed by [description]. */
    override fun toString(): String = "ChipFileNotFound: $description"
}
