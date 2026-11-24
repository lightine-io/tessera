package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol

/**
 * Protocol error: the chip and the reader have no algorithm in common for the access protocol, so
 * the chip cannot be opened.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property protocol The access protocol that needed the algorithm.
 * @property reason What was missing.
 * @property statusWord The status word the chip refused with, when there is one.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipUnsupportedAlgorithm(
    val protocol: ChipAccessProtocol,
    val reason: Reason,
    val statusWord: Int? = null,
) : ChipError() {
    /**
     * What was missing.
     *
     * May gain members in a later release: a `when` over it needs an `else` branch.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** The chip offers only suites the reader does not run, and no other way in. */
        NO_SUPPORTED_SUITE,

        /** The chip refused every suite the reader tried (`6A80` or `6A88` to MSE:Set AT). */
        CHIP_REFUSED_SUITE,

        /** The protocol's commands need extended-length APDUs, which the reader or the chip does not allow. */
        EXTENDED_LENGTH_REQUIRED,

        /** This platform has no cryptography for the protocol yet (iOS until its backend lands). */
        NO_CRYPTOGRAPHY_ON_PLATFORM,

        /**
         * The platform's cryptography could not carry out a step of the protocol with the values the
         * chip sent, for example a key the backend does not take.
         */
        CRYPTOGRAPHY_FAILED,
    }

    /** English text for logs: the protocol, the reason and the status word, when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.NO_SUPPORTED_SUITE -> "the chip offers no suite the reader runs"
                    Reason.CHIP_REFUSED_SUITE -> "the chip refused every suite the reader tried"
                    Reason.EXTENDED_LENGTH_REQUIRED -> "the commands need extended length, which is not allowed"
                    Reason.NO_CRYPTOGRAPHY_ON_PLATFORM -> "this platform has no cryptography for it yet"
                    Reason.CRYPTOGRAPHY_FAILED -> "the cryptography could not run a step with the chip's values"
                }
            val sw = statusWord?.let { " (status ${it.toString(16).uppercase().padStart(4, '0')})" } ?: ""
            return "No common algorithm for $protocol: $what$sw"
        }

    /** `ChipUnsupportedAlgorithm: ` followed by [description]. */
    override fun toString(): String = "ChipUnsupportedAlgorithm: $description"
}
