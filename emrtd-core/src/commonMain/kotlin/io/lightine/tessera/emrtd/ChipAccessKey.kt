package io.lightine.tessera.emrtd

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.MrzField
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
 * **Who clears it.** A key is for one person's action: one read, then it is cleared. The easy
 * entry points (`NfcChipRead`, `CoreNfcChipRead`) take the key in the options over from the start
 * and clear it at the ending of the read, whatever the ending is; a restart after a lost link
 * still has it until then. When you call [ChipReader.read] yourself, you own the key and clear it.
 * Never use one key for a second read: make a new one, so each attempt at the chip is a new
 * action of the person.
 *
 * To make an MRZ key from a scanned or parsed MRZ, use [Mrz.from]; from values a person typed,
 * [Mrz.fromTypedFields] (with [Mrz.normalizeTypedFields] before it, if your form should accept
 * lower-case letters and spaces around a value); from a card access number, [Can.from]. Every
 * builder answers with a result, so bad input is shown to the person, not thrown.
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
     * printed values, and the SDK never corrects them. From a parsed or scanned MRZ use
     * [Companion.from]; when a person types the values in, use [Companion.fromTypedFields], which
     * works out the check digits.
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

            /**
             * The fields whose printed check digit does not match the digit computed from the field
             * (ICAO Doc 9303 Part 3, section 4.9): any of [MrzField.DOCUMENT_NUMBER],
             * [MrzField.DATE_OF_BIRTH] and [MrzField.DATE_OF_EXPIRY]; empty when all three match.
             *
             * It names fields only, never their values. It is a finding, not a correction: the key
             * still holds the values as given.
             *
             * @since 1.0.0
             */
            public val checkDigitFindings: Set<MrzField>

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
                checkDigitFindings =
                    buildSet {
                        if (computeCheckDigit(documentNumber) != documentNumberCheckDigit) add(MrzField.DOCUMENT_NUMBER)
                        if (computeCheckDigit(dateOfBirth) != dateOfBirthCheckDigit) add(MrzField.DATE_OF_BIRTH)
                        if (computeCheckDigit(dateOfExpiry) != dateOfExpiryCheckDigit) add(MrzField.DATE_OF_EXPIRY)
                    }
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

                /**
                 * A key from a scanned or parsed MRZ, in one call: the document number, the date of
                 * birth and the date of expiry, each with its check digit, exactly as printed.
                 *
                 * It works for TD1, TD2 and TD3. For a TD1 or TD2 document whose number is longer
                 * than nine characters it joins the number from the optional data, as
                 * [fromPrintedFields] does (Doc 9303 Part 11, section 9.7.3). Nothing is corrected:
                 * a check digit that does not match its field stays as printed and shows in
                 * [checkDigitFindings], and the check-digit gate at read time still applies.
                 *
                 * A scanner's decoded result holds a [ParseResult]; pass that to the other overload,
                 * or its `document`.
                 *
                 * The key does not keep the document. To have the read compare the printed MRZ with
                 * the chip's DG1, pass the same document to
                 * [ChipReadOptions.Builder.printedDocument] yourself: the two calls are separate on
                 * purpose, so nothing is passed on that you did not choose.
                 *
                 * Nothing is thrown for a document that lacks a field: the answer is
                 * [MrzKeyResult.NotBuilt] with the field and the problem. A visa has no chip
                 * ([MrzKeyProblem.DOCUMENT_HAS_NO_CHIP]).
                 *
                 * @param document The document from `mrz-core`'s parser or the scanner.
                 * @return [MrzKeyResult.Built] with the key, or [MrzKeyResult.NotBuilt].
                 * @since 1.0.0
                 */
                @JvmStatic
                public fun from(document: MrzDocument): MrzKeyResult {
                    val optionalData =
                        when (document) {
                            is TD1 -> document.optionalData1
                            is TD2 -> document.optionalData
                            is TD3 -> ""
                            is MrvA, is MrvB -> return MrzKeyResult.NotBuilt(null, MrzKeyProblem.DOCUMENT_HAS_NO_CHIP)
                        }
                    val c = document.commonFields
                    val number = c.documentNumber
                    val numberCheck = c.checkDigits.documentNumber
                    val dob = c.dateOfBirth.rawYear + c.dateOfBirth.rawMonth + c.dateOfBirth.rawDay
                    val doe = c.dateOfExpiry.rawYear + c.dateOfExpiry.rawMonth + c.dateOfExpiry.rawDay
                    val continues = numberCheck == FILLER
                    val problem =
                        problemOfValue(number, MrzField.DOCUMENT_NUMBER, DOCUMENT_NUMBER_FIELD_LENGTH)
                            ?: problemOfCharacter(numberCheck, MrzField.DOCUMENT_NUMBER)
                            ?: problemOfValue(dob, MrzField.DATE_OF_BIRTH, DATE_LENGTH)
                            ?: problemOfCharacter(c.checkDigits.dateOfBirth, MrzField.DATE_OF_BIRTH)
                            ?: problemOfValue(doe, MrzField.DATE_OF_EXPIRY, DATE_LENGTH)
                            ?: problemOfCharacter(c.checkDigits.dateOfExpiry, MrzField.DATE_OF_EXPIRY)
                            // The optional data is read only when the number continues in it.
                            ?: if (continues) problemOfValue(optionalData, MrzField.OPTIONAL_DATA, null, allowEmpty = true) else null
                    if (problem != null) return problem
                    return try {
                        MrzKeyResult.Built(
                            fromPrintedFields(
                                number,
                                numberCheck,
                                if (continues) optionalData else "",
                                dob,
                                c.checkDigits.dateOfBirth,
                                doe,
                                c.checkDigits.dateOfExpiry,
                            ),
                        )
                    } catch (e: IllegalArgumentException) {
                        // Every other value was checked above: what is left is the missing continuation.
                        MrzKeyResult.NotBuilt(MrzField.DOCUMENT_NUMBER, MrzKeyProblem.NUMBER_CONTINUATION_MISSING)
                    }
                }

                /**
                 * A key from a parse result, as a scanner returns it (`MrzScanResult.Decoded.parse`):
                 * the same as [from] on the document of a success or a partial success.
                 *
                 * @param result The parse result.
                 * @return [MrzKeyResult.Built], or [MrzKeyResult.NotBuilt] with
                 *   [MrzKeyProblem.NO_DOCUMENT] when the parse failed and holds no document.
                 * @since 1.0.0
                 */
                @JvmStatic
                public fun from(result: ParseResult): MrzKeyResult =
                    when (result) {
                        is ParseResult.Success -> from(result.document)
                        is ParseResult.PartialSuccess -> from(result.document)
                        is ParseResult.Failure -> MrzKeyResult.NotBuilt(null, MrzKeyProblem.NO_DOCUMENT)
                    }

                /**
                 * Cleans up the three values a person typed, in the two ways a keyboard makes
                 * likely, and reports what it changed. It is separate from [fromTypedFields] so
                 * that the person always sees the change: show [NormalizedMrzFields.documentNumber],
                 * [NormalizedMrzFields.dateOfBirth] and [NormalizedMrzFields.dateOfExpiry] (and
                 * [NormalizedMrzFields.changedFields]) and let the person confirm, then pass them
                 * to [fromTypedFields].
                 *
                 * It does exactly two things, per value: removes spaces (`' '`) at the start and at
                 * the end, and turns the ASCII letters `a` to `z` into `A` to `Z`. Nothing else is
                 * changed: a space inside a value, a letter outside `A` to `Z`, a digit or a `<`
                 * stays as typed, so [fromTypedFields] still refuses it and says why. It never
                 * guesses a letter for a digit or the other way round.
                 *
                 * It never throws and never checks the values.
                 *
                 * @param documentNumber The document number as typed.
                 * @param dateOfBirth The date of birth as typed.
                 * @param dateOfExpiry The date of expiry as typed.
                 * @return The cleaned values and the fields that were changed.
                 * @since 1.0.0
                 */
                @JvmStatic
                public fun normalizeTypedFields(
                    documentNumber: String,
                    dateOfBirth: String,
                    dateOfExpiry: String,
                ): NormalizedMrzFields {
                    val number = normalizeTyped(documentNumber)
                    val birth = normalizeTyped(dateOfBirth)
                    val expiry = normalizeTyped(dateOfExpiry)
                    val changed =
                        buildSet {
                            if (number != documentNumber) add(MrzField.DOCUMENT_NUMBER)
                            if (birth != dateOfBirth) add(MrzField.DATE_OF_BIRTH)
                            if (expiry != dateOfExpiry) add(MrzField.DATE_OF_EXPIRY)
                        }
                    return NormalizedMrzFields(number, birth, expiry, changed)
                }

                /**
                 * A key from the three values as a person types them, with the check digits worked
                 * out by the SDK: the document number (up to nine characters or a longer complete
                 * number), the date of birth and the date of expiry (`YYMMDD`).
                 *
                 * The document number is padded at its end with `<` to nine characters, as the MRZ
                 * prints it; a longer number is kept whole. The three check digits are computed with
                 * `computeCheckDigit`. Nothing else is changed: letters must be upper case, with no
                 * spaces around the value, and a date is six digits, with no check that it is a real
                 * calendar date. This call is strict on purpose: it never alters what the person
                 * typed in a way they cannot see. When your form should accept lower-case letters
                 * and spaces around a value, call [normalizeTypedFields] first: it says which
                 * fields it changed, so you can show the person the result before the read.
                 *
                 * **A typed value has no printed check digit to compare.** A typing mistake still
                 * gives a key whose digits are right for the mistaken value, so the read sends it
                 * to the chip as a wrong key: one authentication attempt per call, as always. Show
                 * the person what they typed, and let them confirm, before you start the read. When
                 * the document is at hand, prefer the camera and [from].
                 *
                 * Nothing is thrown: a value that cannot be used is [MrzKeyResult.NotBuilt] with the
                 * field and the problem.
                 *
                 * @param documentNumber The document number, at least one character: `A` to `Z`, `0`
                 *   to `9` and `<`.
                 * @param dateOfBirth The date of birth, six digits (`YYMMDD`).
                 * @param dateOfExpiry The date of expiry, six digits (`YYMMDD`).
                 * @return [MrzKeyResult.Built] with the key, or [MrzKeyResult.NotBuilt].
                 * @since 1.0.0
                 */
                @JvmStatic
                public fun fromTypedFields(
                    documentNumber: String,
                    dateOfBirth: String,
                    dateOfExpiry: String,
                ): MrzKeyResult {
                    val problem =
                        problemOfValue(documentNumber, MrzField.DOCUMENT_NUMBER, null)
                            ?: problemOfValue(dateOfBirth, MrzField.DATE_OF_BIRTH, DATE_LENGTH, digitsOnly = true)
                            ?: problemOfValue(dateOfExpiry, MrzField.DATE_OF_EXPIRY, DATE_LENGTH, digitsOnly = true)
                    if (problem != null) return problem
                    val number = documentNumber.padEnd(DOCUMENT_NUMBER_FIELD_LENGTH, FILLER)
                    return MrzKeyResult.Built(
                        Mrz(
                            number,
                            computeCheckDigit(number),
                            dateOfBirth,
                            computeCheckDigit(dateOfBirth),
                            dateOfExpiry,
                            computeCheckDigit(dateOfExpiry),
                        ),
                    )
                }
            }
        }

    /**
     * A key from the card access number (CAN): the short number printed on the document for chip
     * access, often six digits. Use it when the document has one and the MRZ is not at hand, or
     * when a chip asks for it.
     *
     * **The valid form.** Digits `0` to `9` only, at least one, exactly as printed on the document,
     * with no spaces or other characters. ICAO Doc 9303 Part 11 lists the CAN among the passwords
     * of PACE and has it printed or displayed on the document; the SDK uses its digits as ASCII
     * characters for the PACE key. The SDK sets no upper limit on the length, because it cannot know
     * how long a number each issuer prints. A CAN has no check digit, so a mistyped CAN is not
     * noticed before the read: it costs the chip a try, as a wrong MRZ value does.
     *
     * The constructor throws for a value that is not usable; [from] gives the same answer as a
     * result, the same way the MRZ key builders do, so one piece of code can handle both kinds of
     * key.
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

            /** Builds a CAN key without an exception. */
            public companion object {
                /**
                 * A key from the card access number, answering with a result instead of throwing:
                 * the same rules as the [Can] constructor, in the same style as
                 * [Mrz.Companion.from] and [Mrz.Companion.fromTypedFields].
                 *
                 * Nothing is trimmed or corrected. Pass the digits exactly as printed.
                 *
                 * @param can The card access number as printed: digits only, at least one.
                 * @return [CanKeyResult.Built] with the key, or [CanKeyResult.NotBuilt] with the
                 *   problem ([CanKeyProblem.EMPTY], or [CanKeyProblem.CHARACTER_NOT_ALLOWED] with
                 *   the index of the first character that is not a digit).
                 * @since 1.0.0
                 */
                @JvmStatic
                public fun from(can: String): CanKeyResult {
                    if (can.isEmpty()) return CanKeyResult.NotBuilt(CanKeyProblem.EMPTY, null)
                    val bad = can.indexOfFirst { it !in '0'..'9' }
                    if (bad >= 0) return CanKeyResult.NotBuilt(CanKeyProblem.CHARACTER_NOT_ALLOWED, bad)
                    return CanKeyResult.Built(Can(can))
                }
            }
        }
}

