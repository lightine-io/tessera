package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.cms.CmsParser
import io.lightine.tessera.emrtd.internal.cms.LdsSecurityObject
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.EfDirInfo
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoException
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.internal.x509.KeyUsageBit
import io.lightine.tessera.emrtd.internal.x509.X509Certificate

/**
 * What passive authentication reads. Every file is the raw bytes as read from the chip; `null`
 * means it was not read.
 */
internal class PaInputs(
    /** EF.SOD. */
    val securityObject: ByteArray?,
    /** Data groups read, by number, each the whole file (tag, length and value), the bytes the stored hash covers. */
    val dataGroups: Map<Int, ByteArray> = emptyMap(),
    /** EF.CardAccess (unsigned). */
    val cardAccess: ByteArray? = null,
    /** EF.CardSecurity. */
    val cardSecurity: ByteArray? = null,
    /** EF.DIR. */
    val efDir: ByteArray? = null,
    /** The roots the app supplied ([io.lightine.tessera.emrtd.trust.TrustAnchor]). Never bundled or downloaded (ADR-031 point 4). */
    val roots: List<X509Certificate> = emptyList(),
    /** The time the caller asks validity at; `null` means validity is not checked. The SDK never reads a clock for this. */
    val at: CertTime? = null,
    /** The document code from DG1 (for example `P<` or `ID`), for the DocumentType fact. */
    val documentCode: String? = null,
    /** The document code from the printed MRZ, when the app has it. */
    val printedDocumentCode: String? = null,
    /** The document's issue date (from DG12 or the app), for the usage-period fact. Only the date part is compared. */
    val issueDate: CertTime? = null,
)

/**
 * Passive authentication as facts (Doc 9303 Part 11, 5.1.1 steps 2 to 5 and the three best
 * practices; Part 11, 4.2 step 3; Part 12, App D.1.1). [run] returns one [PaCheck] per fact, in a
 * fixed order: the EF.SOD signer facts, one hash fact per data group, the EF.CardSecurity signer
 * facts (when it was read), the EF.CardAccess and EF.DIR copy facts, the best-practice facts and
 * revocation (always NotRun).
 *
 * No outcome depends on another: the hash facts are computed whether or not the signature verifies,
 * and the copy facts whether or not the copy's own signature does. Combining them, and deciding what
 * is enough, is the app's call. Nothing here says "genuine" or "valid".
 */
