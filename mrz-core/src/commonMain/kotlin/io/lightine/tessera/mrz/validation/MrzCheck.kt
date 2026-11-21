package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.vocabulary.MrzField

/**
 * One check the MRZ validator can run on a document, in the stable order the check records are
 * reported in.
 *
 * The entries cover every check digit a format can have (document number, date of birth, date of
 * expiry, optional data or personal number, composite), the calendar-date checks for the date of
 * birth and the date of expiry, and the sex character check.
 *
 * @property field The MRZ field the check covers.
 * @since 1.0.0
 */
public enum class MrzCheck(
    /** The MRZ field the check covers. */
    public val field: MrzField,
) {
    /** The document number check digit agrees with the one computed from the document number. */
    DOCUMENT_NUMBER_CHECK_DIGIT(MrzField.DOCUMENT_NUMBER),

    /** The date of birth check digit agrees with the one computed from the date of birth. */
    DATE_OF_BIRTH_CHECK_DIGIT(MrzField.DATE_OF_BIRTH),

    /** The date of expiry check digit agrees with the one computed from the date of expiry. */
    DATE_OF_EXPIRY_CHECK_DIGIT(MrzField.DATE_OF_EXPIRY),

    /** The optional data check digit (the TD3 personal number check digit) agrees with the one computed from the field. */
    OPTIONAL_DATA_CHECK_DIGIT(MrzField.OPTIONAL_DATA),

    /** The composite check digit agrees with the one computed from the composite input fields. */
    COMPOSITE_CHECK_DIGIT(MrzField.COMPOSITE),

    /** The date of birth parts form a real calendar date. */
    DATE_OF_BIRTH_CALENDAR_DATE(MrzField.DATE_OF_BIRTH),

    /** The date of expiry parts form a real calendar date. */
    DATE_OF_EXPIRY_CALENDAR_DATE(MrzField.DATE_OF_EXPIRY),

    /** The sex character is one of `M`, `F`, `<`, `X`. */
    SEX_CHARACTER(MrzField.SEX),
}