/** The typed value with spaces removed at both ends and the ASCII letters `a` to `z` in upper case; nothing else. */
private fun normalizeTyped(value: String): String =
    buildString {
        for (c in value.trim(' ')) append(if (c in 'a'..'z') c - ('a' - 'A') else c)
    }

private const val FILLER = '<'

private const val DOCUMENT_NUMBER_FIELD_LENGTH = 9

private const val DATE_LENGTH = 6

/** The problem with [value] as the whole of [field], or `null` when it is usable. Never holds the value. */
private fun problemOfValue(
    value: String,
    field: MrzField,
    length: Int?,
    digitsOnly: Boolean = false,
    allowEmpty: Boolean = false,
): MrzKeyResult.NotBuilt? =
    when {
        value.isEmpty() && !allowEmpty -> MrzKeyResult.NotBuilt(field, MrzKeyProblem.EMPTY)
        length != null && value.length != length -> MrzKeyResult.NotBuilt(field, MrzKeyProblem.WRONG_LENGTH)
        digitsOnly && !value.all { it in '0'..'9' } -> MrzKeyResult.NotBuilt(field, MrzKeyProblem.CHARACTER_NOT_ALLOWED)
        !value.all(::isMrzCharacter) -> MrzKeyResult.NotBuilt(field, MrzKeyProblem.CHARACTER_NOT_ALLOWED)
        else -> null
    }

/** The problem with the check digit [c] of [field], or `null` when it is an MRZ character. */
private fun problemOfCharacter(
    c: Char,
    field: MrzField,
): MrzKeyResult.NotBuilt? = if (isMrzCharacter(c)) null else MrzKeyResult.NotBuilt(field, MrzKeyProblem.CHARACTER_NOT_ALLOWED)

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
