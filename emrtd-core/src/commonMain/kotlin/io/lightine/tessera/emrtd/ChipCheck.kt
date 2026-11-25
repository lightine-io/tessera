package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.CheckReason
import io.lightine.tessera.types.vocabulary.MrzField

/**
 * One fact from reading a chip: what was checked, and how it came out.
 *
 * Every check is a calculation anyone can repeat with the same bytes, such as "the hash of DG1
 * equals the one the security object stores" or "the signature verifies under the signer
 * certificate". No check, and no combination of them, says that the chip or the document is
 * genuine: deciding what is enough stays with your app (ICAO Doc 9303 Part 11, section 2).
 *
 * The MRZ validator reports its checks with the same [CheckOutcome], so MRZ and chip results read
 * alike.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property kind What was checked.
 * @property outcome How it came out.
 * @property detail How the check reached its outcome, for the checks that record it: where the
 *   signer certificate came from, how the root's name matched, where the countries were read from.
 *   `null` for the others.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipCheck internal constructor(
    public val kind: ChipCheckKind,
    public val outcome: CheckOutcome,
    public val detail: ChipCheckDetail? = null,
) {
    /** Equal when the kind, the outcome and the detail are equal. */
    override fun equals(other: Any?): Boolean =
        other is ChipCheck && other.kind == kind && other.outcome == outcome && other.detail == detail

    /** Hash of the kind, the outcome and the detail. */
    override fun hashCode(): Int = (31 * kind.hashCode() + outcome.hashCode()) * 31 + detail.hashCode()

    /** `ChipCheck(kind, outcome)`, with the detail when there is one. */
    override fun toString(): String = if (detail == null) "ChipCheck($kind, $outcome)" else "ChipCheck($kind, $outcome, $detail)"
}

/**
 * How a [ChipCheck] reached its outcome: facts about the inputs, kept beside the check so that the
 * kind stays the same whichever way the check went.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipCheckDetail {
    /**
     * For [ChipCheckKind.SignatureVerifies]: where the signer certificate came from.
     *
     * @property origin Where the certificate came from: the signed file or your app.
     */
    public data class SignerCertificate(
        public val origin: ChipSignerOrigin,
    ) : ChipCheckDetail()

    /**
     * For [ChipCheckKind.SignerChainsToSuppliedRoot]: how the signer's issuer name matched the root's subject name.
     *
     * @property comparison How the two names were compared.
     */
    public data class IssuerNameComparison(
        public val comparison: ChipNameComparison,
    ) : ChipCheckDetail()

    /**
     * For [ChipCheckKind.SignerCountryMatchesRootCountry]: where each country was read from, or
     * `null` on a side with no country.
     *
     * @property signer Where the signer certificate's country was read from, or `null` when it has none.
     * @property root Where the matched root's country was read from, or `null` when it has none.
     */
    public data class CountrySources(
        public val signer: ChipCountrySource?,
        public val root: ChipCountrySource?,
    ) : ChipCheckDetail()

    /**
     * For [ChipCheckKind.Dg1MatchesPrintedMrz]: for each MRZ field, whether the chip's DG1 shows the
     * same characters as the printed MRZ your app gave ([ChipReadOptions.printedDocument]). It holds
     * no value from either MRZ.
     *
     * @property sameFormat Whether both MRZs have the same format (TD1, TD2, TD3).
     * @property fields One entry for every [MrzField], in the order of [MrzField.entries].
     */
    public data class PrintedMrzFields(
        public val sameFormat: Boolean,
        public val fields: Map<MrzField, ChipFieldMatch>,
    ) : ChipCheckDetail() {
        /** The fields that differ, in [MrzField] order. */
        public val differingFields: List<MrzField>
            get() = fields.filterValues { it == ChipFieldMatch.DIFFERENT }.keys.toList()
    }
}

/**
 * How one MRZ field compares between the chip's DG1 and the printed MRZ. A fact about the
 * characters, never a verdict on the document.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipFieldMatch {
    /** Both MRZs have the field and show the same characters, check digit included. */
    SAME,

    /** Both MRZs have the field and show different characters. */
    DIFFERENT,

    /** One of the two MRZ formats has no such field, so there is nothing to compare. */
    NOT_COMPARABLE,
}

