// Swift labels for the two dataGroups overloads (TES-346). ObjCName is still experimental in Kotlin.
@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.trust.DocumentSignerCertificate
import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.jvm.JvmOverloads
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * What [ChipReader.read] should do.
 *
 * Kotlin callers use the constructor with named arguments; Java and Swift callers can use
 * [Builder].
 *
 * **Who clears the key.** The low-level [ChipReader.read] does not clear [accessKey]: the caller
 * owns it. The easy entry points (`NfcChipRead` on Android, `CoreNfcChipRead` on iOS) take the key
 * over for the length of the read and clear it at its ending, whatever the ending is; read the
 * entry point's documentation. Either way a key is for one person's action: make a new one for the
 * next read.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property accessKey The key that opens the chip: MRZ values or the card access number.
 *   [ChipReader.read] does not clear it; call [ChipAccessKey.clear] when your app no longer needs
 *   it. The easy entry points clear it at the ending of the read.
 * @property dataGroups The data groups to read, by number (1 to 16), at least one. EF.COM, EF.SOD,
 *   and DG14 and DG15 when the chip lists them, are always read: the checks need them. An empty
 *   selection is refused, because a read that asks for no data group is nearly always a mistake;
 *   ask for DG1 at least. DG3 and DG4 need terminal
 *   authentication, which the SDK does not run, so asking for them gives a
 *   [io.lightine.tessera.types.errors.ChipAccessDenied] entry in [ChipRead.fileErrors].
 * @property trustAnchors The root certificates your app chose for the signer checks. The SDK never
 *   bundles or downloads any; with none, the root checks report that they did not run.
 * @property checkTime With `null` (the default), the validity-period checks are reported as not run
 *   (`CheckOutcome.NotRun` with [ChipCheckReason.NO_TIME_GIVEN]) and [ChipRead.dg1] is `null`; the
 *   raw DG1 is still read. Otherwise this is the time at which certificate validity is checked and
 *   DG1's dates are read. The SDK never reads a clock: give the time your app trusts.
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
 * @property printedDocument The MRZ your app read from the printed page (by the camera or typed in),
 *   or `null`, the default. When it is given, the result holds one more check,
 *   [ChipCheckKind.Dg1MatchesPrintedMrz], whose detail ([ChipCheckDetail.PrintedMrzFields]) says
 *   for each MRZ field whether the chip's DG1 shows the same characters, different ones, or the
 *   field cannot be compared. It holds no value, and it never corrects or prefers either side. Pass
 *   it yourself: no key builder sets it for you. The options keep the document until your app lets
 *   go of them; the SDK cannot wipe it as it wipes the key. A read with no DG1 reports the check as
 *   not run ([ChipCheckReason.NO_CHIP_MRZ]). This works with no [checkTime].
 * @throws IllegalArgumentException when a data group number is outside 1 to 16, or the selection
 *   is empty.
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
        public val printedDocument: MrzDocument? = null,
    ) {
        init {
            require(dataGroups.isNotEmpty()) { "dataGroups is empty: ask for at least one data group (DG1 holds the MRZ)" }
            require(dataGroups.all { it in 1..16 }) { "dataGroups holds a number outside 1 to 16" }
        }

        /** The data groups and counts, never the key. */
        override fun toString(): String =
            "ChipReadOptions(dataGroups=${dataGroups.sorted()}, trustAnchors=${trustAnchors.size}, checkTime=${checkTime != null}, " +
                "allowCheckDigitMismatch=$allowCheckDigitMismatch, printedDocument=${printedDocument != null})"

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
            private var printedDocument: MrzDocument? = null

            /** The data groups to read, by number (1 to 16), at least one. In Swift: `dataGroups(numbers: [1, 2, 11])`. */
            public fun dataGroups(numbers: Set<Int>): Builder = apply { dataGroups = numbers.toSet() }

            /**
             * The data groups to read, by number (1 to 16), from any collection: a list, a range, a
             * set. Repeats count once. An empty selection makes [build] throw. In Swift, where the
             * set form above has the label `numbers:`, this form is `dataGroups(numberCollection:)`.
             */
            public fun dataGroups(
                @ObjCName("numberCollection") numbers: Collection<Int>,
            ): Builder = apply { dataGroups = numbers.toSet() }

            /**
             * The data groups to read, by number (1 to 16): `dataGroups(1, 2, 11)`. In Swift, where a
             * Kotlin `vararg` is an array object, use `dataGroups(numbers:)` with a set instead;
             * this form is `dataGroups(numberArray:)` there.
             */
            public fun dataGroups(
                @ObjCName("numberArray") vararg numbers: Int,
            ): Builder = apply { dataGroups = numbers.toSet() }

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
             * The MRZ your app read from the printed page, to have the result compare it with the
             * chip's DG1 ([ChipReadOptions.printedDocument]); `null` for no comparison.
             */
            public fun printedDocument(document: MrzDocument?): Builder = apply { printedDocument = document }

            /**
             * The options.
             *
             * @throws IllegalArgumentException when a data group number is outside 1 to 16, or no data
             *   group is selected.
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
                    printedDocument,
                )
        }
    }
