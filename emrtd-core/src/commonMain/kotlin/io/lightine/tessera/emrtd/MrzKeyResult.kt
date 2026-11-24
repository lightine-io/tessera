package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.MrzField

/**
 * What [ChipAccessKey.Mrz.from] and [ChipAccessKey.Mrz.fromTypedFields] give back: the key, or the
 * fact that no key could be made, and why.
 *
 * Nothing is thrown for a document that lacks a field or a value that is not usable: that is a
 * normal answer to show the person, not a bug. [Built.key] is always a usable key; whether its
 * check digits match its values is [ChipAccessKey.Mrz.checkDigitFindings], and the check-digit gate
 * of the read ([ChipReadOptions.allowCheckDigitMismatch]) still applies at read time.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class MrzKeyResult {
    /**
     * The key was made.
     *
     * @property key The key. The one who reads with it owns it; see [ChipAccessKey].
     * @since 1.0.0
     */
    public class Built internal constructor(
        public val key: ChipAccessKey.Mrz,
    ) : MrzKeyResult() {
        /** `Built`, without the key. */
        override fun toString(): String = "Built"
    }

    /**
     * No key could be made.
     *
     * @property field The field the problem is in, or `null` when it is about the document as a
     *   whole ([MrzKeyProblem.NO_DOCUMENT], [MrzKeyProblem.DOCUMENT_HAS_NO_CHIP]). Check digits
     *   are reported under the field they belong to.
     * @property problem What is wrong with it.
     * @since 1.0.0
     */
    public class NotBuilt internal constructor(
        public val field: MrzField?,
        public val problem: MrzKeyProblem,
    ) : MrzKeyResult() {
        /** The field and the problem, never a value. */
        override fun toString(): String = "NotBuilt(field=$field, problem=$problem)"
    }
}

/**
 * Why no MRZ key could be made ([MrzKeyResult.NotBuilt]). Each value is a fact about the input,
 * never a verdict on a document.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class MrzKeyProblem {
    /** The scan or parse gave no document, so there is nothing to make a key from. */
    NO_DOCUMENT,

    /** The document is a visa: its format has no chip. */
    DOCUMENT_HAS_NO_CHIP,

    /** The field is empty. */
    EMPTY,

    /** The field has the wrong number of characters. */
    WRONG_LENGTH,

    /** The field holds a character outside the allowed set: `A` to `Z`, `0` to `9` and `<`, and digits only for a typed date. */
    CHARACTER_NOT_ALLOWED,

    /**
     * The document number goes on in the optional data (its check-digit place holds `<`), but the
     * optional data does not hold the rest of the number and its check digit.
     */
    NUMBER_CONTINUATION_MISSING,
}
