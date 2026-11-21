package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

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
     * @param documentNumber The document number field as printed, at least one character.
     * @param documentNumberCheckDigit The check digit printed after the document number.
     * @param dateOfBirth The date of birth as printed, six characters (`YYMMDD`).
     * @param dateOfBirthCheckDigit The check digit printed after the date of birth.
     * @param dateOfExpiry The date of expiry as printed, six characters (`YYMMDD`).
     * @param dateOfExpiryCheckDigit The check digit printed after the date of expiry.
     * @throws IllegalArgumentException when a value is empty, has the wrong length, or holds a
     *   character outside the MRZ characters. The message names the parameter, never the value.
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

private fun isMrzCharacter(c: Char): Boolean = c in 'A'..'Z' || c in '0'..'9' || c == '<'

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