/**
 * Where a signer certificate came from.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipSignerOrigin {
    /** The signed file carries it among its own certificates. */
    STORED_IN_FILE,

    /** Your app supplied it ([ChipReadOptions.signerCertificate]), because the file names no signer of its own. */
    SUPPLIED_BY_APP,
}

/**
 * How two certificate names were found equal.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipNameComparison {
    /** Their DER bytes are equal. */
    BYTE_EQUAL,

    /** The bytes differ, and the comparison of RFC 5280, section 7.1 matched (case and insignificant spaces ignored). */
    RFC_5280_NORMALIZED,
}

/**
 * Where a certificate's country was read from.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipCountrySource {
    /** The subject's `countryName` attribute. */
    SUBJECT_NAME,

    /** A `directoryName` in the SubjectAltName extension (Part 12, 7.1). */
    SUBJECT_ALT_NAME,

    /** A `directoryName` in the IssuerAltName extension. */
    ISSUER_ALT_NAME,
}

/**
 * Which signed chip file a signer check is about.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipSignedFile {
    /** EF.SOD, the document security object in the eMRTD application (Part 10, section 4.6.2). */
    SECURITY_OBJECT,

    /** EF.CardSecurity in the chip's master file (Part 10, section 3.11.4). */
    CARD_SECURITY,
}

/**
 * Where a signed copy of EF.CardAccess's entries was read from (Part 11, section 9.2.11).
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipSignedCopy {
    /** EF.DG14 in the eMRTD application. */
    DG14,

    /** EF.CardSecurity in the master file. */
    CARD_SECURITY,
}

/**
 * Where on the chip chip authentication ran (Part 11, section 6.2).
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipFileContext {
    /** The master file, with the key from EF.CardSecurity. */
    MASTER_FILE,

    /** The eMRTD application, with the key from EF.DG14. */
    EMRTD_APPLICATION,
}

