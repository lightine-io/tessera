package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError

/** A scripted ISO 7816 tag: no device, no radio. Answers at once unless [holdAnswers] is set. */
internal class FakeChannel(
    override val supportsPace: Boolean? = null,
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
) : DetectedTag {
    var connectCalls = 0

    override fun connect(onDone: (error: NfcError?) -> Unit) {
        connectCalls++
        onDone(connectError)
    }
}

/** A stand-in for NFCTagReaderSession that records what the session helper asks of it. */
internal class FakeSessionPort(
    val events: ReaderSessionEvents,
) : ReaderSessionPort {
    val alerts = mutableListOf<String>()
    var began = false
    var restarts = 0
    var invalidated = false
    var invalidatedWith: String? = null

    /** Called by begin(), like Core NFC calling the delegate once polling finds tags. */
    var onBegin: (FakeSessionPort) -> Unit = {}

    override fun setAlertMessage(text: String) {
        alerts += text
    }

    override fun begin() {
        began = true
        onBegin(this)
    }

    override fun restartPolling() {
        restarts++
    }

    override fun invalidate() {
        invalidated = true
    }

    override fun invalidate(errorMessage: String) {
        invalidated = true
        invalidatedWith = errorMessage
    }
}
