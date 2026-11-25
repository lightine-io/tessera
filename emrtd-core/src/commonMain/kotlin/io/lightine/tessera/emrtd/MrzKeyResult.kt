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
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
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
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
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

/**
 * The three values a person typed, cleaned up by [ChipAccessKey.Mrz.normalizeTypedFields], and the
 * fields that cleaning changed.
 *
 * The values are the person's own input in a cleaned form: show them to the person before the read,
 * then pass them to [ChipAccessKey.Mrz.fromTypedFields]. `toString()` never shows them.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property documentNumber The document number with spaces removed at both ends and ASCII letters
 *   in upper case.
 * @property dateOfBirth The date of birth, cleaned the same way.
 * @property dateOfExpiry The date of expiry, cleaned the same way.
 * @property changedFields The fields whose value is not the same as typed: any of
 *   [MrzField.DOCUMENT_NUMBER], [MrzField.DATE_OF_BIRTH] and [MrzField.DATE_OF_EXPIRY]; empty when
 *   nothing needed cleaning.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NormalizedMrzFields internal constructor(
    public val documentNumber: String,
    public val dateOfBirth: String,
    public val dateOfExpiry: String,
    public val changedFields: Set<MrzField>,
) {
    /** `true` when cleaning changed at least one value. */
    public val wasChanged: Boolean
        get() = changedFields.isNotEmpty()

    /** The changed fields, never a value. */
    override fun toString(): String = "NormalizedMrzFields(changedFields=$changedFields)"
}
