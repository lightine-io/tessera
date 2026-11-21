package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.x509.CertTime

/*
 * The internal result shape of passive authentication (Doc 9303 Part 11, 5.1; Part 12, App D): one
 * [PaCheck] per fact, each a check kind with an outcome. This is the shape the read-flow card adopts
 * for its public `ChipCheck(kind, outcome)`: each [PaCheckKind] maps to one public `ChipCheckKind`,
 * and [PaOutcome] maps one to one to the public `CheckOutcome` (Passed, Failed(reason),
 * NotApplicable, NotRun(reason)). Nothing here says "genuine" or "valid" (ADR-031 point 5): every
 * outcome is a calculation anyone can repeat with the same inputs.
 */

/** Which signed object a signature or signer check is about. */
internal enum class SignedObject {
    /** EF.SOD, the document security object (Part 10, 4.6.2). */
    SECURITY_OBJECT,

    /** EF.CardSecurity (Part 10, 3.11.4). */
    CARD_SECURITY,

    /** A CSCA master list the app obtained (Part 12, 9). */
    MASTER_LIST,
}

/** Where a signed copy of EF.CardAccess's SecurityInfos was read from (Part 11, 9.2.11). */
internal enum class SignedCopy {
    DG14,
    CARD_SECURITY,
}

/** What a check is about. */
internal sealed interface PaCheckKind {
    /** The hash of data group [dataGroup] as read equals the one the security object stores (Part 11, 5.1.1 step 5). */
    data class DataGroupHashMatches(
        val dataGroup: Int,
    ) : PaCheckKind

    /**
     * The signature of [signed] verifies under its signer certificate's public key, and the signed
     * attributes (content type and message digest) agree with the signed content (RFC 5652, 5.4 and 5.6).
     */
    data class SignatureVerifies(
        val signed: SignedObject,
    ) : PaCheckKind

    /**
     * The signer certificate of [signed] is signed by one of the roots the app supplied, the one its
     * AuthorityKeyIdentifier names, and its issuer name is that root's subject (Part 12, App D.1.1).
     */
    data class SignerChainsToSuppliedRoot(
        val signed: SignedObject,
    ) : PaCheckKind

    /** The signer certificate of [signed] is inside its validity period at [time], a time the caller gave. */
    data class SignerValidAt(
        val signed: SignedObject,
        val time: CertTime?,
    ) : PaCheckKind

    /** The signer certificate of [signed] has no critical extension the SDK does not know (Part 12, App D.1.1.3 e). */
    data class SignerCriticalExtensionsRecognized(
        val signed: SignedObject,
    ) : PaCheckKind

    /** Every SecurityInfo in EF.CardAccess appears, byte for byte, in the signed copy in [copy] (Part 11, 4.2 step 3). */
    data class CardAccessMatchesSignedCopy(
        val copy: SignedCopy?,
    ) : PaCheckKind

    /** EF.DIR as read equals the copy in EF.CardSecurity's EFDIRInfo (Part 11, 4.2 step 3). */
    data object EfDirMatchesSignedCopy : PaCheckKind

    /** The document code from DG1 (and the printed MRZ, when given) is in the signer's DocumentType extension (Part 11, 5.1.1 best practice 1). */
    data object SignerDocumentTypeMatches : PaCheckKind

    /** With no DocumentType extension: the signer's KeyUsage is digitalSignature and it has no ExtendedKeyUsage (Part 11, 5.1.1 best practice 1). */
    data object SignerKeyUsageIsDigitalSignatureOnly : PaCheckKind

    /** The signer's subject country equals the matched root's subject country (part of Part 11, 5.1.1 best practice 2). */
    data object SignerCountryMatchesRootCountry : PaCheckKind

    /** The issue date the caller gave is inside the signer's PrivateKeyUsagePeriod (Part 11, 5.1.1 best practice 3). */
    data object IssueDateInSignerUsagePeriod : PaCheckKind

    /** Revocation of the signer certificates: never run (no CRLs are fetched or accepted yet; TES-300). */
    data object RevocationChecked : PaCheckKind
}

/** Why a check came out Failed: a fact about the inputs, never a verdict on the document. */
internal enum class PaFailure {
    HASH_DIFFERS,
    NO_HASH_IN_SECURITY_OBJECT,
    MESSAGE_DIGEST_DIFFERS,
    MESSAGE_DIGEST_ATTRIBUTE_MISSING,
    CONTENT_TYPE_ATTRIBUTE_DIFFERS,
    SIGNATURE_DOES_NOT_VERIFY,
    KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM,
    NO_SUPPLIED_ROOT_MATCHES,
    ISSUER_NAME_DIFFERS,
    OUTSIDE_VALIDITY,
    UNRECOGNIZED_CRITICAL_EXTENSION,
    ENTRIES_MISSING_FROM_SIGNED_COPY,
    DIFFERS_FROM_SIGNED_COPY,
    DOCUMENT_TYPE_NOT_LISTED,
    KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY,
    EXTENDED_KEY_USAGE_PRESENT,
    COUNTRY_DIFFERS,
    OUTSIDE_USAGE_PERIOD,
}

/** Why a check does not apply to this chip. */
internal enum class PaNotApplicable {
    NO_CARD_ACCESS,
    NO_EF_DIR,
    HAS_DOCUMENT_TYPE_EXTENSION,
    NO_DOCUMENT_TYPE_EXTENSION,
    NO_PRIVATE_KEY_USAGE_PERIOD,
}

/** Why a check was not run: its inputs are missing or cannot be used. */
internal enum class PaNotRun {
    FILE_NOT_READ,
    UNREADABLE_FILE,
    UNREADABLE_SECURITY_OBJECT,
    DATA_GROUP_NOT_READ,
    NO_SIGNER_CERTIFICATE,
    UNREADABLE_SIGNER_CERTIFICATE,
    NO_ROOTS_SUPPLIED,
    NO_MATCHED_ROOT,
    UNSUPPORTED_ALGORITHM,
    UNREADABLE_KEY,
    CRYPTO_UNAVAILABLE,
    NO_TIME_GIVEN,
    NO_SIGNED_COPY,
    UNREADABLE_SIGNED_COPY,
    NO_DOCUMENT_CODE,
    NO_ISSUE_DATE,
    COUNTRY_NAME_ABSENT,
    NOT_SUPPORTED,
}

/** The outcome of one check. */
internal sealed interface PaOutcome {
    data object Passed : PaOutcome

    data class Failed(
        val reason: PaFailure,
    ) : PaOutcome

    data class NotApplicable(
        val reason: PaNotApplicable,
    ) : PaOutcome

    data class NotRun(
        val reason: PaNotRun,
    ) : PaOutcome
}

/** One fact: what was checked and how it came out. */
internal data class PaCheck(
    val kind: PaCheckKind,
    val outcome: PaOutcome,
)
