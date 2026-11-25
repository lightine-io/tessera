package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import platform.CoreNFC.NFCErrorDomain
import platform.CoreNFC.NFCNdefReaderSessionErrorTagNotWritable
import platform.CoreNFC.NFCNdefReaderSessionErrorTagSizeTooSmall
import platform.CoreNFC.NFCNdefReaderSessionErrorTagUpdateFailure
import platform.CoreNFC.NFCNdefReaderSessionErrorZeroLengthMessage
import platform.CoreNFC.NFCReaderErrorAccessNotAccepted
import platform.CoreNFC.NFCReaderErrorIneligible
import platform.CoreNFC.NFCReaderErrorInvalidParameter
import platform.CoreNFC.NFCReaderErrorInvalidParameterLength
import platform.CoreNFC.NFCReaderErrorParameterOutOfBound
import platform.CoreNFC.NFCReaderErrorRadioDisabled
import platform.CoreNFC.NFCReaderErrorSecurityViolation
import platform.CoreNFC.NFCReaderErrorUnsupportedFeature
import platform.CoreNFC.NFCReaderSessionInvalidationErrorFirstNDEFTagRead
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSessionTerminatedUnexpectedly
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSessionTimeout
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSystemIsBusy
import platform.CoreNFC.NFCReaderSessionInvalidationErrorUserCanceled
import platform.CoreNFC.NFCReaderTransceiveErrorPacketTooLong
import platform.CoreNFC.NFCReaderTransceiveErrorRetryExceeded
import platform.CoreNFC.NFCReaderTransceiveErrorSessionInvalidated
import platform.CoreNFC.NFCReaderTransceiveErrorTagConnectionLost
import platform.CoreNFC.NFCReaderTransceiveErrorTagNotConnected
import platform.CoreNFC.NFCReaderTransceiveErrorTagResponseError
import platform.CoreNFC.NFCTagCommandConfigurationErrorInvalidParameters
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Core NFC's `NFCReaderError` codes, each to its typed [NfcError]. */
class CoreNfcErrorMappingTest {
    private val ended = CoreNfcErrorSource.SESSION_END
    private val operation = CoreNfcErrorSource.TAG_OPERATION

    private fun map(
        code: Long,
        source: CoreNfcErrorSource,
    ) = nfcErrorFor(NFCErrorDomain, code, source)

    private val endedBySystem = NfcUnavailable(Reason.ENDED_BY_SYSTEM)
    private val refused = NfcUnavailable(Reason.REQUEST_REFUSED)

    /**
     * Every code in `NFCError.h` (iOS SDK 27.2) with its error at the session's end and on a tag
     * operation.
     */
    private val table: List<Triple<Long, NfcError, NfcError>> =
        listOf(
            Triple(CoreNfcErrorCode.UNSUPPORTED_FEATURE, NfcUnavailable(Reason.NOT_SUPPORTED), NfcUnavailable(Reason.NOT_SUPPORTED)),
            Triple(CoreNfcErrorCode.SECURITY_VIOLATION, NfcUnavailable(Reason.NOT_PERMITTED), NfcUnavailable(Reason.NOT_PERMITTED)),
            Triple(CoreNfcErrorCode.INVALID_PARAMETER, refused, refused),
            Triple(CoreNfcErrorCode.INVALID_PARAMETER_LENGTH, refused, refused),
            Triple(CoreNfcErrorCode.PARAMETER_OUT_OF_BOUND, refused, refused),
            Triple(CoreNfcErrorCode.RADIO_DISABLED, NfcUnavailable(Reason.DISABLED), NfcUnavailable(Reason.DISABLED)),
            Triple(CoreNfcErrorCode.INELIGIBLE, NfcUnavailable(Reason.NOT_ELIGIBLE), NfcUnavailable(Reason.NOT_ELIGIBLE)),
            Triple(CoreNfcErrorCode.ACCESS_NOT_ACCEPTED, NfcUnavailable(Reason.ACCESS_NOT_ANSWERED), NfcUnavailable(Reason.ACCESS_NOT_ANSWERED)),
            Triple(CoreNfcErrorCode.TAG_CONNECTION_LOST, NfcTagLost, NfcTagLost),
            Triple(CoreNfcErrorCode.RETRY_EXCEEDED, NfcTimeout(null, NfcTimeout.Limit.COMMAND), NfcTimeout(null, NfcTimeout.Limit.COMMAND)),
            Triple(CoreNfcErrorCode.TAG_RESPONSE_ERROR, NfcTagLost, NfcTagLost),
            Triple(CoreNfcErrorCode.SESSION_INVALIDATED, NfcUnavailable(Reason.CLOSED), NfcUnavailable(Reason.CLOSED)),
            Triple(CoreNfcErrorCode.TAG_NOT_CONNECTED, NfcTagLost, NfcTagLost),
            Triple(CoreNfcErrorCode.PACKET_TOO_LONG, NfcUnavailable(Reason.COMMAND_TOO_LONG), NfcUnavailable(Reason.COMMAND_TOO_LONG)),
            Triple(CoreNfcErrorCode.USER_CANCELED, NfcUnavailable(Reason.CANCELLED_BY_USER), NfcUnavailable(Reason.CANCELLED_BY_USER)),
            Triple(CoreNfcErrorCode.SESSION_TIMEOUT, NfcTimeout(null, NfcTimeout.Limit.SESSION), NfcTimeout(null, NfcTimeout.Limit.SESSION)),
            Triple(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, endedBySystem, endedBySystem),
            Triple(CoreNfcErrorCode.SYSTEM_IS_BUSY, NfcUnavailable(Reason.BUSY), NfcUnavailable(Reason.BUSY)),
            // Codes of other kinds of session (NDEF, payment): not expected here, so treated as unknown.
            Triple(CoreNfcErrorCode.FIRST_NDEF_TAG_READ, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.TAG_COMMAND_CONFIGURATION_INVALID_PARAMETERS, refused, refused),
            Triple(CoreNfcErrorCode.NDEF_TAG_NOT_WRITABLE, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.NDEF_TAG_UPDATE_FAILURE, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.NDEF_TAG_SIZE_TOO_SMALL, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.NDEF_ZERO_LENGTH_MESSAGE, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.PAYMENT_LOCATION_AUTHORIZATION_DENIED, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.PAYMENT_LOCATION_SERVICE_RESTRICTED, endedBySystem, NfcTagLost),
            Triple(CoreNfcErrorCode.PAYMENT_ON_DEVICE_LOCATION_UNAVAILABLE, endedBySystem, NfcTagLost),
        )

