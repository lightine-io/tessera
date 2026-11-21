package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreNFC.NFCISO7816APDU
import platform.CoreNFC.NFCISO7816TagProtocol
import platform.CoreNFC.NFCPollingISO14443
import platform.CoreNFC.NFCPollingPACE
import platform.CoreNFC.NFCTagProtocol
import platform.CoreNFC.NFCTagReaderSession
import platform.CoreNFC.NFCTagReaderSessionConfiguration
import platform.CoreNFC.NFCTagReaderSessionDelegateProtocol
import platform.Foundation.NSClassFromString
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.NSObjectMeta
import platform.posix.memcpy

// The parts of Core NFC that the transport and the session use, behind small interfaces, so the
// tests can drive them with fakes: the simulator has no NFC reader. The platform implementations
// below only forward calls and translate types.

/** An ISO 7816 tag: sends one command APDU and calls back once with the answer or an error. */
internal interface Iso7816Channel {
    /** Whether the chip says it supports PACE, or `null` when the system cannot say (before iOS 26.4). */
    val supportsPace: Boolean?

    /** Sends [command]. [onDone] gets the answer with its two status-word bytes, or an error. */
    fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    )
}

/** A tag found by the reader session. */
internal interface DetectedTag {
    /** The tag as ISO 7816, or `null` for any other kind of tag. */
    val iso7816: Iso7816Channel?

    /** Connects the session to this tag; [onDone] gets `null` on success. */
    fun connect(onDone: (error: NfcError?) -> Unit)
}

/** What the reader session reports. Called on the session's own queue. */
internal interface ReaderSessionEvents {
    fun onTagsDetected(tags: List<DetectedTag>)

    fun onInvalidated(error: NfcError)
}

/** The system reader session. */
internal interface ReaderSessionPort {
    fun setAlertMessage(text: String)

    fun begin()

    fun restartPolling()

    fun invalidate()

    fun invalidate(errorMessage: String)
}

/** The eMRTD application identifier (ICAO Doc 9303 Part 10, section 3.1), as Info.plist lists it. */
internal const val EMRTD_AID_HEX: String = "A0000002471001"

/** Whether this system is iOS 26.4 or later, where the session Configuration and `supportsPACE` exist. */
internal fun isAtLeastIos264(): Boolean =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        majorVersion > 26 || (majorVersion == 26L && minorVersion >= 4)
    }

@OptIn(BetaInteropApi::class) // NSData.create(bytes:length:), the copying initializer
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** The real thing: forwards to a Core NFC ISO 7816 tag. */
internal class PlatformIso7816Channel(
    private val tag: NFCISO7816TagProtocol,
) : Iso7816Channel {
    // Read once: the property exists only from iOS 26.4, and asking an older system for it would
    // send a message the tag object does not know.
    override val supportsPace: Boolean? = if (isAtLeastIos264()) tag.supportsPACE else null

    override fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    ) {
        // The SDK builds every command, so the bytes are always a well-formed APDU.
        val apdu = NFCISO7816APDU(data = command.toNSData())
        tag.sendCommandAPDU(apdu) { data: NSData?, sw1: UByte, sw2: UByte, error: NSError? ->
            if (error != null) {
                onDone(null, nfcErrorFor(error))
            } else {
                onDone((data?.toByteArray() ?: ByteArray(0)) + byteArrayOf(sw1.toByte(), sw2.toByte()), null)
            }
        }
    }
}

/** A tag from `tagReaderSession(_:didDetect:)`. */
private class PlatformDetectedTag(
    private val session: NFCTagReaderSession,
    private val tag: NFCTagProtocol,
) : DetectedTag {
    override val iso7816: Iso7816Channel? =
        (tag as? NFCISO7816TagProtocol)?.let { PlatformIso7816Channel(it) }

    override fun connect(onDone: (error: NfcError?) -> Unit) {
        session.connectToTag(tag) { error: NSError? -> onDone(error?.let { nfcErrorFor(it) }) }
    }
}

