package io.lightine.tessera.mrz.camera

/**
 * The text an OCR engine recognized in one frame, as lines in reading order.
 *
 * It is raw OCR output, not an MRZ: lines can be in any case, hold non-MRZ characters, or carry
 * stray spaces. It can hold MRZ text, so do not log it verbatim.
 *
 * @since 0.2.0
 */
public data class RecognizedText(
    /** Every line the engine recognized, in reading order. Empty when it found no text. */
    public val lines: List<RecognizedLine>,
)

/**
 * One line of recognized text, with its OCR confidence when reported.
 *
 * @since 0.2.0
 */
public data class RecognizedLine(
    /** The recognized characters of this line, exactly as the engine reported them. */
    public val text: String,
    /** OCR confidence in `[0, 1]`; `null` when the engine reports none. */
    public val confidence: Float?,
)
