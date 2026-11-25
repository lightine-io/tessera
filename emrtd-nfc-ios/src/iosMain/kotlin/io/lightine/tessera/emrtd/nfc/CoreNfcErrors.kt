package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import platform.CoreNFC.NFCErrorDomain
import platform.Foundation.NSError

/**
 * The `NFCReaderError` codes of Core NFC (`CoreNFC/NFCError.h`, iOS SDK 27.2), by number, so the
 * mapping can be tested with plain values and reads the same as the vendor's header.
 */
internal object CoreNfcErrorCode {
    const val UNSUPPORTED_FEATURE = 1L
    const val SECURITY_VIOLATION = 2L
    const val INVALID_PARAMETER = 3L
    const val INVALID_PARAMETER_LENGTH = 4L
    const val PARAMETER_OUT_OF_BOUND = 5L
    const val RADIO_DISABLED = 6L
    const val INELIGIBLE = 7L
    const val ACCESS_NOT_ACCEPTED = 8L

    const val TAG_CONNECTION_LOST = 100L
    const val RETRY_EXCEEDED = 101L
    const val TAG_RESPONSE_ERROR = 102L
    const val SESSION_INVALIDATED = 103L
    const val TAG_NOT_CONNECTED = 104L
    const val PACKET_TOO_LONG = 105L

    const val USER_CANCELED = 200L
    const val SESSION_TIMEOUT = 201L
    const val SESSION_TERMINATED_UNEXPECTEDLY = 202L
    const val SYSTEM_IS_BUSY = 203L
    const val FIRST_NDEF_TAG_READ = 204L

    const val TAG_COMMAND_CONFIGURATION_INVALID_PARAMETERS = 300L

    const val NDEF_TAG_NOT_WRITABLE = 400L
    const val NDEF_TAG_UPDATE_FAILURE = 401L
    const val NDEF_TAG_SIZE_TOO_SMALL = 402L
    const val NDEF_ZERO_LENGTH_MESSAGE = 403L

    const val PAYMENT_LOCATION_AUTHORIZATION_DENIED = 500L
    const val PAYMENT_LOCATION_SERVICE_RESTRICTED = 501L
    const val PAYMENT_ON_DEVICE_LOCATION_UNAVAILABLE = 502L
}

/** Where a Core NFC error came from: the session's end, or one operation on a tag. */
internal enum class CoreNfcErrorSource {
    /** `tagReaderSession(_:didInvalidateWithError:)`: the session has ended. */
    SESSION_END,

    /** The completion of `connect(to:)` or `sendCommand(apdu:)`: one operation failed. */
    TAG_OPERATION,
}

/**
 * The [NfcError] for a Core NFC failure.
 *
 * - The device cannot read tags (`unsupportedFeature`): [NfcUnavailable] `NOT_SUPPORTED`.
 * - The entitlement is missing, the Info.plist select identifiers hold no valid entry, or a
 *   command selects an application the list does not hold (`securityViolation`):
 *   [NfcUnavailable] `NOT_PERMITTED`.
 * - The system setting or hardware is not eligible (`ineligible`): [NfcUnavailable]
 *   `NOT_ELIGIBLE`.
 * - The person has not yet accepted or declined the app's request to use the reader
 *   (`accessNotAccepted`): [NfcUnavailable] `ACCESS_NOT_ANSWERED`.
 * - The radio is off (`radioDisabled`): [NfcUnavailable] `DISABLED`.
 * - The session was ended by the Cancel button (`userCanceled`): [NfcUnavailable]
 *   `CANCELLED_BY_USER`. Core NFC reports the same code when the app ends the session itself;
 *   [CoreNfcSession] tells the two apart.
 * - Core NFC is temporarily unavailable (`systemIsBusy`): [NfcUnavailable] `BUSY`.
 * - The session ended unexpectedly (`sessionTerminatedUnexpectedly`): [NfcUnavailable]
 *   `ENDED_BY_SYSTEM`, not [NfcTagLost]: the code says the session ended, not that a tag was lost,
 *   and it can come before any tag was connected.
 * - The session was already ended (`sessionInvalidated`): [NfcUnavailable] `CLOSED`.
 * - A parameter, its length or its value was refused (`invalidParameter`,
 *   `invalidParameterLength`, `parameterOutOfBound`, `invalidParameters` of a tag command
 *   configuration): [NfcUnavailable] `REQUEST_REFUSED`. A command was too long
 *   (`packetTooLong`): [NfcUnavailable] `COMMAND_TOO_LONG`, as on Android. The request was not
 *   carried out; the link to the tag was not lost.
 * - The session's own time limit passed (`sessionTimeout`): [NfcTimeout] with limit `SESSION`;
 *   the chip did not answer after the reader's retries (`retryExceeded`): [NfcTimeout] with limit
 *   `COMMAND`. Both with no time, because Core NFC does not say one.
 * - A lost or broken link to the tag (`tagConnectionLost`, `tagNotConnected`,
 *   `tagResponseError`): [NfcTagLost].
 * - Anything else: a code of another kind of session (NDEF, payment), a code this list does not
 *   know, or an error from another domain. At the session's end ([CoreNfcErrorSource.SESSION_END])
 *   it is [NfcUnavailable] `ENDED_BY_SYSTEM`: the system ended the session, and the SDK does not
 *   know why. On a tag operation it is [NfcTagLost], the most common cause, as on Android.
 */
