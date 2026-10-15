package io.lightine.tessera.mrz.transliteration

/**
 * The transliteration profiles known to the SDK, keyed by identifier.
 *
 * The `ICAO` and `AZE` profiles are registered at startup. The registry is not thread-safe:
 * register profiles before any concurrent use.
 *
 * @since 0.1.0
 */
public object TransliterationProfileRegistry {
    private val profiles: MutableMap<String, TransliterationProfile> = mutableMapOf()

    init {
        register(IcaoDefaultTransliterationProfile)
        register(AzeTransliterationProfile)
    }

    /**
     * Registers a profile, replacing any registered profile with the same identifier.
     *
     * @param profile The profile to register.
     */
    public fun register(profile: TransliterationProfile) {
        profiles[profile.identifier] = profile
    }

    /**
     * Returns the profile registered under an identifier.
     *
     * @param identifier The profile identifier. The match is exact and case-sensitive.
     * @return The profile, or `null` if none is registered under it.
     */
    public fun lookup(identifier: String): TransliterationProfile? = profiles[identifier]

    /** Returns a copy of the registered profiles, in registration order. */
    public fun all(): List<TransliterationProfile> = profiles.values.toList()
}