/**
 * The delegate Core NFC calls. The session holds its delegate weakly, so [PlatformReaderSession]
 * keeps a strong reference to it for the session's whole life. It is an `NSObject` subclass, which
 * Swift cannot see; it stays private.
 */
private class SessionDelegate(
    private val events: ReaderSessionEvents,
) : NSObject(),
    NFCTagReaderSessionDelegateProtocol {
    override fun tagReaderSessionDidBecomeActive(session: NFCTagReaderSession) {
        // Nothing to do: the system sheet is showing and the reader is polling.
    }

    override fun tagReaderSession(
        session: NFCTagReaderSession,
        didInvalidateWithError: NSError,
    ) {
        events.onInvalidated(nfcErrorFor(didInvalidateWithError))
    }

    override fun tagReaderSession(
        session: NFCTagReaderSession,
        didDetectTags: List<*>,
    ) {
        val tags = didDetectTags.mapNotNull { (it as? NFCTagProtocol)?.let { tag -> PlatformDetectedTag(session, tag) } }
        events.onTagsDetected(tags)
    }
}

/**
 * The real thing: an `NFCTagReaderSession` that looks for ISO 14443 tags.
 *
 * From iOS 26.4 it uses the session Configuration with ISO 14443 and PACE polling together and the
 * eMRTD application as the only select identifier, so chips that answer only after PACE are found
 * too. Before iOS 26.4, PACE polling cannot be combined with ISO 14443 (it would find PACE chips
 * only), so it polls ISO 14443 alone, with the select identifiers from the app's Info.plist.
 */
internal class PlatformReaderSession(
    events: ReaderSessionEvents,
) : ReaderSessionPort {
    private val delegate = SessionDelegate(events)

    private val session: NFCTagReaderSession =
        emrtdConfiguration()?.let { NFCTagReaderSession(configuration = it, delegate = delegate, queue = null) }
            ?: NFCTagReaderSession(pollingOption = NFCPollingISO14443, delegate = delegate, queue = null)

    override fun setAlertMessage(text: String) {
        session.alertMessage = text
    }

    override fun begin() = session.beginSession()

    override fun restartPolling() = session.restartPolling()

    override fun invalidate() = session.invalidateSession()

    override fun invalidate(errorMessage: String) = session.invalidateSessionWithErrorMessage(errorMessage)

    private companion object {
        /**
         * The iOS 26.4 session Configuration: ISO 14443 and PACE polling, the eMRTD application as
         * the only select identifier. `null` before iOS 26.4.
         *
         * The class is looked up by name at run time, never named in code: Kotlin/Native links a
         * named Objective-C class as a strong symbol, and an app built with this framework would
         * then fail to launch on iOS 18 to 26.3, where the class does not exist. The object is
         * set up with its property setters, which are plain messages.
         */
        @OptIn(BetaInteropApi::class) // the class object from NSClassFromString
        fun emrtdConfiguration(): NFCTagReaderSessionConfiguration? {
            if (!isAtLeastIos264()) return null
            val meta = NSClassFromString(CONFIGURATION_CLASS) as? NSObjectMeta ?: return null
            val configuration: NFCTagReaderSessionConfiguration = meta.new()?.uncheckedAs() ?: return null
            configuration.polling = NFCPollingISO14443 or NFCPollingPACE
            configuration.iso7816SelectIdentifiers = listOf(EMRTD_AID_HEX)
            configuration.felicaSystemCodes = emptyList<String>()
            return configuration
        }

        const val CONFIGURATION_CLASS = "NFCTagReaderSessionConfiguration"
    }
}

/** A cast the compiler does not check, so the type's class symbol is not referenced. */
@Suppress("UNCHECKED_CAST")
private fun <T> Any.uncheckedAs(): T = this as T

/** Whether this device can read NFC tags at all (`NFCTagReaderSession.readingAvailable`). */
internal fun platformReadingAvailable(): Boolean = NFCTagReaderSession.readingAvailable
