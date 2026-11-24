package io.lightine.tessera.emrtd.nfc

import platform.Foundation.NSLock

/**
 * One system reader session, owned by the SDK: it begins at most once and is ended at most once,
 * by the system (its own invalidation came) or by one invalidate call of the SDK. Used by
 * [CoreNfcSession] and [CoreNfcChipRead], so both follow the same rules:
 *
 * - An ending asked for before [begin] means the system session never begins: there is nothing to
 *   end, and [onReleased] runs at once.
 * - An ending asked for while `begin()` runs waits for it to return: Core NFC has no session to end
 *   before it starts.
 * - From [begin] until the system reports the end, [keeper] stays in [LiveSessions], so the
 *   delegate (held weakly by Core NFC) and everything it calls live as long as the system session
 *   does, whatever the app drops.
 * - [onReleased] runs exactly once, when no system session is left: after the system's end report,
 *   or at once when an ending came before [begin].
 *
 * Thread-safe: every decision is taken under one lock, and the port is called outside it.
 */
internal class OwnedReaderSession(
    private val port: ReaderSessionPort,
    private val keeper: Any,
    private val onReleased: () -> Unit = {},
) {
    private val lock = NSLock()
    private var beginning = false
    private var begun = false
    private var deferredEnding: String? = null
    private var hasDeferredEnding = false
    private var endRequested = false
    private var invalidateSent = false
    private var systemEnded = false
    private var released = false

    /** Whether the SDK asked for the ending (the system's later report is then only its echo). */
    val endedBySdk: Boolean get() = locked { endRequested }

    /** Whether no system session is left: it never began, or the system reported its end. */
    val isReleased: Boolean get() = locked { released }

    /** Whether the session is neither ended nor asked to end. */
    val isOpen: Boolean get() = locked { !endRequested && !systemEnded }

    /**
     * Begins the system session, unless an ending came first.
     *
     * @return `false` when it did not begin.
     */
    fun begin(): Boolean {
        val go =
            locked {
                if (endRequested || systemEnded || begun || beginning) {
                    false
                } else {
                    beginning = true
                    true
                }
            }
        if (!go) return false
        LiveSessions.add(keeper)
        port.begin()
        val late =
            locked {
                beginning = false
                begun = true
                val waiting = hasDeferredEnding && !systemEnded && !invalidateSent
                hasDeferredEnding = false
                if (waiting) invalidateSent = true
                if (waiting) Late(deferredEnding) else null
            }
        late?.let { invalidate(it.errorMessage) }
        return true
    }

    private class Late(
        val errorMessage: String?,
    )

    /**
     * Ends the system session once: with success when [errorMessage] is `null` (the system sheet may
     * show its check mark), else with [errorMessage]. Nothing when the system already ended it or an
     * ending was already asked for.
     *
     * @return `true` when this call is the ending.
     */
    fun end(errorMessage: String?): Boolean {
        var now = false
        var releaseNow = false
        val first =
            locked {
                if (endRequested || systemEnded) return@locked false
                endRequested = true
                when {
                    beginning -> {
                        hasDeferredEnding = true
                        deferredEnding = errorMessage
                    }

                    begun -> {
                        invalidateSent = true
                        now = true
                    }

                    else -> {
                        // Never begun, and never will be: no system session to end.
                        releaseNow = !released
                        released = true
                    }
                }
                true
            }
        if (now) invalidate(errorMessage)
        if (releaseNow) onReleased()
        return first
    }

    /**
     * The system reported the session's end. Lets go of the session.
     *
     * @return `false` when the end was already reported.
     */
    fun systemEnd(): Boolean {
        val first =
            locked {
                if (systemEnded) return@locked false
                systemEnded = true
                val firstRelease = !released
                released = true
                firstRelease
            }
        LiveSessions.remove(keeper)
        if (first) onReleased()
        return first
    }

    /** Sets the sheet's text while the session is open. */
    fun setAlert(text: String) {
        if (locked { !endRequested && !systemEnded }) port.setAlertMessage(text)
    }

    /** Restarts polling while the session is open; earlier tags are then no longer valid. */
    fun restartPolling() {
        if (locked { !endRequested && !systemEnded && (begun || beginning) }) port.restartPolling()
    }

    private fun invalidate(errorMessage: String?) {
        if (errorMessage == null) port.invalidate() else port.invalidate(errorMessage)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
