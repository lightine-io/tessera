package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.CountryCodeCategory
import kotlin.jvm.JvmInline

/**
 * A country code as it appears in an MRZ field — a three-character string drawn from the
 * issuer's choice of ISO 3166-1 alpha-3 codes or the ICAO Doc 9303 Part 3 Section 5
 * extensions (organization codes, stateless, refugee, historical states).
 *
 * The [rawCode] is what the document actually contains; the other properties consult
 * [CountryCodeTable] to add SDK-recognized context (display name, category). Lookup
 * failures are not errors — see [isRecognized] and
 * [`MrzUnknownCountryCode`][io.lightine.tessera.types.errors.MrzUnknownCountryCode]
 * for the recognition-failure flow per
 * [ADR-013](https://lightine.youtrack.cloud/articles/TES-A-44).
 */
@JvmInline
public value class CountryCode(
    public val rawCode: String,
) {
    /** The [CountryCodeTable] entry for [rawCode], or `null` if the code is not in the table. */
    public val entry: CountryCodeEntry?
        get() = CountryCodeTable.lookup(rawCode)

    /** True if [rawCode] is in [CountryCodeTable]. */
    public val isRecognized: Boolean
        get() = entry != null

    /** The human-readable name from [entry], or `null` if the code is not recognized. */
    public val displayName: String?
        get() = entry?.displayName

    /** The category from [entry], or `null` if the code is not recognized. */
    public val category: CountryCodeCategory?
        get() = entry?.category
}
