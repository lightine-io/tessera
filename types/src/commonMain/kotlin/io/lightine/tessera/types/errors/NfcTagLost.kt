package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Connection error: the chip left the reader's field, or the link to it broke, before it answered.
 *
 * Usually the document moved away from the device.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data object NfcTagLost : NfcError() {
    /** English text for logs. */
    override val description: String
        get() = "The connection to the chip was lost before it answered"

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "tag-lost"

    /** `NfcTagLost: ` followed by [description]. */
    override fun toString(): String = "NfcTagLost: $description"
}
