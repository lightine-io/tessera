package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * What [ChipAccessKey.Can.from] gives back: the key, or the fact that no key could be made, and
 * why. It has the same shape as [MrzKeyResult], so an app handles both kinds of key the same way:
 * show the problem to the person, or take the key.
 *
 * Nothing is thrown for a number that is not usable: that is a normal answer to show the person,
 * not a bug.
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
public sealed class CanKeyResult {
    /**
     * The key was made.
     *
     * @property key The key. The one who reads with it owns it; see [ChipAccessKey].
     * @since 1.0.0
     */
    public class Built internal constructor(
        public val key: ChipAccessKey.Can,
    ) : CanKeyResult() {
        /** `Built`, without the key. */
        override fun toString(): String = "Built"
    }

    /**
     * No key could be made.
     *
     * @property problem What is wrong with the number.
     * @property characterIndex For [CanKeyProblem.CHARACTER_NOT_ALLOWED], the index of the first
     *   character that is not a digit, counted from 0; `null` otherwise. Never the character.
     * @since 1.0.0
     */
    public class NotBuilt internal constructor(
        public val problem: CanKeyProblem,
        public val characterIndex: Int?,
    ) : CanKeyResult() {
        /** The problem and the index, never a value. */
        override fun toString(): String = "NotBuilt(problem=$problem, characterIndex=$characterIndex)"
    }
}

/**
 * Why no CAN key could be made ([CanKeyResult.NotBuilt]). Each value is a fact about the input,
 * never a verdict on a document.
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
public enum class CanKeyProblem {
    /** The number is empty. */
    EMPTY,

    /** The number holds a character other than the digits `0` to `9`, a space included. */
    CHARACTER_NOT_ALLOWED,
}
