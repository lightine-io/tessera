package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipImageGroupMalformed

/**
 * The outcome of reading the bytes of a chip's image data group: the view, or a typed error.
 * Reading never throws for bad chip bytes.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ImageGroupParse<out T> {
    /** The bytes were read into [value]. */
    public class Parsed<out T>(
        public val value: T,
    ) : ImageGroupParse<T>() {
        override fun toString(): String = "Parsed"
    }

    /** The bytes are not built the way Part 10 defines. */
    public class Malformed(
        public val error: ChipImageGroupMalformed,
    ) : ImageGroupParse<Nothing>() {
        override fun toString(): String = "Malformed: ${error.description}"
    }
}
