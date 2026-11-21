package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * One fact from reading a chip: what was checked, and how it came out.
 *
 * Every check is a calculation anyone can repeat with the same bytes, such as "the hash of DG1
 * equals the one the security object stores" or "the signature verifies under the signer
 * certificate". No check, and no combination of them, says that the chip or the document is
 * genuine: deciding what is enough stays with your app (ICAO Doc 9303 Part 11, section 2).
 *
 * The same record shape is meant for the printed MRZ's checks later, so MRZ and chip results read
 * alike.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property kind What was checked.
 * @property outcome How it came out.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipCheck internal constructor(
    public val kind: ChipCheckKind,
    public val outcome: CheckOutcome,
) {
    /** Equal when both the kind and the outcome are equal. */
    override fun equals(other: Any?): Boolean = other is ChipCheck && other.kind == kind && other.outcome == outcome

    /** Hash of the kind and the outcome. */
    override fun hashCode(): Int = 31 * kind.hashCode() + outcome.hashCode()

    /** `ChipCheck(kind, outcome)`. */
    override fun toString(): String = "ChipCheck($kind, $outcome)"
}

/**
 * How a [ChipCheck] came out.
 *
 * [Passed] and [Failed] are results of a calculation. [NotApplicable] means the chip does not have
 * what the check is about. [NotRun] means the check did not run, or ran only in part: its reason
 * says which. With [ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED] the chip's part held but the file
 * that holds its key has not passed its own checks, so the fact is incomplete, never a pass.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class CheckOutcome {
    /** The calculation gave the expected result. */
    public data object Passed : CheckOutcome()

    /** The calculation did not give the expected result, for [reason]. */
    @ConsistentCopyVisibility
    public data class Failed internal constructor(
        public val reason: ChipCheckReason,
    ) : CheckOutcome()

    /** The check does not apply to this chip, for [reason]. */
    @ConsistentCopyVisibility
    public data class NotApplicable internal constructor(
        public val reason: ChipCheckReason,
    ) : CheckOutcome()

    /** The check could not run, for [reason]. */
    @ConsistentCopyVisibility
    public data class NotRun internal constructor(
        public val reason: ChipCheckReason,
    ) : CheckOutcome()
}

/**
 * Which signed chip file a signer check is about.
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
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipCheckKind {
    /** The hash of data group [dataGroup] as read equals the one EF.SOD stores (Part 11, 5.1.1 step 5). */
    public data class DataGroupHashMatches(
        public val dataGroup: Int,
    ) : ChipCheckKind()

    /** The signature of [file] verifies under its signer certificate, and its signed attributes agree with the content. */
    public data class SignatureVerifies(
        public val file: ChipSignedFile,
    ) : ChipCheckKind()

    /** The signer certificate of [file] is signed by one of the roots your app supplied (Part 12, App D.1.1). */
    public data class SignerChainsToSuppliedRoot(
        public val file: ChipSignedFile,
    ) : ChipCheckKind()

    /** The signer certificate of [file] is inside its validity period at the check time your app gave. */
    public data class SignerWithinValidityPeriod(
        public val file: ChipSignedFile,
    ) : ChipCheckKind()

    /** The signer certificate of [file] has no critical extension the SDK does not know (Part 12, App D.1.1.3). */
    public data class SignerCriticalExtensionsRecognized(
        public val file: ChipSignedFile,
    ) : ChipCheckKind()

    /** Every entry of EF.CardAccess appears, byte for byte, in the signed copy in [copy] (Part 11, 4.2 step 3). */
    public data class CardAccessMatchesSignedCopy(
        public val copy: ChipSignedCopy?,
    ) : ChipCheckKind()

    /** EF.DIR as read equals its copy in EF.CardSecurity (Part 11, 4.2 step 3). */
    public data object EfDirMatchesSignedCopy : ChipCheckKind()

    /** The document code from DG1 is in the signer's document-type list (Part 11, 5.1.1 best practice 1). */
    public data object SignerDocumentTypeMatches : ChipCheckKind()

    /** With no document-type list: the signer key is for digital signatures only (Part 11, 5.1.1 best practice 1). */
    public data object SignerKeyUsageIsDigitalSignatureOnly : ChipCheckKind()

    /** The signer's country equals the matched root's country (Part 11, 5.1.1 best practice 2). */
    public data object SignerCountryMatchesRootCountry : ChipCheckKind()

    /** The issue date from DG12 is inside the signer's private-key usage period (Part 11, 5.1.1 best practice 3). */
    public data object IssueDateInSignerUsagePeriod : ChipCheckKind()

    /**
     * The signer certificates are not on a revocation list. Always [CheckOutcome.NotRun]: the SDK
     * takes no revocation lists yet.
     */
    public data object SignerNotRevoked : ChipCheckKind()

    /**
     * The PACE entries of the signed copy in [copy] (EF.CardSecurity, or DG14) all appear in the
     * unsigned EF.CardAccess, and when the signed copy lists PACE the read used PACE. EF.CardAccess is
     * not signed, so a chip or a reader in between can strip PACE from it and push the read down to
     * BAC; this fact compares in that direction, beside [CardAccessMatchesSignedCopy], which compares
     * EF.CardAccess's entries against the copy.
     */
    public data class PaceOfferMatchesSignedCopy(
        public val copy: ChipSignedCopy?,
    ) : ChipCheckKind()

    /**
     * Chip authentication (Part 11, section 6.2) in [context]: the chip agreed new session keys from
     * its static key, and its next answer verified under them. [CheckOutcome.Passed] also needs the
     * file that holds the key to pass its hash, signature and root checks (Part 11, Appendix J).
     */
    public data class ChipAuthentication(
        public val context: ChipFileContext?,
    ) : ChipCheckKind()

    /**
     * The chip-authentication data of PACE with the chip authentication mapping (Part 11,
     * 4.4.3.5.2) matches the chip's key in EF.CardSecurity. [CheckOutcome.Passed] also needs
     * EF.CardSecurity's signature and root checks to pass.
     */
    public data object PaceChipAuthenticationMapping : ChipCheckKind()

    /**
     * Active authentication (Part 11, section 6.1): the chip signed a fresh challenge and the
     * signature verifies under the key in its EF.DG15. [CheckOutcome.Passed] also needs DG15 to pass
     * its hash, signature and root checks (Part 11, Appendix J).
     */
    public data object ActiveAuthentication : ChipCheckKind()

    /** Every field of the chip's DG1 shows the same characters as the printed MRZ (Part 11, 5.1.1 best practice 2). */
    public data object Dg1MatchesPrintedMrz : ChipCheckKind()
}

/**
 * Why a check came out [CheckOutcome.Failed], [CheckOutcome.NotApplicable] or
 * [CheckOutcome.NotRun]. Each value is a fact about the inputs, never a verdict on the document.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipCheckReason {
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
}