/**
 * What a [ChipCheck] is about.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipCheckKind {
    /**
     * A stable, machine-readable name of this kind, for logs and keys of your own texts: lower-case
     * words joined by hyphens, with a dot and the parameter when the kind has one (for example
     * `data-group-hash-matches.dg1`, `signature-verifies.security-object`,
     * `signer-within-validity-period.card-security`). It never changes once released and never
     * holds a value from the document.
     *
     * @since 1.0.0
     */
    public abstract val code: String

    /**
     * The hash of data group [dataGroup] as read equals the one EF.SOD stores (Part 11, 5.1.1 step 5).
     *
     * @property dataGroup The number of the data group (1 to 16).
     */
    public data class DataGroupHashMatches(
        public val dataGroup: Int,
    ) : ChipCheckKind() {
        override val code: String
            get() = "data-group-hash-matches.dg$dataGroup"
    }

    /**
     * The signature of [file] verifies under its signer certificate, and its signed attributes agree with the content.
     *
     * @property file Which signed chip file the check is about.
     */
    public data class SignatureVerifies(
        public val file: ChipSignedFile,
    ) : ChipCheckKind() {
        override val code: String
            get() = "signature-verifies." + checkCodeWord(file.name)
    }

    /**
     * The signer certificate of [file] is signed by one of the roots your app supplied (Part 12, App D.1.1).
     *
     * @property file Which signed chip file the check is about.
     */
    public data class SignerChainsToSuppliedRoot(
        public val file: ChipSignedFile,
    ) : ChipCheckKind() {
        override val code: String
            get() = "signer-chains-to-supplied-root." + checkCodeWord(file.name)
    }

    /**
     * The signer certificate of [file] is inside its validity period at the check time your app gave.
     *
     * @property file Which signed chip file the check is about.
     */
    public data class SignerWithinValidityPeriod(
        public val file: ChipSignedFile,
    ) : ChipCheckKind() {
        override val code: String
            get() = "signer-within-validity-period." + checkCodeWord(file.name)
    }

    /**
     * The signer certificate of [file] has no critical extension the SDK does not know (Part 12, App D.1.1.3).
     *
     * @property file Which signed chip file the check is about.
     */
    public data class SignerCriticalExtensionsRecognized(
        public val file: ChipSignedFile,
    ) : ChipCheckKind() {
        override val code: String
            get() = "signer-critical-extensions-recognized." + checkCodeWord(file.name)
    }

    /**
     * Every entry of EF.CardAccess appears, byte for byte, in the signed copy in [copy] (Part 11, 4.2 step 3).
     *
     * @property copy Which signed copy is compared against, or `null` when there is no signed copy or no EF.CardAccess.
     */
    public data class CardAccessMatchesSignedCopy(
        public val copy: ChipSignedCopy?,
    ) : ChipCheckKind() {
        override val code: String
            get() = "card-access-matches-signed-copy." + (copy?.name?.let(::checkCodeWord) ?: "none")
    }

    /** EF.DIR as read equals its copy in EF.CardSecurity (Part 11, 4.2 step 3). */
    public data object EfDirMatchesSignedCopy : ChipCheckKind() {
        override val code: String
            get() = "ef-dir-matches-signed-copy"
    }

    /** The document code from DG1 is in the signer's document-type list (Part 11, 5.1.1 best practice 1). */
    public data object SignerDocumentTypeMatches : ChipCheckKind() {
        override val code: String
            get() = "signer-document-type-matches"
    }

    /** With no document-type list: the signer key is for digital signatures only (Part 11, 5.1.1 best practice 1). */
    public data object SignerKeyUsageIsDigitalSignatureOnly : ChipCheckKind() {
        override val code: String
            get() = "signer-key-usage-is-digital-signature-only"
    }

    /**
     * The signer's country equals the matched root's country (Part 11, 5.1.1 best practice 2). Each
     * country comes from the subject name, else the alternative-name extensions; the check's
     * [ChipCheckDetail.CountrySources] says which.
     */
    public data object SignerCountryMatchesRootCountry : ChipCheckKind() {
        override val code: String
            get() = "signer-country-matches-root-country"
    }

    /** The issue date from DG12 is inside the signer's private-key usage period (Part 11, 5.1.1 best practice 3). */
    public data object IssueDateInSignerUsagePeriod : ChipCheckKind() {
        override val code: String
            get() = "issue-date-in-signer-usage-period"
    }

    /**
     * The signer certificates are not on a revocation list. Always [CheckOutcome.NotRun]: the SDK
     * takes no revocation lists yet.
     */
    public data object SignerNotRevoked : ChipCheckKind() {
        override val code: String
            get() = "signer-not-revoked"
    }

    /**
     * The PACE entries of the signed copy in [copy] (EF.CardSecurity, or DG14) all appear in the
     * unsigned EF.CardAccess, and when the signed copy lists PACE the read used PACE. EF.CardAccess is
     * not signed, so a chip or a reader in between can strip PACE from it and push the read down to
     * BAC; this fact compares in that direction, beside [CardAccessMatchesSignedCopy], which compares
     * EF.CardAccess's entries against the copy.
     *
     * @property copy Which signed copy the PACE entries come from, or `null` when there is no signed copy.
     */
    public data class PaceOfferMatchesSignedCopy(
        public val copy: ChipSignedCopy?,
    ) : ChipCheckKind() {
        override val code: String
            get() = "pace-offer-matches-signed-copy." + (copy?.name?.let(::checkCodeWord) ?: "none")
    }

    /**
     * Chip authentication (Part 11, section 6.2) in [context]: the chip agreed new session keys from
     * its static key, and its next answer verified under them. [CheckOutcome.Passed] also needs the
     * file that holds the key to pass its hash, signature and root checks (Part 11, Appendix J).
     *
     * @property context Which file holds the chip's key, or `null` when the read stopped before any chip authentication was tried.
     */
    public data class ChipAuthentication(
        public val context: ChipFileContext?,
    ) : ChipCheckKind() {
        override val code: String
            get() = "chip-authentication." + (context?.name?.let(::checkCodeWord) ?: "none")
    }

    /**
     * The chip-authentication data of PACE with the chip authentication mapping (Part 11,
     * 4.4.3.5.2) matches the chip's key in EF.CardSecurity. [CheckOutcome.Passed] also needs
     * EF.CardSecurity's signature and root checks to pass.
     */
    public data object PaceChipAuthenticationMapping : ChipCheckKind() {
        override val code: String
            get() = "pace-chip-authentication-mapping"
    }

    /**
     * Active authentication (Part 11, section 6.1): the chip signed a fresh challenge and the
     * signature verifies under the key in its EF.DG15. [CheckOutcome.Passed] also needs DG15 to pass
     * its hash, signature and root checks (Part 11, Appendix J).
     */
    public data object ActiveAuthentication : ChipCheckKind() {
        override val code: String
            get() = "active-authentication"
    }

    /**
     * Every field of the chip's DG1 shows the same characters as the printed MRZ (Part 11, 5.1.1
     * best practice 2). Made only when your app gave a printed MRZ
     * ([ChipReadOptions.printedDocument]) or combined one with a read ([ChipMrzComparison]); its
     * [ChipCheckDetail.PrintedMrzFields] says which fields differ. Never decides what a difference
     * means.
     */
    public data object Dg1MatchesPrintedMrz : ChipCheckKind() {
        override val code: String
            get() = "dg1-matches-printed-mrz"
    }
}

