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
 * May gain members in a later release: a `when` over it needs an `else` branch.
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
}
