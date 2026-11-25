package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Connection error: a time limit passed. [limit] says which one, as a fact: the limit on one
 * command, the app's own wait for a document, or the system's limit on one NFC session.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property timeoutMillis The time limit that passed, in milliseconds, or `null` when the platform
 *   does not say.
 * @property limit Which time limit passed.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class NfcTimeout(
    val timeoutMillis: Long?,
    val limit: Limit,
) : NfcError() {
    /**
     * Which time limit passed.
     *
     * May gain members in a later release: an exhaustive `when` over it then stops compiling until
     * the new member is handled. An `else` branch keeps it compiling without handling the new member:
     * that is your choice, and today the compiler warns that such an `else` is redundant.
     *
     * @since 1.0.0
     */
    public enum class Limit {
        /**
         * The chip did not answer one command within the time limit for that command: the SDK's
         * own limit, or on iPhone the system's retries for one command (Core NFC's code 101, with
         * no time given). The link is treated as lost.
         */
        COMMAND,

        /**
         * No document was found within the app's own wait for one
         * (`NfcReadSettings.waitForDocumentMillis` on Android). The app set this limit: not the
         * system, and not the person.
         */
        WAIT_FOR_DOCUMENT,

        /**
         * The system's time limit on one NFC session passed (Core NFC's code 201 on iPhone, with no
         * time given). The system ended the session.
         */
        SESSION,
    }

    /** English text for logs: which limit passed, and its time when known. */
    override val description: String
        get() {
            val within = if (timeoutMillis == null) "" else " ($timeoutMillis ms)"
            return when (limit) {
                Limit.COMMAND -> "The chip did not answer a command within its time limit$within"
                Limit.WAIT_FOR_DOCUMENT -> "No document was found within the app's wait for one$within"
                Limit.SESSION -> "The system's time limit for the NFC session passed$within"
            }
        }

    /**
     * A stable name of this error: see [ChipError.code]. `timeout.command`,
     * `timeout.wait-for-document` or `timeout.session`.
     */
    override val code: String
        get() = "timeout." + slug(limit.name)

    /** `NfcTimeout: ` followed by [description]. */
    override fun toString(): String = "NfcTimeout: $description"
}
