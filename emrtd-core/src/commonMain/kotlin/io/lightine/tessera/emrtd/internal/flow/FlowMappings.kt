package io.lightine.tessera.emrtd.internal.flow

import io.lightine.tessera.emrtd.CheckOutcome
import io.lightine.tessera.emrtd.ChipCheck
import io.lightine.tessera.emrtd.ChipCheckDetail
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipCountrySource
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipNameComparison
import io.lightine.tessera.emrtd.ChipPaceMapping
import io.lightine.tessera.emrtd.ChipSignedCopy
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.ChipSignerOrigin
import io.lightine.tessera.emrtd.internal.aa.ActiveAuthenticationFailure
import io.lightine.tessera.emrtd.internal.bac.BacException
import io.lightine.tessera.emrtd.internal.bac.BacFailure
import io.lightine.tessera.emrtd.internal.ca.CaContext
import io.lightine.tessera.emrtd.internal.pa.CountrySource
import io.lightine.tessera.emrtd.internal.pa.IssuerNameMatch
import io.lightine.tessera.emrtd.internal.pa.PaCheck
import io.lightine.tessera.emrtd.internal.pa.PaCheckKind
import io.lightine.tessera.emrtd.internal.pa.PaFailure
import io.lightine.tessera.emrtd.internal.pa.PaNotApplicable
import io.lightine.tessera.emrtd.internal.pa.PaNotRun
import io.lightine.tessera.emrtd.internal.pa.PaOutcome
import io.lightine.tessera.emrtd.internal.pa.SignedCopy
import io.lightine.tessera.emrtd.internal.pa.SignedObject
import io.lightine.tessera.emrtd.internal.pa.SignerCertificateOrigin
import io.lightine.tessera.emrtd.internal.pace.CamFailure
import io.lightine.tessera.emrtd.internal.pace.CamNotRun
import io.lightine.tessera.emrtd.internal.pace.CamOutcome
import io.lightine.tessera.emrtd.internal.pace.PaceException
import io.lightine.tessera.emrtd.internal.pace.PaceFailure
import io.lightine.tessera.emrtd.internal.pace.PaceStep
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.sm.SecureMessagingException
import io.lightine.tessera.emrtd.internal.sm.SmFailure
import io.lightine.tessera.emrtd.lds.SecurityObjectProblem
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol

/**
 * The flow boundary: internal protocol results and failures become the public check records and
 * `Chip*` errors here, and nowhere else. Nothing here carries bytes from the chip.
 */
internal object FlowMappings {
    fun check(pa: PaCheck): ChipCheck = ChipCheck(kind(pa.kind), outcome(pa.outcome), detail(pa.kind))

    /** The detail a passive-authentication fact records about how it was reached, as public facts. */
    fun detail(k: PaCheckKind): ChipCheckDetail? =
        when (k) {
            is PaCheckKind.SignatureVerifies -> {
                k.signerOrigin?.let {
                    ChipCheckDetail.SignerCertificate(
                        when (it) {
                            SignerCertificateOrigin.STORED_IN_FILE -> ChipSignerOrigin.STORED_IN_FILE
                            SignerCertificateOrigin.SUPPLIED_BY_APP -> ChipSignerOrigin.SUPPLIED_BY_APP
                        },
                    )
                }
            }

            is PaCheckKind.SignerChainsToSuppliedRoot -> {
                k.issuerNameMatch?.let {
                    ChipCheckDetail.IssuerNameComparison(
                        when (it) {
                            IssuerNameMatch.BYTE_EQUAL -> ChipNameComparison.BYTE_EQUAL
                            IssuerNameMatch.NORMALIZED -> ChipNameComparison.RFC_5280_NORMALIZED
                        },
                    )
                }
            }

            is PaCheckKind.SignerCountryMatchesRootCountry -> {
                ChipCheckDetail.CountrySources(k.signerSource?.let(::countrySource), k.rootSource?.let(::countrySource))
            }

            else -> {
                null
            }
        }

