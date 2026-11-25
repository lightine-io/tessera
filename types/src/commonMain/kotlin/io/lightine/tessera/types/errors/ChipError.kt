package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Root of the errors that stop reading a document's chip.
 *
 * [NfcError] holds the errors of the connection to the chip: the tag was lost, the chip did not
 * answer in time, or NFC cannot be used. Beside it are the errors of the chip protocol
 * ([ChipAccessDenied], [ChipSecureMessagingLost], [ChipUnsupportedAlgorithm], [ChipUnexpectedAnswer])
 * and of the chip's content ([ChipMalformedFile], [ChipFileNotFound], [ChipBlank]).
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipError {
    /**
     * English text for logs. It is not localized.
     *
     * The text never contains values from the document or from the chip: names, numbers, dates,
     * codes, keys, or file content. `toString()` of every subtype is its simple class name, a
     * colon, and this text.
     */
    public abstract val description: String

    /**
     * The status word the chip answered with, when this error carries one (for example `0x6300`
     * for a refused key), otherwise `null`. The same value as the `statusWord` of the subtypes that
     * have one, so an app reads it without a cast.
     *
     * @since 1.0.0
     */
    public open val statusWord: Int?
        get() = null

    /**
     * A stable, machine-readable name of this error, for logs, metrics and keys of your own texts:
     * lower-case words joined by hyphens, with a dot before the reason when the error has one (for
     * example `access-denied.key-not-matched`, `tag-lost`). A code never changes once released,
     * and it never contains a value from the document or the chip.
     *
     * @since 1.0.0
     */
    public abstract val code: String

    internal companion object {
        /** `KEY_NOT_MATCHED` as `key-not-matched`. */
        fun slug(name: String): String = name.lowercase().replace('_', '-')
    }
}
