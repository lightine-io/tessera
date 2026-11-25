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
     * May gain members in a later release: an exhaustive `when` over it then stops compiling until
     * the new member is handled. An `else` branch keeps it compiling without handling the new member:
     * that is your choice, and today the compiler warns that such an `else` is redundant.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /**
         * The device has no NFC reader, or its NFC reader cannot do what a chip read needs: on
         * Android, the tag cannot be reached as an ISO 14443-4 (ISO-DEP) chip on this device.
         */
        NOT_SUPPORTED,

        /** The device has an NFC reader, but it is turned off. */
        DISABLED,

        /**
         * The app may not use NFC. On iPhone (Core NFC's code 2, "security violation"): the app
         * lacks the NFC tag reading entitlement, or its Info.plist lacks
         * `NFCReaderUsageDescription` or the ISO 7816 select identifiers the read needs; fix the
         * app's setup. On Android: the platform refused reader mode with a `SecurityException`.
         */
        NOT_PERMITTED,

        /**
         * The link to the chip was closed, so no more commands can be sent: by the read's own
         * ending, by the app closing a transport it owns, or by the platform (a link it closed with
         * no cause it reports). A chip read that sees the platform close its link ends with
         * `ChipReadEnding.EndedBySystem`.
         */
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

        /**
         * The screen (the Android Activity) the read belongs to is finishing or destroyed: when the
         * read was started with it (the read does not start), or while the read ran, for example by
         * a configuration change such as a rotation, so reader mode ended with it. The app can start
         * a new read on the new screen, with a new key.
         *
         * @since 1.0.0
         */
        ACTIVITY_DESTROYED,

        /**
         * Android only: NFC is on, but the device's option for reading tags is turned off, so
         * reader mode finds no document. The person can turn it on in the system's NFC settings.
         * The platform tells this from Android 15 (API level 35) on; on earlier versions the SDK
         * cannot know it and never reports it.
         *
         * @since 1.0.0
         */
        TAG_READING_DISABLED,

        /**
         * A command was longer than the device's NFC link accepts (Android's "transceive length
         * exceeds" refusal, Core NFC's code 105 on iPhone), so it was not sent.
         *
         * @since 1.0.0
         */
        COMMAND_TOO_LONG,

        /**
         * iPhone only (Core NFC's code 7): the system setting or the hardware is not eligible for
         * reading tags, as the system decides, for example in some regions. The app cannot fix it
         * itself; tell the person that chip reading is not available on this device now.
         *
         * @since 1.0.0
         */
        NOT_ELIGIBLE,

        /**
         * iPhone only (Core NFC's code 8): the person has not yet accepted or declined the app's
         * request to use the NFC reader. The person answers the system's request, then the app
         * starts a new read.
         *
         * @since 1.0.0
         */
        ACCESS_NOT_ANSWERED,

        /**
         * Something the SDK did not expect went wrong in the platform's NFC code or in the SDK
         * itself, so the read stopped. Not about the document or the person: report it as a bug,
         * with the read's telemetry.
         *
         * @since 1.0.0
         */
        UNEXPECTED_FAULT,
    }

    /** English text for logs: the reason. */
    override val description: String
        get() =
            when (reason) {
                Reason.NOT_SUPPORTED -> "This device cannot read chips over NFC"
                Reason.DISABLED -> "NFC is turned off on this device"
                Reason.NOT_PERMITTED -> "The app may not use NFC on this device"
                Reason.CLOSED -> "The connection to the chip was closed"
                Reason.CANCELLED_BY_USER -> "The person ended the NFC reading"
                Reason.BUSY -> "The NFC reader is busy with another task"
                Reason.ENDED_BY_SYSTEM -> "The system ended the NFC session"
                Reason.REQUEST_REFUSED -> "The NFC reader refused the request"
                Reason.READ_IN_PROGRESS -> "Another chip read is already running in this app"
                Reason.ACTIVITY_DESTROYED -> "The screen of the chip read is finishing or was destroyed"
                Reason.TAG_READING_DISABLED -> "NFC is on, but reading tags is turned off on this device"
                Reason.COMMAND_TOO_LONG -> "A command was too long for this device's NFC link"
                Reason.NOT_ELIGIBLE -> "This device is not eligible to read tags now"
                Reason.ACCESS_NOT_ANSWERED -> "The person has not yet answered the app's request to use NFC"
                Reason.UNEXPECTED_FAULT -> "An unexpected fault stopped the chip read"
            }

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "unavailable." + slug(reason.name)

    /** `NfcUnavailable: ` followed by [description]. */
    override fun toString(): String = "NfcUnavailable: $description"
}