    private fun countrySource(s: CountrySource): ChipCountrySource =
        when (s) {
            CountrySource.SUBJECT_NAME -> ChipCountrySource.SUBJECT_NAME
            CountrySource.SUBJECT_ALT_NAME -> ChipCountrySource.SUBJECT_ALT_NAME
            CountrySource.ISSUER_ALT_NAME -> ChipCountrySource.ISSUER_ALT_NAME
        }

    fun kind(k: PaCheckKind): ChipCheckKind =
        when (k) {
            is PaCheckKind.DataGroupHashMatches -> ChipCheckKind.DataGroupHashMatches(k.dataGroup)
            is PaCheckKind.SignatureVerifies -> ChipCheckKind.SignatureVerifies(signed(k.signed))
            is PaCheckKind.SignerChainsToSuppliedRoot -> ChipCheckKind.SignerChainsToSuppliedRoot(signed(k.signed))
            is PaCheckKind.SignerValidAt -> ChipCheckKind.SignerWithinValidityPeriod(signed(k.signed))
            is PaCheckKind.SignerCriticalExtensionsRecognized -> ChipCheckKind.SignerCriticalExtensionsRecognized(signed(k.signed))
            is PaCheckKind.CardAccessMatchesSignedCopy -> ChipCheckKind.CardAccessMatchesSignedCopy(k.copy?.let(::copy))
            PaCheckKind.EfDirMatchesSignedCopy -> ChipCheckKind.EfDirMatchesSignedCopy
            PaCheckKind.SignerDocumentTypeMatches -> ChipCheckKind.SignerDocumentTypeMatches
            PaCheckKind.SignerKeyUsageIsDigitalSignatureOnly -> ChipCheckKind.SignerKeyUsageIsDigitalSignatureOnly
            is PaCheckKind.SignerCountryMatchesRootCountry -> ChipCheckKind.SignerCountryMatchesRootCountry
            PaCheckKind.IssueDateInSignerUsagePeriod -> ChipCheckKind.IssueDateInSignerUsagePeriod
            PaCheckKind.RevocationChecked -> ChipCheckKind.SignerNotRevoked
        }

    // The flow never runs passive authentication over a master list, so MASTER_LIST does not reach here.
    private fun signed(s: SignedObject): ChipSignedFile =
        if (s == SignedObject.CARD_SECURITY) ChipSignedFile.CARD_SECURITY else ChipSignedFile.SECURITY_OBJECT

    private fun copy(c: SignedCopy): ChipSignedCopy =
        when (c) {
            SignedCopy.DG14 -> ChipSignedCopy.DG14
            SignedCopy.CARD_SECURITY -> ChipSignedCopy.CARD_SECURITY
        }

    fun outcome(o: PaOutcome): CheckOutcome =
        when (o) {
            PaOutcome.Passed -> CheckOutcome.Passed
            is PaOutcome.Failed -> CheckOutcome.Failed(failure(o.reason))
            is PaOutcome.NotApplicable -> CheckOutcome.NotApplicable(notApplicable(o.reason))
            is PaOutcome.NotRun -> CheckOutcome.NotRun(notRun(o.reason))
        }

