package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcUnavailable
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
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy

// The parts of Core NFC that the transport and the session use, behind small interfaces, so the
// tests can drive them with fakes: the simulator has no NFC reader. The platform implementations
// below only forward calls and translate types.

/** An ISO 7816 tag: sends one command APDU and calls back once with the answer or an error. */
internal interface Iso7816Channel {
    /** Whether the chip says it supports PACE, or `null` when the system cannot say (before iOS 26.4). */
    val supportsPace: Boolean?

    /**
     * The application the system selected when it found the tag (`initialSelectedAID`, a hex
     * string), or `null` when it does not say.
     */
    val initialSelectedAid: String?

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
    fun connect(onDone: (failure: ConnectFailure?) -> Unit)
}

/**
 * Why `connect(to:)` failed: the typed [error], and whether polling may restart for another try.
 * Only a lost link or a chip that did not answer may ([connectRetryable]); a session that is
 * ending (its time limit, an ended session) or a refused request may not.
 */
internal class ConnectFailure(
    val error: NfcError,
    val retryable: Boolean,
)

/** What the reader session reports. Called on the session's own serial queue, never the main queue. */
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

    // Available from iOS 13 (NFCISO7816Tag.h), so on every system the module supports.
    override val initialSelectedAid: String? = tag.initialSelectedAID

    override fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    ) = sendApdu(command, onDone) { apdu, completion -> tag.sendCommandAPDU(apdu, completion) }
}

/** What `sendCommandAPDU(_:completionHandler:)` calls back with: data, SW1, SW2 and an error. */
internal typealias ApduCompletion = (data: NSData?, sw1: UByte, sw2: UByte, error: NSError?) -> Unit

/**
 * Sends [command] with [send] and gives [onDone] the answer with its status word, or an error.
 *
 * Core NFC's `initWithData:` returns nil when the bytes are not a valid APDU (`NFCISO7816Tag.h`):
 * then nothing is sent and [onDone] gets [NfcUnavailable] `REQUEST_REFUSED` for this command.
 */
internal fun sendApdu(
    command: ByteArray,
    onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    send: (apdu: NFCISO7816APDU, completion: ApduCompletion) -> Unit,
) {
    val apdu = commandApdu(command)
    if (apdu == null) {
        onDone(null, NfcUnavailable(NfcUnavailable.Reason.REQUEST_REFUSED))
        return
    }
    send(apdu) { data: NSData?, sw1: UByte, sw2: UByte, error: NSError? ->
        if (error != null) {
            onDone(null, nfcErrorFor(error, CoreNfcErrorSource.TAG_OPERATION))
        } else {
            onDone((data?.toByteArray() ?: ByteArray(0)) + byteArrayOf(sw1.toByte(), sw2.toByte()), null)
        }
    }
}

/**
 * [command] as a Core NFC APDU, or `null` when Core NFC finds no valid APDU in it. The Kotlin
 * constructor cannot return null, so this calls the initializer itself, which can.
 */
@Suppress("DEPRECATION", "DEPRECATION_ERROR") // alloc and initWithData: stand behind the constructor, which hides the nil
internal fun commandApdu(command: ByteArray): NFCISO7816APDU? = NFCISO7816APDU.alloc()?.initWithData(command.toNSData())

