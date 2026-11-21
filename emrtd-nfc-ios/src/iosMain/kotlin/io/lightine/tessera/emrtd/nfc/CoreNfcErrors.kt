package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import platform.CoreNFC.NFCErrorDomain
import platform.Foundation.NSError

/**
 * The `NFCReaderError` codes of Core NFC (`CoreNFC/NFCError.h`, iOS SDK 26.4), by number, so the
 * mapping can be tested with plain values and reads the same as Apple's header.
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
}

/**
 * The [NfcError] for a Core NFC failure.
 *
 * - The device cannot read tags (`unsupportedFeature`): [NfcUnavailable] `NOT_SUPPORTED`.
 * - The entitlement or a privacy key is missing (`securityViolation`), the system setting or
 *   hardware is not eligible (`ineligible`), or the person has not accepted the app's use of the
 *   reader (`accessNotAccepted`): [NfcUnavailable] `NOT_PERMITTED`.
 * - The radio is off (`radioDisabled`): [NfcUnavailable] `DISABLED`.
 * - The session was ended by the Cancel button (`userCanceled`): [NfcUnavailable]
 *   `CANCELLED_BY_USER`. Core NFC reports the same code when the app ends the session itself;
 *   [CoreNfcSession] tells the two apart.
 * - The system reader is busy (`systemIsBusy`): [NfcUnavailable] `BUSY`.
 * - The session was already ended (`sessionInvalidated`): [NfcUnavailable] `CLOSED`.
 * - The session's own time limit passed (`sessionTimeout`, about 60 seconds) or the chip did not
 *   answer after the reader's retries (`retryExceeded`): [NfcTimeout], with no time limit because
 *   Core NFC does not say one.
 * - Everything else, including a lost connection (`tagConnectionLost`, `tagNotConnected`,
 *   `tagResponseError`, `sessionTerminatedUnexpectedly`), an error from another domain and a code
 *   this list does not know: [NfcTagLost], the most common cause, as on Android.
 */
internal fun nfcErrorFor(
    domain: String?,
    code: Long,
): NfcError {
    if (domain != NFCErrorDomain) return NfcTagLost
    return when (code) {
        CoreNfcErrorCode.UNSUPPORTED_FEATURE -> NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)

        CoreNfcErrorCode.SECURITY_VIOLATION,
        CoreNfcErrorCode.INELIGIBLE,
        CoreNfcErrorCode.ACCESS_NOT_ACCEPTED,
        -> NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED)

        CoreNfcErrorCode.RADIO_DISABLED -> NfcUnavailable(NfcUnavailable.Reason.DISABLED)

        CoreNfcErrorCode.USER_CANCELED -> NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER)

        CoreNfcErrorCode.SYSTEM_IS_BUSY -> NfcUnavailable(NfcUnavailable.Reason.BUSY)

        CoreNfcErrorCode.SESSION_INVALIDATED -> NfcUnavailable(NfcUnavailable.Reason.CLOSED)

        CoreNfcErrorCode.SESSION_TIMEOUT,
        CoreNfcErrorCode.RETRY_EXCEEDED,
        -> NfcTimeout(null)

        else -> NfcTagLost
    }
}

/** [nfcErrorFor] for a platform error. */
internal fun nfcErrorFor(error: NSError): NfcError = nfcErrorFor(error.domain, error.code)