/**
 * Why a check came out [CheckOutcome.Failed], [CheckOutcome.NotApplicable] or
 * [CheckOutcome.NotRun]. Each value is a fact about the inputs, never a verdict on the document.
 * With [ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED] the chip's part held but the file that holds
 * its key has not passed its own checks, so the fact is incomplete, never a pass.
 *
 * Implements [CheckReason], the shared reason type of [CheckOutcome], so a chip check's outcome
 * carries a typed reason.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipCheckReason : CheckReason {
    /** A data group's hash differs from the one stored. */
    HASH_DIFFERS,

    /** The security object stores no hash for the data group. */
    NO_HASH_IN_SECURITY_OBJECT,

    /** The message-digest attribute differs from the hash of the signed content. */
    MESSAGE_DIGEST_DIFFERS,

    /** The signed attributes have no message-digest attribute. */
    MESSAGE_DIGEST_ATTRIBUTE_MISSING,

    /** The content-type attribute differs from the content's type. */
    CONTENT_TYPE_ATTRIBUTE_DIFFERS,

    /** A signature does not verify. */
    SIGNATURE_DOES_NOT_VERIFY,

    /** The signer key does not fit the signature algorithm. */
    KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM,

    /** None of the roots your app supplied signed the signer certificate. */
    NO_SUPPLIED_ROOT_MATCHES,

    /** The signer's issuer name differs from the matched root's subject. */
    ISSUER_NAME_DIFFERS,

    /** The check time is outside the certificate's validity period. */
    OUTSIDE_VALIDITY,

    /** The certificate has a critical extension the SDK does not know. */
    UNRECOGNIZED_CRITICAL_EXTENSION,

    /** EF.CardAccess has entries the signed copy lacks. */
    ENTRIES_MISSING_FROM_SIGNED_COPY,

    /** A file differs from its signed copy. */
    DIFFERS_FROM_SIGNED_COPY,

    /** The document code is not in the signer's document-type list. */
    DOCUMENT_TYPE_NOT_LISTED,

    /** The signer key usage is not digital signature only. */
    KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY,

    /** The signer certificate has an extended key usage. */
    EXTENDED_KEY_USAGE_PRESENT,

    /** Two country codes differ. */
    COUNTRY_DIFFERS,

    /** The issue date is outside the signer's private-key usage period. */
    OUTSIDE_USAGE_PERIOD,

    /** The chip has no EF.CardAccess. */
    NO_CARD_ACCESS,

    /** EF.DIR was not read. */
    NO_EF_DIR,

    /** The signer has a document-type list, so the key-usage form of the check does not apply. */
    HAS_DOCUMENT_TYPE_EXTENSION,

    /** The signer has no document-type list. */
    NO_DOCUMENT_TYPE_EXTENSION,

    /** The signer has no private-key usage period. */
    NO_PRIVATE_KEY_USAGE_PERIOD,

    /** A file the check needs was not read. */
    FILE_NOT_READ,

    /** A file the check needs could not be read. */
    UNREADABLE_FILE,

    /** EF.SOD could not be read. */
    UNREADABLE_SECURITY_OBJECT,

    /** The data group was not read. */
    DATA_GROUP_NOT_READ,

    /** The signed file carries no signer certificate. */
    NO_SIGNER_CERTIFICATE,

    /** The signer certificate could not be read. */
    UNREADABLE_SIGNER_CERTIFICATE,

    /** Your app supplied no roots. */
    NO_ROOTS_SUPPLIED,

    /** No supplied root matched the signer, so the root-based check could not run. */
    NO_MATCHED_ROOT,

    /** An algorithm the SDK does not run. */
    UNSUPPORTED_ALGORITHM,

    /** A public key could not be read. */
    UNREADABLE_KEY,

    /** This platform has no cryptography yet (iOS until its backend lands). */
    CRYPTO_UNAVAILABLE,

    /** Your app gave no check time. */
    NO_TIME_GIVEN,

    /** There is no signed copy to compare with. */
    NO_SIGNED_COPY,

    /** The signed copy could not be read. */
    UNREADABLE_SIGNED_COPY,

    /** There is no document code to compare. */
    NO_DOCUMENT_CODE,

    /** There is no issue date to compare. */
    NO_ISSUE_DATE,

    /** A certificate has no country name. */
    COUNTRY_NAME_ABSENT,

    /** The SDK does not run this check yet. */
    NOT_SUPPORTED,

    /** The chip does not offer this protocol. */
    NOT_OFFERED,

    /** The chip refused the protocol's command. */
    CHIP_REFUSED,

    /** The chip offers the protocol only in forms the reader cannot run. */
    NO_USABLE_SUITE,

    /** The protocol needs a secure-messaging session, and the chip was read without one. */
    NO_SECURE_MESSAGING,

    /** The chip agreed new keys, but its next answer did not verify under them. */
    NEW_KEYS_NOT_CONFIRMED,

    /** The calculation holds, but the file with the key has not passed its hash, signature and root checks. */
    KEY_FILE_CHECKS_NOT_PASSED,

    /** Another form of chip authentication already ran in this read. */
    OTHER_CHIP_AUTHENTICATION_USED,

    /** The read stopped before the check could run. */
    READ_STOPPED,

    /** The chip sent no chip-authentication data with its last PACE answer. */
    CHIP_AUTHENTICATION_DATA_ABSENT,

    /** The chip-authentication data is not in the defined form. */
    CHIP_AUTHENTICATION_DATA_MALFORMED,

    /** The chip-authentication data is zero or not below the group order. */
    CHIP_AUTHENTICATION_DATA_OUT_OF_RANGE,

    /** The key computed from the chip-authentication data differs from the chip's mapping key. */
    MAPPING_KEY_DIFFERS,

    /** EF.CardSecurity has no chip-authentication key with the key number PACE used. */
    NO_CHIP_AUTHENTICATION_KEY,

    /** The chip-authentication key is not in the PACE group or its point is not valid there. */
    UNUSABLE_CHIP_AUTHENTICATION_KEY,

    /** A public key does not hold: a point off its curve, or a modulus that is not positive and odd. */
    INVALID_PUBLIC_KEY,

    /** The chip names no signature algorithm for its active-authentication key. */
    MISSING_SIGNATURE_ALGORITHM,

    /** The chip's signature does not have the length its key gives. */
    WRONG_SIGNATURE_LENGTH,

    /** The chip's signature is not in the defined form. */
    MALFORMED_SIGNATURE,

    /** At least one field of DG1 differs from the printed MRZ. */
    FIELDS_DIFFER,

    /** The reader could not make its key pair or the shared secret on the chip's parameters. */
    KEY_AGREEMENT_FAILED,

    /** The platform's cryptography could not carry out a step with the values the chip sent. */
    CRYPTOGRAPHY_FAILED,

    /** The signed copy lists a PACE entry that EF.CardAccess lacks, or EF.CardAccess is missing. */
    PACE_MISSING_FROM_CARD_ACCESS,

    /** The signed copy lists PACE, and the read used another access protocol. */
    PACE_OFFERED_BUT_NOT_USED,

    /** The chip's DG1 was not read or could not be parsed. */
    NO_CHIP_MRZ,

    ;

    /** The stable code of this reason: `chip` and the name in lower case, words joined by hyphens. */
    override val code: String
        get() = "chip." + name.lowercase().replace('_', '-')
}

/** `SECURITY_OBJECT` as `security-object`. */
private fun checkCodeWord(name: String): String = name.lowercase().replace('_', '-')
