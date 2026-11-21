package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.time.Instant

/**
 * What [ChipReader.read] should do.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property accessKey The key that opens the chip: MRZ values or the card access number. The reader
 *   does not clear it; call [ChipAccessKey.clear] when your app no longer needs it.
 * @property dataGroups The data groups to read, by number (1 to 16). EF.COM, EF.SOD, and DG14 and
 *   DG15 when the chip lists them, are always read: the checks need them. DG3 and DG4 need terminal
 *   authentication, which the SDK does not run, so asking for them gives a
 *   [io.lightine.tessera.types.errors.ChipAccessDenied] entry in [ChipRead.fileErrors].
 * @property trustAnchors The root certificates your app chose for the signer checks. The SDK never
 *   bundles or downloads any; with none, the root checks report that they did not run.
 * @property checkTime The time at which certificate validity is checked. The SDK never reads a
 *   clock for this; with `null`, the validity checks report that they did not run.
 * @property allowExtendedLength Whether the reader may use extended-length APDUs. They are used only
 *   when this is `true`, the transport carries them and the chip states it accepts them.
 * @throws IllegalArgumentException when a data group number is outside 1 to 16.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReadOptions
    @Throws(IllegalArgumentException::class)
    constructor(
        public val accessKey: ChipAccessKey,
        public val dataGroups: Set<Int> = setOf(1, 2),
        public val trustAnchors: List<TrustAnchor> = emptyList(),
        public val checkTime: Instant? = null,
        public val allowExtendedLength: Boolean = true,
    ) {
        init {
            require(dataGroups.all { it in 1..16 }) { "dataGroups holds a number outside 1 to 16" }
        }

        /** The data groups and counts, never the key. */
        override fun toString(): String =
            "ChipReadOptions(dataGroups=${dataGroups.sorted()}, trustAnchors=${trustAnchors.size}, checkTime=${checkTime != null})"
    }
