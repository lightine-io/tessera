package io.lightine.tessera.mrz.parsing

internal data class NameFields(
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val nameTruncated: Boolean,
)

/**
 * Parses an MRZ name field per ICAO Doc 9303 conventions.
 *
 * Rules:
 * - Truncation is detected when the field contains no trailing filler character (`<`).
 *   ICAO Doc 9303 convention: a complete name always leaves at least one trailing `<`,
 *   so a field that fills exactly to the boundary is indistinguishable from a truncated
 *   one and is treated as truncated.
 * - Primary and secondary identifiers are separated by the first `<<` occurrence.
 *   Before the separator is the primary identifier (typically surname); after is the
 *   secondary identifier (typically given names). If no `<<` is found, the entire
 *   trimmed field is the primary identifier and the secondary is empty (mononym case).
 * - Within each component, the single filler `<` is decoded as a space. This is the
 *   ICAO reverse-mapping. Apostrophes and hyphens (transliterated to `<` per ICAO)
 *   are lossy - the raw field is preserved on `CommonFields.rawNameField` per
 *   Principle 5 so consumers can handle this themselves.
 * - Malformed input with multiple `<<` in the secondary is preserved verbatim: only
 *   the first `<<` splits, subsequent ones decode as double spaces in the secondary.
 *   No auto-correction (Principle 1).
 */
internal fun parseNameField(rawNameField: String): NameFields {
    val truncated = rawNameField.isNotEmpty() && rawNameField.last() != '<'
    val trimmed = rawNameField.trimEnd('<')
    val separatorIndex = trimmed.indexOf("<<")
    val primaryRaw: String
    val secondaryRaw: String
    if (separatorIndex == -1) {
        primaryRaw = trimmed
        secondaryRaw = ""
    } else {
        primaryRaw = trimmed.substring(0, separatorIndex)
        secondaryRaw = trimmed.substring(separatorIndex + 2)
    }
    return NameFields(
        primaryIdentifier = primaryRaw.replace('<', ' '),
        secondaryIdentifier = secondaryRaw.replace('<', ' '),
        nameTruncated = truncated,
    )
}
