package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.ImageGroupReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * DG5 (displayed portrait) or DG7 (displayed signature or usual mark), read down to the image
 * bytes (Doc 9303 Part 10, sections 4.7.5 and 4.7.7).
 *
 * The images are handed over untouched. Part 10 names the format owner for the group: ISO/IEC
 * 10918 with the JFIF option (JPEG), and for DG7 also ISO/IEC 15444 (JPEG 2000). The file itself
 * does not declare which one an image uses, and this SDK does not look inside the bytes to guess:
 * it decodes nothing.
 *
 * Each read of [images] returns new copies.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property declaredInstances The number the file's integer object (tag `02`, in the first
 *   template) says it holds, or `null` when absent or not one to four bytes. It is not checked
 *   against [images].
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class DisplayedImageGroup internal constructor(
    public val declaredInstances: Int?,
    images: List<ByteArray>,
) {
    private val imageBytes = images

    /** The image bytes in file order: the value of each `5F40` (DG5) or `5F43` (DG7) object. */
    public val images: List<ByteArray> get() = imageBytes.map { it.copyOf() }

    override fun toString(): String = "DisplayedImageGroup(images=${imageBytes.size})"

    public companion object {
        /**
         * Reads the content of EF.DG5: one or more `65` templates, each with the displayed
         * portrait in a `5F40` object.
         *
         * Bytes after the last template cannot be told from a template and are an error; use the
         * length the file declares to cut a padded read first.
         *
         * @param bytes the whole content of the file
         */
        public fun parseDg5(bytes: ByteArray): LdsParseResult<DisplayedImageGroup> = ImageGroupReader.readDisplayed(bytes, dg7 = false)

        /**
         * Reads the content of EF.DG7: one or more `67` templates, each with the displayed
         * signature or usual mark in a `5F43` object.
         *
         * @param bytes the whole content of the file
         */
        public fun parseDg7(bytes: ByteArray): LdsParseResult<DisplayedImageGroup> = ImageGroupReader.readDisplayed(bytes, dg7 = true)
    }
}
