package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import platform.CoreNFC.NFCErrorDomain
import platform.CoreNFC.NFCReaderErrorRadioDisabled
import platform.CoreNFC.NFCReaderErrorSecurityViolation
import platform.CoreNFC.NFCReaderErrorUnsupportedFeature
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSessionTimeout
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSystemIsBusy
import platform.CoreNFC.NFCReaderSessionInvalidationErrorUserCanceled
import platform.CoreNFC.NFCReaderTransceiveErrorRetryExceeded
import platform.CoreNFC.NFCReaderTransceiveErrorSessionInvalidated
import platform.CoreNFC.NFCReaderTransceiveErrorTagConnectionLost
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertEquals

/** Core NFC's `NFCReaderError` codes, each to its typed [io.lightine.tessera.types.errors.NfcError]. */
class CoreNfcErrorMappingTest {
    private fun map(code: Long) = nfcErrorFor(NFCErrorDomain, code)

    @Test
    fun the_codes_match_apples_header() {
        // The numbers in CoreNfcErrorCode are read from NFCError.h; the platform constants prove them.
        assertEquals(NFCReaderErrorUnsupportedFeature, CoreNfcErrorCode.UNSUPPORTED_FEATURE)
        assertEquals(NFCReaderErrorSecurityViolation, CoreNfcErrorCode.SECURITY_VIOLATION)
        assertEquals(NFCReaderErrorRadioDisabled, CoreNfcErrorCode.RADIO_DISABLED)
        assertEquals(NFCReaderTransceiveErrorTagConnectionLost, CoreNfcErrorCode.TAG_CONNECTION_LOST)
        assertEquals(NFCReaderTransceiveErrorRetryExceeded, CoreNfcErrorCode.RETRY_EXCEEDED)
        assertEquals(NFCReaderTransceiveErrorSessionInvalidated, CoreNfcErrorCode.SESSION_INVALIDATED)
        assertEquals(NFCReaderSessionInvalidationErrorUserCanceled, CoreNfcErrorCode.USER_CANCELED)
        assertEquals(NFCReaderSessionInvalidationErrorSessionTimeout, CoreNfcErrorCode.SESSION_TIMEOUT)
        assertEquals(NFCReaderSessionInvalidationErrorSystemIsBusy, CoreNfcErrorCode.SYSTEM_IS_BUSY)
    }

    @Test
    fun device_and_permission_problems_are_nfc_unavailable_with_their_reason() {
        assertEquals(NfcUnavailable(Reason.NOT_SUPPORTED), map(CoreNfcErrorCode.UNSUPPORTED_FEATURE))
        assertEquals(NfcUnavailable(Reason.NOT_PERMITTED), map(CoreNfcErrorCode.SECURITY_VIOLATION))
        assertEquals(NfcUnavailable(Reason.NOT_PERMITTED), map(CoreNfcErrorCode.INELIGIBLE))
        assertEquals(NfcUnavailable(Reason.NOT_PERMITTED), map(CoreNfcErrorCode.ACCESS_NOT_ACCEPTED))
        assertEquals(NfcUnavailable(Reason.DISABLED), map(CoreNfcErrorCode.RADIO_DISABLED))
        assertEquals(NfcUnavailable(Reason.BUSY), map(CoreNfcErrorCode.SYSTEM_IS_BUSY))
    }

    @Test
    fun session_endings_have_their_own_reason() {
        assertEquals(NfcUnavailable(Reason.CANCELLED_BY_USER), map(CoreNfcErrorCode.USER_CANCELED))
        assertEquals(NfcUnavailable(Reason.CLOSED), map(CoreNfcErrorCode.SESSION_INVALIDATED))
    }

    @Test
    fun time_limits_are_nfc_timeout_without_a_value() {
        assertEquals(NfcTimeout(null), map(CoreNfcErrorCode.SESSION_TIMEOUT))
        assertEquals(NfcTimeout(null), map(CoreNfcErrorCode.RETRY_EXCEEDED))
    }

    @Test
    fun link_failures_are_tag_lost() {
        listOf(
            CoreNfcErrorCode.TAG_CONNECTION_LOST,
            CoreNfcErrorCode.TAG_NOT_CONNECTED,
            CoreNfcErrorCode.TAG_RESPONSE_ERROR,
            CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
            CoreNfcErrorCode.PACKET_TOO_LONG,
            CoreNfcErrorCode.INVALID_PARAMETER,
        ).forEach { assertEquals(NfcTagLost, map(it), "code $it") }
    }

    @Test
    fun an_unknown_code_or_another_domain_is_tag_lost() {
        assertEquals(NfcTagLost, map(9_999))
        assertEquals(NfcTagLost, nfcErrorFor(NSCocoaErrorDomain, CoreNfcErrorCode.RADIO_DISABLED))
        assertEquals(NfcTagLost, nfcErrorFor(null, CoreNfcErrorCode.RADIO_DISABLED))
    }

    @Test
    fun a_platform_error_object_maps_by_domain_and_code() {
        val error = NSError.errorWithDomain(NFCErrorDomain, CoreNfcErrorCode.RADIO_DISABLED, null)
        assertEquals(NfcUnavailable(Reason.DISABLED), nfcErrorFor(error))
    }
}
