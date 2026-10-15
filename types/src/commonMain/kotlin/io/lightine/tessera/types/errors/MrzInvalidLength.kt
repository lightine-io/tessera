package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Parse error: the line count or a line length does not fit the format you named.
 *
 * Reported by format-specific entry points such as `parseTD3`. The auto-detecting entry
 * point reports [MrzFormatNotDetected] instead.
 *
 * @property format The format the caller asked for.
 * @property expectedLineCount Number of lines the format has.
 * @property expectedLineLength Length of each line in the format, in characters.
 * @property observedLineCount Number of lines in the input.
 * @property observedLineLengths Length of each input line, in order.
 * @since 0.1.0
 */
public data class MrzInvalidLength(
    val format: MrzFormat,
    val expectedLineCount: Int,
    val expectedLineLength: Int,
    val observedLineCount: Int,
    val observedLineLengths: List<Int>,
) : MrzParseError() {
    /** English text for logs: the format with expected and observed shapes. */
    override val description: String
        get() =
            "Format $format expected $expectedLineCount lines of $expectedLineLength characters; " +
                "observed $observedLineCount lines with lengths $observedLineLengths"
}
