package io.lightine.tessera.telemetry

/**
 * Utilities for masking sensitive content in telemetry event payloads.
 *
 * 0.1.0 ships one helper: [redactMrzLine]. Additional helpers (document number, name,
 * date-of-birth, etc.) are added alongside the events that need them, in the releases
 * of the emitting modules. Speculating on the surface for events that do not yet exist
 * would lock the wrong API under
 * [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37).
 */
public object Redaction {
    /**
     * Returns [line] with every ASCII letter and digit replaced by `'*'`. Filler
     * characters (`'<'`) and any other non-alphanumeric characters are preserved
     * verbatim, so the output keeps the same length and overall shape as the input.
     *
     * The function does not validate the input: malformed lines (wrong length, content
     * outside the MRZ alphabet) are masked the same way. Validation is the caller's
     * responsibility if they need it.
     */
    public fun redactMrzLine(line: String): String =
        buildString(line.length) {
            for (c in line) {
                append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') '*' else c)
            }
        }
}
