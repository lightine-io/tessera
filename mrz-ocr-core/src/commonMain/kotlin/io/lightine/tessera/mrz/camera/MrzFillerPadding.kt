package io.lightine.tessera.mrz.camera

/**
 * Filler characters `<` that the reader added at the end of a name line that was read short.
 *
 * Text readers often shorten a long run of the `<` filler, so the name line of an MRZ comes back
 * shorter than its fixed width. ICAO Doc 9303 Part 3 fills every position after the name with `<`
 * up to the end of the line, so the reader adds `<` at the end until the line has its full width,
 * and reports it here. The added characters were not seen by the camera: this value is how the app
 * learns that the line was not read whole.
 *
 * The reader pads only when all of these hold:
 * - the MRZ has two lines (TD2, TD3, MRV-A or MRV-B), so the name line is line 1;
 * - line 2 was read at exactly the format's width, and both lines hold only MRZ characters;
 * - at least one check digit on line 2 (document number, birth date or expiry date) agrees with its
 *   field, so line 2 is a data line and not a stray line of text;
 * - line 1 is shorter than line 2, holds at least the document code, the issuing state and one
 *   name character, and its last character as read is the filler `<`.
 *
 * The last rule is the limit: the read itself must show that the name had ended and the filler had
 * begun, so the added `<` all go after the last name character that was read. A line that stops on a
 * letter or a digit is never padded, because its end could be part of the name. Line 2, the lines of
 * a TD1 card, and a line longer than its width are never changed. A line read at full width is never
 * changed either, and padding is tried only when no MRZ of exact width was found.
 *
 * Padding cannot tell where the text reader lost the characters. A `<<` between two parts of the
 * name that was read as one `<` looks the same as a short filler run at the end, so a padded name
 * can be split into its parts in a different place than the document shows. No check digit covers
 * the name, so nothing in the MRZ can confirm it. The raw text on the result is unchanged.
 *
 * @property lineIndex the zero-based index of the padded line; always 0, the name line.
 * @property recognizedLength how many characters the line had as read, after case folding and
 *   chevron recovery.
 * @property paddedLength the line's length after padding: the format's line width.
 * @since 1.0.0
 */
public data class MrzFillerPadding(
    public val lineIndex: Int,
    public val recognizedLength: Int,
    public val paddedLength: Int,
) {
    /** How many `<` the reader added at the end of the line. */
    public val addedCount: Int get() = paddedLength - recognizedLength
}
