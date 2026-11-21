package io.lightine.tessera.emrtd.pcsc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import java.security.ProviderException
import javax.smartcardio.CardException
import javax.smartcardio.CardNotPresentException

internal enum class CardOperation {
    READER_DISCOVERY,
    CONNECT,
    EXCHANGE,
    DISCONNECT,
}

internal fun CardException.toTransportException(operation: CardOperation): ApduTransportException =
    ApduTransportException(errorFor(operation), this)

internal fun SecurityException.toTransportException(): ApduTransportException =
    ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED), this)

internal fun ProviderException.toTransportException(): ApduTransportException =
    ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.DISABLED), this)

private fun CardException.errorFor(operation: CardOperation): NfcError {
    // javax.smartcardio exposes a general CardException; providers may include a symbolic or
    // hexadecimal PC/SC status in the exception or its cause, but the status format is not specified.
    val diagnostics =
        generateSequence<Throwable>(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

    return when {
        this is CardNotPresentException ||
            diagnostics.hasAny(
                "cardnotpresent",
                "no_smartcard",
                "no smart card",
                "no card present",
                "removed_card",
                "removed card",
                "card removed",
                "0x8010000c",
                "0x80100069",
            )
        -> {
            NfcTagLost
        }

        diagnostics.hasAny(
            "no_readers_available",
            "no readers",
            "reader unavailable",
            "reader_unavailable",
            "0x8010002e",
            "0x80100017",
        ) -> {
            NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED)
        }

        diagnostics.hasAny(
            "securityexception",
            "access denied",
            "permission denied",
            "not permitted",
            "no_access",
            "0x80100027",
        ) -> {
            NfcUnavailable(NfcUnavailable.Reason.NOT_PERMITTED)
        }

        diagnostics.hasAny(
            "no_service",
            "service_stopped",
            "service unavailable",
            "service not available",
            "pc/sc service",
            "0x8010001d",
            "0x8010001e",
        ) -> {
            NfcUnavailable(NfcUnavailable.Reason.DISABLED)
        }

        operation == CardOperation.EXCHANGE || operation == CardOperation.DISCONNECT -> {
            NfcTagLost
        }

        else -> {
            NfcUnavailable(NfcUnavailable.Reason.DISABLED)
        }
    }
}

private fun String.hasAny(vararg values: String): Boolean = values.any(::contains)
