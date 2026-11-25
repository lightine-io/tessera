package io.lightine.tessera.types.vocabulary

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * How a reader gained access to a document's chip (ICAO Doc 9303 Part 11, section 4).
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
public enum class ChipAccessProtocol {
    /** No access protocol: the chip answers without one (a "plain" chip, Part 11, section 4.1, Note 1). */
    NONE,

    /** Basic Access Control (Part 11, section 4.3). */
    BAC,

    /** Password Authenticated Connection Establishment (Part 11, section 4.4). */
    PACE,
}
