package io.lightine.tessera.mrz.recognition

import io.lightine.tessera.types.vocabulary.CountryCodeCategory
import kotlin.jvm.JvmInline

/**
 * A country code as printed in an MRZ: three characters.
 *
 * @property rawCode The code exactly as printed.
 * @since 0.1.0
 */
@JvmInline
public value class CountryCode(
    public val rawCode: String,
) {
    /** The [CountryCodeTable] entry for [rawCode], or `null` if the table has none. */
    public val entry: CountryCodeEntry?
        get() = CountryCodeTable.lookup(rawCode)

    /** `true` if [CountryCodeTable] has an entry for [rawCode]. */
    public val isRecognized: Boolean
        get() = entry != null

    /** The display name from [entry], or `null` if the code is not recognized. */
    public val displayName: String?
        get() = entry?.displayName

    /** The category from [entry], or `null` if the code is not recognized. */
    public val category: CountryCodeCategory?
        get() = entry?.category
}
