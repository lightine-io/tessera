package io.lightine.tessera.logging

/** Writes one line to the platform log under [tag]. */
internal expect fun writeLogLine(
    tag: String,
    line: String,
)
