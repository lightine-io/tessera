package io.lightine.tessera.telemetry

/**
 * Helpers that mask sensitive text before it goes into a telemetry payload.
 *
 * @since 0.1.0
 */
public object Redaction {
    /**
     * Replaces every ASCII letter and digit in [line] with `'*'`.
     *
     * Filler (`'<'`) and other characters stay as they are, so the result has the same length
     * as [line]. The function does not validate [line].
     *
     * @param line The text to mask, usually one MRZ line.
     * @return The masked text.
     */
    public fun redactMrzLine(line: String): String =
        buildString(line.length) {
            for (c in line) {
                append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') '*' else c)
            }
        }
}
