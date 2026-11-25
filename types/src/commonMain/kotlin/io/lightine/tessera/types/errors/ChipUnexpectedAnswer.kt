package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Protocol error: the chip answered in a way the reader cannot use: a status word no step
 * expects, an answer with no status word, or a file answer that cannot be put together.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property reason What was wrong with the answer.
 * @property statusWord The status word, when there is one.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public data class ChipUnexpectedAnswer(
    val reason: Reason,
    override val statusWord: Int? = null,
) : ChipError() {
    /**
     * What was wrong with the answer.
     *
     * May gain members in a later release: a `when` over it needs an `else` branch.
     *
     * @since 1.0.0
     */
    public enum class Reason {
        /** A status word the step does not handle. */
        UNEXPECTED_STATUS_WORD,

        /** An answer shorter than a status word (two bytes); seen on chips that return nothing. */
        NO_STATUS_WORD,

        /** A file answer that could not be put together: too long, empty before the end, or a command the reader could not send. */
        UNUSABLE_FILE_ANSWER,
    }

    /** English text for logs: the reason and the status word, when known. */
    override val description: String
        get() {
            val what =
                when (reason) {
                    Reason.UNEXPECTED_STATUS_WORD -> "a status word the step does not handle"
                    Reason.NO_STATUS_WORD -> "an answer without a status word"
                    Reason.UNUSABLE_FILE_ANSWER -> "a file answer that could not be put together"
                }
            val sw = statusWord?.let { " (status ${it.toString(16).uppercase().padStart(4, '0')})" } ?: ""
            return "The chip gave $what$sw"
        }

    /** A stable name of this error: see [ChipError.code]. */
    override val code: String
        get() = "unexpected-answer." + slug(reason.name)

    /** `ChipUnexpectedAnswer: ` followed by [description]. */
    override fun toString(): String = "ChipUnexpectedAnswer: $description"
}