internal fun nfcErrorFor(
    domain: String?,
    code: Long,
    source: CoreNfcErrorSource,
): NfcError {
    val unknown: NfcError =
        when (source) {
            CoreNfcErrorSource.SESSION_END -> NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM)
            CoreNfcErrorSource.TAG_OPERATION -> NfcTagLost
        }
    if (domain != NFCErrorDomain) return unknown
    return when (code) {
        CoreNfcErrorCode.UNSUPPORTED_FEATURE -> NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)

        CoreNfcErrorCode.SECURITY_VIOLATION -> NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED)

        CoreNfcErrorCode.INELIGIBLE -> NfcUnavailable(NfcUnavailable.Reason.NOT_ELIGIBLE)

        CoreNfcErrorCode.ACCESS_NOT_ACCEPTED -> NfcUnavailable(NfcUnavailable.Reason.ACCESS_NOT_ANSWERED)

        CoreNfcErrorCode.RADIO_DISABLED -> NfcUnavailable(NfcUnavailable.Reason.DISABLED)

        CoreNfcErrorCode.USER_CANCELED -> NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER)

        CoreNfcErrorCode.SYSTEM_IS_BUSY -> NfcUnavailable(NfcUnavailable.Reason.BUSY)

        CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY -> NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM)

        CoreNfcErrorCode.SESSION_INVALIDATED -> NfcUnavailable(NfcUnavailable.Reason.CLOSED)

        CoreNfcErrorCode.INVALID_PARAMETER,
        CoreNfcErrorCode.INVALID_PARAMETER_LENGTH,
        CoreNfcErrorCode.PARAMETER_OUT_OF_BOUND,
        CoreNfcErrorCode.TAG_COMMAND_CONFIGURATION_INVALID_PARAMETERS,
        -> NfcUnavailable(NfcUnavailable.Reason.REQUEST_REFUSED)

        CoreNfcErrorCode.PACKET_TOO_LONG -> NfcUnavailable(NfcUnavailable.Reason.COMMAND_TOO_LONG)

        CoreNfcErrorCode.SESSION_TIMEOUT -> NfcTimeout(null, NfcTimeout.Limit.SESSION)

        CoreNfcErrorCode.RETRY_EXCEEDED -> NfcTimeout(null, NfcTimeout.Limit.COMMAND)

        CoreNfcErrorCode.TAG_CONNECTION_LOST,
        CoreNfcErrorCode.TAG_NOT_CONNECTED,
        CoreNfcErrorCode.TAG_RESPONSE_ERROR,
        -> NfcTagLost

        else -> unknown
    }
}

/** [nfcErrorFor] for a platform error. */
internal fun nfcErrorFor(
    error: NSError,
    source: CoreNfcErrorSource,
): NfcError = nfcErrorFor(error.domain, error.code, source)

/**
 * Whether a failed `connect(to:)` with this Core NFC error may be tried again after
 * `restartPolling`: the link to the tag was lost (`tagConnectionLost`, `tagNotConnected`,
 * `tagResponseError`) or the chip did not answer after the reader's retries (`retryExceeded`).
 * Never the session's time limit (`sessionTimeout`, an
 * [io.lightine.tessera.types.errors.NfcTimeout] with limit `SESSION`): that session is ending, and restarting its
 * polling would have no effect (`NFCTagReaderSession.h`, `restartPolling`).
 */
internal fun connectRetryable(
    domain: String?,
    code: Long,
): Boolean =
    domain == NFCErrorDomain &&
        code in
        setOf(
            CoreNfcErrorCode.TAG_CONNECTION_LOST,
            CoreNfcErrorCode.TAG_NOT_CONNECTED,
            CoreNfcErrorCode.TAG_RESPONSE_ERROR,
            CoreNfcErrorCode.RETRY_EXCEEDED,
        )

/** The raw code of an error of Core NFC's domain, as a fact; `null` for an error of another domain. */
internal fun systemCodeOf(
    domain: String?,
    code: Long,
): Long? = if (domain == NFCErrorDomain) code else null
