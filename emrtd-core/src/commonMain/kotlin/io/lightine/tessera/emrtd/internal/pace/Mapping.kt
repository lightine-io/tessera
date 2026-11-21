package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.sm.SessionKeys

/** What a mapping step works with. Shared by the engine and the mapping; owns no secrets. */
internal class MappingContext(
    val crypto: ChipCrypto,
    val suite: PaceSuite,
    /** A fresh private key in a group (the engine's random source; tests script it). */
    val newPrivateKey: (PaceGroup) -> ByteArray,
    /** Fresh random bytes (for a nonce the reader sends, such as t of the integrated mapping). */
    val randomBytes: (Int) -> ByteArray,
)

/**
 * The chip's answer to the mutual authentication step (Table 4), beyond its token: data objects a
 * mapping may need, by tag (0x8A encrypted chip authentication data for the chip authentication
 * mapping; 0x87 and 0x88 certification authority references).
 */
internal class AuthenticationResponse(
    val objects: Map<Int, ByteArray>,
)

/**
 * What a mapping learned beyond the session keys, such as the chip authentication data of the chip
 * authentication mapping (section 4.4.3.5). Each mapping that has some adds its own subtype.
 */
internal interface MappingEvidence

/**
 * One PACE mapping (Part 11, section 4.4.3.3): turns the nonce s into ephemeral domain parameters.
 * The engine ([Pace]) runs every other step; a mapping runs step 2 of Table 4 and may read the
 * last answer. One instance serves one run, so it may keep state between [map] and
 * [afterAuthentication].
 *
 * - Generic mapping: [GenericMapping].
 * - Integrated mapping (section 4.4.3.3.2) and chip authentication mapping (4.4.3.3.3, 4.4.3.5)
 *   plug in here with their own implementation and a line in [Mappings.forKind].
 */
internal interface Mapping {
    /** The mapping this implements. */
    val kind: PaceMapping

    /**
     * Step 2: exchanges the mapping data through [exchange] (it sends the value of data object 0x81
     * and returns the value of the chip's 0x82) and returns the ephemeral group D = Map(D_IC, s, ...).
     * Throws [PaceException] for a bad answer.
     */
    suspend fun map(
        context: MappingContext,
        nonce: ByteArray,
        exchange: suspend (ByteArray) -> ByteArray,
    ): PaceGroup

    /**
     * After both tokens verified: reads whatever the mapping needs from the chip's last answer.
     * Returns null when the mapping has nothing to add (generic mapping).
     */
    fun afterAuthentication(
        context: MappingContext,
        keys: SessionKeys,
        response: AuthenticationResponse,
    ): MappingEvidence? = null

    /** Wipes any secret the mapping still holds. Called by the engine at the end of every run. */
    fun wipe() {}
}

/** The mapping implementations the reader has, by kind. */
internal object Mappings {
    /** A new instance for [kind], or null when the reader has none yet. */
    fun forKind(kind: PaceMapping): Mapping? =
        when (kind) {
            PaceMapping.GENERIC -> GenericMapping()
            PaceMapping.INTEGRATED -> null
            PaceMapping.CHIP_AUTHENTICATION -> null
        }
}
