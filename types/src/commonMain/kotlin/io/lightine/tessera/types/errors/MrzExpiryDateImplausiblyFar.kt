package io.lightine.tessera.types.errors

import kotlinx.datetime.LocalDate

/**
 * Warning: the document's [expiryDate] is more than [thresholdYears] after [referenceDate].
 *
 * Emitted by [`MrzValidator`][io.lightine.tessera.mrz.validation.MrzValidator]. The
 * threshold is currently 10 years; documents with longer validity periods exist but are
 * uncommon, and a far-future expiry is often a sign of transcription error.
 *
 * A warning rather than a validation failure because the SDK does not commit to "this
 * document is invalid" based on expiry plausibility alone (Principle 1 — reader, not
 * oracle). Configurable thresholds are a deferred enhancement; see
 * the project's issue tracker.
 */
public data class MrzExpiryDateImplausiblyFar(
    val expiryDate: LocalDate,
    val referenceDate: LocalDate,
    val thresholdYears: Int,
) : MrzWarning() {
    override val description: String
        get() = "Document expiry date $expiryDate is more than $thresholdYears years after reference date $referenceDate"
}