    private fun failure(f: PaFailure): ChipCheckReason =
        when (f) {
            PaFailure.HASH_DIFFERS -> ChipCheckReason.HASH_DIFFERS
            PaFailure.NO_HASH_IN_SECURITY_OBJECT -> ChipCheckReason.NO_HASH_IN_SECURITY_OBJECT
            PaFailure.MESSAGE_DIGEST_DIFFERS -> ChipCheckReason.MESSAGE_DIGEST_DIFFERS
            PaFailure.MESSAGE_DIGEST_ATTRIBUTE_MISSING -> ChipCheckReason.MESSAGE_DIGEST_ATTRIBUTE_MISSING
            PaFailure.CONTENT_TYPE_ATTRIBUTE_DIFFERS -> ChipCheckReason.CONTENT_TYPE_ATTRIBUTE_DIFFERS
            PaFailure.SIGNATURE_DOES_NOT_VERIFY -> ChipCheckReason.SIGNATURE_DOES_NOT_VERIFY
            PaFailure.KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM -> ChipCheckReason.KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM
            PaFailure.NO_SUPPLIED_ROOT_MATCHES -> ChipCheckReason.NO_SUPPLIED_ROOT_MATCHES
            PaFailure.ISSUER_NAME_DIFFERS -> ChipCheckReason.ISSUER_NAME_DIFFERS
            PaFailure.OUTSIDE_VALIDITY -> ChipCheckReason.OUTSIDE_VALIDITY
            PaFailure.UNRECOGNIZED_CRITICAL_EXTENSION -> ChipCheckReason.UNRECOGNIZED_CRITICAL_EXTENSION
            PaFailure.ENTRIES_MISSING_FROM_SIGNED_COPY -> ChipCheckReason.ENTRIES_MISSING_FROM_SIGNED_COPY
            PaFailure.DIFFERS_FROM_SIGNED_COPY -> ChipCheckReason.DIFFERS_FROM_SIGNED_COPY
            PaFailure.DOCUMENT_TYPE_NOT_LISTED -> ChipCheckReason.DOCUMENT_TYPE_NOT_LISTED
            PaFailure.KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY -> ChipCheckReason.KEY_USAGE_NOT_DIGITAL_SIGNATURE_ONLY
            PaFailure.EXTENDED_KEY_USAGE_PRESENT -> ChipCheckReason.EXTENDED_KEY_USAGE_PRESENT
            PaFailure.COUNTRY_DIFFERS -> ChipCheckReason.COUNTRY_DIFFERS
            PaFailure.OUTSIDE_USAGE_PERIOD -> ChipCheckReason.OUTSIDE_USAGE_PERIOD
        }

    private fun notApplicable(n: PaNotApplicable): ChipCheckReason =
        when (n) {
            PaNotApplicable.NO_CARD_ACCESS -> ChipCheckReason.NO_CARD_ACCESS
            PaNotApplicable.NO_EF_DIR -> ChipCheckReason.NO_EF_DIR
            PaNotApplicable.HAS_DOCUMENT_TYPE_EXTENSION -> ChipCheckReason.HAS_DOCUMENT_TYPE_EXTENSION
            PaNotApplicable.NO_DOCUMENT_TYPE_EXTENSION -> ChipCheckReason.NO_DOCUMENT_TYPE_EXTENSION
            PaNotApplicable.NO_PRIVATE_KEY_USAGE_PERIOD -> ChipCheckReason.NO_PRIVATE_KEY_USAGE_PERIOD
        }

    private fun notRun(n: PaNotRun): ChipCheckReason =
        when (n) {
            PaNotRun.FILE_NOT_READ -> ChipCheckReason.FILE_NOT_READ
            PaNotRun.UNREADABLE_FILE -> ChipCheckReason.UNREADABLE_FILE
            PaNotRun.UNREADABLE_SECURITY_OBJECT -> ChipCheckReason.UNREADABLE_SECURITY_OBJECT
            PaNotRun.DATA_GROUP_NOT_READ -> ChipCheckReason.DATA_GROUP_NOT_READ
            PaNotRun.NO_SIGNER_CERTIFICATE -> ChipCheckReason.NO_SIGNER_CERTIFICATE
            PaNotRun.UNREADABLE_SIGNER_CERTIFICATE -> ChipCheckReason.UNREADABLE_SIGNER_CERTIFICATE
            PaNotRun.NO_ROOTS_SUPPLIED -> ChipCheckReason.NO_ROOTS_SUPPLIED
            PaNotRun.NO_MATCHED_ROOT -> ChipCheckReason.NO_MATCHED_ROOT
            PaNotRun.UNSUPPORTED_ALGORITHM -> ChipCheckReason.UNSUPPORTED_ALGORITHM
            PaNotRun.UNREADABLE_KEY -> ChipCheckReason.UNREADABLE_KEY
            PaNotRun.CRYPTO_UNAVAILABLE -> ChipCheckReason.CRYPTO_UNAVAILABLE
            PaNotRun.NO_TIME_GIVEN -> ChipCheckReason.NO_TIME_GIVEN
            PaNotRun.NO_SIGNED_COPY -> ChipCheckReason.NO_SIGNED_COPY
            PaNotRun.UNREADABLE_SIGNED_COPY -> ChipCheckReason.UNREADABLE_SIGNED_COPY
            PaNotRun.NO_DOCUMENT_CODE -> ChipCheckReason.NO_DOCUMENT_CODE
            PaNotRun.NO_ISSUE_DATE -> ChipCheckReason.NO_ISSUE_DATE
            PaNotRun.COUNTRY_NAME_ABSENT -> ChipCheckReason.COUNTRY_NAME_ABSENT
            PaNotRun.NOT_SUPPORTED -> ChipCheckReason.NOT_SUPPORTED
        }