internal class PassiveAuthentication(
    private val cryptoProvider: () -> ChipCrypto = ::chipCrypto,
) {
    fun run(inputs: PaInputs): List<PaCheck> {
        val crypto =
            try {
                cryptoProvider()
            } catch (e: ChipCryptoUnavailableException) {
                null
            }
        val checks = ArrayList<PaCheck>()
        val sod =
            if (inputs.securityObject == null) {
                SignedObjectChecks.notRun(SignedObject.SECURITY_OBJECT, inputs.at, PaNotRun.FILE_NOT_READ)
            } else {
                SignedObjectChecks.run(
                    SignedObject.SECURITY_OBJECT,
                    inputs.securityObject,
                    CmsParser.OID_LDS_SECURITY_OBJECT,
                    inputs.roots,
                    inputs.at,
                    crypto,
                )
            }
        checks += sod.checks
        checks += dataGroupHashes(inputs, sod, crypto)

        val cardSecurity =
            inputs.cardSecurity?.let {
                SignedObjectChecks.run(SignedObject.CARD_SECURITY, it, SecurityOids.ID_SECURITY_OBJECT, inputs.roots, inputs.at, crypto)
            }
        cardSecurity?.let { checks += it.checks }
        checks += cardAccessCopies(inputs, cardSecurity)
        checks += efDirCopy(inputs, cardSecurity)

        checks += bestPractice(inputs, sod.signer, sod.root)
        checks += PaCheck(PaCheckKind.RevocationChecked, PaOutcome.NotRun(PaNotRun.NOT_SUPPORTED))
        return checks
    }

    private fun dataGroupHashes(
        inputs: PaInputs,
        sod: SignedObjectResult,
        crypto: ChipCrypto?,
    ): List<PaCheck> {
        val lds = parseLds(sod)
        val numbers = (inputs.dataGroups.keys + lds?.dataGroupHashes?.map { it.number }.orEmpty()).distinct().sorted()

        fun all(reason: PaNotRun) =
            inputs.dataGroups.keys
                .sorted()
                .map { PaCheck(PaCheckKind.DataGroupHashMatches(it), PaOutcome.NotRun(reason)) }
        if (inputs.securityObject == null) return all(PaNotRun.FILE_NOT_READ)
        if (lds == null) return all(PaNotRun.UNREADABLE_SECURITY_OBJECT)
        val algorithm =
            try {
                SignatureAlgorithms.hash(lds.hashAlgorithm.oid)
            } catch (e: UnsupportedAlgorithmException) {
                return numbers.map { PaCheck(PaCheckKind.DataGroupHashMatches(it), PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)) }
            }
        return numbers.map { n ->
            val stored = lds.dataGroupHashes.firstOrNull { it.number == n }?.hash
            val read = inputs.dataGroups[n]
            val outcome =
                when {
                    read == null -> PaOutcome.NotRun(PaNotRun.DATA_GROUP_NOT_READ)
                    stored == null -> PaOutcome.Failed(PaFailure.NO_HASH_IN_SECURITY_OBJECT)
                    crypto == null -> PaOutcome.NotRun(PaNotRun.CRYPTO_UNAVAILABLE)
                    crypto.digest(algorithm, read).contentEquals(stored) -> PaOutcome.Passed
                    else -> PaOutcome.Failed(PaFailure.HASH_DIFFERS)
                }
            PaCheck(PaCheckKind.DataGroupHashMatches(n), outcome)
        }
    }

    private fun parseLds(sod: SignedObjectResult): LdsSecurityObject? =
        sod.signedData?.let {
            try {
                LdsSecurityObject.parse(it.eContent)
            } catch (e: Asn1Exception) {
                null
            }
        }

    /** The DER of each element of a SecurityInfos SET, or `null` if it is not one. */
    private fun setElements(der: ByteArray): List<ByteArray>? =
        try {
            val top = DerReader(der)
            val set = top.readSet()
            top.finish()
            val out = ArrayList<ByteArray>()
            while (set.hasMore) out.add(set.read().encoded())
            out
        } catch (e: Asn1Exception) {
            null
        }

    private fun cardAccessCopies(
        inputs: PaInputs,
        cardSecurity: SignedObjectResult?,
    ): List<PaCheck> {
        val cardAccess =
            inputs.cardAccess
                ?: return listOf(
                    PaCheck(PaCheckKind.CardAccessMatchesSignedCopy(null), PaOutcome.NotApplicable(PaNotApplicable.NO_CARD_ACCESS)),
                )
        val dg14 = inputs.dataGroups[14]
        if (dg14 == null && inputs.cardSecurity == null) {
            return listOf(PaCheck(PaCheckKind.CardAccessMatchesSignedCopy(null), PaOutcome.NotRun(PaNotRun.NO_SIGNED_COPY)))
        }
        val accessEntries = setElements(cardAccess)
        val out = ArrayList<PaCheck>()
        if (dg14 != null) {
            val copy =
                try {
                    val dg = TlvParser().parseSingle(dg14)
                    if (dg.tag == 0x6E) setElements(dg.value()) else null
                } catch (e: TlvException) {
                    null
                }
            out += PaCheck(PaCheckKind.CardAccessMatchesSignedCopy(SignedCopy.DG14), compareEntries(accessEntries, copy))
        }
        if (inputs.cardSecurity != null) {
            val copy = cardSecurity?.signedData?.let { setElements(it.eContent) }
            out += PaCheck(PaCheckKind.CardAccessMatchesSignedCopy(SignedCopy.CARD_SECURITY), compareEntries(accessEntries, copy))
        }
        return out
    }

    private fun compareEntries(
        access: List<ByteArray>?,
        copy: List<ByteArray>?,
    ): PaOutcome =
        when {
            access == null -> PaOutcome.NotRun(PaNotRun.UNREADABLE_FILE)
            copy == null -> PaOutcome.NotRun(PaNotRun.UNREADABLE_SIGNED_COPY)
            access.all { a -> copy.any { it.contentEquals(a) } } -> PaOutcome.Passed
            else -> PaOutcome.Failed(PaFailure.ENTRIES_MISSING_FROM_SIGNED_COPY)
        }

    private fun efDirCopy(
        inputs: PaInputs,
        cardSecurity: SignedObjectResult?,
    ): PaCheck {
        val kind = PaCheckKind.EfDirMatchesSignedCopy
        val efDir = inputs.efDir ?: return PaCheck(kind, PaOutcome.NotApplicable(PaNotApplicable.NO_EF_DIR))
        if (inputs.cardSecurity == null) return PaCheck(kind, PaOutcome.NotRun(PaNotRun.NO_SIGNED_COPY))
        val content = cardSecurity?.signedData?.eContent ?: return PaCheck(kind, PaOutcome.NotRun(PaNotRun.UNREADABLE_SIGNED_COPY))
        val infos =
            try {
                SecurityInfoParser.parse(content)
            } catch (e: SecurityInfoException) {
                return PaCheck(kind, PaOutcome.NotRun(PaNotRun.UNREADABLE_SIGNED_COPY))
            }
        val copy: EfDirInfo = infos.efDirInfo ?: return PaCheck(kind, PaOutcome.NotRun(PaNotRun.NO_SIGNED_COPY))
        return PaCheck(
            kind,
            if (copy.encodedEfDir.contentEquals(efDir)) PaOutcome.Passed else PaOutcome.Failed(PaFailure.DIFFERS_FROM_SIGNED_COPY),
        )
    }

    private fun bestPractice(
        inputs: PaInputs,
        signer: X509Certificate?,
        root: X509Certificate?,
    ): List<PaCheck> {
        val kinds =
            listOf(
                PaCheckKind.SignerDocumentTypeMatches,
                PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly,
                PaCheckKind.SignerCountryMatchesRootCountry,
                PaCheckKind.IssueDateInSignerUsagePeriod,
            )
        if (signer == null) {
            val reason = if (inputs.securityObject == null) PaNotRun.FILE_NOT_READ else PaNotRun.NO_SIGNER_CERTIFICATE
            return kinds.map { PaCheck(it, PaOutcome.NotRun(reason)) }
        }
        return listOf(
            PaCheck(PaCheckKind.SignerDocumentTypeMatches, documentType(inputs, signer)),
            PaCheck(PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly, keyUsage(signer)),
            PaCheck(PaCheckKind.SignerCountryMatchesRootCountry, country(signer, root)),
            PaCheck(PaCheckKind.IssueDateInSignerUsagePeriod, usagePeriod(inputs, signer)),
        )
    }

    /**
     * Part 12, 7.1.1.6: a listed type of one letter covers every document code starting with it; a
     * listed type of two letters covers that code. The MRZ filler `<` after a one-letter code is not
     * part of the code.
     */
    private fun documentType(
        inputs: PaInputs,
        signer: X509Certificate,
    ): PaOutcome {
        val list = signer.decoded.documentTypeList?.types ?: return PaOutcome.NotApplicable(PaNotApplicable.NO_DOCUMENT_TYPE_EXTENSION)
        val codes = listOfNotNull(inputs.documentCode, inputs.printedDocumentCode)
        if (codes.isEmpty()) return PaOutcome.NotRun(PaNotRun.NO_DOCUMENT_CODE)

        fun covered(code: String): Boolean {
            val c = code.trimEnd('<')
            return list.any { t -> c == t || (t.length == 1 && c.startsWith(t)) }
        }
        return if (codes.all(::covered)) PaOutcome.Passed else PaOutcome.Failed(PaFailure.DOCUMENT_TYPE_NOT_LISTED)
    }

    private fun keyUsage(signer: X509Certificate): PaOutcome {
        if (signer.decoded.documentTypeList != null) return PaOutcome.NotApplicable(PaNotApplicable.HAS_DOCUMENT_TYPE_EXTENSION)
        return when {
            signer.decoded.keyUsage !=
                setOf(
                    KeyUsageBit.DIGITAL_SIGNATURE,
                )
            -> PaOutcome.Failed(PaFailure.KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY)

            signer.decoded.extendedKeyUsage != null -> PaOutcome.Failed(PaFailure.EXTENDED_KEY_USAGE_PRESENT)

            else -> PaOutcome.Passed
        }
    }

    private fun country(
        signer: X509Certificate,
        root: X509Certificate?,
    ): PaOutcome {
        if (root == null) return PaOutcome.NotRun(PaNotRun.NO_MATCHED_ROOT)
        val a = signer.subject.countryName
        val b = root.subject.countryName
        if (a == null || b == null) return PaOutcome.NotRun(PaNotRun.COUNTRY_NAME_ABSENT)
        return if (a == b) PaOutcome.Passed else PaOutcome.Failed(PaFailure.COUNTRY_DIFFERS)
    }

    private fun usagePeriod(
        inputs: PaInputs,
        signer: X509Certificate,
    ): PaOutcome {
        val period = signer.decoded.privateKeyUsagePeriod ?: return PaOutcome.NotApplicable(PaNotApplicable.NO_PRIVATE_KEY_USAGE_PERIOD)
        val issued = inputs.issueDate?.let { dateOnly(it) } ?: return PaOutcome.NotRun(PaNotRun.NO_ISSUE_DATE)
        val from = period.notBefore?.let { dateOnly(it) }
        val to = period.notAfter?.let { dateOnly(it) }
        val inside = (from == null || issued >= from) && (to == null || issued <= to)
        return if (inside) PaOutcome.Passed else PaOutcome.Failed(PaFailure.OUTSIDE_USAGE_PERIOD)
    }

    private fun dateOnly(t: CertTime) = CertTime(t.year, t.month, t.day, 0, 0, 0)
}
