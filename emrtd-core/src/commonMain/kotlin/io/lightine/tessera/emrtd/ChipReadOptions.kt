package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.trust.DocumentSignerCertificate
import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.jvm.JvmOverloads
import kotlin.time.Instant

/**
 * What [ChipReader.read] should do.
 *
 * Kotlin callers use the constructor with named arguments; Java and Swift callers can use
 * [Builder].
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
 * @property checkTime The time at which certificate validity is checked and DG1's dates are read.
 *   The SDK never reads a clock: with `null`, the validity checks report that they did not run and
 *   [ChipRead.dg1] is `null` (the raw DG1 is still read).
 * @property allowExtendedLength Whether the reader may use extended-length APDUs. They are used only
 *   when this is `true`, the transport carries them and the chip states it accepts them.
 * @property signerCertificate A document signer certificate your app supplies for a chip whose
 *   EF.SOD carries none. It is used only when EF.SOD names no signer of its own, and the signature
 *   check then records [ChipSignerOrigin.SUPPLIED_BY_APP].
 * @property allowCheckDigitMismatch Whether a read may use an MRZ key whose check digits do not
 *   match their fields ([ChipAccessKey.Mrz.checkDigitFindings]). With `false`, the default, such a
 *   read ends before any command is sent, with
 *   [io.lightine.tessera.types.errors.ChipKeyCheckDigitMismatch]: a value read or typed with a
 *   mistake would otherwise cost the chip an access attempt. Set it to `true` only for a document
 *   known to be printed with a wrong check digit. A card access number has no check digit.
 * @throws IllegalArgumentException when a data group number is outside 1 to 16.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReadOptions
    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    constructor(
        public val accessKey: ChipAccessKey,
        public val dataGroups: Set<Int> = setOf(1, 2),
        public val trustAnchors: List<TrustAnchor> = emptyList(),
        public val checkTime: Instant? = null,
        public val allowExtendedLength: Boolean = true,
        public val signerCertificate: DocumentSignerCertificate? = null,
        public val allowCheckDigitMismatch: Boolean = false,
    ) {
        init {
            require(dataGroups.all { it in 1..16 }) { "dataGroups holds a number outside 1 to 16" }
        }

        /** The data groups and counts, never the key. */
        override fun toString(): String =
            "ChipReadOptions(dataGroups=${dataGroups.sorted()}, trustAnchors=${trustAnchors.size}, checkTime=${checkTime != null}, " +
                "allowCheckDigitMismatch=$allowCheckDigitMismatch)"

        /**
         * Builds [ChipReadOptions] step by step, with the constructor's defaults.
         *
         * @param accessKey The key that opens the chip.
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder(
            private val accessKey: ChipAccessKey,
        ) {
            private var dataGroups: Set<Int> = setOf(1, 2)
            private var trustAnchors: List<TrustAnchor> = emptyList()
            private var checkTime: Instant? = null
            private var allowExtendedLength: Boolean = true
            private var signerCertificate: DocumentSignerCertificate? = null
            private var allowCheckDigitMismatch: Boolean = false

            /** The data groups to read, by number (1 to 16). */
            public fun dataGroups(numbers: Set<Int>): Builder = apply { dataGroups = numbers.toSet() }

            /** The root certificates for the signer checks. */
            public fun trustAnchors(anchors: List<TrustAnchor>): Builder = apply { trustAnchors = anchors.toList() }

            /** The time at which validity is checked and DG1's dates are read. */
            public fun checkTime(time: Instant?): Builder = apply { checkTime = time }

            /** Whether extended-length APDUs may be used. */
            public fun allowExtendedLength(allow: Boolean): Builder = apply { allowExtendedLength = allow }

            /** A document signer certificate for a chip whose EF.SOD carries none. */
            public fun signerCertificate(certificate: DocumentSignerCertificate?): Builder = apply { signerCertificate = certificate }

            /** Whether an MRZ key whose check digits do not match their fields may be used. */
            public fun allowCheckDigitMismatch(allow: Boolean): Builder = apply { allowCheckDigitMismatch = allow }

            /**
             * The options.
             *
             * @throws IllegalArgumentException when a data group number is outside 1 to 16.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): ChipReadOptions =
                ChipReadOptions(
                    accessKey,
                    dataGroups,
                    trustAnchors,
                    checkTime,
                    allowExtendedLength,
                    signerCertificate,
                    allowCheckDigitMismatch,
                )
        }
    }
