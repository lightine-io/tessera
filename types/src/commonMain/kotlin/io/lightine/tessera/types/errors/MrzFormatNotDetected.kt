package io.lightine.tessera.types.errors

/**
 * Parse error: the line count and line lengths fit no supported format.
 *
 * Reported only by the auto-detecting entry point. Empty input is reported this way too.
 * A format-specific entry point reports [MrzInvalidLength] instead.
 *
 * @property observedLineCount Number of lines in the input.
 * @property observedLineLengths Length of each input line, in order.
 * @since 0.1.0
 */
public data class MrzFormatNotDetected(
    val observedLineCount: Int,
    val observedLineLengths: List<Int>,
) : MrzParseError() {
    /** English text for logs: the observed shape and the shapes the SDK accepts. */
    override val description: String
        get() =
            "MRZ format could not be detected from input shape: " +
                "$observedLineCount line(s) with lengths $observedLineLengths. " +
                "Expected one of: 3 lines of 30 characters (TD1), " +
                "2 lines of 36 characters (TD2 or MRV-B), " +
                "or 2 lines of 44 characters (TD3 or MRV-A)."
}