    fun camOutcome(o: CamOutcome): CheckOutcome =
        when (o) {
            CamOutcome.Passed -> {
                CheckOutcome.Passed
            }

            is CamOutcome.Failed -> {
                CheckOutcome.Failed(
                    when (o.reason) {
                        CamFailure.DATA_ABSENT -> ChipCheckReason.CHIP_AUTHENTICATION_DATA_ABSENT
                        CamFailure.DATA_MALFORMED -> ChipCheckReason.CHIP_AUTHENTICATION_DATA_MALFORMED
                        CamFailure.DATA_OUT_OF_RANGE -> ChipCheckReason.CHIP_AUTHENTICATION_DATA_OUT_OF_RANGE
                        CamFailure.KEY_DOES_NOT_MATCH -> ChipCheckReason.MAPPING_KEY_DIFFERS
                    },
                )
            }

            is CamOutcome.NotRun -> {
                CheckOutcome.NotRun(
                    when (o.reason) {
                        CamNotRun.CARD_SECURITY_NOT_READ -> ChipCheckReason.FILE_NOT_READ
                        CamNotRun.UNREADABLE_CARD_SECURITY -> ChipCheckReason.UNREADABLE_FILE
                        CamNotRun.NO_CHIP_AUTHENTICATION_KEY -> ChipCheckReason.NO_CHIP_AUTHENTICATION_KEY
                        CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY -> ChipCheckReason.UNUSABLE_CHIP_AUTHENTICATION_KEY
                        CamNotRun.CRYPTO_UNAVAILABLE -> ChipCheckReason.CRYPTO_UNAVAILABLE
                        CamNotRun.CARD_SECURITY_NOT_AUTHENTICATED -> ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED
                    },
                )
            }
        }

    /**
     * An active-authentication failure as a public outcome. What the reader cannot check (a key it
     * cannot read or use, an algorithm it does not run, no named algorithm) is NotRun: nothing is
     * known about the chip then. What the chip answered wrongly is Failed.
     */
    fun aaOutcome(f: ActiveAuthenticationFailure): CheckOutcome =
        when (f) {
            ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE -> CheckOutcome.NotRun(ChipCheckReason.UNREADABLE_KEY)
            ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY -> CheckOutcome.NotRun(ChipCheckReason.UNSUPPORTED_ALGORITHM)
            ActiveAuthenticationFailure.INVALID_PUBLIC_KEY -> CheckOutcome.NotRun(ChipCheckReason.INVALID_PUBLIC_KEY)
            ActiveAuthenticationFailure.MISSING_SIGNATURE_ALGORITHM -> CheckOutcome.NotRun(ChipCheckReason.MISSING_SIGNATURE_ALGORITHM)
            ActiveAuthenticationFailure.UNSUPPORTED_SIGNATURE_ALGORITHM -> CheckOutcome.NotRun(ChipCheckReason.UNSUPPORTED_ALGORITHM)
            ActiveAuthenticationFailure.CHIP_REFUSED -> CheckOutcome.Failed(ChipCheckReason.CHIP_REFUSED)
            ActiveAuthenticationFailure.WRONG_SIGNATURE_LENGTH -> CheckOutcome.Failed(ChipCheckReason.WRONG_SIGNATURE_LENGTH)
            ActiveAuthenticationFailure.MALFORMED_SIGNATURE -> CheckOutcome.Failed(ChipCheckReason.MALFORMED_SIGNATURE)
            ActiveAuthenticationFailure.WRONG_SIGNATURE -> CheckOutcome.Failed(ChipCheckReason.SIGNATURE_DOES_NOT_VERIFY)
        }

