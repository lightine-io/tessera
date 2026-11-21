package io.lightine.tessera.mrz.camera

/**
 * The text an OCR engine recognized in one frame, as lines in reading order.
 *
 * It is raw OCR output, not an MRZ: lines can be in any case, hold non-MRZ characters, or carry
 * stray spaces. It can hold MRZ text, so do not log it verbatim. `toString()` on it and on
 * [RecognizedLine] shows no document values; they are in the properties.
 *
 * @since 0.2.0
 */
public data class RecognizedText(
    /** Every line the engine recognized, in reading order. Empty when it found no text. */
    public val lines: List<RecognizedLine>,
) {
    /** How many lines were recognized; never their text. */
    override fun toString(): String = "RecognizedText(lines=${lines.size})"
}

/**
 * One line of recognized text, with its OCR confidence when reported.
 *
 * `toString()` shows no document values; they are in the properties.
 *
 * @since 0.2.0
 */
public data class RecognizedLine(
    /** The recognized characters of this line, exactly as the engine reported them. */
    public val text: String,
    /** OCR confidence in `[0, 1]`; `null` when the engine reports none. */
    public val confidence: Float?,
) {
    /** How long the line is and the OCR confidence; never the text itself. */
    override fun toString(): String = "RecognizedLine(length=${text.length}, confidence=$confidence)"
}