    @Test
    fun the_codes_match_the_vendors_header() {
        // The numbers in CoreNfcErrorCode are read from NFCError.h; the platform constants prove them.
        assertEquals(NFCReaderErrorUnsupportedFeature, CoreNfcErrorCode.UNSUPPORTED_FEATURE)
        assertEquals(NFCReaderErrorSecurityViolation, CoreNfcErrorCode.SECURITY_VIOLATION)
        assertEquals(NFCReaderErrorInvalidParameter, CoreNfcErrorCode.INVALID_PARAMETER)
        assertEquals(NFCReaderErrorInvalidParameterLength, CoreNfcErrorCode.INVALID_PARAMETER_LENGTH)
        assertEquals(NFCReaderErrorParameterOutOfBound, CoreNfcErrorCode.PARAMETER_OUT_OF_BOUND)
        assertEquals(NFCReaderErrorRadioDisabled, CoreNfcErrorCode.RADIO_DISABLED)
        assertEquals(NFCReaderErrorIneligible, CoreNfcErrorCode.INELIGIBLE)
        assertEquals(NFCReaderErrorAccessNotAccepted, CoreNfcErrorCode.ACCESS_NOT_ACCEPTED)
        assertEquals(NFCReaderTransceiveErrorTagConnectionLost, CoreNfcErrorCode.TAG_CONNECTION_LOST)
        assertEquals(NFCReaderTransceiveErrorRetryExceeded, CoreNfcErrorCode.RETRY_EXCEEDED)
        assertEquals(NFCReaderTransceiveErrorTagResponseError, CoreNfcErrorCode.TAG_RESPONSE_ERROR)
        assertEquals(NFCReaderTransceiveErrorSessionInvalidated, CoreNfcErrorCode.SESSION_INVALIDATED)
        assertEquals(NFCReaderTransceiveErrorTagNotConnected, CoreNfcErrorCode.TAG_NOT_CONNECTED)
        assertEquals(NFCReaderTransceiveErrorPacketTooLong, CoreNfcErrorCode.PACKET_TOO_LONG)
        assertEquals(NFCReaderSessionInvalidationErrorUserCanceled, CoreNfcErrorCode.USER_CANCELED)
        assertEquals(NFCReaderSessionInvalidationErrorSessionTimeout, CoreNfcErrorCode.SESSION_TIMEOUT)
        assertEquals(NFCReaderSessionInvalidationErrorSessionTerminatedUnexpectedly, CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
        assertEquals(NFCReaderSessionInvalidationErrorSystemIsBusy, CoreNfcErrorCode.SYSTEM_IS_BUSY)
        assertEquals(NFCReaderSessionInvalidationErrorFirstNDEFTagRead, CoreNfcErrorCode.FIRST_NDEF_TAG_READ)
        assertEquals(NFCTagCommandConfigurationErrorInvalidParameters, CoreNfcErrorCode.TAG_COMMAND_CONFIGURATION_INVALID_PARAMETERS)
        assertEquals(NFCNdefReaderSessionErrorTagNotWritable, CoreNfcErrorCode.NDEF_TAG_NOT_WRITABLE)
        assertEquals(NFCNdefReaderSessionErrorTagUpdateFailure, CoreNfcErrorCode.NDEF_TAG_UPDATE_FAILURE)
        assertEquals(NFCNdefReaderSessionErrorTagSizeTooSmall, CoreNfcErrorCode.NDEF_TAG_SIZE_TOO_SMALL)
        assertEquals(NFCNdefReaderSessionErrorZeroLengthMessage, CoreNfcErrorCode.NDEF_ZERO_LENGTH_MESSAGE)
        // The payment codes (iOS 26.6) are not in Kotlin/Native's platform library yet; NFCError.h
        // lines 81-83 number them 500, 501 and 502.
        assertEquals(
            listOf(500L, 501L, 502L),
            listOf(
                CoreNfcErrorCode.PAYMENT_LOCATION_AUTHORIZATION_DENIED,
                CoreNfcErrorCode.PAYMENT_LOCATION_SERVICE_RESTRICTED,
                CoreNfcErrorCode.PAYMENT_ON_DEVICE_LOCATION_UNAVAILABLE,
            ),
        )
    }

    @Test
    fun every_code_maps_as_the_table_says() {
        table.forEach { (code, atEnd, onOperation) ->
            assertEquals(atEnd, map(code, ended), "code $code at the session's end")
            assertEquals(onOperation, map(code, operation), "code $code on a tag operation")
        }
    }

    @Test
    fun the_table_lists_every_code_once() {
        assertEquals(27, table.map { it.first }.toSet().size)
        assertEquals(table.size, table.map { it.first }.toSet().size)
    }

    @Test
    fun a_session_the_system_ended_is_not_a_lost_tag() {
        assertEquals(endedBySystem, map(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, ended))
        assertNotEquals<NfcError>(NfcTagLost, map(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, operation))
    }

    @Test
    fun refused_requests_are_not_a_lost_link() {
        listOf(
            CoreNfcErrorCode.INVALID_PARAMETER,
            CoreNfcErrorCode.INVALID_PARAMETER_LENGTH,
            CoreNfcErrorCode.PARAMETER_OUT_OF_BOUND,
            CoreNfcErrorCode.TAG_COMMAND_CONFIGURATION_INVALID_PARAMETERS,
        ).forEach {
            assertEquals(refused, map(it, operation), "code $it")
            assertEquals(refused, map(it, ended), "code $it")
        }
        // A command too long has its own reason, the same as Android's for the same refusal.
        val tooLong = NfcUnavailable(Reason.COMMAND_TOO_LONG)
        assertEquals(tooLong, map(CoreNfcErrorCode.PACKET_TOO_LONG, operation))
        assertEquals(tooLong, map(CoreNfcErrorCode.PACKET_TOO_LONG, ended))
    }

    @Test
    fun an_unknown_code_or_another_domain_ends_the_session_or_loses_the_tag() {
        assertEquals(endedBySystem, map(9_999, ended))
        assertEquals(NfcTagLost, map(9_999, operation))
        assertEquals(endedBySystem, nfcErrorFor(NSCocoaErrorDomain, CoreNfcErrorCode.RADIO_DISABLED, ended))
        assertEquals(NfcTagLost, nfcErrorFor(NSCocoaErrorDomain, CoreNfcErrorCode.RADIO_DISABLED, operation))
        assertEquals(NfcTagLost, nfcErrorFor(null, CoreNfcErrorCode.RADIO_DISABLED, operation))
    }

    @Test
    fun a_platform_error_object_maps_by_domain_and_code() {
        val error = NSError.errorWithDomain(NFCErrorDomain, CoreNfcErrorCode.RADIO_DISABLED, null)
        assertEquals(NfcUnavailable(Reason.DISABLED), nfcErrorFor(error, operation))
    }

    @Test
    fun only_a_lost_link_or_no_answer_lets_a_failed_connect_restart_polling() {
        val retryable =
            listOf(
                NFCReaderTransceiveErrorTagConnectionLost,
                NFCReaderTransceiveErrorTagNotConnected,
                NFCReaderTransceiveErrorTagResponseError,
                NFCReaderTransceiveErrorRetryExceeded,
            )
        retryable.forEach { assertEquals(true, connectRetryable(NFCErrorDomain, it), "code $it") }
        val ending =
            listOf(
                NFCReaderSessionInvalidationErrorSessionTimeout,
                NFCReaderSessionInvalidationErrorSessionTerminatedUnexpectedly,
                NFCReaderSessionInvalidationErrorUserCanceled,
                NFCReaderSessionInvalidationErrorSystemIsBusy,
                NFCReaderTransceiveErrorSessionInvalidated,
                NFCReaderErrorSecurityViolation,
                NFCReaderErrorInvalidParameter,
                NFCReaderTransceiveErrorPacketTooLong,
            )
        ending.forEach { assertEquals(false, connectRetryable(NFCErrorDomain, it), "code $it") }
        assertEquals(false, connectRetryable(NSCocoaErrorDomain, NFCReaderTransceiveErrorTagConnectionLost))
        assertEquals(false, connectRetryable(null, NFCReaderTransceiveErrorTagConnectionLost))
    }
}
