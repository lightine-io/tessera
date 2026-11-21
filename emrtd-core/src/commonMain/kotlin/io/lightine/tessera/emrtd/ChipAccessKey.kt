package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.jvm.JvmStatic

/**
 * What opens a document's chip: values from its MRZ, or its card access number (CAN).
 *
 * The chip only answers a reader that proves it has seen the document. Both kinds of key are
 * read from the document itself, by the camera or typed in by a person.
 *
 * The key holds its values as bytes, which [clear] overwrites with zeros once the read is done. Its
 * `toString()` never shows them. Two keys are equal only when they are the same object.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipAccessKey {
    // The bytes the access protocols derive their keys from. Internal: never part of the API.
    internal abstract val secret: ByteArray

    private var cleared = false

    /** `true` after [clear]: the key can no longer open a chip. */
    public val isCleared: Boolean
        get() = cleared

    /**
     * Overwrites the stored values with zeros. A cleared key can no longer open a chip.
     *
     * The strings the key was made from are not affected: the platform keeps them until it frees
     * them.
     */
    public fun clear() {
        secret.fill(0)
        cleared = true
    }

    // A copy of the secret bytes for one protocol run; the caller wipes the copy.
    internal fun secretCopy(): ByteArray {
        check(!cleared) { "The chip access key was cleared" }
        return secret.copyOf()
    }

    /**
     * A key from the MRZ: the document number, the date of birth and the date of expiry, each with
     * its check digit.
     *
     * Give each value exactly as it is printed in the MRZ, fillers (`<`) included, and the check
     * digits as printed, even when one does not match its field: the chip was set up with the
     * printed values, and the SDK never corrects them. When a person types the values in, the app
     * can compute a check digit with `computeCheckDigit` from `mrz-core`.
     *
     * Every value may hold only the MRZ characters `A` to `Z`, `0` to `9` and `<`.
     *
     * The document number is always the complete number (Doc 9303 Part 11, section 9.7.3). On a
     * TD1 or TD2 document whose number is longer than nine characters, the number field holds only
     * its first nine, the check-digit place holds a filler (`<`), and the rest of the number and its
     * check digit sit at the start of the optional data. Give such a number whole, with its real
     * check digit, or let [fromPrintedFields] join it from the printed fields.
     *
     * @param documentNumber The complete document number as printed, at least one character: the
     *   nine-character field with its fillers, or a longer number joined from the optional data.
     * @param documentNumberCheckDigit The check digit of the document number, as printed.
     * @param dateOfBirth The date of birth as printed, six characters (`YYMMDD`).
     * @param dateOfBirthCheckDigit The check digit printed after the date of birth.
     * @param dateOfExpiry The date of expiry as printed, six characters (`YYMMDD`).
     * @param dateOfExpiryCheckDigit The check digit printed after the date of expiry.
     * @throws IllegalArgumentException when a value is empty, has the wrong length, or holds a
     *   character outside the MRZ characters, or when [documentNumberCheckDigit] is `<`: that filler
     *   marks a number that goes on in the optional data, so the number given is not complete. The
     *   message names the parameter, never the value.
     * @since 1.0.0
     */
    @ExperimentalTesseraChipApi
    public class Mrz
        @Throws(IllegalArgumentException::class)
        constructor(
            documentNumber: String,
            documentNumberCheckDigit: Char,
            dateOfBirth: String,
            dateOfBirthCheckDigit: Char,
            dateOfExpiry: String,
            dateOfExpiryCheckDigit: Char,
        ) : ChipAccessKey() {
            override val secret: ByteArray

            init {
                require(documentNumber.isNotEmpty()) { "documentNumber is empty" }
                requireMrzCharacters(documentNumber, "documentNumber")
                requireDate(dateOfBirth, "dateOfBirth")
                requireDate(dateOfExpiry, "dateOfExpiry")
                requireMrzCharacter(documentNumberCheckDigit, "documentNumberCheckDigit")
                require(documentNumberCheckDigit != FILLER) {
                    "documentNumberCheckDigit is a filler: the document number goes on in the optional data; " +
                        "give the complete number or use fromPrintedFields"
                }
                requireMrzCharacter(dateOfBirthCheckDigit, "dateOfBirthCheckDigit")
                requireMrzCharacter(dateOfExpiryCheckDigit, "dateOfExpiryCheckDigit")
                // The MRZ information of Doc 9303 Part 11, section 4.3.2: each field followed by its
                // check digit, in this order. All characters are ASCII, checked above.
                val text =
                    documentNumber + documentNumberCheckDigit + dateOfBirth + dateOfBirthCheckDigit +
                        dateOfExpiry + dateOfExpiryCheckDigit
                secret = ByteArray(text.length) { text[it].code.toByte() }
            }

            /** `ChipAccessKey.Mrz`, without the values. */
            override fun toString(): String = "ChipAccessKey.Mrz"

            /** Builds an MRZ key from the fields as printed. */
            public companion object {
                /**
                 * A key from the fields as printed in a TD1 or TD2 MRZ, where a document number
                 * longer than nine characters continues in the optional data.
                 *
                 * Doc 9303 Part 5, section 4.2.2, note j (TD1) and Part 6, section 4.2.2, note j
                 * (TD2): such a number shows its first nine characters in the number field and a
                 * filler (`<`) in the check-digit place; the rest of the number, its check digit and
                 * a filler start the optional data. Part 11, section 9.7.3: the key uses the complete number, joined
                 * without that filler.
                 *
                 * When [documentNumberCheckDigit] is a digit, the number is complete as printed,
                 * [optionalData] is not used, and the result is the same as the [Mrz] constructor.
                 * When it is `<`, the number is [documentNumberField] followed by the characters of
                 * [optionalData] up to its first filler, less the last of them, which is the check
                 * digit. Nothing is corrected: every character is used as printed.
                 *
                 * For a TD3 passport, whose number field has no such extension, use the
                 * constructor.
                 *
                 * @param documentNumberField The nine-character document number field as printed.
                 * @param documentNumberCheckDigit The character printed after the number field: a
                 *   check digit, or `<` for a longer number.
                 * @param optionalData The optional data field as printed: on TD1 the 15 characters
                 *   of the upper line after the check-digit place, on TD2 the 7 characters before
                 *   the composite check digit.
                 * @param dateOfBirth The date of birth as printed, six characters (`YYMMDD`).
                 * @param dateOfBirthCheckDigit The check digit printed after the date of birth.
                 * @param dateOfExpiry The date of expiry as printed, six characters (`YYMMDD`).
                 * @param dateOfExpiryCheckDigit The check digit printed after the date of expiry.
                 * @throws IllegalArgumentException when a value has the wrong length or holds a
                 *   character outside the MRZ characters, or when the number field says the number
                 *   continues but the optional data does not start with at least one character and
                 *   a check digit before a filler. The message names the parameter, never the value.
                 * @since 1.0.0
                 */
                @JvmStatic
                @Throws(IllegalArgumentException::class)
                public fun fromPrintedFields(
                    documentNumberField: String,
                    documentNumberCheckDigit: Char,
                    optionalData: String,
                    dateOfBirth: String,
                    dateOfBirthCheckDigit: Char,
                    dateOfExpiry: String,
                    dateOfExpiryCheckDigit: Char,
                ): Mrz {
                    require(documentNumberField.length == DOCUMENT_NUMBER_FIELD_LENGTH) {
                        "documentNumberField must have $DOCUMENT_NUMBER_FIELD_LENGTH characters, has ${documentNumberField.length}"
                    }
                    requireMrzCharacters(documentNumberField, "documentNumberField")
                    requireMrzCharacters(optionalData, "optionalData")
                    if (documentNumberCheckDigit != FILLER) {
                        return Mrz(
                            documentNumberField,
                            documentNumberCheckDigit,
                            dateOfBirth,
                            dateOfBirthCheckDigit,
                            dateOfExpiry,
                            dateOfExpiryCheckDigit,
                        )
                    }
                    val end = optionalData.indexOf(FILLER).let { if (it < 0) optionalData.length else it }
                    require(end >= 2) {
                        "optionalData does not hold the rest of the document number and its check digit"
                    }
                    return Mrz(
                        documentNumberField + optionalData.substring(0, end - 1),
                        optionalData[end - 1],
                        dateOfBirth,
                        dateOfBirthCheckDigit,
                        dateOfExpiry,
                        dateOfExpiryCheckDigit,
                    )
                }
            }
        }

    /**
     * A key from the card access number (CAN): the short number printed on the document for chip
     * access, often six digits.
     *
     * @param can The card access number as printed: digits only, at least one.
     * @throws IllegalArgumentException when [can] is empty or holds a character other than `0` to
     *   `9`. The message never contains the value.
     * @since 1.0.0
     */
    @ExperimentalTesseraChipApi
    public class Can
        @Throws(IllegalArgumentException::class)
        constructor(
            can: String,
        ) : ChipAccessKey() {
            override val secret: ByteArray

            init {
                require(can.isNotEmpty()) { "can is empty" }
                can.forEachIndexed { i, c -> require(c in '0'..'9') { "can holds a non-digit at index $i" } }
                secret = ByteArray(can.length) { can[it].code.toByte() }
            }

            /** `ChipAccessKey.Can`, without the value. */
            override fun toString(): String = "ChipAccessKey.Can"
        }
}

private const val FILLER = '<'

private const val DOCUMENT_NUMBER_FIELD_LENGTH = 9

private fun isMrzCharacter(c: Char): Boolean = c in 'A'..'Z' || c in '0'..'9' || c == FILLER

private fun requireMrzCharacter(
    c: Char,
    name: String,
) {
    require(isMrzCharacter(c)) { "$name is not an MRZ character" }
}

private fun requireMrzCharacters(
    value: String,
    name: String,
) {
    value.forEachIndexed { i, c -> require(isMrzCharacter(c)) { "$name holds a character outside the MRZ characters at index $i" } }
}

private fun requireDate(
    value: String,
    name: String,
) {
    require(value.length == 6) { "$name must have 6 characters, has ${value.length}" }
    requireMrzCharacters(value, name)
}
