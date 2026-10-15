package io.lightine.tessera.types.vocabulary

/**
 * The category of a country code found in the SDK's country code table.
 *
 * @since 0.1.0
 */
public enum class CountryCodeCategory {
    /** A state code from ISO 3166-1 alpha-3. */
    STATE,

    /** A code for an international organization that issues travel documents. */
    ORGANIZATION,

    /** A code for stateless persons. */
    STATELESS,

    /** A code for refugees. */
    REFUGEE,

    /** A code for a state that no longer exists or changed identity. */
    HISTORICAL,

    /** A recognized code that fits none of the other categories. */
    OTHER,
}
