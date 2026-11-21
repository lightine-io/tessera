package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Connection error: NFC cannot be used on this device right now, so no chip can be reached.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property reason Why NFC cannot be used.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class NfcUnavailable(
    val reason: Reason,
) : NfcError() {
    /**
     * Why NFC cannot be used.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** The device has no NFC reader. */
        NOT_SUPPORTED,

        /** The device has an NFC reader, but it is turned off. */
        DISABLED,

        /** The app may not use NFC, for example because a permission or an entitlement is missing. */
        NOT_PERMITTED,
    }

    /** English text for logs: the reason. */
    override val description: String
        get() =
            when (reason) {
                Reason.NOT_SUPPORTED -> "This device has no NFC reader"
                Reason.DISABLED -> "NFC is turned off on this device"
                Reason.NOT_PERMITTED -> "The app may not use NFC on this device"
            }

    /** `NfcUnavailable: ` followed by [description]. */
    override fun toString(): String = "NfcUnavailable: $description"
}
