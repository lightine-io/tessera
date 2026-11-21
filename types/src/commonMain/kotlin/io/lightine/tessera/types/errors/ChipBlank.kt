package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Content error: the chip answers but holds no eMRTD application.
 *
 * An issuer may leave a chip blank on purpose, for example when its signing infrastructure was not
 * available (ICAO Doc 9303 Part 10, section 4.5.4). A blank chip is a fact about the chip only: it
 * does not by itself make the document unusable, and the document's other security features still
 * apply (Part 11, section 2).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data object ChipBlank : ChipError() {
    /** English text for logs. */
    override val description: String
        get() = "The chip holds no eMRTD application; it may have been left blank by the issuer"

    /** `ChipBlank: ` followed by [description]. */
    override fun toString(): String = "ChipBlank: $description"
}
