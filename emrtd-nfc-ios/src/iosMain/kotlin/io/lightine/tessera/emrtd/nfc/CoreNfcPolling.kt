package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * What the system session of a [CoreNfcChipRead] polls for: the setting
 * [CoreNfcReadSettings.polling].
 *
 * Core NFC can poll for ISO 14443 tags and, from iOS 26.4, for PACE tags: chips that answer only
 * after PACE polling. From iOS 26.4 both can be asked for together, with the session Configuration
 * (`NFCTagReaderSessionConfiguration`); before iOS 26.4 PACE polling cannot be combined with ISO
 * 14443 (it would find PACE chips only), so ISO 14443 is polled alone there.
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
public enum class CoreNfcPolling {
    /**
     * The SDK's choice, the default: today the same as [ISO14443_AND_PACE]. It may change in a later
     * release when devices show that another choice reads more documents.
     */
    AUTOMATIC,

    /**
     * ISO 14443 polling only, on every iOS version, with the session opened by the polling option
     * (`NFCTagReaderSession(pollingOption:delegate:queue:)`), as before iOS 26.4: the select
     * identifiers come from the app's Info.plist (`com.apple.developer.nfc.readersession.iso7816.select-identifiers`).
     * Finds chips that answer ISO 14443 polling; misses chips that answer only after PACE polling.
     */
    ISO14443,

    /**
     * ISO 14443 and PACE polling together, from iOS 26.4, with the session Configuration and the
     * eMRTD application as the only select identifier: finds chips that answer ISO 14443 polling
     * and chips that answer only after PACE polling. Before iOS 26.4, or where the system has no
     * Configuration, the system cannot combine them, so the session polls ISO 14443 alone, as
     * [ISO14443] does.
     */
    ISO14443_AND_PACE,
}
