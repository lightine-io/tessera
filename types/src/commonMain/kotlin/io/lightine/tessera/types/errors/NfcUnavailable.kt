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
     * May gain members in a later release: a `when` over it needs an `else` branch.
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

        /** The transport was closed by the app, so it cannot send any more commands. */
        CLOSED,

        /**
         * The person ended the reading, for example with the Cancel button of the system's NFC
         * sheet on iPhone.
         *
         * @since 1.0.0
         */
        CANCELLED_BY_USER,

        /**
         * The system's NFC reader is busy with something else for now, such as a payment, so it
         * cannot be used at this moment.
         *
         * The platform documents this as temporary: wait, then try again.
         *
         * @since 1.0.0
         */
        BUSY,

        /**
         * The system ended the NFC session on its own: not the app, and not the person.
         *
         * A new session started soon after may be refused for a short time. The SDK does not know
         * why the system ended the session.
         *
         * @since 1.0.0
         */
        ENDED_BY_SYSTEM,

        /**
         * The system's NFC reader refused a request from the SDK, such as a command or a session
         * setting it does not accept, so the request was not carried out.
         *
         * @since 1.0.0
         */
        REQUEST_REFUSED,

        /**
         * The app already has a chip read running, so a second one was not started. Only one read
         * at a time can use the NFC reader: wait for the running read's ending, then start again.
         *
         * @since 1.0.0
         */
        READ_IN_PROGRESS,
    }

    /** English text for logs: the reason. */
    override val description: String
        get() =
            when (reason) {
                Reason.NOT_SUPPORTED -> "This device has no NFC reader"
                Reason.DISABLED -> "NFC is turned off on this device"
                Reason.NOT_PERMITTED -> "The app may not use NFC on this device"
                Reason.CLOSED -> "The connection to the chip was closed by the app"
                Reason.CANCELLED_BY_USER -> "The person ended the NFC reading"
                Reason.BUSY -> "The NFC reader is busy with another task"
                Reason.ENDED_BY_SYSTEM -> "The system ended the NFC session"
                Reason.REQUEST_REFUSED -> "The NFC reader refused the request"
                Reason.READ_IN_PROGRESS -> "Another chip read is already running in this app"
            }

    /** `NfcUnavailable: ` followed by [description]. */
    override fun toString(): String = "NfcUnavailable: $description"
}