/** A tag from `tagReaderSession(_:didDetect:)`. */
private class PlatformDetectedTag(
    private val session: NFCTagReaderSession,
    private val tag: NFCTagProtocol,
) : DetectedTag {
    override val iso7816: Iso7816Channel? =
        (tag as? NFCISO7816TagProtocol)?.let { PlatformIso7816Channel(it) }

    override fun connect(onDone: (failure: ConnectFailure?) -> Unit) {
        session.connectToTag(tag) { error: NSError? ->
            onDone(
                error?.let {
                    ConnectFailure(nfcErrorFor(it, CoreNfcErrorSource.TAG_OPERATION), connectRetryable(it.domain, it.code))
                },
            )
        }
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
        events.onInvalidated(nfcErrorFor(didInvalidateWithError, CoreNfcErrorSource.SESSION_END))
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
 *
 * Both ways get the session's own serial queue: the delegate calls and the tag completions run on
 * it (`NFCReaderSession.h`, `sessionQueue`), never on the main queue. The Configuration initializer
 * declares the queue non-null, so it is never passed nil.
 */
internal class PlatformReaderSession internal constructor(
    events: ReaderSessionEvents,
    configuration: NFCTagReaderSessionConfiguration?,
) : ReaderSessionPort {
    constructor(events: ReaderSessionEvents) : this(events, emrtdConfiguration())

    private val delegate = SessionDelegate(events)

    private val session: NFCTagReaderSession =
        newTagReaderSession(
            configuration,
            newSessionQueue(),
            withConfiguration = { config, queue -> NFCTagReaderSession(configuration = config, delegate = delegate, queue = queue) },
            withPolling = { queue -> NFCTagReaderSession(pollingOption = NFCPollingISO14443, delegate = delegate, queue = queue) },
        )

    override fun setAlertMessage(text: String) {
        session.alertMessage = text
    }

    override fun begin() = session.beginSession()

    override fun restartPolling() = session.restartPolling()

    override fun invalidate() = session.invalidateSession()

    override fun invalidate(errorMessage: String) = session.invalidateSessionWithErrorMessage(errorMessage)

    internal companion object {
        /**
         * The iOS 26.4 session Configuration: ISO 14443 and PACE polling, the eMRTD application as
         * the only select identifier. `null` before iOS 26.4.
         *
         * The class is looked up by name at run time, never named in code: Kotlin/Native links a
         * named Objective-C class as a strong symbol, and an app built with this framework would
         * then fail to launch on iOS 15 (the framework's minimum) to 26.3, where the class does not
         * exist. The object is set up with its property setters, which are plain messages.
         */
        @OptIn(BetaInteropApi::class) // the class object from NSClassFromString
        internal fun emrtdConfiguration(): NFCTagReaderSessionConfiguration? {
            if (!isAtLeastIos264()) return null
            val meta = NSClassFromString(CONFIGURATION_CLASS) as? NSObjectMeta ?: return null
            val configuration: NFCTagReaderSessionConfiguration = meta.new()?.uncheckedAs() ?: return null
            configuration.polling = NFCPollingISO14443 or NFCPollingPACE
            configuration.iso7816SelectIdentifiers = listOf(EMRTD_AID_HEX)
            configuration.felicaSystemCodes = emptyList<String>()
            return configuration
        }

        private const val CONFIGURATION_CLASS = "NFCTagReaderSessionConfiguration"
    }
}

/** A new serial dispatch queue for one reader session (a `null` attribute makes it serial). */
internal fun newSessionQueue(): NSObject = checkNotNull(dispatch_queue_create(SESSION_QUEUE_LABEL, null))

private const val SESSION_QUEUE_LABEL = "io.lightine.tessera.emrtd.nfc.session"

/**
 * Makes the reader session the way the system allows, always with [queue]: with the Configuration
 * when there is one, else with the polling option. Generic so the tests can check the queue: the
 * Simulator cannot make a real `NFCTagReaderSession`.
 */
internal fun <C : Any, S> newTagReaderSession(
    configuration: C?,
    queue: NSObject,
    withConfiguration: (configuration: C, queue: NSObject) -> S,
    withPolling: (queue: NSObject) -> S,
): S = if (configuration != null) withConfiguration(configuration, queue) else withPolling(queue)

/** A cast the compiler does not check, so the type's class symbol is not referenced. */
@Suppress("UNCHECKED_CAST")
private fun <T> Any.uncheckedAs(): T = this as T

/** Whether this device can read NFC tags at all (`NFCTagReaderSession.readingAvailable`). */
internal fun platformReadingAvailable(): Boolean = NFCTagReaderSession.readingAvailable
