package io.lightine.tessera.mrz.camera

/**
 * The text an OCR engine recognized in one frame, as lines in reading order.
 *
 * Every character is an engine read, never edited by the SDK. A short MRZ row may be the engine's
 * second read of the same region; [RecognizedLine.secondRead] marks that row.
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
public class RecognizedLine(
    /**
     * The recognized characters of this line, exactly as the engine reported them; the SDK does not
     * edit them.
     */
    public val text: String,
    /** OCR confidence in `[0, 1]`; `null` when the engine reports none. */
    public val confidence: Float?,
    /**
     * Whether this is the engine's second read of the row's region that replaced a shorter first read.
     *
     * @since 1.1.0
     */
    public val secondRead: Boolean,
) {
    /** Creates a line that was not reported as a second read. */
    public constructor(text: String, confidence: Float?) : this(text, confidence, secondRead = false)

    /** The recognized characters. */
    public operator fun component1(): String = text

    /** The OCR confidence. */
    public operator fun component2(): Float? = confidence

    /** Whether this line came from a second read. */
    public operator fun component3(): Boolean = secondRead

    /** Copies this line while preserving its second-read status. */
    public fun copy(
        text: String = this.text,
        confidence: Float? = this.confidence,
    ): RecognizedLine = RecognizedLine(text, confidence, secondRead)

    /** Copies this line with an explicit second-read status. */
    public fun copy(
        text: String = this.text,
        confidence: Float? = this.confidence,
        secondRead: Boolean,
    ): RecognizedLine = RecognizedLine(text, confidence, secondRead)

    override fun equals(other: Any?): Boolean =
        other is RecognizedLine && text == other.text && confidence == other.confidence && secondRead == other.secondRead

    override fun hashCode(): Int = 31 * (31 * text.hashCode() + (confidence?.hashCode() ?: 0)) + secondRead.hashCode()

    /** How long the line is and the OCR confidence; never the text itself. */
    override fun toString(): String = "RecognizedLine(length=${text.length}, confidence=$confidence)"
}