    fun context(c: CaContext): ChipFileContext =
        when (c) {
            CaContext.MASTER_FILE -> ChipFileContext.MASTER_FILE
            CaContext.EMRTD_APPLICATION -> ChipFileContext.EMRTD_APPLICATION
        }

    fun mapping(m: PaceMapping): ChipPaceMapping =
        when (m) {
            PaceMapping.GENERIC -> ChipPaceMapping.GENERIC
            PaceMapping.INTEGRATED -> ChipPaceMapping.INTEGRATED
            PaceMapping.CHIP_AUTHENTICATION -> ChipPaceMapping.CHIP_AUTHENTICATION
        }

    fun secureMessaging(e: SecureMessagingException): ChipError =
        ChipSecureMessagingLost(
            when (e.failure) {
                SmFailure.WRONG_MAC -> ChipSecureMessagingLost.Reason.WRONG_CHECKSUM
                SmFailure.MISSING_MAC -> ChipSecureMessagingLost.Reason.MISSING_CHECKSUM
                SmFailure.MALFORMED_RESPONSE -> ChipSecureMessagingLost.Reason.MALFORMED_ANSWER
                SmFailure.WRONG_PADDING -> ChipSecureMessagingLost.Reason.WRONG_PADDING
                SmFailure.CHIP_ENDED_SESSION -> ChipSecureMessagingLost.Reason.CHIP_ENDED_SESSION
                SmFailure.PLAIN_ANSWER_LENGTH_HINT -> ChipSecureMessagingLost.Reason.PLAIN_LENGTH_HINT
                SmFailure.SESSION_CLOSED -> ChipSecureMessagingLost.Reason.SESSION_CLOSED
                SmFailure.UNAUTHENTICATED_STATUS -> ChipSecureMessagingLost.Reason.UNAUTHENTICATED_STATUS
            },
            e.sw,
        )

    fun bac(e: BacException): ChipError =
        when (e.failure) {
            BacFailure.AUTHENTICATION_REFUSED -> {
                ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, e.sw)
            }

            BacFailure.CHALLENGE_REFUSED -> {
                ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.CHIP_REFUSED, e.sw)
            }

            BacFailure.WRONG_CHALLENGE_LENGTH,
            BacFailure.WRONG_RESPONSE_LENGTH,
            BacFailure.WRONG_CHIP_MAC,
            BacFailure.NONCE_MISMATCH,
            -> {
                ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, e.sw)
            }
        }

    /**
     * A PACE failure after the chip took the suite. 63xx at a GENERAL AUTHENTICATE step means the
     * chip did not match the password (section 4.4.4.2); other refusals and wrong answers are named
     * as such.
     */
    fun pace(e: PaceException): ChipError =
        when (e.failure) {
            PaceFailure.STEP_REFUSED -> {
                val sw = e.sw
                if (sw != null && sw shr 8 == 0x63 && e.step == PaceStep.MUTUAL_AUTHENTICATION) {
                    ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, sw)
                } else {
                    ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.CHIP_REFUSED, sw)
                }
            }

            PaceFailure.SET_AUTHENTICATION_TEMPLATE_REFUSED,
            PaceFailure.REFERENCED_DATA_NOT_FOUND,
            PaceFailure.ALGORITHM_NOT_SUPPORTED,
            -> {
                ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.CHIP_REFUSED, e.sw)
            }

            else -> {
                ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, e.sw)
            }
        }

    /** EF.SOD's own parse problem, as the one public malformed-file error. */
    fun securityObject(
        problem: SecurityObjectProblem,
        offset: Int,
    ): ChipMalformedFile =
        ChipMalformedFile(
            FILE_SOD,
            when (problem) {
                SecurityObjectProblem.MALFORMED_ENCODING -> ChipMalformedFile.Reason.NOT_VALID_TLV
                SecurityObjectProblem.NOT_SIGNED_DATA -> ChipMalformedFile.Reason.NOT_SIGNED_DATA
                SecurityObjectProblem.NOT_A_SECURITY_OBJECT -> ChipMalformedFile.Reason.UNEXPECTED_CONTENT_TYPE
            },
            offset,
        )

    const val FILE_SOD = 0x011D
}
