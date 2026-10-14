package io.lightine.tessera.types.vocabulary

/**
 * The MRZ formats defined by ICAO Doc 9303.
 *
 * @since 0.1.0
 */
public enum class MrzFormat {
    /** Three lines of 30 characters. Identity cards. */
    TD1,

    /** Two lines of 36 characters. Smaller identity documents. */
    TD2,

    /** Two lines of 44 characters. Passports. */
    TD3,

    /** Two lines of 44 characters. Type-A visas. */
    MRV_A,

    /** Two lines of 36 characters. Type-B visas. */
    MRV_B,
}
