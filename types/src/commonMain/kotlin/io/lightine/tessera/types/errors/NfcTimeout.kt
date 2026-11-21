package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Connection error: the chip did not answer a command within the transport's time limit.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property timeoutMillis The time limit the transport waited, in milliseconds, or `null` when the
 *   platform does not say.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class NfcTimeout(
    val timeoutMillis: Long?,
) : NfcError() {
    /** English text for logs: the time limit, when known. */
    override val description: String
        get() =
            if (timeoutMillis == null) {
                "The chip did not answer within the time limit"
            } else {
                "The chip did not answer within $timeoutMillis ms"
            }

    /** `NfcTimeout: ` followed by [description]. */
    override fun toString(): String = "NfcTimeout: $description"
}
