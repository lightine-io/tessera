package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable

/** A scripted ISO 7816 tag: no device, no radio. Answers at once unless [holdAnswers] is set. */
internal class FakeChannel(
    override val supportsPace: Boolean? = null,
    override val initialSelectedAid: String? = null,
) : Iso7816Channel {
    val sent = mutableListOf<ByteArray>()
    var answer: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    var error: NfcError? = null
    var holdAnswers = false
    val held = mutableListOf<(ByteArray?, NfcError?) -> Unit>()

    override fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    ) {
        sent += command
        if (holdAnswers) {
            held += onDone
            return
        }
        val failure = error
        if (failure != null) onDone(null, failure) else onDone(answer, null)
    }
}

internal class FakeTag(
    override val iso7816: Iso7816Channel?,
    private val connectError: NfcError? = null,
    private val retryable: Boolean = connectError is NfcTagLost,
) : DetectedTag {
    var connectCalls = 0

    /** When set, the connect completion is held here instead of called at once: a late callback. */
    var holdConnect = false
    var heldConnect: ((ConnectFailure?) -> Unit)? = null

    override fun connect(onDone: (failure: ConnectFailure?) -> Unit) {
        connectCalls++
        if (holdConnect) {
            heldConnect = onDone
            return
        }
        onDone(connectError?.let { ConnectFailure(it, retryable) })
    }
}

/**
 * A stand-in for NFCTagReaderSession that records what the session helper asks of it. Like Core
 * NFC, an invalidate call by the app is followed by the system's own end report (code
 * "user canceled"), unless [reportEnd] is off; the test can also deliver any end at any moment
 * through [events].
 */
internal class FakeSessionPort(
    val events: ReaderSessionEvents,
) : ReaderSessionPort {
    val alerts = mutableListOf<String>()

    /** When each alert was set, by [clock]. */
    val alertTimes = mutableListOf<Long>()
    var clock: () -> Long = { 0L }
    var began = false
    var restarts = 0

    /** Every invalidate call, in order: `null` for the plain one, else its error message. */
    val endings = mutableListOf<String?>()
    val invalidated: Boolean get() = endings.isNotEmpty()
    val invalidatedWith: String? get() = endings.lastOrNull()

    /** Whether invalidate is followed by the system's end report, as on a device. */
    var reportEnd = true

    /** Called by begin(), like Core NFC calling the delegate once polling finds tags. */
    var onBegin: (FakeSessionPort) -> Unit = {}

    /** How many times the system ended the session on its own, through [systemEnd]. */
    var systemEnds = 0

    /** The system ends the session on its own with [error]. */
    fun systemEnd(error: NfcError) {
        systemEnds++
        events.onInvalidated(error)
    }

    override fun setAlertMessage(text: String) {
        alerts += text
        alertTimes += clock()
    }

    override fun begin() {
        began = true
        onBegin(this)
    }

    override fun restartPolling() {
        restarts++
    }

    override fun invalidate() {
        endings += null
        if (reportEnd) events.onInvalidated(NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER))
    }

    override fun invalidate(errorMessage: String) {
        endings += errorMessage
        if (reportEnd) events.onInvalidated(NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER))
    }
}
